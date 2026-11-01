// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Projects ordinary Forge-family installer profiles into typed fields.
use crate::{fail, game_manifest::{libraries, OrderedEntries}, Error};
use serde_json::{json, value::RawValue, Value};
use std::collections::HashMap;

fn artifact(value: &Value) -> Result<Value, Error> {
    let name = value.as_str().ok_or_else(|| fail("unsupported_metadata", "invalid artifact"))?;
    let parts: Vec<_> = name.split(':').collect();
    if !(3..=4).contains(&parts.len()) || parts.iter().any(|v| v.is_empty() || v.contains('\\'))
        || parts[..parts.len()-1].iter().any(|v| v.contains('@')) { return Err(fail("unsupported_metadata", "unsupported artifact")); }
    let last: Vec<_> = parts[parts.len()-1].split('@').collect();
    if last.len() > 2 || last.iter().any(|v| v.is_empty()) { return Err(fail("unsupported_metadata", "invalid artifact extension")); }
    let (version, classifier) = if parts.len() == 4 { (parts[2], Some(last[0])) } else { (last[0], None) };
    Ok(json!({"group":parts[0],"name":parts[1],"version":version,"classifier":classifier,"extension":last.get(1)}))
}

/// Projects the legacy installer descriptor without applying Gson coercions.
pub(crate) fn legacy_install(value: &Value) -> Result<Value, Error> {
    let fields = value.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid legacy installer"))?;
    let mut output = serde_json::Map::new();
    for key in ["profileName", "target", "version", "filePath", "welcome", "minecraft", "mirrorList", "logo"] {
        let field = fields.get(key).cloned().unwrap_or(Value::Null);
        if !field.is_null() && !field.is_string() { return Err(fail("unsupported_metadata", "invalid legacy field")); }
        output.insert(key.to_owned(), field);
    }
    let path = match fields.get("path") { None | Some(Value::Null) => Value::Null, Some(value) => artifact(value)? };
    output.insert("path".to_owned(), path);
    output.insert("legacy_install_v1".to_owned(), Value::Bool(true));
    Ok(Value::Object(output))
}

fn string_list(value: Option<&Value>) -> Result<Value, Error> {
    match value {
        None | Some(Value::Null) => Ok(Value::Null),
        Some(Value::Array(items)) if items.iter().all(|v| v.is_null() || v.is_string()) => Ok(json!(items)),
        _ => Err(fail("unsupported_metadata", "invalid installer list")),
    }
}

/// Reads fields with source order preserved where later Java processing depends on it.
pub(crate) fn project(source: &str, value: &Value) -> Result<Value, Error> {
    let object = value.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid installer profile"))?;
    let raw: HashMap<String, Box<RawValue>> = serde_json::from_str(source).map_err(|_| fail("unsupported_metadata", "invalid installer profile"))?;
    let required = |key: &str| object.get(key).and_then(Value::as_str).ok_or_else(|| fail("unsupported_metadata", "missing installer field"));
    let spec = match object.get("spec") { None | Some(Value::Null) => 0,
        Some(v) => v.as_i64().and_then(|n| i32::try_from(n).ok()).ok_or_else(|| fail("unsupported_metadata", "invalid installer spec"))? };
    let path = match object.get("path") { None | Some(Value::Null) => Value::Null, Some(v) => artifact(v)? };
    let libraries = libraries(raw.get("libraries"))?;
    let processors = match raw.get("processors") {
        None => Value::Null,
        Some(v) if v.get() == "null" => Value::Null,
        Some(v) => {
            let entries: Vec<Box<RawValue>> = serde_json::from_str(v.get()).map_err(|_| fail("unsupported_metadata", "invalid processors"))?;
            if entries.len() > 20_000 { return Err(fail("unsupported_metadata", "too many processors")); }
            let mut out = Vec::new();
            for raw_processor in entries {
                let value: Value = serde_json::from_str(raw_processor.get()).map_err(|_| fail("unsupported_metadata", "invalid processor"))?;
                let obj = value.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid processor"))?;
                let fields: HashMap<String, Box<RawValue>> = serde_json::from_str(raw_processor.get()).map_err(|_| fail("unsupported_metadata", "invalid processor"))?;
                let jar = artifact(obj.get("jar").ok_or_else(|| fail("unsupported_metadata", "missing processor jar"))?)?;
                let classpath = match obj.get("classpath") {
                    None | Some(Value::Null) => Value::Null,
                    Some(Value::Array(items)) => { let mut out = Vec::new(); for item in items { out.push(artifact(item)?); } json!(out) },
                    _ => return Err(fail("unsupported_metadata", "invalid processor classpath")),
                };
                let outputs = match fields.get("outputs") {
                    None => Value::Null,
                    Some(raw) if raw.get() == "null" => Value::Null,
                    Some(raw) => {
                        let OrderedEntries(entries) = serde_json::from_str(raw.get()).map_err(|_| fail("unsupported_metadata", "invalid processor outputs"))?;
                        let mut out = Vec::new();
                        for (key, value) in entries {
                            if !value.is_null() && !value.is_string() { return Err(fail("unsupported_metadata", "invalid processor output")); }
                            out.push(json!({"key":key,"value":value}));
                        }
                        json!(out)
                    },
                };
                out.push(json!({"jar":jar,"sides":string_list(obj.get("sides"))?,"classpath":classpath,
                    "args":string_list(obj.get("args"))?,"outputs":outputs}));
            }
            json!(out)
        },
    };
    let data = match raw.get("data") {
        None => Value::Null,
        Some(v) if v.get() == "null" => Value::Null,
        Some(v) => {
            let OrderedEntries(entries) = serde_json::from_str(v.get()).map_err(|_| fail("unsupported_metadata", "invalid installer data"))?;
            let mut out = Vec::new();
            for (key, value) in entries {
                let datum = value.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid installer datum"))?;
                let client = datum.get("client").cloned().unwrap_or(Value::Null);
                if !client.is_null() && !client.is_string() { return Err(fail("unsupported_metadata", "invalid client datum")); }
                out.push(json!({"key":key,"client":client}));
            }
            json!(out)
        },
    };
    Ok(json!({"spec":spec,"minecraft":required("minecraft")?,"json":required("json")?,"version":required("version")?,
        "path":path,"libraries":libraries,"processors":processors,"data":data}))
}
