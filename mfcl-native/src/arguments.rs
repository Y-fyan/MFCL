// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Minecraft launch-argument placeholder expansion.
use crate::{fail, Error};
use regex::Regex;
use serde_json::{json, Value};

const MAX_GROUPS: usize = 32;
const MAX_ARGUMENTS: usize = 20_000;
const MAX_MATCHES: usize = 10_000;
const MAX_OUTPUT_BYTES: usize = 4 * 1024 * 1024;

/// Expands placeholders with the same sequential replacement order as Java StringArgument.
pub(crate) fn expand(params: &Value) -> Result<Value, Error> {
    let groups = params.get("groups").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "argument groups are required"))?;
    let keys = params.get("keys").and_then(Value::as_object).ok_or_else(|| fail("invalid_params", "placeholder map is required"))?;
    if groups.len() > MAX_GROUPS || keys.len() > 1000 { return Err(fail("argument_limit", "too many argument groups or keys")); }
    if keys.values().any(|value| !value.is_string()) { return Err(fail("invalid_params", "placeholder values must be strings")); }
    // Java Pattern's default dot excludes these line terminators. Matches are taken from the
    // original argument; each replacement applies to the whole evolving result in match order.
    let pattern = Regex::new(r"\$\{[^\n\r\x{85}\x{2028}\x{2029}]*?\}").map_err(|_| fail("internal_error", "invalid placeholder pattern"))?;
    let mut expanded = Vec::with_capacity(groups.len());
    let mut count = 0usize;
    let mut matches = 0usize;
    let mut output_bytes = 0usize;
    for group in groups {
        let args = group.as_array().ok_or_else(|| fail("invalid_params", "argument group must be an array"))?;
        count = count.checked_add(args.len()).ok_or_else(|| fail("argument_limit", "too many arguments"))?;
        if count > MAX_ARGUMENTS { return Err(fail("argument_limit", "too many arguments")); }
        let mut result = Vec::with_capacity(args.len());
        for arg in args {
            if arg.is_null() { continue; }
            let original = arg.as_str().ok_or_else(|| fail("invalid_params", "arguments must be strings or null"))?;
            let mut value = original.to_owned();
            for found in pattern.find_iter(original) {
                matches += 1;
                if matches > MAX_MATCHES { return Err(fail("argument_limit", "too many placeholders")); }
                let token = found.as_str();
                if let Some(replacement) = keys.get(token).and_then(Value::as_str) {
                    let occurrences = value.matches(token).count();
                    let removed = occurrences.checked_mul(token.len()).ok_or_else(|| fail("argument_limit", "argument size overflow"))?;
                    let inserted = occurrences.checked_mul(replacement.len()).ok_or_else(|| fail("argument_limit", "argument size overflow"))?;
                    let new_size = value.len().checked_sub(removed).and_then(|n| n.checked_add(inserted))
                        .ok_or_else(|| fail("argument_limit", "argument size overflow"))?;
                    if new_size > MAX_OUTPUT_BYTES { return Err(fail("argument_limit", "expanded argument exceeds limit")); }
                    value = value.replace(token, replacement);
                }
            }
            output_bytes = output_bytes.checked_add(value.len()).ok_or_else(|| fail("argument_limit", "argument size overflow"))?;
            if output_bytes > MAX_OUTPUT_BYTES { return Err(fail("argument_limit", "expanded arguments exceed limit")); }
            result.push(value);
        }
        expanded.push(result);
    }
    Ok(json!({"groups": expanded}))
}

/// Matches a bounded common subset of Java and Rust regular expressions.
fn portable_rule_regex(rule: &Value, pattern_key: &str, value_key: &str) -> Result<bool, Error> {
    let Some(pattern) = rule.get(pattern_key).filter(|value| !value.is_null()) else { return Ok(true); };
    let pattern = pattern.as_str().ok_or_else(|| fail("invalid_params", "invalid OS regex"))?;
    let value = rule.get(value_key).and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing OS value"))?;
    if pattern.len() > 256 || !value.bytes().all(|c| (32..127).contains(&c)) {
        return Err(fail("invalid_params", "unsupported OS regex"));
    }
    let mut chars = pattern.bytes();
    while let Some(ch) = chars.next() {
        if ch == b'\\' {
            if chars.next() != Some(b'.') { return Err(fail("invalid_params", "unsupported OS escape")); }
        } else if !ch.is_ascii_alphanumeric() && !b"._^$*+-".contains(&ch) {
            return Err(fail("invalid_params", "unsupported OS regex"));
        }
    }
    let regex = Regex::new(&format!(r"\A(?:{})\z", pattern)).map_err(|_| fail("invalid_params", "invalid OS regex"))?;
    Ok(regex.is_match(value))
}

