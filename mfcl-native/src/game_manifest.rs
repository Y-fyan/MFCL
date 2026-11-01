// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Projects standard base-game manifest fields while retaining raw nested JSON.
use crate::{fail, Error};
use serde::{de::{MapAccess, Visitor}, Deserialize, Deserializer};
use serde_json::{json, value::RawValue, Value};
use std::fmt;
use std::collections::HashMap;

/// Preserves original order for download-type maps.
pub(crate) struct OrderedEntries(pub(crate) Vec<(String, Value)>);
impl<'de> Deserialize<'de> for OrderedEntries {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct EntriesVisitor;
        impl<'de> Visitor<'de> for EntriesVisitor {
            type Value = OrderedEntries;
            fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result { formatter.write_str("an ordered object") }
            fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Self::Value, A::Error> {
                let mut entries = Vec::new();
                while let Some(entry) = map.next_entry()? { entries.push(entry); }
                Ok(OrderedEntries(entries))
            }
        }
        deserializer.deserialize_map(EntriesVisitor)
    }
}

/// Reads a canonical download descriptor without Gson-specific coercions.
fn download(value: &Value) -> Result<Value, Error> {
    if value.is_null() { return Ok(Value::Null); }
    let object = value.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid download"))?;
    let url = object.get("url").and_then(Value::as_str).filter(|url| url.is_ascii()
        && !url.bytes().all(|ch| (9..=13).contains(&ch) || (28..=32).contains(&ch)))
        .ok_or_else(|| fail("unsupported_metadata", "invalid download URL"))?;
    let sha1 = match object.get("sha1") { None | Some(Value::Null) => None, Some(Value::String(v)) => Some(v.as_str()),
        _ => return Err(fail("unsupported_metadata", "invalid download digest")) };
    let size = match object.get("size") { None | Some(Value::Null) => 0,
        Some(value) => value.as_i64().and_then(|n| i32::try_from(n).ok()).ok_or_else(|| fail("unsupported_metadata", "invalid download size"))? };
    Ok(json!({"url":url,"sha1":sha1,"size":size}))
}

/// Reads a canonical asset index, preserving Java defaults for optional sizes.
fn asset(value: &Value) -> Result<Value, Error> {
    if value.is_null() { return Ok(Value::Null); }
    let object = value.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid asset index"))?;
    let id = object.get("id").and_then(Value::as_str).filter(|id| id.is_ascii()
        && !id.bytes().all(|ch| (9..=13).contains(&ch) || (28..=32).contains(&ch)))
        .ok_or_else(|| fail("unsupported_metadata", "invalid asset index id"))?;
    let descriptor = download(value)?;
    let total = match object.get("totalSize") { None | Some(Value::Null) => 0,
        Some(value) => value.as_i64().ok_or_else(|| fail("unsupported_metadata", "invalid asset total size"))? };
    Ok(json!({"id":id,"url":descriptor["url"],"sha1":descriptor["sha1"],"size":descriptor["size"],"totalSize":total}))
}

/// Reads Java runtime requirements without coercing malformed values.
fn java_version(value: Option<&Value>) -> Result<Value, Error> {
    let Some(value) = value else { return Ok(Value::Null) };
    if value.is_null() { return Ok(Value::Null); }
    let object = value.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid Java version"))?;
    let component = object.get("component").cloned().unwrap_or(Value::Null);
    if !component.is_null() && !component.is_string() { return Err(fail("unsupported_metadata", "invalid Java component")); }
    let major = match object.get("majorVersion") { None | Some(Value::Null) => 0,
        Some(n) => n.as_i64().and_then(|n| i32::try_from(n).ok()).ok_or_else(|| fail("unsupported_metadata", "invalid Java major version"))? };
    Ok(json!({"component":component,"majorVersion":major}))
}

