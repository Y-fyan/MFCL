// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Selects default JVM options while preserving user and external-file overrides.
use crate::{fail, Error};
use serde_json::{json, Value};

/// Parses an exact -XX boolean option using the Java builder's ASCII key rules.
fn boolean_key(arg: &str) -> Option<&str> {
    let rest = arg.strip_prefix("-XX:")?;
    let key = rest.strip_prefix('+').or_else(|| rest.strip_prefix('-'))?;
    if !key.is_empty() && key.bytes().all(|b| b.is_ascii_alphanumeric()) { Some(key) } else { None }
}

/// Parses an exact -XX key/value option using the Java builder's key rules.
fn value_key(arg: &str) -> Option<&str> {
    let (key, value) = arg.strip_prefix("-XX:")?.split_once('=')?;
    if !key.is_empty() && key.bytes().all(|b| b.is_ascii_alphanumeric())
        && !value.chars().any(|c| matches!(c, '\n' | '\r' | '\u{0085}' | '\u{2028}' | '\u{2029}')) { Some(key) } else { None }
}

/// Merges memory and ordinary JVM defaults, preserving the caller's quoting mode.
pub(crate) fn initial(params: &Value) -> Result<Value, Error> {
    fn strings<'a>(value: &'a Value, key: &str) -> Result<Vec<&'a str>, Error> {
        let items = value.get(key).and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing JVM arguments"))?;
        if items.len() > 20_000 { return Err(fail("argument_limit", "too many JVM arguments")); }
        items.iter().map(|v| v.as_str().ok_or_else(|| fail("invalid_params", "invalid JVM argument"))).collect()
    }
    fn number(value: &Value, key: &str) -> Result<Option<i64>, Error> {
        match value.get(key) {
            None | Some(Value::Null) => Ok(None),
            Some(v) => v.as_i64().filter(|v| i32::try_from(*v).is_ok()).map(Some)
                .ok_or_else(|| fail("invalid_params", "invalid memory setting")),
        }
    }
    let mut raw: Vec<String> = strings(params, "raw")?.into_iter().map(str::to_owned).collect();
    let external = strings(params, "external")?; let defaults = strings(params, "defaults")?;
    let version = params.get("java_version").and_then(Value::as_i64).ok_or_else(|| fail("invalid_params", "missing Java version"))?;
    let min = number(params, "min_memory")?; let max = number(params, "max_memory")?; let meta = number(params, "metaspace")?;
    let mut added = Vec::new();
    let add = |raw: &mut Vec<String>, added: &mut Vec<Value>, text: String, parse: bool| {
        added.push(json!({"argument":text,"parse":parse})); raw.push(text);
    };
    let exists_prefix = |raw: &Vec<String>, prefix: &str| raw.iter().map(String::as_str).chain(external.iter().copied()).any(|a| a.starts_with(prefix));
    if let Some(v) = max.filter(|v| *v > 0) { if !exists_prefix(&raw, "-Xmx") { add(&mut raw, &mut added, format!("-Xmx{v}m"), true); } }
    if let Some(v) = min.filter(|v| *v > 0 && max.is_none_or(|max| *v <= max)) {
        if !exists_prefix(&raw, "-Xms") { add(&mut raw, &mut added, format!("-Xms{v}m"), true); }
    }
    if let Some(v) = meta.filter(|v| *v > 0) {
        let prefix = if version < 8 { "-XX:PermSize=" } else { "-XX:MetaspaceSize=" };
        if !exists_prefix(&raw, prefix) { add(&mut raw, &mut added, format!("{prefix}{v}m"), true); }
    }
    for arg in defaults {
        let all = || raw.iter().map(String::as_str).chain(external.iter().copied());
        let duplicate = if arg.starts_with("-D") {
            match arg.find('=') {
                Some(index) => all().any(|v| v.starts_with(&arg[..index + 1])),
                None => { let key = format!("{arg}="); all().any(|v| v == arg || v.starts_with(&key)) },
            }
        } else if let Some(key) = value_key(arg) {
            all().any(|v| value_key(v) == Some(key))
        } else if let Some(key) = boolean_key(arg) {
            all().any(|v| boolean_key(v) == Some(key))
        } else if let Some(prefix) = ["-Xmx", "-Xms", "-Xmn", "-Xss"].into_iter().find(|prefix| arg.starts_with(prefix)) {
            all().any(|v| v.starts_with(prefix))
        } else { all().any(|v| v == arg) };
        if !duplicate { add(&mut raw, &mut added, arg.to_owned(), false); }
    }
    Ok(json!({"arguments":added}))
}

