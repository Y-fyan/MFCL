// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Direct Yggdrasil JSON transport; credentials never appear in diagnostics.
use crate::{fail, Error};
use base64::{engine::general_purpose::{STANDARD, STANDARD_NO_PAD}, Engine};
use serde::{de::{Error as DeError, MapAccess, Visitor}, Deserialize, Deserializer};
use serde_json::{json, Value};
use serde_json::value::RawValue;
use sha2::{Digest, Sha256};
use std::{collections::HashSet, fmt, fs::OpenOptions, io::Read, time::Duration};
use url::Url;

const MAX_RESPONSE: u64 = 4 * 1024 * 1024;
const MAX_SKIN: u64 = 8 * 1024 * 1024;

/// Projects ordinary authlib-injector server metadata with ordered link fields.
pub(crate) fn plan_authlib_metadata(params: &Value) -> Result<Value, Error> {
    let source = params.get("metadata").and_then(Value::as_str).filter(|s| s.len() <= 768 * 1024)
        .ok_or_else(|| fail("invalid_params", "invalid server metadata"))?;
    let root = crate::json_unique::parse(source.as_bytes()).map_err(|_| fail("invalid_metadata", "invalid or ambiguous server metadata"))?;
    let object = root.as_object().ok_or_else(|| fail("invalid_metadata", "invalid server metadata"))?;
    let mut name = Value::Null; let mut links = Vec::new(); let mut non_email = false; let mut links_present = false;
    if let Some(meta) = object.get("meta").and_then(Value::as_object) {
        match meta.get("serverName") {
            Some(Value::String(value)) => name = json!(value),
            Some(Value::Bool(value)) => name = json!(value.to_string()),
            Some(Value::Number(_)) => return Err(fail("unsupported_metadata", "numeric server name")),
            _ => (),
        }
        match meta.get("feature.non_email_login") {
            Some(Value::Bool(value)) => non_email = *value,
            Some(Value::String(value)) => non_email = value.eq_ignore_ascii_case("true"),
            Some(Value::Number(_)) => return Err(fail("unsupported_metadata", "numeric feature")),
            _ => (),
        }
        if meta.get("links").is_some_and(Value::is_object) {
            links_present = true;
            let root_raw: std::collections::HashMap<String, Box<RawValue>> = serde_json::from_str(source)
                .map_err(|_| fail("invalid_metadata", "invalid server metadata"))?;
            let raw_meta: std::collections::HashMap<String, Box<RawValue>> = serde_json::from_str(root_raw.get("meta")
                .ok_or_else(|| fail("invalid_metadata", "missing metadata"))?.get()).map_err(|_| fail("invalid_metadata", "invalid metadata"))?;
            let crate::game_manifest::OrderedEntries(entries) = serde_json::from_str(raw_meta.get("links")
                .ok_or_else(|| fail("invalid_metadata", "missing links"))?.get()).map_err(|_| fail("invalid_metadata", "invalid links"))?;
            for (key, value) in entries {
                let text = match value { Value::String(value) => value, Value::Bool(value) => value.to_string(),
                    Value::Number(_) => return Err(fail("unsupported_metadata", "numeric link")), _ => continue };
                links.push(json!({"key":key,"value":text}));
            }
        }
    }
    Ok(json!({"name":name,"links":links,"links_present":links_present,"non_email_login":non_email}))
}

/// Known top-level authentication fields, retaining nested JSON exactly for Java adapters.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct AuthenticationResponse {
    error: Option<Box<RawValue>>,
    error_message: Option<Box<RawValue>>,
    cause: Option<Box<RawValue>>,
    client_token: Option<Box<RawValue>>,
    access_token: Option<Box<RawValue>>,
    selected_profile: Option<Box<RawValue>>,
    available_profiles: Option<Box<RawValue>>,
    user: Option<Box<RawValue>>,
}

/// Strict profile fields; Gson-only coercions and duplicate fields retain Java fallback.
#[derive(Deserialize)]
struct Profile { id: Option<String>, name: Option<String> }

/// Complete profile fields, with properties kept raw until validated.
#[derive(Deserialize)]
struct CompleteProfile { id: Option<String>, name: Option<String>, properties: Option<Box<RawValue>> }

/// Strict user fields; raw properties preserve Gson's array semantics.
#[derive(Deserialize)]
struct User { id: Option<String>, properties: Option<Box<RawValue>> }

/// One string-valued property entry.
#[derive(Deserialize)]
struct Property { name: Option<String>, value: Option<String> }

