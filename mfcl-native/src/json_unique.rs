// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Strict JSON parsing that rejects duplicate object keys.
use serde::{de::{Error as DeError, MapAccess, SeqAccess, Visitor}, Deserialize, Deserializer};
use serde_json::{json, Value};
use std::fmt;

/// JSON value that rejects duplicate object keys before they can be silently overwritten.
struct UniqueJson(Value);

impl<'de> Deserialize<'de> for UniqueJson {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct UniqueVisitor;
        impl<'de> Visitor<'de> for UniqueVisitor {
            type Value = UniqueJson;
            fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result { formatter.write_str("JSON without duplicate object keys") }
            fn visit_unit<E: DeError>(self) -> Result<Self::Value, E> { Ok(UniqueJson(Value::Null)) }
            fn visit_none<E: DeError>(self) -> Result<Self::Value, E> { Ok(UniqueJson(Value::Null)) }
            fn visit_bool<E: DeError>(self, value: bool) -> Result<Self::Value, E> { Ok(UniqueJson(json!(value))) }
            fn visit_i64<E: DeError>(self, value: i64) -> Result<Self::Value, E> { Ok(UniqueJson(json!(value))) }
            fn visit_u64<E: DeError>(self, value: u64) -> Result<Self::Value, E> { Ok(UniqueJson(json!(value))) }
            fn visit_f64<E: DeError>(self, value: f64) -> Result<Self::Value, E> {
                serde_json::Number::from_f64(value).map(|n| UniqueJson(Value::Number(n))).ok_or_else(|| E::custom("invalid JSON number"))
            }
            fn visit_str<E: DeError>(self, value: &str) -> Result<Self::Value, E> { Ok(UniqueJson(json!(value))) }
            fn visit_string<E: DeError>(self, value: String) -> Result<Self::Value, E> { Ok(UniqueJson(json!(value))) }
            fn visit_seq<A: SeqAccess<'de>>(self, mut sequence: A) -> Result<Self::Value, A::Error> {
                let mut result = Vec::new();
                while let Some(value) = sequence.next_element::<UniqueJson>()? { result.push(value.0); }
                Ok(UniqueJson(Value::Array(result)))
            }
            fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Self::Value, A::Error> {
                let mut result = serde_json::Map::new();
                while let Some(key) = map.next_key::<String>()? {
                    if result.contains_key(&key) { return Err(A::Error::custom("duplicate JSON object key")); }
                    result.insert(key, map.next_value::<UniqueJson>()?.0);
                }
                Ok(UniqueJson(Value::Object(result)))
            }
        }
        deserializer.deserialize_any(UniqueVisitor)
    }
}

/// Parses JSON while rejecting duplicate object keys.
pub(crate) fn parse(source: &[u8]) -> Result<Value, serde_json::Error> {
    serde_json::from_slice::<UniqueJson>(source).map(|value| value.0)
}