/// Reads an ordered logging configuration map.
fn logging(value: Option<&Box<RawValue>>) -> Result<Value, Error> {
    let Some(value) = value else { return Ok(Value::Null) };
    if value.get() == "null" { return Ok(Value::Null); }
    let OrderedEntries(entries) = serde_json::from_str(value.get()).map_err(|_| fail("unsupported_metadata", "invalid logging"))?;
    let mut output = Vec::new();
    for (name, value) in entries {
        if !name.is_ascii() { return Err(fail("unsupported_metadata", "invalid logging key")); }
        let kind = name.to_ascii_lowercase();
        if !["client", "server", "windows_server", "client_mappings", "server_mappings"].contains(&kind.as_str()) { continue; }
        let info = if value.is_null() { Value::Null } else {
            let object = value.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid logging entry"))?;
            let nonblank = |key: &str| -> Result<&str, Error> {
                object.get(key).and_then(Value::as_str).filter(|v| v.is_ascii() && !v.bytes().all(|c| (9..=13).contains(&c) || (28..=32).contains(&c)))
                    .ok_or_else(|| fail("unsupported_metadata", "invalid logging string"))
            };
            let file = asset(object.get("file").ok_or_else(|| fail("unsupported_metadata", "missing logging file"))?)?;
            if file.is_null() { return Err(fail("unsupported_metadata", "invalid logging file")); }
            json!({"argument":nonblank("argument")?,"type":nonblank("type")?,"file":file})
        };
        output.push(json!({"kind":kind,"info":info}));
    }
    Ok(json!(output))
}

/// Reads a library download with its optional repository path.
fn library_download(value: &Value) -> Result<Value, Error> {
    if value.is_null() { return Ok(Value::Null); }
    let mut descriptor = download(value)?;
    let path = match value.get("path") { None | Some(Value::Null) => Value::Null, Some(Value::String(s)) => json!(s), _ => return Err(fail("unsupported_metadata", "invalid library path")) };
    descriptor["path"] = path;
    Ok(descriptor)
}

/// Normalizes compatibility rules with Gson's constructor defaults.
fn rules(value: Option<&Value>) -> Result<Value, Error> {
    let Some(value) = value else { return Ok(Value::Null) };
    if value.is_null() { return Ok(Value::Null); }
    let items = value.as_array().ok_or_else(|| fail("unsupported_metadata", "invalid compatibility rules"))?;
    let mut out = Vec::new();
    for item in items {
        if item.is_null() { out.push(Value::Null); continue; }
        let rule = item.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid compatibility rule"))?;
        let action = match rule.get("action") {
            None => json!("allow"), Some(Value::Null) => Value::Null,
            Some(Value::String(s)) if s.is_ascii() => match s.to_ascii_lowercase().as_str() { "allow" => json!("allow"), "disallow" => json!("disallow"), _ => Value::Null },
            _ => return Err(fail("unsupported_metadata", "invalid rule action")),
        };
        let os = match rule.get("os") {
            None | Some(Value::Null) => Value::Null,
            Some(value) => {
                let os = value.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid rule OS"))?;
                let field = |key: &str, default: Value| -> Result<Value, Error> {
                    let value = os.get(key).cloned().unwrap_or(default);
                    if !value.is_null() && !value.is_string() { return Err(fail("unsupported_metadata", "invalid OS field")); }
                    Ok(value)
                };
                json!({"name":field("name",json!("universal"))?,"version":field("version",Value::Null)?,"arch":field("arch",Value::Null)?})
            },
        };
        let features = rule.get("features").cloned().unwrap_or(Value::Null);
        if !features.is_null() && !features.as_object().is_some_and(|map| map.values().all(|v| v.is_null() || v.is_boolean())) {
            return Err(fail("unsupported_metadata", "invalid rule features"));
        }
        out.push(json!({"action":action,"os":os,"features":features}));
    }
    Ok(json!(out))
}

/// Normalizes launch arguments and compatibility rules.
fn arguments(value: Option<&Value>) -> Result<Value, Error> {
    let Some(value) = value else { return Ok(Value::Null) };
    if value.is_null() { return Ok(Value::Null); }
    let fields = value.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid arguments"))?;
    let read_list = |key: &str| -> Result<Value, Error> {
        let Some(value) = fields.get(key) else { return Ok(Value::Null) };
        if value.is_null() { return Ok(Value::Null); }
        let entries = value.as_array().ok_or_else(|| fail("unsupported_metadata", "invalid argument list"))?;
        let mut result = Vec::new();
        for entry in entries {
            if entry.is_null() { result.push(Value::Null); continue; }
            if let Some(text) = entry.as_str() { result.push(json!({"string":text})); continue; }
            let obj = entry.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid argument"))?;
            let rules = rules(obj.get("rules"))?;
            let value = obj.get("values").or_else(|| obj.get("value")).ok_or_else(|| fail("unsupported_metadata", "missing argument value"))?;
            let values = if value.is_null() { Value::Null } else if let Some(value) = value.as_str() { json!([value]) }
                else if value.as_array().is_some_and(|list| list.iter().all(|v| v.is_null() || v.is_string())) { value.clone() }
                else { return Err(fail("unsupported_metadata", "invalid argument value")); };
            result.push(json!({"rules":rules,"values":values}));
        }
        Ok(json!(result))
    };
    Ok(json!({"game":read_list("game")?,"jvm":read_list("jvm")?}))
}