/// Texture response with an exact raw texture map.
#[derive(Deserialize)]
struct TexturePayload { textures: Option<Box<RawValue>> }

/// Texture values that require no Gson-specific coercion.
#[derive(Deserialize)]
struct TextureValue { url: Option<String>, metadata: Option<Box<RawValue>> }

/// Ordered JSON map whose values are retained as raw JSON.
struct OrderedRawMap(Vec<(String, Option<Box<RawValue>>)>);

impl<'de> Deserialize<'de> for OrderedRawMap {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct MapVisitor;
        impl<'de> Visitor<'de> for MapVisitor {
            type Value = OrderedRawMap;
            fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { f.write_str("an ordered JSON object") }
            fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Self::Value, A::Error> {
                let mut result = Vec::new(); let mut names = HashSet::new();
                while let Some(key) = map.next_key::<String>()? {
                    if result.len() >= 20_000 || !names.insert(key.clone()) { return Err(A::Error::custom("invalid map keys")); }
                    result.push((key, map.next_value::<Option<Box<RawValue>>>()?));
                }
                Ok(OrderedRawMap(result))
            }
        }
        deserializer.deserialize_map(MapVisitor)
    }
}

/// Ordered metadata map with string or null values.
struct OrderedStringMap(Vec<(String, Option<String>)>);

impl<'de> Deserialize<'de> for OrderedStringMap {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct MapVisitor;
        impl<'de> Visitor<'de> for MapVisitor {
            type Value = OrderedStringMap;
            fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { f.write_str("an ordered string map") }
            fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Self::Value, A::Error> {
                let mut result = Vec::new(); let mut names = HashSet::new();
                while let Some(key) = map.next_key::<String>()? {
                    if result.len() >= 20_000 || !names.insert(key.clone()) { return Err(A::Error::custom("invalid map keys")); }
                    result.push((key, map.next_value::<Option<String>>()?));
                }
                Ok(OrderedStringMap(result))
            }
        }
        deserializer.deserialize_map(MapVisitor)
    }
}

/// Matches Java's whitespace classification for ASCII input.
fn ascii_blank(value: &str) -> bool {
    value.bytes().all(|b| b == b' ' || (0x09..=0x0d).contains(&b) || (0x1c..=0x1f).contains(&b))
}

/// Converts ordinary compact and hyphenated UUIDs to canonical lowercase form.
fn canonical_uuid(value: &str) -> Option<String> {
    let bytes = value.as_bytes();
    let compact = if bytes.len() == 32 && bytes.iter().all(u8::is_ascii_hexdigit) { value.to_ascii_lowercase() }
        else if bytes.len() == 36 && bytes.iter().enumerate().all(|(i, b)| {
            if [8, 13, 18, 23].contains(&i) { *b == b'-' } else { b.is_ascii_hexdigit() }
        }) { value.chars().filter(|c| *c != '-').collect::<String>().to_ascii_lowercase() }
        else { return None; };
    Some(format!("{}-{}-{}-{}-{}", &compact[..8], &compact[8..12], &compact[12..16], &compact[16..20], &compact[20..]))
}

/// Parses a canonical game profile; other Gson-compatible forms use Java fallback.
fn profile(raw: &RawValue) -> Option<Value> {
    if !raw.get().trim_start().starts_with('{') { return None; }
    let parsed: Profile = serde_json::from_str(raw.get()).ok()?;
    let id = canonical_uuid(parsed.id.as_deref()?)?; let name = parsed.name?;
    Some(json!({"id":id,"name":name}))
}

/// Parses a profile list while preserving null entries and input order.
fn profiles(raw: &RawValue) -> Option<Value> {
    let values: Vec<Option<Box<RawValue>>> = serde_json::from_str(raw.get()).ok()?;
    if values.len() > 20_000 { return None; }
    let mut result = Vec::with_capacity(values.len());
    for value in values {
        result.push(match value { None => Value::Null, Some(value) => profile(&value)? });
    }
    Some(Value::Array(result))
}

/// Parses user properties as ordered pairs, retaining duplicate-key overwrite semantics in Java.
fn user(raw: &RawValue) -> Option<Value> {
    if !raw.get().trim_start().starts_with('{') { return None; }
    let parsed: User = serde_json::from_str(raw.get()).ok()?;
    let id = parsed.id?; if !id.is_ascii() || ascii_blank(&id) { return None; }
    let properties = match parsed.properties { None => Value::Null, Some(raw) => property_pairs(&raw)? };
    Some(json!({"id":id,"properties":properties}))
}

