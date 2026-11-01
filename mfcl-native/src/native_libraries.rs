// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Selects applicable native library descriptors without touching archives.
use crate::{fail, Error};
use serde_json::{json, Value};

/// Evaluates library rules and plans native archive paths in a single request.
pub(crate) fn plan_with_rules(params: &Value) -> Result<Value, Error> {
    let planned = crate::classpath::apply_library_rules(params)?;
    let indices: Vec<_> = planned.get("libraries").and_then(Value::as_array)
        .ok_or_else(|| fail("invalid_params", "missing libraries"))?.iter().enumerate()
        .filter_map(|(index, item)| (item.get("applicable").and_then(Value::as_bool) == Some(true)).then_some(index)).collect();
    let mut result = plan(&planned)?;
    result.as_object_mut().ok_or_else(|| fail("internal_error", "invalid native plan"))?
        .insert("applicable_indices".to_owned(), json!(indices));
    Ok(result)
}

/// Selects native-library positions, preserving the original library order.
pub(crate) fn select(params: &Value) -> Result<Value, Error> {
    let libraries = params.get("libraries").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing libraries"))?;
    if libraries.len() > 20_000 { return Err(fail("library_limit", "too many libraries")); }
    let mut indices = Vec::new();
    for (index, library) in libraries.iter().enumerate() {
        if !library.get("applicable").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "invalid applicability"))? { continue; }
        let mapped = library.get("natives_present").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "invalid natives"))?;
        let names = library.get("classifiers").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "invalid classifiers"))?;
        let mut native = mapped;
        for name in names {
            let name = name.as_str().ok_or_else(|| fail("invalid_params", "invalid classifier"))?;
            native |= name.starts_with("native");
        }
        if native { indices.push(index); }
    }
    Ok(json!({"indices":indices}))
}

/// Resolves native archive paths for the standard repository layout.
pub(crate) fn plan(params: &Value) -> Result<Value, Error> {
    use std::path::{Path, PathBuf};
    let libraries = params.get("libraries").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing libraries"))?;
    if libraries.len() > 20_000 { return Err(fail("library_limit", "too many libraries")); }
    let required = |object: &Value, key: &str| -> Result<String, Error> {
        object.get(key).and_then(Value::as_str).filter(|s| !s.contains('\0')).map(str::to_owned)
            .ok_or_else(|| fail("invalid_params", "invalid library field"))
    };
    let optional = |object: &Value, key: &str| -> Result<Option<String>, Error> {
        match object.get(key) {
            None | Some(Value::Null) => Ok(None),
            Some(Value::String(s)) if !s.contains('\0') => Ok(Some(s.clone())),
            _ => Err(fail("invalid_params", "invalid optional library field")),
        }
    };
    let root = PathBuf::from(required(params, "libraries_root")?);
    let instance = PathBuf::from(required(params, "instance_root")?);
    let os = required(params, "platform_os")?; let arch = required(params, "platform_arch")?; let bits = required(params, "platform_bits")?;
    let mut result = Vec::new();
    for (index, lib) in libraries.iter().enumerate() {
        if !lib.get("applicable").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "invalid applicability"))? { continue; }
        let natives = match lib.get("natives") { None | Some(Value::Null) => None, Some(Value::Object(o)) => Some(o), _ => return Err(fail("invalid_params", "invalid natives")) };
        let classifiers = match lib.get("classifiers") { None | Some(Value::Null) => None, Some(Value::Object(o)) => Some(o), _ => return Err(fail("invalid_params", "invalid classifiers")) };
        let is_native = natives.is_some() || classifiers.is_some_and(|m| m.keys().any(|key| key.starts_with("native")));
        if !is_native { continue; }
        let group = required(lib,"group")?; let name = required(lib,"name")?; let version = required(lib,"version")?; let ext = required(lib,"extension")?;
        let original = optional(lib,"original_classifier")?;
        let mut classifier = original.clone();
        if classifier.is_none() {
            'outer: for suffix in ["", arch.as_str(), bits.as_str()] {
                for prefix in ["", "native-", "natives-"] {
                    let key = if suffix.is_empty() { format!("{prefix}{os}") } else { format!("{prefix}{os}-{suffix}") };
                    if let Some(mapping) = natives {
                        match mapping.get(&key) {
                            Some(Value::String(value)) => { classifier = Some(value.replace("${arch}", &bits)); break 'outer; },
                            None | Some(Value::Null) => (),
                            _ => return Err(fail("invalid_params", "invalid native mapping")),
                        }
                    } else if classifiers.and_then(|m| m.get(&key)).is_some_and(|v| v.is_object()) {
                        classifier = Some(key); break 'outer;
                    }
                }
            }
        }
        let filename = |classifier: Option<&str>| {
            let mut file = format!("{name}-{version}");
            if let Some(value) = classifier { file.push('-'); file.push_str(value); }
            file.push('.'); file.push_str(&ext); file
        };
        let path = if optional(lib,"hint")?.as_deref() == Some("local") {
            instance.join("libraries").join(optional(lib,"filename")?.unwrap_or_else(|| filename(original.as_deref())))
        } else {
            let override_path = match classifier.as_ref().and_then(|c| classifiers.and_then(|map| map.get(c))) {
                None | Some(Value::Null) => None,
                Some(info) if info.is_object() => optional(info, "path")?,
                _ => return Err(fail("invalid_params", "invalid classifier info")),
            };
            let relative = override_path.unwrap_or_else(|| format!("{}/{}/{}/{}", group.replace('.', "/"), name, version, filename(classifier.as_deref())));
            root.join(Path::new(&relative))
        };
        let path = path.to_str().ok_or_else(|| fail("invalid_params", "unsupported native archive path"))?;
        result.push(json!({"index":index,"path":path}));
    }
    Ok(json!({"archives":result}))
}