/// Selects rule-governed launch values, then applies normal placeholder expansion.
pub(crate) fn expand_ruled(params: &Value) -> Result<Value, Error> {
    let groups = params.get("groups").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "argument groups are required"))?;
    if groups.len() > MAX_GROUPS { return Err(fail("argument_limit", "too many argument groups")); }
    let mut plain_groups = Vec::with_capacity(groups.len());
    let mut count = 0usize;
    for group in groups {
        let args = group.get("arguments").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "arguments must be an array"))?;
        let features = group.get("features").and_then(Value::as_object).ok_or_else(|| fail("invalid_params", "features must be an object"))?;
        if features.values().any(|v| !v.is_boolean() && !v.is_null()) { return Err(fail("invalid_params", "invalid feature value")); }
        let mut selected = Vec::new();
        for argument in args {
            let before = selected.len();
            if argument.is_null() { continue; }
            if argument.is_string() { selected.push(argument.clone()); }
            else {
                let object = argument.as_object().ok_or_else(|| fail("invalid_params", "invalid ruled argument"))?;
                let mut allow = true;
                if let Some(rule_values) = object.get("rules").filter(|v| !v.is_null()) {
                    let rules = rule_values.as_array().ok_or_else(|| fail("invalid_params", "invalid rules"))?;
                    if !rules.is_empty() {
                        allow = false;
                        for rule in rules {
                            let os = if let Some(value) = rule.get("os_name") {
                                let name = if value.is_null() { "" } else { value.as_str().ok_or_else(|| fail("invalid_params", "invalid OS name"))? };
                                let system = rule.get("system_os").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "invalid current OS"))?;
                                let name = name.trim_matches(|c: char| c <= ' ').to_ascii_lowercase();
                                if !name.is_ascii() { return Err(fail("invalid_params", "unsupported OS name")); }
                                let mut compound = None;
                                if name.contains('-') {
                                    let (os, arch) = name.split_once('-').ok_or_else(|| fail("invalid_params", "invalid compound OS"))?;
                                    let os = os.trim_matches(|c: char| c <= ' ');
                                    let arch = arch.trim_matches(|c: char| c <= ' ');
                                    let requested_arch = match arch {
                                        "x8664" | "x86_64" | "amd64" | "ia32e" | "em64t" | "x64" | "intel64" => "X86_64",
                                        "x8632" | "x86_32" | "x86" | "i86pc" | "i386" | "i486" | "i586" | "i686" | "ia32" | "x32" => "X86",
                                        "arm64" | "aarch64" => "ARM64",
                                        "arm" | "arm32" => "ARM32",
                                        _ => return Err(fail("invalid_params", "unsupported compound architecture")),
                                    };
                                    if !["linux", "windows", "win", "osx", "mac", "darwin", "freebsd", "solaris", "unix", "sunos"].contains(&os) {
                                        return Err(fail("invalid_params", "unsupported compound OS"));
                                    }
                                    let system_arch = rule.get("system_arch_enum").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing system architecture"))?;
                                    compound = Some((os, requested_arch == system_arch));
                                }
                                let name = compound.map(|(os, _)| os).unwrap_or(&name);
                                let requested = if name.contains("mac") || name.contains("darwin") || name.contains("osx") { "MACOS" }
                                    else if name.contains("win") { "WINDOWS" }
                                    else if ["solaris", "linux", "unix", "sunos"].iter().any(|s| name.contains(s)) { "LINUX" }
                                    else if name == "freebsd" { "FREEBSD" } else { "UNKNOWN" };
                                if !["MACOS", "WINDOWS", "LINUX", "FREEBSD", "UNKNOWN"].contains(&system) { return Err(fail("invalid_params", "invalid current OS")); }
                                let os_matches = requested == "UNKNOWN" || requested == system || (requested == "LINUX" && system == "FREEBSD");
                                if let Some((_, arch_matches)) = compound { os_matches && arch_matches }
                                else { os_matches && portable_rule_regex(rule, "version_regex", "system_version")?
                                    && portable_rule_regex(rule, "arch_regex", "system_arch")? }
                            } else {
                                rule.get("os_matches").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "invalid OS match"))?
                            };
                            if !os { continue; }
                            if let Some(feature_values) = rule.get("features").filter(|v| !v.is_null()) {
                                let required = feature_values.as_object().ok_or_else(|| fail("invalid_params", "invalid feature requirements"))?;
                                let mut matches = true;
                                for (key, expected) in required {
                                    if !expected.is_boolean() && !expected.is_null() { return Err(fail("invalid_params", "invalid feature requirement")); }
                                    if features.get(key).unwrap_or(&Value::Null) != expected { matches = false; break; }
                                }
                                if !matches { continue; }
                            }
                            match rule.get("action") {
                                None | Some(Value::Null) => {},
                                Some(Value::String(action)) if action == "allow" => allow = true,
                                Some(Value::String(action)) if action == "disallow" => allow = false,
                                _ => return Err(fail("invalid_params", "invalid rule action")),
                            }
                        }
                    }
                }
                if allow {
                    if let Some(values) = object.get("values").filter(|v| !v.is_null()) {
                        let values = values.as_array().ok_or_else(|| fail("invalid_params", "invalid argument values"))?;
                        for value in values {
                            if value.is_null() { continue; }
                            if !value.is_string() { return Err(fail("invalid_params", "invalid argument value")); }
                            selected.push(value.clone());
                        }
                    }
                }
            }
            count = count.checked_add(selected.len() - before).ok_or_else(|| fail("argument_limit", "too many arguments"))?;
            if count > MAX_ARGUMENTS { return Err(fail("argument_limit", "too many arguments")); }
        }
        plain_groups.push(Value::Array(selected));
    }
    expand(&json!({"groups":plain_groups,"keys":params.get("keys").unwrap_or(&Value::Null)}))
}
