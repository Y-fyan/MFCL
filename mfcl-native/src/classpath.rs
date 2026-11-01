// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Builds the resolved library classpath for the default Minecraft repository layout.
use crate::{fail, Error};
use serde_json::{json, Value};
use std::{collections::HashSet, path::{Component, Path, PathBuf}};

const MAX_LIBRARIES: usize = 20_000;

/// Evaluates applicability and resolves the classpath in one read-only request.
pub(crate) fn plan_with_rules(params: &Value) -> Result<Value, Error> {
    plan_with_applicability(&apply_library_rules(params)?)
}

/// Applies the shared library rule engine to a plan's input descriptors.
pub(crate) fn apply_library_rules(params: &Value) -> Result<Value, Error> {
    let rules = params.get("rule_params").ok_or_else(|| fail("invalid_params", "missing library rules"))?;
    let selected = crate::arguments::expand_ruled(rules)?;
    let groups = selected.get("groups").and_then(Value::as_array).filter(|groups| groups.len() == 1)
        .ok_or_else(|| fail("invalid_params", "invalid library rule groups"))?;
    let indices = groups[0].as_array().ok_or_else(|| fail("invalid_params", "invalid library rule selection"))?;
    let mut plan = params.clone();
    let libraries = plan.get_mut("libraries").and_then(Value::as_array_mut).ok_or_else(|| fail("invalid_params", "missing libraries"))?;
    if libraries.len() > MAX_LIBRARIES { return Err(fail("library_limit", "too many libraries")); }
    let mut selected = vec![false; libraries.len()]; let mut previous = None;
    for item in indices {
        let value = item.as_str().ok_or_else(|| fail("invalid_params", "invalid library index"))?;
        let index: usize = value.parse().map_err(|_| fail("invalid_params", "invalid library index"))?;
        if value != index.to_string() || index >= libraries.len() || previous.is_some_and(|prior| index <= prior) {
            return Err(fail("invalid_params", "invalid library selection"));
        }
        selected[index] = true; previous = Some(index);
    }
    for (library, applicable) in libraries.iter_mut().zip(selected) {
        library.as_object_mut().ok_or_else(|| fail("invalid_params", "invalid library"))?.insert("applicable".into(), Value::Bool(applicable));
    }
    Ok(plan)
}

/// Reads a required string from a JSON object.
fn required<'a>(value: &'a Value, field: &str) -> Result<&'a str, Error> {
    value.get(field).and_then(Value::as_str).filter(|v| !v.contains('\0'))
        .ok_or_else(|| fail("invalid_params", format!("invalid {field}")))
}
/// Reads an optional string, rejecting other JSON types and embedded NUL bytes.
fn optional<'a>(value: &'a Value, field: &str) -> Result<Option<&'a str>, Error> {
    match value.get(field) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(text)) if !text.contains('\0') => Ok(Some(text)),
        _ => Err(fail("invalid_params", format!("invalid {field}"))),
    }
}
/// Performs lexical normalization without resolving symbolic links, matching Java Path.normalize.
fn normalize(path: &Path) -> PathBuf {
    let mut result = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {},
            Component::ParentDir => { result.pop(); },
            _ => result.push(component.as_os_str()),
        }
    }
    result
}
/// Formats the file name for one Maven artifact.
fn artifact_file(name: &str, version: &str, classifier: Option<&str>, extension: &str) -> String {
    let mut result = format!("{name}-{version}");
    if let Some(classifier) = classifier { result.push('-'); result.push_str(classifier); }
    result.push('.'); result.push_str(extension); result
}
/// Computes the relative Maven path for an artifact.
fn artifact_path(group: &str, name: &str, version: &str, classifier: Option<&str>, extension: &str) -> String {
    format!("{}/{}/{}/{}", group.replace('.', "/"), name, version, artifact_file(name,version,classifier,extension))
}
/// Adds an existing regular file to the ordered, deduplicated classpath.
fn append_if_file(path: &Path, entries: &mut Vec<String>, seen: &mut HashSet<String>) -> Result<(), Error> {
    if !path.is_file() { return Ok(()); }
    let normalized = normalize(path);
    let value = normalized.to_str().ok_or_else(|| fail("invalid_path", "classpath path is not UTF-8"))?.to_owned();
    if seen.insert(value.clone()) { entries.push(value); }
    Ok(())
}
/// Resolves applicable libraries while preserving OptiFine ordering and Java path semantics.
pub(crate) fn plan(params: &Value) -> Result<Value, Error> {
    plan_with_applicability(params)
}