/// Parses the ordered property array used by both user and complete-profile responses.
fn property_pairs(raw: &RawValue) -> Option<Value> {
    let values: Vec<Box<RawValue>> = serde_json::from_str(raw.get()).ok()?;
    if values.len() > 20_000 { return None; }
    let mut result = Vec::new();
    for value in values {
        if !value.get().trim_start().starts_with('{') { continue; }
        let property: Property = serde_json::from_str(value.get()).ok()?;
        result.push(json!({"name":property.name?,"value":property.value?}));
    }
    Some(Value::Array(result))
}

/// Parses an ordinary complete profile without changing or fetching account data.
pub(crate) fn plan_complete_profile(params: &Value) -> Result<Value, Error> {
    let text = params.get("response").and_then(Value::as_str).filter(|s| s.len() <= 768 * 1024)
        .ok_or_else(|| fail("invalid_params", "profile response is required"))?;
    if !text.trim_start().starts_with('{') { return Err(fail("unsupported_response", "unsupported profile response")); }
    let parsed: CompleteProfile = serde_json::from_str(text).map_err(|_| fail("unsupported_response", "unsupported profile response"))?;
    let id = parsed.id.as_deref().and_then(canonical_uuid).ok_or_else(|| fail("unsupported_response", "unsupported profile ID"))?;
    let name = parsed.name.ok_or_else(|| fail("unsupported_response", "missing profile name"))?;
    let properties = parsed.properties.as_ref().and_then(|raw| property_pairs(raw))
        .ok_or_else(|| fail("unsupported_response", "unsupported profile properties"))?;
    Ok(json!({"profile":{"id":id,"name":name,"properties":properties}}))
}

/// Decodes ordinary skin/cape texture properties without fetching remote images.
pub(crate) fn plan_textures(params: &Value) -> Result<Value, Error> {
    let encoded = params.get("textures").and_then(Value::as_str).filter(|s| s.len() <= 768 * 1024)
        .ok_or_else(|| fail("invalid_params", "texture property is required"))?;
    let bytes = STANDARD.decode(encoded).or_else(|_| STANDARD_NO_PAD.decode(encoded))
        .map_err(|_| fail("unsupported_response", "unsupported texture encoding"))?;
    let text = std::str::from_utf8(&bytes).map_err(|_| fail("unsupported_response", "unsupported texture encoding"))?;
    if !text.trim_start().starts_with('{') { return Err(fail("unsupported_response", "unsupported texture response")); }
    let payload: TexturePayload = serde_json::from_str(text).map_err(|_| fail("unsupported_response", "unsupported texture response"))?;
    let Some(raw) = payload.textures else { return Ok(json!({"present":false,"textures":null})); };
    let OrderedRawMap(values) = serde_json::from_str(raw.get()).map_err(|_| fail("unsupported_response", "unsupported texture map"))?;
    let mut result = Vec::with_capacity(values.len());
    for (kind, raw) in values {
        if kind != "SKIN" && kind != "CAPE" { return Err(fail("unsupported_response", "unsupported texture type")); }
        let texture = match raw {
            None => Value::Null,
            Some(raw) => {
                if !raw.get().trim_start().starts_with('{') { return Err(fail("unsupported_response", "unsupported texture value")); }
                let value: TextureValue = serde_json::from_str(raw.get()).map_err(|_| fail("unsupported_response", "unsupported texture value"))?;
                let metadata = match value.metadata {
                    None => Value::Null,
                    Some(raw) => {
                        let OrderedStringMap(entries) = serde_json::from_str(raw.get()).map_err(|_| fail("unsupported_response", "unsupported texture metadata"))?;
                        json!(entries.into_iter().map(|(name,value)| json!({"name":name,"value":value})).collect::<Vec<_>>())
                    }
                };
                json!({"url":value.url,"metadata":metadata})
            }
        };
        result.push(json!({"type":kind,"texture":texture}));
    }
    Ok(json!({"present":true,"textures":result}))
}

/// Produces an absent, decoded or Java-fallback state while retaining unsupported raw JSON.
fn nested(raw: &Option<Box<RawValue>>, parse: impl FnOnce(&RawValue) -> Option<Value>) -> Value {
    match raw {
        None => json!({"state":"absent","decoded":null}),
        Some(raw) => match parse(raw) {
            Some(value) => json!({"state":"decoded","decoded":value}),
            None => json!({"state":"fallback","decoded":null}),
        }
    }
}

