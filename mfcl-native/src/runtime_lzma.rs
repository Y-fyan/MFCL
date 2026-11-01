// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Bounded LZMA staging for Mojang Java runtime files.
use crate::{fail, Error};
use serde_json::{json, Value};
use sha1::{Digest, Sha1};
use std::{fs::OpenOptions, io::{self, BufReader, BufWriter, Read, Write}, path::Path};
use tempfile::Builder;

const MAX_FILE: u64 = 1024 * 1024 * 1024;
const MAX_DICTIONARY: usize = 128 * 1024 * 1024;

/// Output wrapper that enforces the expected size and hashes bytes as they are written.
struct VerifiedWriter<W> { inner: W, hash: Sha1, count: u64, limit: u64 }

impl<W: Write> Write for VerifiedWriter<W> {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if self.count.checked_add(bytes.len() as u64).is_none_or(|n| n > self.limit) {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "runtime output exceeds limit"));
        }
        let written = self.inner.write(bytes)?;
        self.hash.update(&bytes[..written]); self.count += written as u64;
        Ok(written)
    }
    fn flush(&mut self) -> io::Result<()> { self.inner.flush() }
}

/// Decodes a verified LZMA payload into a caller-owned private staging path.
pub(crate) fn stage(params: &Value) -> Result<Value, Error> {
    let source = params.get("source").and_then(Value::as_str).filter(|s| !s.is_empty() && !s.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "invalid runtime compressed path"))?;
    let target = params.get("stage").and_then(Value::as_str).filter(|s| !s.is_empty() && !s.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "invalid runtime staging path"))?;
    let probe = params.get("probe").and_then(Value::as_str).filter(|s| !s.is_empty() && !s.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "invalid runtime permissions probe"))?;
    let expected = params.get("sha1").and_then(Value::as_str).filter(|s| s.len() == 40 && s.bytes().all(|b| b.is_ascii_hexdigit()))
        .ok_or_else(|| fail("invalid_params", "invalid runtime SHA-1"))?;
    let size = params.get("size").and_then(Value::as_u64).filter(|s| *s > 0 && *s <= MAX_FILE)
        .ok_or_else(|| fail("invalid_params", "invalid runtime output size"))?;
    let parent = Path::new(target).parent().filter(|p| p.is_dir()).ok_or_else(|| fail("invalid_params", "invalid runtime staging directory"))?;
    if Path::new(probe).parent() != Some(parent) { return Err(fail("invalid_params", "runtime probe is outside staging directory")); }
    let mut open = OpenOptions::new(); open.read(true);
    #[cfg(unix)] { use std::os::unix::fs::OpenOptionsExt; open.custom_flags(libc::O_NONBLOCK | libc::O_NOFOLLOW); }
    let input = open.open(source).map_err(|_| fail("io_error", "cannot open runtime compressed file"))?;
    let probe_file = open.open(probe).map_err(|_| fail("io_error", "cannot inspect runtime permissions probe"))?;
    if !probe_file.metadata().is_ok_and(|v| v.is_file()) || !crate::library_check::has_no_extended_attributes(&probe_file) {
        return Err(fail("unsupported_file", "runtime permissions require Java handling"));
    }
    let metadata = input.metadata().map_err(|_| fail("io_error", "cannot inspect runtime compressed file"))?;
    if !metadata.is_file() || metadata.len() > MAX_FILE { return Err(fail("invalid_params", "unsupported runtime compressed file")); }
    let mut input = BufReader::new(input.take(MAX_FILE + 1));
    let mut output = Builder::new().prefix(".mfcl-runtime-lzma-").tempfile_in(parent)
        .map_err(|_| fail("io_error", "cannot create runtime staging file"))?;
    if !crate::library_check::has_no_extended_attributes(output.as_file()) || !crate::library_check::has_no_extended_attributes(&probe_file) {
        return Err(fail("unsupported_file", "runtime staging attributes require Java handling"));
    }
    let (count, actual) = {
        let mut writer = VerifiedWriter { inner: BufWriter::new(output.as_file_mut()), hash: Sha1::new(), count: 0, limit: size };
        let options = lzma_rs::decompress::Options { memlimit: Some(MAX_DICTIONARY), ..Default::default() };
        lzma_rs::lzma_decompress_with_options(&mut input, &mut writer, &options)
            .map_err(|_| fail("invalid_archive", "runtime LZMA decode failed"))?;
        writer.flush().map_err(|_| fail("io_error", "cannot flush runtime staging file"))?;
        (writer.count, format!("{:x}", writer.hash.clone().finalize()))
    };
    if count != size || !actual.eq_ignore_ascii_case(expected) {
        return Err(fail("checksum_mismatch", "runtime output does not match metadata"));
    }
    if !crate::library_check::has_no_extended_attributes(output.as_file()) || !crate::library_check::has_no_extended_attributes(&probe_file) {
        return Err(fail("unsupported_file", "runtime staging attributes require Java handling"));
    }
    output.flush().map_err(|_| fail("io_error", "cannot flush runtime staging file"))?;
    output.persist_noclobber(target).map_err(|_| fail("io_error", "cannot publish runtime staging file"))?;
    Ok(json!({"size":count,"sha1":actual}))
}
