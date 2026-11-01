// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Selects canonical Mojang Java runtime metadata while rejecting ambiguous input.
use crate::{fail, Error};
use serde_json::{json, Value};

/// Mirrors JavaInfo.parseVersion for ASCII digits and legacy 1.x version names.
fn version_number(value: &str) -> i32 {
    let rest = value.strip_prefix("1.").unwrap_or(value);
    let length = rest.bytes().take_while(u8::is_ascii_digit).count();
    if length == 0 { return -1; }
    rest[..length].parse::<i32>().unwrap_or(-1)
}

/// Accepts only canonical integer values representable by the Java record fields.
fn int(value: Option<&Value>) -> bool {
    value.and_then(Value::as_i64).is_some_and(|n| i32::try_from(n).is_ok())
}

/// Requires ordinary metadata shapes so Gson-specific coercions keep the Java path.
fn canonical_candidate(value: &Value) -> bool {
    let Some(availability) = value.get("availability").and_then(Value::as_object) else { return false; };
    let Some(manifest) = value.get("manifest").and_then(Value::as_object) else { return false; };
    let Some(version) = value.get("version").and_then(Value::as_object) else { return false; };
    int(availability.get("group")) && int(availability.get("progress")) && int(manifest.get("size"))
        && manifest.get("url").and_then(Value::as_str).is_some_and(|s| !s.trim().is_empty())
        && manifest.get("sha1").and_then(Value::as_str).is_some()
        && version.get("name").and_then(Value::as_str).is_some()
        && version.get("released").and_then(Value::as_str).is_some()
}

/// Parses the complete canonical list and returns the first compatible candidate.
pub(crate) fn select(params: &Value) -> Result<Value, Error> {
    let source = params.get("metadata").and_then(Value::as_str).filter(|v| v.len() <= 768 * 1024)
        .ok_or_else(|| fail("invalid_params", "invalid runtime list"))?;
    let platform = params.get("platform").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing runtime platform"))?;
    let component = params.get("component").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing runtime component"))?;
    let major = params.get("major").and_then(Value::as_i64).and_then(|v| i32::try_from(v).ok())
        .ok_or_else(|| fail("invalid_params", "invalid runtime major version"))?;
    let data = crate::json_unique::parse(source.as_bytes()).map_err(|_| fail("invalid_metadata", "invalid or ambiguous runtime list"))?;
    let platforms = data.as_object().ok_or_else(|| fail("invalid_metadata", "invalid runtime platforms"))?;
    let mut count = 0usize;
    for components in platforms.values() {
        let components = components.as_object().ok_or_else(|| fail("invalid_metadata", "invalid runtime components"))?;
        for candidates in components.values() {
            let candidates = candidates.as_array().ok_or_else(|| fail("invalid_metadata", "invalid runtime candidates"))?;
            count = count.saturating_add(candidates.len());
            if count > 20_000 || candidates.iter().any(|candidate| !canonical_candidate(candidate)) {
                return Err(fail("invalid_metadata", "unsupported runtime candidate"));
            }
        }
    }
    let selected = platforms.get(platform).and_then(Value::as_object).and_then(|v| v.get(component)).and_then(Value::as_array)
        .and_then(|items| items.iter().find(|item| version_number(item["version"]["name"].as_str().unwrap_or("")) >= major));
    Ok(json!({"selected":selected}))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn selects_first_compatible_and_rejects_ambiguous_metadata() {
        let candidate = |version: &str| json!({"availability":{"group":0,"progress":100},
            "manifest":{"url":"https://example.test/java.json","sha1":"abc","size":1},
            "version":{"name":version,"released":"2026-01-01"}});
        let metadata = json!({"linux":{"java-runtime-beta":[candidate("16.0.2"),candidate("17.0.1"),candidate("21")]} }).to_string();
        let params = json!({"metadata":metadata,"platform":"linux","component":"java-runtime-beta","major":17});
        assert_eq!(select(&params).ok().unwrap()["selected"]["version"]["name"], "17.0.1");
        assert_eq!(version_number("1.8.0_321"), 8);
        assert_eq!(version_number("999999999999999999999"), -1);
        let duplicate = json!({"metadata":"{\"linux\":{},\"linux\":{}}","platform":"linux","component":"x","major":17});
        assert!(select(&duplicate).is_err());
        let malformed = json!({"metadata":"{\"linux\":{\"x\":[{}]}}","platform":"linux","component":"x","major":17});
        assert!(select(&malformed).is_err());
    }
}
