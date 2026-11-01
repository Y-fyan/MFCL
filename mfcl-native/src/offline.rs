// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
use crate::{fail, Error};
use md5::{Digest, Md5};
use serde_json::{json, Value};

/// Computes the Java name-based UUID used by Minecraft offline profiles.
pub(crate) fn profile(params: &Value) -> Result<Value, Error> {
    let name = params.get("username").and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty()).ok_or_else(|| fail("invalid_params", "username must not be blank"))?;
    let mut hash = Md5::new();
    hash.update(b"OfflinePlayer:");
    hash.update(name.as_bytes());
    let mut bytes = hash.finalize();
    bytes[6] = (bytes[6] & 0x0f) | 0x30;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    let s = bytes.iter().map(|b| format!("{b:02x}")).collect::<String>();
    Ok(json!({"username":name,"uuid":format!("{}-{}-{}-{}-{}", &s[0..8], &s[8..12], &s[12..16], &s[16..20], &s[20..32])}))
}
