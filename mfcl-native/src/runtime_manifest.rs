// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Parses ordinary Mojang Java runtime manifests while preserving entry order.
use crate::{fail, Error};
use serde::{de::{MapAccess, Visitor}, Deserialize, Deserializer};
use serde_json::{json, value::RawValue, Value};
use std::fmt;

/// Top-level manifest, retaining the original files object for ordered parsing.
#[derive(Deserialize)]
struct Manifest<'a> { #[serde(borrow)] files: &'a RawValue }

/// Manifest entries in the source order, including their already parsed properties.
struct OrderedFiles(Vec<(String, Value)>);
impl<'de> Deserialize<'de> for OrderedFiles {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct OrderedVisitor;
        impl<'de> Visitor<'de> for OrderedVisitor {
            type Value = OrderedFiles;
            fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { f.write_str("an ordered runtime files object") }
            fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Self::Value, A::Error> {
                let mut entries = Vec::new();
                while let Some(entry) = map.next_entry()? { entries.push(entry); }
                Ok(OrderedFiles(entries))
            }
        }
        deserializer.deserialize_map(OrderedVisitor)
    }
}

/// Converts canonical file records to an ordered and explicitly typed plan.
pub(crate) fn parse(params: &Value) -> Result<Value, Error> {
    let source = params.get("metadata").and_then(Value::as_str).filter(|s| s.len() <= 512 * 1024)
        .ok_or_else(|| fail("invalid_params", "invalid runtime manifest"))?;
    // Reject duplicate keys at every depth so the Java parser owns ambiguous input.
    crate::json_unique::parse(source.as_bytes()).map_err(|_| fail("invalid_metadata", "ambiguous runtime manifest"))?;
    let manifest: Manifest<'_> = serde_json::from_str(source).map_err(|_| fail("invalid_metadata", "invalid runtime manifest"))?;
    let OrderedFiles(entries) = serde_json::from_str(manifest.files.get()).map_err(|_| fail("invalid_metadata", "invalid runtime entries"))?;
    if entries.len() > 20_000 { return Err(fail("invalid_metadata", "too many runtime entries")); }
    let mut result = Vec::with_capacity(entries.len());
    for (path, entry) in entries {
        let kind = entry.get("type").and_then(Value::as_str).ok_or_else(|| fail("invalid_metadata", "missing runtime type"))?;
        let item = match kind {
            "directory" => json!({"path":path,"type":"directory"}),
            "link" => {
                let target = entry.get("target").and_then(Value::as_str).ok_or_else(|| fail("invalid_metadata", "invalid runtime link"))?;
                json!({"path":path,"type":"link","target":target})
            },
            "file" => {
                let executable = entry.get("executable").and_then(Value::as_bool).ok_or_else(|| fail("invalid_metadata", "invalid executable flag"))?;
                let downloads = entry.get("downloads").and_then(Value::as_object).ok_or_else(|| fail("invalid_metadata", "invalid runtime downloads"))?;
                let mut parsed = Vec::with_capacity(downloads.len());
                for (name, download) in downloads {
                    let url = download.get("url").and_then(Value::as_str).filter(|s| !s.trim().is_empty()).ok_or_else(|| fail("invalid_metadata", "invalid runtime URL"))?;
                    let sha1 = download.get("sha1").and_then(Value::as_str).ok_or_else(|| fail("invalid_metadata", "invalid runtime hash"))?;
                    let size = download.get("size").and_then(Value::as_i64).and_then(|v| i32::try_from(v).ok()).ok_or_else(|| fail("invalid_metadata", "invalid runtime size"))?;
                    parsed.push(json!({"name":name,"url":url,"sha1":sha1,"size":size}));
                }
                json!({"path":path,"type":"file","executable":executable,"downloads":parsed})
            },
            _ => return Err(fail("invalid_metadata", "unsupported runtime type")),
        };
        result.push(item);
    }
    Ok(json!({"entries":result}))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn preserves_order_and_rejects_ambiguous_records() {
        let params = json!({"metadata":"{\"files\":{\"z\":{\"type\":\"directory\"},\"a\":{\"type\":\"link\",\"target\":\"z\"}}}"});
        let result = parse(&params).ok().unwrap();
        assert_eq!(result["entries"][0]["path"], "z");
        assert_eq!(result["entries"][1]["path"], "a");
        let duplicate = json!({"metadata":"{\"files\":{\"a\":{\"type\":\"directory\"},\"a\":{\"type\":\"directory\"}}}"});
        assert!(parse(&duplicate).is_err());
        assert!(parse(&json!({"metadata":"{\"files\":{\"a\":{\"type\":\"unknown\"}}}"})).is_err());
    }
}
