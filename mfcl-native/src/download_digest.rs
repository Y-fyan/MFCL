// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Shared digest handling for serial, parallel and resumed downloads.
use crate::{fail, Error};
use md5::Md5;
use serde_json::Value;
use sha1::{Digest, Sha1};
use sha2::{Sha256, Sha512};

/// Expected digests supplied by the caller.
pub(crate) struct Expected {
    sha1: Option<String>, sha256: Option<String>, sha512: Option<String>, md5: Option<String>,
}

impl Expected {
    /// Parses all supplied digests, rejecting malformed or unverified requests.
    pub(crate) fn parse(params: &Value) -> Result<Self, Error> {
        fn digest(params: &Value, name: &str, length: usize) -> Result<Option<String>, Error> {
            match params.get(name) {
                None | Some(Value::Null) => Ok(None),
                Some(Value::String(v)) if v.len() == length && v.bytes().all(|b| b.is_ascii_hexdigit()) => Ok(Some(v.to_ascii_lowercase())),
                _ => Err(fail("invalid_params", format!("{name} has an invalid digest"))),
            }
        }
        let result = Self { sha1: digest(params, "sha1", 40)?, sha256: digest(params, "sha256", 64)?,
            sha512: digest(params, "sha512", 128)?, md5: digest(params, "md5", 32)? };
        if result.key().is_none() && params.get("allow_unverified").and_then(Value::as_bool) != Some(true) {
            return Err(fail("invalid_params", "a verified digest is required"));
        }
        Ok(result)
    }

    /// Selects the strongest supplied digest as a checkpoint namespace.
    pub(crate) fn key(&self) -> Option<&str> {
        self.sha512.as_deref().or(self.sha256.as_deref()).or(self.sha1.as_deref()).or(self.md5.as_deref())
    }

    /// Checks whether metadata mode has an unsupported non-SHA-1 verification requirement.
    pub(crate) fn has_non_sha1(&self) -> bool { self.sha256.is_some() || self.sha512.is_some() || self.md5.is_some() }

    /// Returns whether all supplied digests match the computed result.
    pub(crate) fn matches(&self, result: &Actual) -> bool {
        self.sha1.as_ref().is_none_or(|v| *v == result.sha1)
            && self.sha256.as_ref().is_none_or(|v| result.sha256.as_ref() == Some(v))
            && self.sha512.as_ref().is_none_or(|v| result.sha512.as_ref() == Some(v))
            && self.md5.as_ref().is_none_or(|v| result.md5.as_ref() == Some(v))
    }
}

/// Computed digest values; SHA-1 is always retained for protocol compatibility.
pub(crate) struct Actual { pub(crate) sha1: String, pub(crate) sha256: Option<String>, pub(crate) sha512: Option<String>, pub(crate) md5: Option<String> }

/// Incrementally computes only the requested digests plus the legacy SHA-1 result.
pub(crate) struct Hasher { sha1: Sha1, sha256: Option<Sha256>, sha512: Option<Sha512>, md5: Option<Md5> }

impl Hasher {
    /// Creates a digest set from the requested algorithms.
    pub(crate) fn new(expected: &Expected) -> Self {
        Self { sha1: Sha1::new(), sha256: expected.sha256.as_ref().map(|_| Sha256::new()),
            sha512: expected.sha512.as_ref().map(|_| Sha512::new()), md5: expected.md5.as_ref().map(|_| Md5::new()) }
    }
    /// Adds one byte sequence to the active digests.
    pub(crate) fn update(&mut self, bytes: &[u8]) {
        self.sha1.update(bytes);
        if let Some(hash) = self.sha256.as_mut() { hash.update(bytes); }
        if let Some(hash) = self.sha512.as_mut() { hash.update(bytes); }
        if let Some(hash) = self.md5.as_mut() { hash.update(bytes); }
    }
    /// Finalizes all active digests.
    pub(crate) fn finish(self) -> Actual {
        Actual { sha1: format!("{:x}", self.sha1.finalize()),
            sha256: self.sha256.map(|v| format!("{:x}", v.finalize())),
            sha512: self.sha512.map(|v| format!("{:x}", v.finalize())), md5: self.md5.map(|v| format!("{:x}", v.finalize())) }
    }
}
