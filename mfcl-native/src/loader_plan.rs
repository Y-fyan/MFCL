// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Plans Fabric-family loader patches without writing game files.
use crate::{fail, Error};
use serde_json::{json, Value};

/// Projects bundled LiteLoader version records while preserving library order.
pub(crate) fn liteloader_catalog(params: &Value) -> Result<Value, Error> {
    let source = params.get("metadata").and_then(Value::as_str).filter(|s| s.len() <= 768 * 1024)
        .ok_or_else(|| fail("invalid_params", "invalid LiteLoader catalog"))?;
    let value = crate::json_unique::parse(source.as_bytes()).map_err(|_| fail("invalid_metadata", "invalid LiteLoader catalog"))?;
    let items = value.as_array().filter(|items| items.len() <= 20_000).ok_or_else(|| fail("invalid_metadata", "invalid LiteLoader records"))?;
    let raw_items: Vec<Box<serde_json::value::RawValue>> = serde_json::from_str(source)
        .map_err(|_| fail("invalid_metadata", "invalid LiteLoader records"))?;
    let mut output = Vec::with_capacity(items.len());
    for (item, raw_item) in items.iter().zip(raw_items) {
        let fields = item.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid LiteLoader record"))?;
        let string = |key: &str| fields.get(key).and_then(Value::as_str).ok_or_else(|| fail("unsupported_metadata", "invalid LiteLoader field"));
        let snapshot = fields.get("snapshot").and_then(Value::as_bool).ok_or_else(|| fail("unsupported_metadata", "invalid LiteLoader snapshot"))?;
        if !fields.get("libraries").and_then(Value::as_array).is_some_and(|items| items.iter().all(Value::is_object)) {
            return Err(fail("unsupported_metadata", "invalid LiteLoader libraries"));
        }
        let raw: std::collections::HashMap<String, Box<serde_json::value::RawValue>> = serde_json::from_str(raw_item.get())
            .map_err(|_| fail("invalid_metadata", "invalid LiteLoader record"))?;
        let libraries = crate::game_manifest::libraries(raw.get("libraries"))?;
        if !libraries.is_array() { return Err(fail("unsupported_metadata", "missing LiteLoader libraries")); }
        output.push(json!({"gameVersion":string("gameVersion")?,"version":string("version")?,"snapshot":snapshot,
            "url":string("url")?,"tweakClass":string("tweakClass")?,"libraries":libraries}));
    }
    Ok(json!({"records":output,"library_v3":true,"rules_v1":true}))
}

/// Builds the LiteLoader patch and its ordered library descriptors.
pub(crate) fn liteloader_patch(params: &Value) -> Result<Value, Error> {
    let version = params.get("version").and_then(Value::as_str).filter(|v| !v.is_empty() && v.is_ascii()
        && !v.contains(':') && !v.contains('@') && !v.contains('\\'))
        .ok_or_else(|| fail("unsupported_metadata", "unsupported LiteLoader version"))?;
    let url = params.get("url").and_then(Value::as_str).filter(|s| !s.is_empty())
        .ok_or_else(|| fail("unsupported_metadata", "missing LiteLoader URL"))?;
    let source = params.get("libraries").and_then(Value::as_str).filter(|s| s.len() <= 768 * 1024)
        .ok_or_else(|| fail("invalid_params", "invalid LiteLoader libraries"))?;
    let parsed = crate::json_unique::parse(source.as_bytes()).map_err(|_| fail("invalid_metadata", "invalid LiteLoader libraries"))?;
    if !parsed.as_array().is_some_and(|items| items.len() <= 20_000 && items.iter().all(Value::is_object)) {
        return Err(fail("unsupported_metadata", "invalid LiteLoader libraries"));
    }
    let raw = serde_json::value::RawValue::from_string(source.to_owned()).map_err(|_| fail("invalid_metadata", "invalid LiteLoader libraries"))?;
    let mut libraries = crate::game_manifest::libraries(Some(&raw))?.as_array().cloned()
        .ok_or_else(|| fail("unsupported_metadata", "invalid LiteLoader libraries"))?;
    let own = json!([{"name":format!("com.mumfrey:liteloader:{version}"),"url":"https://dl.liteloader.com/versions/",
        "downloads":{"artifact":{"url":url}}}]).to_string();
    let raw_own = serde_json::value::RawValue::from_string(own).map_err(|_| fail("invalid_metadata", "invalid LiteLoader library"))?;
    libraries.extend(crate::game_manifest::libraries(Some(&raw_own))?.as_array()
        .ok_or_else(|| fail("invalid_metadata", "invalid LiteLoader library"))?.iter().cloned());
    Ok(json!({"id":"liteloader","version":version,"priority":60000,"main_class":"net.minecraft.launchwrapper.Launch",
        "tweaker":"com.mumfrey.liteloader.launch.LiteLoaderTweaker","libraries":libraries,"library_v3":true,"rules_v1":true}))
}