/// Projects ordinary library descriptors, preserving classifier iteration order.
pub(crate) fn libraries(value: Option<&Box<RawValue>>) -> Result<Value, Error> {
    libraries_with_format(value, false)
}

fn libraries_with_format(value: Option<&Box<RawValue>>, tlauncher: bool) -> Result<Value, Error> {
    let Some(value) = value else { return Ok(Value::Null) };
    if value.get() == "null" { return Ok(Value::Null); }
    let entries: Vec<Box<RawValue>> = serde_json::from_str(value.get()).map_err(|_| fail("unsupported_metadata", "invalid libraries"))?;
    let mut output = Vec::new();
    for raw_entry in entries {
        let entry: Value = serde_json::from_str(raw_entry.get()).map_err(|_| fail("invalid_metadata", "invalid library"))?;
        if entry.is_null() {
            if tlauncher { return Err(fail("unsupported_metadata", "invalid TLauncher library")); }
            continue;
        }
        let obj = entry.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid library"))?;
        let raw_fields: HashMap<String, Box<RawValue>> = serde_json::from_str(raw_entry.get()).map_err(|_| fail("invalid_metadata", "invalid library"))?;
        let name = obj.get("name").and_then(Value::as_str).ok_or_else(|| fail("unsupported_metadata", "invalid library name"))?;
        let parts: Vec<_> = name.split(':').collect();
        if !(3..=4).contains(&parts.len()) || parts.iter().any(|v| v.is_empty() || v.contains('\\')) || parts[..parts.len()-1].iter().any(|v| v.contains('@')) {
            return Err(fail("unsupported_metadata", "unsupported library name"));
        }
        let last: Vec<_> = parts[parts.len()-1].split('@').collect();
        if last.len() > 2 || last.iter().any(|v| v.is_empty()) { return Err(fail("unsupported_metadata", "invalid library extension")); }
        let (version, classifier) = if parts.len() == 4 { (parts[2], Some(last[0])) } else { (last[0], None) };
        let downloads = if tlauncher {
            let artifact = obj.get("artifact").map(library_download).transpose()?.unwrap_or(Value::Null);
            let classifiers = match raw_fields.get("classifies") {
                None => Value::Null,
                Some(raw) if raw.get() == "null" => Value::Null,
                Some(raw) => {
                    let OrderedEntries(entries) = serde_json::from_str(raw.get()).map_err(|_| fail("unsupported_metadata", "invalid classifiers"))?;
                    let mut output = Vec::new();
                    for (name, descriptor) in entries { output.push(json!({"name":name,"info":library_download(&descriptor)?})); }
                    json!(output)
                },
            };
            json!({"artifact":artifact,"classifiers":classifiers})
        } else { match obj.get("downloads") {
            None | Some(Value::Null) => Value::Null,
            Some(value) => {
                let map = value.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid library downloads"))?;
                let artifact = map.get("artifact").map(library_download).transpose()?.unwrap_or(Value::Null);
                // Parse the original bytes, since Value's object map sorts keys.
                let raw_downloads: HashMap<String, Box<RawValue>> = serde_json::from_str(raw_fields.get("downloads").ok_or_else(|| fail("invalid_metadata", "missing downloads"))?.get()).map_err(|_| fail("invalid_metadata", "invalid downloads"))?;
                let classifiers = match raw_downloads.get("classifiers") {
                    None => Value::Null,
                    Some(raw) if raw.get() == "null" => Value::Null,
                    Some(raw) => {
                        let OrderedEntries(entries) = serde_json::from_str(raw.get()).map_err(|_| fail("unsupported_metadata", "invalid classifiers"))?;
                        let mut output = Vec::new();
                        for (name, descriptor) in entries { output.push(json!({"name":name,"info":library_download(&descriptor)?})); }
                        json!(output)
                    },
                };
                json!({"artifact":artifact,"classifiers":classifiers})
            },
        }};
        let rules = rules(obj.get("rules"))?;
        let text_field = |primary: &str, alternate: Option<&str>| -> Result<Value, Error> {
            if alternate.is_some_and(|name| obj.contains_key(primary) && obj.contains_key(name)) { return Err(fail("unsupported_metadata", "ambiguous library field")); }
            let value = obj.get(primary).or_else(|| alternate.and_then(|name| obj.get(name))).cloned().unwrap_or(Value::Null);
            if !value.is_null() && !value.is_string() { return Err(fail("unsupported_metadata", "invalid library string")); }
            Ok(value)
        };
        let checksums = obj.get("checksums").cloned().unwrap_or(Value::Null);
        if !checksums.is_null() && !checksums.as_array().is_some_and(|list| list.iter().all(|v| v.is_null() || v.is_string())) { return Err(fail("unsupported_metadata", "invalid library checksums")); }
        let extract = match obj.get("extract") {
            None | Some(Value::Null) => Value::Null,
            Some(value) => {
                let extract = value.as_object().ok_or_else(|| fail("unsupported_metadata", "invalid extraction rules"))?;
                let exclude = extract.get("exclude").cloned().unwrap_or(Value::Null);
                if !exclude.is_null() && !exclude.as_array().is_some_and(|list| list.iter().all(Value::is_string)) { return Err(fail("unsupported_metadata", "invalid extraction exclusions")); }
                if exclude.is_null() { json!([]) } else { exclude }
            },
        };
        let natives = match raw_fields.get("natives") {
            None => Value::Null,
            Some(raw) if raw.get() == "null" => Value::Null,
            Some(raw) => {
                let OrderedEntries(entries) = serde_json::from_str(raw.get()).map_err(|_| fail("unsupported_metadata", "invalid library natives"))?;
                let mut out = Vec::new();
                for (name, value) in entries {
                    if !value.is_null() && !value.is_string() { return Err(fail("unsupported_metadata", "invalid native classifier")); }
                    out.push(json!({"name":name,"value":value}));
                }
                json!(out)
            },
        };
        output.push(json!({"group":parts[0],"name":parts[1],"version":version,"classifier":classifier,
            "extension":last.get(1),"downloads":downloads,"rules":rules,"url":text_field("url",None)?,
            "checksums":checksums,"extract":extract,"natives":natives,"hint":if tlauncher { Value::Null } else { text_field("hint",Some("MMC-hint"))? },
            "filename":if tlauncher { Value::Null } else { text_field("filename",Some("MMC-filename"))? }}));
    }
    Ok(json!(output))
}

