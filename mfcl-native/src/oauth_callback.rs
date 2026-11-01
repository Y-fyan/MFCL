// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Parses ordinary OAuth callback queries without retaining credentials.
use crate::{fail, Error};
use serde_json::{json, Value};
use std::collections::HashSet;

/// Parses unambiguous URL-encoded callback parameters; unusual forms use Java handling.
pub(crate) fn parse(params: &Value) -> Result<Value, Error> {
    let query = params.get("query").and_then(Value::as_str).filter(|q| q.len() <= 64 * 1024)
        .ok_or_else(|| fail("invalid_params", "invalid callback query"))?;
    if query.is_empty() || !query.bytes().all(|b| (32..127).contains(&b)) {
        return Err(fail("unsupported_query", "unsupported callback query"));
    }
    let bytes = query.as_bytes();
    let mut pos = 0;
    while pos < bytes.len() {
        if bytes[pos] == b'%' {
            if pos + 2 >= bytes.len() || !bytes[pos + 1].is_ascii_hexdigit() || !bytes[pos + 2].is_ascii_hexdigit() {
                return Err(fail("unsupported_query", "invalid URL encoding"));
            }
            pos += 3;
        } else { pos += 1; }
    }
    let parts: Vec<&str> = query.split('&').collect();
    if parts.len() > 128 || parts.iter().any(|part| part.is_empty() || part.matches('=').count() != 1 || part.starts_with('=') || part.ends_with('=')) {
        return Err(fail("unsupported_query", "ambiguous callback parameters"));
    }
    let mut seen = HashSet::new(); let mut result = serde_json::Map::new();
    for (key, value) in url::form_urlencoded::parse(query.as_bytes()) {
        if key.contains('\u{fffd}') || value.contains('\u{fffd}') || !seen.insert(key.to_string()) {
            return Err(fail("unsupported_query", "ambiguous callback parameters"));
        }
        if ["code", "state", "id_token"].contains(&key.as_ref()) {
            result.insert(key.into_owned(), json!(value.into_owned()));
        }
    }
    Ok(Value::Object(result))
}

/// Classifies a parsed callback against the session state without exposing values.
pub(crate) fn classify(fields: &Value, expected_state: &str) -> Option<&'static str> {
    let fields = fields.as_object()?;
    if fields.get("code").and_then(Value::as_str).is_none() { return Some("missing_code"); }
    match fields.get("state").and_then(Value::as_str) {
        None => Some("missing_state"),
        Some(value) if value == expected_state => Some("ok"),
        Some(_) => Some("invalid_state"),
    }
}
