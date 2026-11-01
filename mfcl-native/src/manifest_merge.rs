// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Merges typed manifest fields represented by caller-owned opaque tokens.
use crate::{fail, Error};
use serde_json::{json, Map, Value};

/// Returns a nullable opaque token after validating its representation.
fn token(object: &Map<String, Value>, name: &str) -> Result<Value, Error> {
    match object.get(name) {
        Some(Value::Null) => Ok(Value::Null),
        Some(value) if value.as_u64().is_some_and(|id| id > 0) => Ok(value.clone()),
        _ => Err(fail("invalid_params", "invalid manifest token")),
    }
}

/// Joins two nullable token arrays while preserving element order and null elements.
fn join(a: Option<&Value>, b: Option<&Value>, null_if_both: bool) -> Result<Value, Error> {
    if null_if_both && a.is_none_or(Value::is_null) && b.is_none_or(Value::is_null) { return Ok(Value::Null); }
    let mut output = Vec::new();
    for value in [a, b].into_iter().flatten() {
        if value.is_null() { continue; }
        let list = value.as_array().ok_or_else(|| fail("invalid_params", "invalid manifest token list"))?;
        if output.len() + list.len() > 100_000 { return Err(fail("manifest_limit", "too many merged entries")); }
        for item in list {
            if !item.is_null() && !item.as_u64().is_some_and(|id| id > 0) { return Err(fail("invalid_params", "invalid list token")); }
            output.push(item.clone());
        }
    }
    Ok(Value::Array(output))
}

/// Merges nullable argument groups while retaining an untouched source reference when possible.
fn merge_arguments(a: &Value, b: &Value) -> Result<Value, Error> {
    if a.is_null() { return Ok(b.clone()); }
    if b.is_null() { return Ok(a.clone()); }
    let a = a.as_object().ok_or_else(|| fail("invalid_params", "invalid arguments"))?;
    let b = b.as_object().ok_or_else(|| fail("invalid_params", "invalid arguments"))?;
    Ok(json!({"ref":null,"game":join(a.get("game"),b.get("game"),true)?,"jvm":join(a.get("jvm"),b.get("jvm"),true)?}))
}

/// Parses a nullable Java integer launcher version.
fn minimum(object: &Map<String, Value>) -> Result<Option<i64>, Error> {
    match object.get("minimum") { Some(Value::Null) => Ok(None), Some(v) => v.as_i64().filter(|n| i32::try_from(*n).is_ok()).map(Some).ok_or_else(|| fail("invalid_params", "invalid minimum version")), None => Err(fail("invalid_params", "missing minimum version")) }
}

/// Applies the inheritance merge order to opaque tokens and primitive version values.
pub(crate) fn merge(params: &Value) -> Result<Value, Error> {
    let child = params.get("child").and_then(Value::as_object).ok_or_else(|| fail("invalid_params", "child is required"))?;
    let parent = params.get("parent").and_then(Value::as_object).ok_or_else(|| fail("invalid_params", "parent is required"))?;
    let mut out = Map::new();
    for name in ["id", "compliance", "hidden"] { out.insert(name.into(), token(child, name)?); }
    for name in ["minecraft_arguments", "main_class", "jar", "asset_index", "assets", "java_version", "downloads", "logging", "type", "time", "release_time"] {
        let value = token(child, name)?;
        out.insert(name.into(), if value.is_null() { token(parent, name)? } else { value });
    }
    out.insert("libraries".into(), join(child.get("libraries"), parent.get("libraries"), false)?);
    out.insert("rules".into(), join(parent.get("rules"), child.get("rules"), false)?);
    let patch = token(child, "self_patch")?;
    if patch.is_null() { return Err(fail("invalid_params", "missing child patch")); }
    let parent_patches = join(parent.get("patches"), Some(&json!([patch])), false)?;
    out.insert("patches".into(), join(Some(&parent_patches), child.get("patches"), false)?);
    let a = parent.get("arguments").ok_or_else(|| fail("invalid_params", "missing arguments"))?;
    let b = child.get("arguments").ok_or_else(|| fail("invalid_params", "missing arguments"))?;
    let arguments = merge_arguments(a, b)?;
    out.insert("arguments".into(), arguments);
    let minimum = match (minimum(child)?, minimum(parent)?) { (Some(a), Some(b)) => Some(a.max(b)), (Some(a), None) => Some(a), (None, Some(b)) => Some(b), _ => None };
    out.insert("minimum".into(), json!(minimum));
    Ok(Value::Object(out))
}

/// Applies patches to a typed-token base, optionally sorting by stable priority.
pub(crate) fn apply_patches(params: &Value, sort: bool) -> Result<Value, Error> {
    let mut result = params.get("base").and_then(Value::as_object).cloned().ok_or_else(|| fail("invalid_params", "base is required"))?;
    let patches = params.get("patches").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "patches are required"))?;
    if patches.len() > 20_000 { return Err(fail("manifest_limit", "too many patches")); }
    let mut ordered = Vec::with_capacity(patches.len());
    for value in patches {
        let patch = value.as_object().ok_or_else(|| fail("invalid_params", "invalid patch"))?;
        let priority = patch.get("priority").and_then(Value::as_i64).filter(|v| i32::try_from(*v).is_ok())
            .ok_or_else(|| fail("invalid_params", "invalid patch priority"))?;
        ordered.push((priority, patch));
    }
    if sort { ordered.sort_by_key(|(priority, _)| *priority); }
    for (_, patch) in ordered {
        for name in ["minecraft_arguments", "main_class", "asset_index", "assets", "compliance", "java_version", "downloads", "logging", "type", "time", "release_time"] {
            let value = token(patch, name)?;
            if !value.is_null() { result.insert(name.into(), value); }
        }
        result.insert("libraries".into(), join(result.get("libraries"), patch.get("libraries"), false)?);
        result.insert("rules".into(), join(result.get("rules"), patch.get("rules"), false)?);
        let args = merge_arguments(result.get("arguments").ok_or_else(|| fail("invalid_params", "missing base arguments"))?,
                                   patch.get("arguments").ok_or_else(|| fail("invalid_params", "missing patch arguments"))?)?;
        result.insert("arguments".into(), args);
        let version = match (minimum(&result)?, minimum(patch)?) {
            (Some(a), Some(b)) => Some(a.max(b)), (Some(a), None) => Some(a), (None, Some(b)) => Some(b), _ => None,
        };
        result.insert("minimum".into(), json!(version));
    }
    Ok(Value::Object(result))
}