/// Extracts supported base-game fields; unusual manifests retain the Java parser.
pub(crate) fn project(params: &Value) -> Result<Value, Error> {
    let source = params.get("metadata").and_then(Value::as_str).filter(|s| s.len() <= 768 * 1024)
        .ok_or_else(|| fail("invalid_params", "invalid game manifest"))?;
    project_source(source, params.get("loader_mode").and_then(Value::as_bool).unwrap_or(false), false)
}

fn project_source(source: &str, loader_mode: bool, patch_mode: bool) -> Result<Value, Error> {
    let value = crate::json_unique::parse(source.as_bytes()).map_err(|_| fail("invalid_metadata", "invalid or ambiguous game manifest"))?;
    let object = value.as_object().ok_or_else(|| fail("invalid_metadata", "invalid game manifest"))?;
    if (object.contains_key("tlauncherVersion") && (!loader_mode || patch_mode)) || (object.contains_key("patches") && (!loader_mode || patch_mode))
        || (!loader_mode && ["inheritsFrom", "jar", "compatibilityRules"].iter().any(|key| object.contains_key(*key))) {
        return Err(fail("unsupported_metadata", "nonstandard game manifest"));
    }
    if !patch_mode && object.get("id").and_then(Value::as_str).is_none() { return Err(fail("unsupported_metadata", "missing game id")); }
    for key in ["id", "version", "minecraftArguments", "mainClass", "assets", "type", "time", "releaseTime", "inheritsFrom", "jar"] {
        if object.get(key).is_some_and(|v| !v.is_null() && !v.is_string()) { return Err(fail("unsupported_metadata", "invalid game field")); }
    }
    for key in ["priority", "complianceLevel", "minimumLauncherVersion"] {
        if object.get(key).is_some_and(|v| !v.is_null() && v.as_i64().and_then(|n| i32::try_from(n).ok()).is_none()) {
            return Err(fail("unsupported_metadata", "unsupported game number"));
        }
    }
    for key in ["root", "hidden"] {
        if object.get(key).is_some_and(|v| !v.is_null() && !v.is_boolean()) { return Err(fail("unsupported_metadata", "invalid game flag")); }
    }
    for key in ["arguments", "assetIndex", "javaVersion", "downloads", "logging"] {
        if object.get(key).is_some_and(|v| !v.is_null() && !v.is_object()) { return Err(fail("unsupported_metadata", "invalid nested game field")); }
    }
    if object.get("libraries").is_some_and(|v| !v.is_null() && !v.is_array()) { return Err(fail("unsupported_metadata", "invalid libraries")); }
    if object.get("compatibilityRules").is_some_and(|v| !v.is_null() && !v.is_array()) { return Err(fail("unsupported_metadata", "invalid manifest rules")); }
    let raw: HashMap<String, Box<RawValue>> = serde_json::from_str(source).map_err(|_| fail("invalid_metadata", "invalid game manifest"))?;
    let fields = ["id", "version", "priority", "minecraftArguments", "arguments", "mainClass", "assetIndex", "assets", "complianceLevel", "javaVersion",
        "libraries", "downloads", "logging", "type", "time", "releaseTime", "minimumLauncherVersion", "root", "hidden", "inheritsFrom", "jar"];
    let mut result = serde_json::Map::new();
    for key in fields { if let Some(raw) = raw.get(key) { result.insert(key.to_owned(), json!(raw.get())); } }
    let asset = object.get("assetIndex").map(asset).transpose()?.unwrap_or(Value::Null);
    let downloads = match object.get("downloads") {
        None | Some(Value::Null) => Value::Null,
        Some(_) => {
            let raw_downloads = raw.get("downloads").ok_or_else(|| fail("invalid_metadata", "missing downloads"))?;
            let OrderedEntries(entries) = serde_json::from_str(raw_downloads.get()).map_err(|_| fail("invalid_metadata", "invalid downloads"))?;
            let mut output = Vec::new();
            for (name, value) in entries {
                if !name.is_ascii() { return Err(fail("unsupported_metadata", "unsupported download key")); }
                let normalized = name.to_ascii_lowercase();
                if ["client", "server", "windows_server", "client_mappings", "server_mappings"].contains(&normalized.as_str()) {
                    output.push(json!({"kind":normalized,"info":download(&value)?}));
                }
            }
            json!(output)
        },
    };
    let libraries = libraries_with_format(raw.get("libraries"), object.contains_key("tlauncherVersion"))?;
    let arguments = arguments(object.get("arguments"))?;
    let manifest_rules = rules(object.get("compatibilityRules"))?;
    let patches = if !patch_mode && loader_mode {
        match object.get("patches") {
            Some(Value::Array(items)) => {
                let mut projected = Vec::new();
                let raw_items: Vec<Box<RawValue>> = serde_json::from_str(raw.get("patches").ok_or_else(|| fail("invalid_metadata", "missing patches"))?.get())
                    .map_err(|_| fail("invalid_metadata", "invalid patches"))?;
                for (item, raw_item) in items.iter().zip(raw_items) {
                    if item.is_object() { projected.push(project_source(raw_item.get(), true, true)?); }
                }
                json!(projected)
            },
            _ => Value::Null,
        }
    } else { Value::Null };
    Ok(json!({"fields":result,"nested_v1":true,"asset":asset,"downloads":downloads,"library_v3":true,"libraries":libraries,
        "arguments_v1":true,"arguments":arguments,"rules_v1":true,"runtime_logging_v1":true,
        "javaVersion":java_version(object.get("javaVersion"))?,"logging":logging(raw.get("logging"))?,
        "loader_v1":loader_mode,"manifest_rules":manifest_rules,"patches_v1":true,"patches":patches}))
}