/// Selects platform and game-network properties, then appends JVM optimization defaults.
pub(crate) fn platform(params: &Value) -> Result<Value, Error> {
    let items = params.get("raw").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing JVM arguments"))?;
    let external_items = params.get("external").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing external arguments"))?;
    if items.len() > 20_000 || external_items.len() > 20_000 { return Err(fail("argument_limit", "too many JVM arguments")); }
    let mut raw: Vec<String> = items.iter().map(|v| v.as_str().map(str::to_owned).ok_or_else(|| fail("invalid_params", "invalid JVM argument"))).collect::<Result<_, _>>()?;
    let external: Vec<&str> = external_items.iter().map(|v| v.as_str().ok_or_else(|| fail("invalid_params", "invalid JVM argument"))).collect::<Result<_, _>>()?;
    let start = raw.len();
    let default = |raw: &mut Vec<String>, prefix: &str, value: &str| {
        if !raw.iter().map(String::as_str).chain(external.iter().copied()).any(|v| v.starts_with(prefix)) {
            raw.push(format!("{prefix}{value}"));
        }
    };
    let client = params.get("client_jar").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing client path"))?;
    default(&mut raw, "-Dminecraft.client.jar=", client);
    for (key, prefix) in [("dock_name", "-Xdock:name="), ("dock_icon", "-Xdock:icon="), ("user_home", "-Duser.home=")] {
        match params.get(key) {
            None | Some(Value::Null) => (),
            Some(Value::String(value)) => default(&mut raw, prefix, value),
            _ => return Err(fail("invalid_params", "invalid platform setting")),
        }
    }
    let proxy = params.get("proxy").and_then(Value::as_object).ok_or_else(|| fail("invalid_params", "missing game proxy"))?;
    let kind = proxy.get("kind").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "invalid game proxy"))?;
    let has_proxy = raw.iter().any(|v| ["-Djava.net.useSystemProxies=", "-Dhttp.proxy", "-Dhttps.proxy", "-DsocksProxy", "-Djava.net.socks."]
        .iter().any(|prefix| v.starts_with(prefix)));
    if !has_proxy {
        match kind {
            "default" => raw.push("-Djava.net.useSystemProxies=true".into()),
            "direct" => (),
            "http" | "socks" => {
                let host = proxy.get("host").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing proxy host"))?;
                let port = proxy.get("port").and_then(Value::as_u64).filter(|p| *p <= 65535).ok_or_else(|| fail("invalid_params", "invalid proxy port"))?;
                if kind == "http" {
                    raw.extend([format!("-Dhttp.proxyHost={host}"), format!("-Dhttp.proxyPort={port}"),
                        format!("-Dhttps.proxyHost={host}"), format!("-Dhttps.proxyPort={port}")]);
                } else { raw.extend([format!("-DsocksProxyHost={host}"), format!("-DsocksProxyPort={port}")]); }
                if let Some(user) = proxy.get("username").filter(|v| !v.is_null()) {
                    let user = user.as_str().ok_or_else(|| fail("invalid_params", "invalid proxy user"))?;
                    let pass = proxy.get("password").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "invalid proxy password"))?;
                    if kind == "http" {
                        raw.extend([format!("-Dhttp.proxyUser={user}"), format!("-Dhttp.proxyPassword={pass}"),
                            format!("-Dhttps.proxyUser={user}"), format!("-Dhttps.proxyPassword={pass}")]);
                    } else { raw.extend([format!("-Djava.net.socks.username={user}"), format!("-Djava.net.socks.password={pass}")]); }
                }
            },
            _ => return Err(fail("invalid_params", "invalid game proxy")),
        }
    }
    let mut updated = params.clone(); updated["raw"] = json!(raw);
    let optimized = plan(&updated)?;
    let mut output = updated["raw"].as_array().unwrap()[start..].to_vec();
    output.extend(optimized["arguments"].as_array().ok_or_else(|| fail("internal_error", "invalid defaults"))?.iter().cloned());
    Ok(json!({"arguments":output}))
}

