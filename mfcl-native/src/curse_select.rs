// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Plans CurseForge metadata and download selection without handling credentials.
use crate::{fail, Error};
use serde_json::{json, Value};
use std::collections::HashSet;

/// Selects metadata lookups or client downloads while preserving source order.
pub(crate) fn select(params: &Value) -> Result<Value, Error> {
    let files = params.get("files").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing CurseForge files"))?;
    if files.len() > 20_000 { return Err(fail("modpack_limit", "too many CurseForge files")); }
    let mode = params.get("mode").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing selection mode"))?;
    if mode != "resolve" && mode != "download" { return Err(fail("invalid_params", "invalid selection mode")); }
    let excluded = match params.get("excluded") {
        None | Some(Value::Null) => None,
        Some(Value::Array(items)) => {
            let mut set = HashSet::new();
            for item in items { set.insert(item.as_str().ok_or_else(|| fail("invalid_params", "invalid excluded key"))?); }
            Some(set)
        },
        _ => return Err(fail("invalid_params", "invalid exclusions")),
    };
    let mut selected = Vec::new();
    for (index, file) in files.iter().enumerate() {
        let name = match file.get("name") { None | Some(Value::Null) => None, Some(Value::String(value)) => Some(value.as_str()), _ => return Err(fail("invalid_params", "invalid file name")) };
        let include = if mode == "resolve" {
            match name {
                None => true,
                Some(value) if value.is_ascii() => value.bytes().all(|ch| (9..=13).contains(&ch) || (28..=32).contains(&ch)),
                _ => return Err(fail("unsupported_value", "non-ASCII file name requires Java handling")),
            }
        } else if name.is_none() { false }
        else if let Some(excluded) = &excluded {
            let project = file.get("project").and_then(Value::as_i64).and_then(|n| i32::try_from(n).ok()).ok_or_else(|| fail("invalid_params", "invalid project"))?;
            let id = file.get("file").and_then(Value::as_i64).and_then(|n| i32::try_from(n).ok()).ok_or_else(|| fail("invalid_params", "invalid file id"))?;
            !excluded.contains(format!("curseforge:{project}:{id}").as_str())
        } else { true };
        if include { selected.push(index); }
    }
    Ok(json!({"indices":selected}))
}

/// Plans download candidates and the first available supported integrity algorithm.
pub(crate) fn plan(params: &Value) -> Result<Value, Error> {
    if params.get("mode").and_then(Value::as_str) != Some("download") { return Err(fail("invalid_params", "invalid selection mode")); }
    let selected = select(params)?;
    let files = params["files"].as_array().ok_or_else(|| fail("invalid_params", "missing files"))?;
    let mut result = Vec::new();
    for value in selected["indices"].as_array().ok_or_else(|| fail("invalid_params", "invalid selection"))? {
        let index = value.as_u64().and_then(|n| usize::try_from(n).ok()).ok_or_else(|| fail("invalid_params", "invalid index"))?;
        let hashes = match files[index].get("hashes") {
            None | Some(Value::Null) => None,
            Some(Value::Object(values)) => Some(values),
            _ => return Err(fail("invalid_params", "invalid hashes")),
        };
        let mut integrity = None;
        if let Some(hashes) = hashes {
            for (key, algorithm) in [("sha1", "SHA-1"), ("sha256", "SHA-256"), ("sha512", "SHA-512"), ("md5", "MD5")] {
                match hashes.get(key) {
                    None | Some(Value::Null) => (),
                    Some(Value::String(value)) => { integrity = Some(json!({"algorithm":algorithm,"checksum":value})); break; },
                    _ => return Err(fail("invalid_params", "invalid checksum")),
                }
            }
        }
        result.push(json!({"index":index,"integrity":integrity}));
    }
    Ok(json!({"files":result}))
}
