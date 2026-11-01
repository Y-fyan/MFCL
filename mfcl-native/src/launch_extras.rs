// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Plans optional game launch arguments, including direct-connect addressing.
use crate::{fail, Error};
use serde_json::{json, Value};

fn string<'a>(value: &'a Value, key: &str) -> Result<&'a str, Error> {
    value.get(key).and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing launch option"))
}
fn flag(value: &Value, key: &str) -> Result<bool, Error> {
    value.get(key).and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "missing launch flag"))
}

/// Mirrors ServerAddress.parse; unsupported numeric spellings fall back to Java.
fn parse_address(address: &str) -> Result<(&str, Option<u16>), Error> {
    let (host, port) = if address.starts_with('[') {
        let colon = address.find(':').ok_or_else(|| fail("invalid_address", "invalid server address"))?;
        let close = address.rfind(']').filter(|&i| i >= colon).ok_or_else(|| fail("invalid_address", "invalid server address"))?;
        let host = &address[1..close];
        if close + 1 == address.len() { (host, None) }
        else {
            let rest = &address[close + 1..];
            if !rest.starts_with(':') || rest.len() < 2 { return Err(fail("invalid_address", "invalid server address")); }
            (host, Some(&rest[1..]))
        }
    } else if let Some((host, port)) = address.split_once(':') { (host, Some(port)) }
    else { (address, None) };
    let port = match port {
        None => None,
        Some(value) => {
            if !value.is_ascii() || value.is_empty() { return Err(fail("invalid_address", "unsupported server port")); }
            let parsed = value.parse::<i32>().map_err(|_| fail("invalid_address", "invalid server address"))?;
            if !(0..=65535).contains(&parsed) { return Err(fail("invalid_address", "invalid server address")); }
            Some(parsed as u16)
        }
    };
    Ok((host, port))
}

/// Builds arguments after the main class without reading or modifying files.
pub(crate) fn plan(params: &Value) -> Result<Value, Error> {
    let quick = params.get("quick").ok_or_else(|| fail("invalid_params", "missing quick-play option"))?;
    let supported = flag(params, "quick_supported")?;
    let mut args = Vec::<String>::new();
    if !quick.is_null() {
        let kind = string(quick, "kind")?; let value = string(quick, "value")?;
        match kind {
            "multiplayer" => {
                let (host, port) = parse_address(value)?;
                if supported {
                    args.push("--quickPlayMultiplayer".into());
                    args.push(if port.is_some() { value.to_owned() } else { format!("{host}:25565") });
                } else {
                    args.extend(["--server".into(), host.into(), "--port".into(), port.unwrap_or(25565).to_string()]);
                }
            }
            "singleplayer" if supported => args.extend(["--quickPlaySingleplayer".into(), value.into()]),
            "realm" if supported => args.extend(["--quickPlayRealms".into(), value.into()]),
            "singleplayer" | "realm" => (),
            _ => return Err(fail("invalid_params", "invalid quick-play option")),
        }
    }
    if flag(params, "fullscreen")? { args.push("--fullscreen".into()); }
    if let Some(proxy) = params.get("socks").filter(|value| !value.is_null()) {
        args.extend(["--proxyHost".into(), string(proxy,"host")?.into(), "--proxyPort".into(), string(proxy,"port")?.into()]);
        if let Some(username) = proxy.get("username").filter(|value| !value.is_null()) {
            args.extend(["--proxyUser".into(), username.as_str().ok_or_else(|| fail("invalid_params","invalid proxy user"))?.into(),
                "--proxyPass".into(), string(proxy,"password")?.into()]);
        }
    }
    if let Some(graphics) = params.get("graphics").filter(|value| !value.is_null()) {
        args.extend(["--graphicsBackend".into(), graphics.as_str().ok_or_else(|| fail("invalid_params","invalid graphics backend"))?.into()]);
    }
    if args.iter().map(String::len).sum::<usize>() > 512 * 1024 { return Err(fail("argument_limit", "launch options too large")); }
    Ok(json!({"arguments":args}))
}
