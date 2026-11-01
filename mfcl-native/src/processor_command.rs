// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Prepares the class and classpath for a Forge-family installer processor.
use crate::{fail, Error};
use serde_json::{json, Value};
use std::{collections::HashSet, fs::File, io::{Read, Seek, SeekFrom}, path::Path};

const MAX_MANIFEST: u64 = 1024 * 1024;
const MAX_ENTRIES: usize = 20_000;

/// Reads the non-ZIP64 directory entry count without accepting ambiguous archive tails.
pub(crate) fn original_entry_count(file: &mut File) -> Result<usize, Error> {
    let length = file.seek(SeekFrom::End(0)).map_err(|_| fail("io_error", "cannot inspect processor archive"))?;
    if length < 22 { return Err(fail("invalid_archive", "invalid processor archive")); }
    let tail_length = length.min(65_557) as usize;
    file.seek(SeekFrom::End(-(tail_length as i64))).map_err(|_| fail("io_error", "cannot inspect processor archive"))?;
    let mut tail = vec![0u8; tail_length];
    file.read_exact(&mut tail).map_err(|_| fail("io_error", "cannot inspect processor archive"))?;
    let mut found = None;
    for offset in (0..=tail.len() - 22).rev() {
        if &tail[offset..offset + 4] != b"PK\x05\x06" { continue; }
        let read16 = |position: usize| u16::from_le_bytes([tail[offset + position], tail[offset + position + 1]]);
        let read32 = |position: usize| u32::from_le_bytes([tail[offset + position], tail[offset + position + 1], tail[offset + position + 2], tail[offset + position + 3]]);
        let comment_length = read16(20) as usize;
        if offset + 22 + comment_length != tail.len() { continue; }
        let disk = read16(4); let directory_disk = read16(6); let disk_entries = read16(8); let entries = read16(10);
        let directory_size = read32(12); let directory_offset = read32(16);
        let zip64_locator = offset >= 20 && &tail[offset - 20..offset - 16] == b"PK\x06\x07";
        if disk != 0 || directory_disk != 0 || disk_entries != entries || entries == u16::MAX || zip64_locator
                || directory_size == u32::MAX || directory_offset == u32::MAX || directory_size > 16 * 1024 * 1024
                || u64::from(directory_offset) + u64::from(directory_size) > length {
            return Err(fail("invalid_archive", "unsupported processor archive directory"));
        }
        if found.replace(entries as usize).is_some() { return Err(fail("invalid_archive", "ambiguous processor archive tail")); }
    }
    found.ok_or_else(|| fail("invalid_archive", "unsupported processor archive tail"))
}

/// Reads a simple main manifest attribute, rejecting formats needing Java's JAR semantics.
pub(crate) fn main_class(params: &Value) -> Result<Value, Error> {
    let path = params.get("jar").and_then(Value::as_str).filter(|s| !s.is_empty() && !s.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "processor archive is required"))?;
    let mut file = crate::library_check::open_candidate(Path::new(path)).map_err(|_| fail("io_error", "cannot open processor archive"))?;
    if !file.metadata().map_err(|_| fail("io_error", "cannot inspect processor archive"))?.is_file() {
        return Err(fail("invalid_params", "processor archive must be a file"));
    }
    let original_entries = original_entry_count(&mut file)?;
    if original_entries > MAX_ENTRIES { return Err(fail("archive_limit", "too many processor entries")); }
    let mut zip = zip::ZipArchive::new(file).map_err(|_| fail("invalid_archive", "invalid processor archive"))?;
    if zip.len() != original_entries { return Err(fail("invalid_archive", "duplicate processor archive entries")); }
    let mut manifest_index = None;
    for (index, name) in zip.file_names().enumerate() {
        if !name.is_ascii() { return Err(fail("invalid_archive", "unsupported processor entry name")); }
        if name.eq_ignore_ascii_case("META-INF/MANIFEST.MF") {
            if manifest_index.is_some() || name != "META-INF/MANIFEST.MF" {
                return Err(fail("invalid_archive", "ambiguous processor manifest"));
            }
            manifest_index = Some(index);
        }
    }
    let index = manifest_index.ok_or_else(|| fail("invalid_archive", "missing processor manifest"))?;
    let mut entry = zip.by_index(index).map_err(|_| fail("invalid_archive", "cannot read processor manifest"))?;
    if entry.size() > MAX_MANIFEST || entry.compressed_size() > 4 * MAX_MANIFEST {
        return Err(fail("archive_limit", "processor manifest is too large"));
    }
    let mut bytes = Vec::new(); (&mut entry).take(MAX_MANIFEST + 1).read_to_end(&mut bytes)
        .map_err(|_| fail("invalid_archive", "cannot read processor manifest"))?;
    if bytes.len() as u64 > MAX_MANIFEST { return Err(fail("archive_limit", "processor manifest is too large")); }
    let text = std::str::from_utf8(&bytes).map_err(|_| fail("invalid_archive", "unsupported processor manifest"))?;
    if !text.ends_with('\n') { return Err(fail("invalid_archive", "unsupported processor manifest")); }
    let mut names = HashSet::new(); let mut headers = Vec::<(String, String)>::new(); let mut main_section_finished = false;
    for raw in text.split('\n') {
        let line = raw.strip_suffix('\r').unwrap_or(raw);
        if line.len() > 512 || line.chars().any(|c| c.is_control() && c != '\t') {
            return Err(fail("invalid_archive", "unsupported processor manifest"));
        }
        if line.is_empty() { main_section_finished = true; continue; }
        if main_section_finished { return Err(fail("invalid_archive", "processor manifest has additional sections")); }
        if let Some(continuation) = line.strip_prefix(' ') {
            let (_, value) = headers.last_mut().ok_or_else(|| fail("invalid_archive", "invalid processor manifest continuation"))?;
            value.push_str(continuation);
            continue;
        }
        let (name, value) = line.split_once(": ").ok_or_else(|| fail("invalid_archive", "unsupported processor manifest"))?;
        if name.is_empty() || name.len() > 70 || !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
            return Err(fail("invalid_archive", "unsupported processor manifest header"));
        }
        let name = name.to_ascii_lowercase();
        if !names.insert(name.clone()) { return Err(fail("invalid_archive", "duplicate processor manifest header")); }
        headers.push((name, value.to_owned()));
    }
    let main = headers.iter().find(|(name, _)| name == "main-class").map(|(_, value)| value.as_str())
        .ok_or_else(|| fail("invalid_archive", "missing processor main class"))?;
    if main.is_empty() || !main.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'$')) {
        return Err(fail("invalid_archive", "unsupported processor main class"));
    }
    Ok(json!({"main_class":main}))
}

