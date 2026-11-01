// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Expands Forge-family processor literals without touching the filesystem.
use crate::{fail, Error};
use serde_json::{json, Map, Value};
use std::path::Path;

const MAX_OUTPUT_BYTES: usize = 4 * 1024 * 1024;

/// Bounds intermediate expansion as well as the final response.
fn append(output: &mut String, value: &str) -> Result<(), Error> {
    if value.len() > MAX_OUTPUT_BYTES.saturating_sub(output.len()) {
        return Err(fail("argument_limit", "processor output too large"));
    }
    output.push_str(value);
    Ok(())
}

/// Validates processor variable and artifact maps shared by both operations.
fn maps(params: &Value) -> Result<(&Map<String, Value>, &Map<String, Value>), Error> {
    let vars = params.get("variables").and_then(Value::as_object).ok_or_else(|| fail("invalid_params", "variables are required"))?;
    let artifacts = params.get("artifacts").and_then(Value::as_object).ok_or_else(|| fail("invalid_params", "artifacts are required"))?;
    if vars.len() > 20_000 || artifacts.len() > 20_000 { return Err(fail("argument_limit", "too many processor values")); }
    if vars.values().any(|v| !v.is_string() && !v.is_null()) || artifacts.values().any(|v| !v.is_string()) {
        return Err(fail("invalid_params", "invalid processor value"));
    }
    Ok((vars, artifacts))
}

/// Expands one literal with Java-compatible whole-value and token rules.
fn expand_one(literal: &str, vars: &Map<String, Value>, artifacts: &Map<String, Value>) -> Result<Value, Error> {
    if literal.starts_with('{') && literal.ends_with('}') {
        Ok(vars.get(&literal[1..literal.len()-1]).cloned().unwrap_or(Value::Null))
    } else if literal.starts_with('\'') && literal.ends_with('\'') {
        if literal.len() >= 2 { Ok(json!(&literal[1..literal.len()-1])) } else { Ok(json!(literal)) }
    } else if literal.starts_with('[') && literal.ends_with(']') {
        artifacts.get(literal).filter(|v| v.is_string()).cloned().ok_or_else(|| fail("invalid_params", "unresolved artifact"))
    } else { Ok(json!(replace_tokens(literal, vars)?)) }
}

/// Expands processor strings using supplied variables and caller-resolved artifact paths.
pub(crate) fn expand(params: &Value) -> Result<Value, Error> {
    let literals = params.get("literals").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "literals are required"))?;
    let (vars, artifacts) = maps(params)?;
    if literals.len() > 20_000 { return Err(fail("argument_limit", "too many processor values")); }
    let mut output = Vec::with_capacity(literals.len()); let mut bytes = 0usize;
    for value in literals {
        let literal = value.as_str().ok_or_else(|| fail("invalid_params", "invalid processor literal"))?;
        let result = expand_one(literal, vars, artifacts)?;
        if let Some(text) = result.as_str() { bytes = bytes.checked_add(text.len()).ok_or_else(|| fail("argument_limit", "processor output too large"))?; }
        if bytes > MAX_OUTPUT_BYTES { return Err(fail("argument_limit", "processor output too large")); }
        output.push(result);
    }
    Ok(json!({"values":output}))
}

/// Resolves ordinary Maven coordinates under the launcher library root before expanding literals.
pub(crate) fn expand_v2(params: &Value) -> Result<Value, Error> {
    let root = params.get("artifact_root").and_then(Value::as_str).filter(|s| !s.is_empty() && !s.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "invalid artifact root"))?;
    let literals = params.get("literals").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "literals are required"))?;
    if literals.len() > 20_000 { return Err(fail("argument_limit", "too many processor values")); }
    let artifacts = artifact_map(root, literals.iter())?;
    let mut expanded = params.clone();
    expanded.as_object_mut().ok_or_else(|| fail("invalid_params", "invalid processor arguments"))?.insert("artifacts".into(), Value::Object(artifacts));
    expand(&expanded)
}

/// Resolves bracketed coordinates while keeping unsupported descriptors on the legacy path.
fn artifact_map<'a>(root: &str, literals: impl Iterator<Item = &'a Value>) -> Result<Map<String, Value>, Error> {
    let mut artifacts = Map::new();
    for literal in literals {
        let literal = literal.as_str().ok_or_else(|| fail("invalid_params", "invalid processor literal"))?;
        if !literal.starts_with('[') || !literal.ends_with(']') { continue; }
        let descriptor = &literal[1..literal.len() - 1];
        let parts: Vec<&str> = descriptor.split(':').collect();
        if !(3..=4).contains(&parts.len()) { return Err(fail("unsupported_metadata", "unsupported artifact descriptor")); }
        let last: Vec<&str> = parts[parts.len() - 1].split('@').collect();
        if last.len() > 2 { return Err(fail("unsupported_metadata", "unsupported artifact extension")); }
        let ordinary = |s: &str| !s.is_empty() && s != "." && s != ".."
            && s.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-' | b'+'));
        if !parts[..parts.len() - 1].iter().all(|s| ordinary(s)) || !last.iter().all(|s| ordinary(s))
            || parts[0].split('.').any(|s| s.is_empty() || s == "." || s == "..") {
            return Err(fail("unsupported_metadata", "unsupported artifact coordinate"));
        }
        let version = if parts.len() == 4 { parts[2] } else { last[0] };
        let classifier = if parts.len() == 4 { Some(last[0]) } else { None };
        let extension = last.get(1).copied().unwrap_or("jar");
        let filename = match classifier { Some(c) => format!("{}-{version}-{c}.{extension}",parts[1]), None => format!("{}-{version}.{extension}",parts[1]) };
        let relative = format!("{}/{}/{version}/{filename}",parts[0].replace('.', "/"),parts[1]);
        let path = Path::new(root).join(relative);
        let path = path.to_str().ok_or_else(|| fail("unsupported_metadata", "invalid artifact path"))?;
        artifacts.insert(literal.to_owned(), json!(path));
    }
    Ok(artifacts)
}

