// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Selects Modrinth client downloads before filesystem inspection.
use crate::{fail, Error};
use serde_json::{json, Value};
use std::collections::HashSet;

/// Returns eligible manifest positions in their original order.
pub(crate) fn select(params: &Value) -> Result<Value, Error> {
    let files = params.get("files").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing modpack files"))?;
    if files.len() > 20_000 { return Err(fail("modpack_limit", "too many modpack files")); }
    let excluded = match params.get("excluded") {
        None | Some(Value::Null) => None,
        Some(Value::Array(items)) => {
            let mut set = HashSet::new();
            for item in items { set.insert(item.as_str().ok_or_else(|| fail("invalid_params", "invalid excluded key"))?); }
            Some(set)
        },
        _ => return Err(fail("invalid_params", "invalid exclusions")),
    };
    let mut indices = Vec::new();
    for (index, file) in files.iter().enumerate() {
        let client = file.get("client").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "invalid client environment"))?;
        if client == "unsupported" { continue; }
        if !file.get("has_downloads").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "invalid downloads"))? { continue; }
        if let Some(excluded) = &excluded {
            let path = file.get("path").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "invalid modpack path"))?;
            let sha = file.get("sha512").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "invalid modpack hash"))?;
            if excluded.contains(format!("modrinth:{sha}:{path}").as_str()) { continue; }
        }
        indices.push(index);
    }
    Ok(json!({"indices":indices}))
}

/// Resolves canonical relative client file paths inside the absolute game root.
pub(crate) fn plan(params: &Value) -> Result<Value, Error> {
    use std::path::Path;
    let root = params.get("root").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing modpack root"))?;
    let root = Path::new(root);
    if !root.is_absolute() { return Err(fail("invalid_params", "modpack root must be absolute")); }
    let selection = select(params)?;
    let files = params["files"].as_array().ok_or_else(|| fail("invalid_params", "missing modpack files"))?;
    let mut result = Vec::new();
    for value in selection["indices"].as_array().ok_or_else(|| fail("invalid_params", "invalid selection"))? {
        let index = value.as_u64().and_then(|v| usize::try_from(v).ok()).ok_or_else(|| fail("invalid_params", "invalid selection index"))?;
        let raw = files[index].get("path").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "invalid modpack path"))?;
        // Ambiguous or platform-specific paths retain the Java implementation.
        if raw.is_empty() || raw.contains('\\') || raw.contains('\0') || raw.starts_with('/')
                || raw.split('/').any(|part| part.is_empty() || part == "." || part == "..") {
            return Err(fail("unsupported_path", "noncanonical modpack path"));
        }
        let relative = Path::new(raw);
        if relative.is_absolute() || relative.components().any(|part| !matches!(part, std::path::Component::Normal(_))) {
            return Err(fail("unsupported_path", "noncanonical modpack path"));
        }
        let path = root.join(relative);
        let path = path.to_str().ok_or_else(|| fail("unsupported_path", "invalid modpack path encoding"))?;
        let sha = files[index].get("sha512").and_then(Value::as_str);
        let valid_sha512 = sha.is_some_and(|value| value.len() == 128 && value.bytes().all(|ch| ch.is_ascii_hexdigit()));
        result.push(json!({"index":index,"path":path,"sha512":sha,"valid_sha512":valid_sha512}));
    }
    Ok(json!({"files":result}))
}

/// Finds old entries absent from the new manifest using canonical JSON equality.
pub(crate) fn removed(params: &Value) -> Result<Value, Error> {
    let old = params.get("old").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing old files"))?;
    let new = params.get("new").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing new files"))?;
    if old.len() > 20_000 || new.len() > 20_000 { return Err(fail("modpack_limit", "too many modpack files")); }
    let mut present = HashSet::with_capacity(new.len());
    for value in new {
        if !value.is_object() { return Err(fail("invalid_params", "invalid modpack file")); }
        present.insert(serde_json::to_string(value).map_err(|_| fail("invalid_params", "invalid modpack file"))?);
    }
    let mut indices = Vec::new();
    for (index, value) in old.iter().enumerate() {
        if !value.is_object() { return Err(fail("invalid_params", "invalid modpack file")); }
        let key = serde_json::to_string(value).map_err(|_| fail("invalid_params", "invalid modpack file"))?;
        if !present.contains(&key) { indices.push(index); }
    }
    Ok(json!({"indices":indices}))
}