fn plan_with_applicability(params: &Value) -> Result<Value, Error> {
    let libraries_root = PathBuf::from(required(params,"libraries_root")?);
    let instance_root = PathBuf::from(required(params,"instance_root")?);
    if !libraries_root.is_absolute() || !instance_root.is_absolute() { return Err(fail("invalid_params","classpath roots must be absolute")); }
    let libraries=params.get("libraries").and_then(Value::as_array).ok_or_else(|| fail("invalid_params","libraries must be an array"))?;
    if libraries.len()>MAX_LIBRARIES { return Err(fail("library_limit","too many libraries")); }
    let process_optifine=params.get("process_optifine").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params","missing OptiFine setting"))?;
    let modlauncher=params.get("modlauncher").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params","missing ModLauncher setting"))?;
    let mut entries=Vec::new(); let mut seen=HashSet::new(); let mut selected_installer=None;
    for library in libraries {
        let applicable=library.get("applicable").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params","invalid applicability"))?;
        if !applicable { continue; }
        // Older protocol callers may supply precomputed flags and classifiers.
        let computed = if library.get("native").is_none() {
            let natives = library.get("natives_present").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "invalid native descriptor"))?;
            let descriptors = library.get("classifiers").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "invalid classifiers"))?;
            let mut keys = Vec::with_capacity(descriptors.len());
            for descriptor in descriptors {
                let name = required(descriptor, "name")?;
                let present = descriptor.get("present").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "invalid classifier"))?;
                keys.push((name, present));
            }
            if natives || keys.iter().any(|(name, _)| name.starts_with("native")) { continue; }
            let os = required(params, "platform_os")?;
            let arch = required(params, "platform_arch")?;
            let bits = required(params, "platform_bits")?;
            let mut selected = None;
            'outer: for suffix in ["", arch, bits] {
                for prefix in ["", "native-", "natives-"] {
                    let candidate = if suffix.is_empty() { format!("{prefix}{os}") } else { format!("{prefix}{os}-{suffix}") };
                    if keys.iter().any(|(name, present)| *present && *name == candidate) { selected = Some(candidate); break 'outer; }
                }
            }
            Some(selected)
        } else {
            if !library.get("native").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "invalid native flag"))? { None } else { continue; }
        };
        let group=required(library,"group")?; let name=required(library,"name")?; let version=required(library,"version")?;
        let extension=required(library,"extension")?;
        let original_classifier=optional(library,"original_classifier")?;
        let effective_classifier = match &computed {
            Some(selected) => original_classifier.or(selected.as_deref()),
            None => optional(library,"effective_classifier")?,
        };
        if process_optifine && group=="optifine" {
            if name=="OptiFine" {
                let installer=libraries_root.join(artifact_path("optifine","OptiFine",version,Some("installer"),"jar"));
                if installer.is_file() { selected_installer=Some(installer); continue; }
            } else if name=="launchwrapper-of" { continue; }
        }
        let hint=optional(library,"hint")?;
        let file = if hint==Some("local") {
            let filename=match optional(library,"filename")? { Some(name) => name.to_owned(), None => artifact_file(name,version,original_classifier,extension) };
            instance_root.join("libraries").join(filename)
        } else {
            let path=match optional(library,"download_path")? { Some(path) => path.to_owned(), None => artifact_path(group,name,version,effective_classifier,extension) };
            libraries_root.join(path)
        };
        append_if_file(&file,&mut entries,&mut seen)?;
    }
    if !modlauncher { if let Some(installer)=selected_installer { append_if_file(&installer,&mut entries,&mut seen)?; } }
    Ok(json!({"classpath":entries}))
}