/// Converts a string-valued field without accepting Gson-specific coercions.
fn optional_string(value: &Option<Box<RawValue>>) -> Result<Option<String>, Error> {
    match value {
        None => Ok(None),
        Some(value) => serde_json::from_str::<String>(value.get()).map(Some)
            .map_err(|_| fail("unsupported_response", "unsupported authentication field")),
    }
}

/// Parses and classifies a Yggdrasil login or refresh response without logging its contents.
pub(crate) fn plan_response(params: &Value) -> Result<Value, Error> {
    let text = params.get("response").and_then(Value::as_str).filter(|s| s.len() <= 768 * 1024)
        .ok_or_else(|| fail("invalid_params", "authentication response is required"))?;
    let expected = params.get("client_token").and_then(Value::as_str).filter(|s| s.len() <= 65_536)
        .ok_or_else(|| fail("invalid_params", "client token is required"))?;
    if !text.trim_start().starts_with('{') { return Err(fail("unsupported_response", "unsupported authentication response")); }
    let parsed: AuthenticationResponse = serde_json::from_str(text)
        .map_err(|_| fail("unsupported_response", "unsupported authentication response"))?;
    let error = optional_string(&parsed.error)?; let error_message = optional_string(&parsed.error_message)?;
    let cause = optional_string(&parsed.cause)?; let client = optional_string(&parsed.client_token)?;
    let access = optional_string(&parsed.access_token)?;
    if error.as_ref().is_some_and(|value| !value.is_ascii()) {
        return Err(fail("unsupported_response", "unsupported authentication error"));
    }
    let has_error = error.as_ref().is_some_and(|value| !ascii_blank(value));
    let outcome = if has_error { "remote_error" } else if client.as_deref() != Some(expected) { "token_mismatch" } else { "ok" };
    Ok(json!({"outcome":outcome,"error":error,"error_message":error_message,"cause":cause,
        "client_token":client,"access_token":access,
        "selected_decoded":nested(&parsed.selected_profile, profile),
        "available_decoded":nested(&parsed.available_profiles, profiles),
        "user_decoded":nested(&parsed.user, user),
        "selected_profile":parsed.selected_profile.as_ref().map(|v| v.get()),
        "available_profiles":parsed.available_profiles.as_ref().map(|v| v.get()),
        "user":parsed.user.as_ref().map(|v| v.get())}))
}

/// Builds an upload body without following redirects or logging credentials.
fn skin_body(params: &Value) -> Result<(String, Vec<u8>), Error> {
    let path = params.get("path").and_then(Value::as_str).filter(|v| !v.is_empty() && !v.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "skin path is required"))?;
    let filename = params.get("filename").and_then(Value::as_str).filter(|v| !v.is_empty() && v.len() <= 4096
        && !v.chars().any(|c| c == '"' || c == '\r' || c == '\n' || c == '\0'))
        .ok_or_else(|| fail("invalid_params", "unsupported skin filename"))?;
    let content_type = params.get("file_content_type").and_then(Value::as_str)
        .filter(|v| v.starts_with("image/") && v.len() <= 4096 && v.bytes().all(|b| (0x20..=0x7e).contains(&b)))
        .ok_or_else(|| fail("invalid_params", "invalid skin content type"))?;
    let slim = params.get("slim").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "skin model is required"))?;
    let mut options = OpenOptions::new(); options.read(true);
    #[cfg(unix)] {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NONBLOCK);
    }
    let mut file = options.open(path).map_err(|_| fail("io_error", "cannot open skin file"))?;
    let metadata = file.metadata().map_err(|_| fail("io_error", "cannot inspect skin file"))?;
    if !metadata.is_file() || metadata.len() > MAX_SKIN { return Err(fail("invalid_params", "unsupported skin file")); }
    let mut bytes = Vec::new();
    (&mut file).take(MAX_SKIN + 1).read_to_end(&mut bytes).map_err(|_| fail("io_error", "cannot read skin file"))?;
    if bytes.len() as u64 > MAX_SKIN { return Err(fail("invalid_params", "unsupported skin file")); }
    let digest = format!("{:x}", Sha256::digest(&bytes)); let mut boundary = format!("mfcl-{}", &digest[..40]);
    for _ in 0..16 {
        if !bytes.windows(boundary.len()).any(|part| part == boundary.as_bytes()) { break; }
        boundary.push('x');
    }
    if bytes.windows(boundary.len()).any(|part| part == boundary.as_bytes()) { return Err(fail("invalid_params", "cannot encode skin file")); }
    let mut body = format!("--{boundary}\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n{}\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\nContent-Type: {content_type}\r\n\r\n", if slim { "slim" } else { "" }).into_bytes();
    body.extend_from_slice(&bytes); body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    Ok((boundary, body))
}

