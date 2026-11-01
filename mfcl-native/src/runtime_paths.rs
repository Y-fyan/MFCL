// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Validates relative Mojang runtime paths and symbolic-link relationships.
use crate::{fail, Error};
use serde_json::{json, Value};
use std::collections::HashSet;

/// Parses an ordinary slash-separated relative entry path.
fn entry_path(value: &str) -> Result<Vec<&str>, Error> {
    if value.is_empty() || value.starts_with('/') || value.contains('\0') {
        return Err(fail("unsafe_runtime_path", "runtime entry path is not relative"));
    }
    if value.contains('\\') || value.contains(':') { return Err(fail("unsupported_path", "runtime path requires Java handling")); }
    let parts: Vec<&str> = value.split('/').collect();
    if parts.iter().any(|part| *part == "..") { return Err(fail("unsafe_runtime_path", "runtime entry contains a parent component")); }
    if parts.iter().any(|part| part.is_empty() || *part == ".") { return Err(fail("unsupported_path", "runtime path requires Java handling")); }
    Ok(parts)
}

/// Resolves a relative symlink target without leaving the runtime root.
fn link_target<'a>(parent: &[&'a str], target: &'a str) -> Result<Vec<&'a str>, Error> {
    if target.is_empty() || target.starts_with('/') || target.contains('\0') {
        return Err(fail("unsafe_runtime_path", "runtime link target is not relative"));
    }
    if target.contains('\\') || target.contains(':') { return Err(fail("unsupported_path", "runtime link requires Java handling")); }
    let mut result = parent.to_vec(); let mut saw_name = false;
    for part in target.split('/') {
        match part {
            "" | "." => (),
            ".." => {
                if saw_name || result.pop().is_none() { return Err(fail("unsafe_runtime_path", "runtime link has unsafe parent traversal")); }
            },
            _ => { saw_name = true; result.push(part); },
        }
    }
    Ok(result)
}

/// Validates an ordered batch of entries without touching the filesystem.
pub(crate) fn validate(params: &Value) -> Result<Value, Error> {
    let entries = params.get("entries").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing runtime entries"))?;
    if entries.len() > 50_000 { return Err(fail("runtime_limit", "too many runtime entries")); }
    let mut all = HashSet::new(); let mut links = HashSet::new(); let mut paths = Vec::with_capacity(entries.len());
    for item in entries {
        let raw = item.get("path").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "invalid runtime entry"))?;
        let parts = entry_path(raw)?;
        if !all.insert(raw) { return Err(fail("unsafe_runtime_path", "duplicate runtime entry")); }
        let target = match item.get("target") {
            None | Some(Value::Null) => None,
            Some(Value::String(value)) => {
                links.insert(raw);
                Some(link_target(&parts[..parts.len() - 1], value)?)
            },
            _ => return Err(fail("invalid_params", "invalid runtime link target")),
        };
        paths.push((parts, target));
    }
    for (path, target) in paths {
        for count in 1..path.len() {
            if links.contains(path[..count].join("/").as_str()) {
                return Err(fail("unsafe_runtime_path", "runtime entry has a link ancestor"));
            }
        }
        if let Some(target) = target {
            for count in 1..=target.len() {
                if links.contains(target[..count].join("/").as_str()) {
                    return Err(fail("unsafe_runtime_path", "runtime link targets another link"));
                }
            }
        }
    }
    Ok(json!({"validated":true}))
}