/// Resolves the processor entry point and classpath in one request.
pub(crate) fn command(params: &Value) -> Result<Value, Error> {
    let main = main_class(params)?;
    let paths = classpath(params)?;
    let mut result = json!({"main_class":main["main_class"],"classpath":paths["classpath"]});
    if let Some(arguments) = params.get("arguments") {
        result["arguments"] = crate::forge_literals::expand(arguments)?;
    }
    Ok(result)
}

/// Resolves standard artifact coordinates alongside the processor command.
pub(crate) fn command_v2(params: &Value) -> Result<Value, Error> {
    let main = main_class(params)?; let paths = classpath(params)?;
    let args = params.get("arguments").ok_or_else(|| fail("invalid_params", "missing processor arguments"))?;
    Ok(json!({"main_class":main["main_class"],"classpath":paths["classpath"],"arguments":crate::forge_literals::expand_v2(args)?}))
}

/// Resolves processor dependencies from canonical coordinates and prepares the complete command.
pub(crate) fn command_v3(params: &Value) -> Result<Value, Error> {
    let root = params.get("libraries_root").and_then(Value::as_str).filter(|v| !v.is_empty() && !v.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "invalid libraries root"))?;
    let coordinates = params.get("coordinates").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing processor coordinates"))?;
    if coordinates.len() > MAX_ENTRIES { return Err(fail("argument_limit", "too many processor libraries")); }
    let mut literals = Vec::with_capacity(coordinates.len());
    for coordinate in coordinates {
        let coordinate = coordinate.as_str().ok_or_else(|| fail("invalid_params", "invalid processor coordinate"))?;
        literals.push(format!("[{coordinate}]"));
    }
    let resolved = crate::forge_literals::expand_v2(&json!({"artifact_root":root,"literals":literals,"variables":{}}))?;
    let paths = resolved.get("values").and_then(Value::as_array).ok_or_else(|| fail("invalid_response", "invalid resolved paths"))?;
    let mut input = params.clone();
    input.as_object_mut().ok_or_else(|| fail("invalid_params", "invalid processor command"))?.insert("libraries".into(), json!(paths));
    let main = main_class(&input)?; let classpath = classpath(&input)?;
    let mut result = json!({"main_class":main["main_class"],"classpath":classpath["classpath"],"library_paths":paths});
    if let Some(arguments) = params.get("arguments") {
        result["arguments"] = if arguments.get("artifact_root").is_some() {
            crate::forge_literals::expand_v2(arguments)?
        } else { crate::forge_literals::expand(arguments)? };
    }
    Ok(result)
}

/// Resolves and validates an ordered processor classpath without changing files.
pub(crate) fn classpath(params: &Value) -> Result<Value, Error> {
    let jar = params.get("jar").and_then(Value::as_str).filter(|s| !s.is_empty() && !s.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "processor archive is required"))?;
    let libraries = params.get("libraries").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "processor libraries are required"))?;
    if libraries.len() > MAX_ENTRIES { return Err(fail("argument_limit", "too many processor libraries")); }
    let separator = params.get("separator").and_then(Value::as_str).filter(|s| *s == ":" || *s == ";")
        .ok_or_else(|| fail("invalid_params", "invalid classpath separator"))?;
    let mut paths = Vec::with_capacity(libraries.len() + 1); let mut bytes = jar.len();
    for value in libraries {
        let path = value.as_str().filter(|s| !s.is_empty() && !s.contains('\0'))
            .ok_or_else(|| fail("invalid_params", "invalid processor library"))?;
        if !Path::new(path).is_file() { return Err(fail("missing_file", "processor dependency missing")); }
        bytes = bytes.checked_add(path.len() + 1).ok_or_else(|| fail("argument_limit", "processor classpath too large"))?;
        if bytes > 512 * 1024 { return Err(fail("argument_limit", "processor classpath too large")); }
        paths.push(path);
    }
    paths.push(jar);
    Ok(json!({"classpath":paths.join(separator)}))
}