/// Plans options after resolving standard artifact paths in Rust.
pub(crate) fn plan_options_v2(params: &Value) -> Result<Value, Error> {
    let root = params.get("artifact_root").and_then(Value::as_str).filter(|s| !s.is_empty() && !s.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "invalid artifact root"))?;
    let groups = params.get("groups").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "processor groups are required"))?;
    if groups.len() > 20_000 { return Err(fail("argument_limit", "too many processor groups")); }
    let mut literals = Vec::new();
    for group in groups {
        let group = group.as_array().ok_or_else(|| fail("invalid_params", "invalid processor group"))?;
        if group.len() > 20_000usize.saturating_sub(literals.len()) { return Err(fail("argument_limit", "too many processor arguments")); }
        literals.extend(group.iter());
    }
    let artifacts = artifact_map(root, literals.into_iter())?;
    let mut expanded = params.clone();
    expanded.as_object_mut().ok_or_else(|| fail("invalid_params", "invalid processor options"))?.insert("artifacts".into(), Value::Object(artifacts));
    plan_options(&expanded)
}

/// Selects and expands option values for a sequence of installer processors.
pub(crate) fn plan_options(params: &Value) -> Result<Value, Error> {
    let groups = params.get("groups").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "processor groups are required"))?;
    let (vars, artifacts) = maps(params)?;
    if groups.len() > 20_000 { return Err(fail("argument_limit", "too many processor groups")); }
    let mut output = Vec::with_capacity(groups.len()); let mut count = 0usize; let mut bytes = 0usize;
    for group in groups {
        let args = group.as_array().ok_or_else(|| fail("invalid_params", "invalid processor group"))?;
        count = count.checked_add(args.len()).ok_or_else(|| fail("argument_limit", "too many processor arguments"))?;
        if count > 20_000 { return Err(fail("argument_limit", "too many processor arguments")); }
        let mut options = Vec::new(); let mut pending: Option<&str> = None;
        for arg in args {
            let arg = arg.as_str().ok_or_else(|| fail("invalid_params", "invalid processor argument"))?;
            if let Some(name) = arg.strip_prefix("--") {
                if let Some(previous) = pending { options.push(json!({"name":previous,"value":""})); }
                pending = Some(name);
            } else if let Some(name) = pending.take() {
                let value = expand_one(arg, vars, artifacts)?;
                bytes = bytes.checked_add(name.len()).and_then(|v| v.checked_add(value.as_str().map_or(0, str::len)))
                    .ok_or_else(|| fail("argument_limit", "processor output too large"))?;
                if bytes > MAX_OUTPUT_BYTES { return Err(fail("argument_limit", "processor output too large")); }
                options.push(json!({"name":name,"value":value}));
            }
        }
        if let Some(name) = pending { options.push(json!({"name":name,"value":""})); }
        output.push(options);
    }
    Ok(json!({"groups":output}))
}

/// Reproduces the launcher's brace, quote and backslash substitution for ordinary literals.
fn replace_tokens(input: &str, vars: &Map<String, Value>) -> Result<String, Error> {
    let mut chars = input.chars(); let mut output = String::new();
    while let Some(ch) = chars.next() {
        if ch == '\\' {
            let escaped = chars.next().ok_or_else(|| fail("invalid_literal", "bad escape"))?;
            append(&mut output, escaped.encode_utf8(&mut [0; 4]))?;
        } else if ch == '{' || ch == '\'' {
            let end = if ch == '{' { '}' } else { '\'' }; let mut key = String::new(); let mut closed = false;
            while let Some(next) = chars.next() {
                if next == '\\' { key.push(chars.next().ok_or_else(|| fail("invalid_literal", "bad escape"))?); }
                else if next == end { closed = true; break; }
                else { key.push(next); }
            }
            if !closed { return Err(fail("invalid_literal", "unclosed token")); }
            if ch == '\'' { append(&mut output, &key)?; }
            else {
                match vars.get(&key) {
                    Some(Value::String(value)) => append(&mut output, value)?,
                    Some(Value::Null) => append(&mut output, "null")?,
                    _ => return Err(fail("invalid_literal", "missing token")),
                }
            }
        } else { append(&mut output, ch.encode_utf8(&mut [0; 4]))?; }
    }
    Ok(output)
}