/// Selects launcher metadata, ordered libraries and Maven coordinates for Fabric-family loaders.
pub(crate) fn plan(params: &Value) -> Result<Value, Error> {
    let kind = params.get("kind").and_then(Value::as_str).filter(|s| ["fabric", "legacyfabric", "quilt"].contains(s))
        .ok_or_else(|| fail("invalid_params", "unsupported loader kind"))?;
    let source = params.get("metadata").and_then(Value::as_str).filter(|s| s.len() <= 768 * 1024)
        .ok_or_else(|| fail("invalid_params", "invalid loader metadata"))?;
    let metadata: Value = serde_json::from_str(source).map_err(|_| fail("invalid_metadata", "loader metadata is not valid JSON"))?;
    let launcher = metadata.get("launcherMeta").and_then(Value::as_object)
        .ok_or_else(|| fail("invalid_metadata", "missing launcher metadata"))?;
    let main = launcher.get("mainClass").and_then(|value| if value.is_object() { value.get("client") } else { Some(value) })
        .and_then(Value::as_str).ok_or_else(|| fail("invalid_metadata", "missing client main class"))?;
    let tweaker = match launcher.get("launchwrapper") {
        None => None,
        Some(value) => Some(value.get("tweakers").and_then(|v| v.get("client")).and_then(Value::as_array)
            .and_then(|items| items.first()).and_then(Value::as_str)
            .ok_or_else(|| fail("invalid_metadata", "invalid launchwrapper tweaker"))?),
    };
    let libraries = launcher.get("libraries").and_then(Value::as_object)
        .ok_or_else(|| fail("invalid_metadata", "missing launcher libraries"))?;
    let mut selected = Vec::new();
    for side in ["common", "server"] {
        let list = libraries.get(side).and_then(Value::as_array)
            .ok_or_else(|| fail("invalid_metadata", "missing library side"))?;
        if selected.len() + list.len() > 20_000 { return Err(fail("metadata_limit", "too many libraries")); }
        for item in list {
            if !item.is_object() || contains_float(item) { return Err(fail("invalid_metadata", "unsupported library value")); }
            selected.push(item.clone());
        }
    }
    let coordinate = |field: &str| -> Result<Option<&str>, Error> {
        match metadata.get(field) {
            None | Some(Value::Null) if field == "intermediary" && kind == "quilt" => Ok(None),
            Some(value) => value.get("maven").and_then(Value::as_str).map(Some)
                .ok_or_else(|| fail("invalid_metadata", "missing Maven coordinate")),
            _ => Err(fail("invalid_metadata", "missing Maven coordinate")),
        }
    };
    let mut coordinates = Vec::new();
    if let Some(value) = coordinate("intermediary")? { coordinates.push(value); }
    if let Some(value) = coordinate("loader")? { coordinates.push(value); }
    Ok(json!({"main_class":main,"tweaker":tweaker,"libraries":selected,"coordinates":coordinates}))
}

/// Rejects floating-point JSON numbers to avoid changing their textual meaning during round trips.
fn contains_float(value: &Value) -> bool {
    match value {
        Value::Number(number) => number.is_f64(),
        Value::Array(items) => items.iter().any(contains_float),
        Value::Object(items) => items.values().any(contains_float),
        _ => false,
    }
}