/// Plans the generated JVM compatibility and optimization defaults in insertion order.
pub(crate) fn plan(params: &Value) -> Result<Value, Error> {
    fn strings<'a>(value: &'a Value, key: &str) -> Result<Vec<&'a str>, Error> {
        let items = value.get(key).and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing JVM arguments"))?;
        if items.len() > 20_000 { return Err(fail("argument_limit", "too many JVM arguments")); }
        items.iter().map(|v| v.as_str().ok_or_else(|| fail("invalid_params", "invalid JVM argument"))).collect()
    }
    fn flag(value: &Value, key: &str) -> Result<bool, Error> {
        value.get(key).and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "missing JVM option"))
    }
    let mut raw: Vec<String> = strings(params, "raw")?.into_iter().map(str::to_owned).collect();
    let external = strings(params, "external")?;
    let version = params.get("java_version").and_then(Value::as_i64).ok_or_else(|| fail("invalid_params", "missing Java version"))?;
    let is64 = flag(params, "is_64_bit")?; let memory_large = flag(params, "memory_large")?;
    let optimize = flag(params, "optimize")?; let start = raw.len();
    let boolean = |raw: &mut Vec<String>, key: &str, enabled: bool| {
        if !raw.iter().map(String::as_str).chain(external.iter().copied()).any(|v| boolean_key(v) == Some(key)) {
            raw.push(format!("-XX:{}{key}", if enabled {'+'} else {'-'}));
        }
    };
    let value = |raw: &mut Vec<String>, key: &str, value: &str| {
        if !raw.iter().map(String::as_str).chain(external.iter().copied()).any(|v| value_key(v) == Some(key)) {
            raw.push(format!("-XX:{key}={value}"));
        }
    };
    let default = |raw: &mut Vec<String>, prefix: &str, value: &str| {
        if !raw.iter().map(String::as_str).chain(external.iter().copied()).any(|v| v.starts_with(prefix)) {
            raw.push(format!("{prefix}{value}"));
        }
    };
    if optimize {
        boolean(&mut raw, "UnlockExperimentalVMOptions", true);
        boolean(&mut raw, "UnlockDiagnosticVMOptions", true);
        if version >= 8 && !raw.iter().any(|v| v == "-XX:-UseG1GC" || (v.starts_with("-XX:+Use") && v.ends_with("GC"))) {
            boolean(&mut raw, "UseG1GC", true);
            for (key, val) in [("G1MixedGCCountTarget", "5"), ("G1NewSizePercent", "20"), ("G1ReservePercent", "20"),
                ("MaxGCPauseMillis", "50"), ("G1HeapRegionSize", "32m")] { value(&mut raw, key, val); }
        }
        boolean(&mut raw, "OmitStackTraceInFastThrow", false);
        if version <= 8 { value(&mut raw, "MaxInlineLevel", "15"); }
        if is64 && memory_large {
            boolean(&mut raw, "DontCompileHugeMethods", false);
            for (key, val) in [("MaxNodeLimit", "240000"), ("NodeLimitFudgeFactor", "8000"),
                ("TieredCompileTaskTimeout", "10000"), ("ReservedCodeCacheSize", "400M")] { value(&mut raw, key, val); }
            if version >= 9 { value(&mut raw, "NonNMethodCodeHeapSize", "12M"); value(&mut raw, "ProfiledCodeHeapSize", "194M"); }
            if version >= 8 { value(&mut raw, "NmethodSweepActivity", "1"); }
        }
        if is64 && (25..=26).contains(&version) { boolean(&mut raw, "UseCompactObjectHeaders", true); }
        if !is64 { default(&mut raw, "-Xss", "1m"); }
    }
    if version == 16 { default(&mut raw, "--illegal-access=", "permit"); }
    if version == 24 || version == 25 { default(&mut raw, "--sun-misc-unsafe-memory-access=", "allow"); }
    default(&mut raw, "-Dfml.ignoreInvalidMinecraftCertificates=", "true");
    default(&mut raw, "-Dfml.ignorePatchDiscrepancies=", "true");
    Ok(json!({"arguments": &raw[start..]}))
}