/// Performs one non-retrying request against a user-selected authentication service.
pub(crate) fn request(params: &Value) -> Result<Value, Error> {
    let raw_url = params.get("url").and_then(Value::as_str).filter(|v| !v.is_empty() && v.len() <= 65_536)
        .ok_or_else(|| fail("invalid_params", "authentication URL is required"))?;
    let url = Url::parse(raw_url).map_err(|_| fail("invalid_params", "invalid authentication URL"))?;
    if !["http", "https"].contains(&url.scheme()) || url.host().is_none() || !url.username().is_empty()
        || url.password().is_some() || url.fragment().is_some() {
        return Err(fail("invalid_params", "unsupported authentication URL"));
    }
    let method = match params.get("method") {
        None => "POST",
        Some(Value::String(value)) => value.as_str(),
        _ => return Err(fail("invalid_params", "invalid authentication method")),
    };
    if !["GET", "POST", "PUT"].contains(&method) { return Err(fail("invalid_params", "unsupported authentication method")); }
    let header = |key: &str| -> Result<&str, Error> {
        params.get(key).and_then(Value::as_str).filter(|v| v.len() <= 4096 && v.bytes().all(|b| (0x20..=0x7e).contains(&b)))
            .ok_or_else(|| fail("invalid_params", "invalid authentication header"))
    };
    let user_agent = header("user_agent")?; let language = header("language")?;
    let agent = ureq::AgentBuilder::new().try_proxy_from_env(false).redirects(0)
        .timeout_connect(Duration::from_secs(10)).timeout_read(Duration::from_secs(10)).timeout_write(Duration::from_secs(10)).build();
    let request = agent.request(method, url.as_str()).set("User-Agent", user_agent).set("Accept-Language", language);
    let sent = match method {
        "GET" => request.call(),
        "POST" => {
            let body = params.get("body").and_then(Value::as_str).filter(|v| v.len() <= 768 * 1024)
                .ok_or_else(|| fail("invalid_params", "invalid authentication body"))?;
            request.set("Content-Type", "application/json; charset=utf-8").send_bytes(body.as_bytes())
        }
        "PUT" => {
            let token = params.get("access_token").and_then(Value::as_str).filter(|v| v.len() <= 65_536
                && v.bytes().all(|b| (0x20..=0x7e).contains(&b)))
                .ok_or_else(|| fail("invalid_params", "invalid authentication token"))?;
            let (boundary, body) = skin_body(params)?;
            request.set("Authorization", &format!("Bearer {token}"))
                .set("Content-Type", &format!("multipart/form-data; boundary={boundary}")).send_bytes(&body)
        }
        _ => unreachable!(),
    };
    let response = match sent {
        Ok(response) => response,
        Err(ureq::Error::Status(_, response)) => response,
        Err(_) => return Err(fail("network_error", "authentication request failed")),
    };
    let status = response.status();
    if method == "GET" && (300..=308).contains(&status) && status != 304 && status != 306 {
        return Ok(json!({"status":status,"body_base64":"","content_type":null,"content_encoding":null}));
    }
    let content_type = response.header("Content-Type").map(str::to_owned);
    let content_encoding = response.header("Content-Encoding").map(str::to_owned);
    let authlib_location = if method == "GET" { response.header("x-authlib-injector-api-location").map(str::to_owned) } else { None };
    if content_type.as_ref().is_some_and(|v| v.len() > 8192) || content_encoding.as_ref().is_some_and(|v| v.len() > 8192)
        || authlib_location.as_ref().is_some_and(|v| v.len() > 8192) {
        return Err(fail("invalid_response", "authentication response header too large"));
    }
    let mut bytes = Vec::new();
    response.into_reader().take(MAX_RESPONSE + 1).read_to_end(&mut bytes)
        .map_err(|_| fail("network_error", "authentication response read failed"))?;
    if bytes.len() as u64 > MAX_RESPONSE { return Err(fail("invalid_response", "authentication response too large")); }
    if status >= 400 && bytes.is_empty() { return Err(fail("network_error", "authentication service returned an empty error")); }
    Ok(json!({"status":status,"body_base64":STANDARD.encode(bytes),"content_type":content_type,
        "content_encoding":content_encoding,"authlib_location":authlib_location}))
}
