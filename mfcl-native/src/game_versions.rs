// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Parses ordinary official game-version catalogs without guessing legacy coercions.
use crate::{fail, Error};
use serde_json::{json, Value};

/// Parses canonical version records in source order.
pub(crate) fn parse(params: &Value) -> Result<Value, Error> {
    let source = params.get("metadata").and_then(Value::as_str).filter(|s| s.len() <= 768 * 1024)
        .ok_or_else(|| fail("invalid_params", "invalid game version catalog"))?;
    let data = crate::json_unique::parse(source.as_bytes()).map_err(|_| fail("invalid_metadata", "invalid or ambiguous game catalog"))?;
    let versions = data.get("versions").and_then(Value::as_array).ok_or_else(|| fail("invalid_metadata", "missing game versions"))?;
    if versions.len() > 20_000 { return Err(fail("version_limit", "too many game versions")); }
    let latest = data.get("latest").and_then(Value::as_object).ok_or_else(|| fail("invalid_metadata", "unsupported latest versions"))?;
    let release = latest.get("release").and_then(Value::as_str).ok_or_else(|| fail("invalid_metadata", "invalid latest release"))?;
    let snapshot = latest.get("snapshot").and_then(Value::as_str).ok_or_else(|| fail("invalid_metadata", "invalid latest snapshot"))?;
    let mut result = Vec::with_capacity(versions.len());
    for version in versions {
        let string = |name: &str| version.get(name).and_then(Value::as_str).ok_or_else(|| fail("invalid_metadata", "unsupported game version record"));
        let id = string("id")?; let url = string("url")?; let kind = string("type")?;
        let time = string("time")?; let released = string("releaseTime")?;
        let blank = |value: &str| value.bytes().all(|ch| (9..=13).contains(&ch) || (28..=32).contains(&ch));
        if !id.is_ascii() || !url.is_ascii() || !kind.is_ascii() || blank(id) || blank(url) {
            return Err(fail("invalid_metadata", "unsupported game version record"));
        }
        result.push(json!({"id":id,"url":url,"type":kind,"time":time,"releaseTime":released}));
    }
    Ok(json!({"versions":result,"latest":{"release":release,"snapshot":snapshot}}))
}
