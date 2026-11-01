// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
use crate::{fail, path_param, Error};
use serde_json::{json, Value};
use sha1::{Digest, Sha1};
use std::{collections::{HashMap, HashSet}, fs::{self, OpenOptions}, io::{self, Read}, path::{Component, Path}};
use tempfile::Builder;
use zip::ZipArchive;

const MAX_ENTRIES: usize = 20_000;
const MAX_FILE_BYTES: u64 = 1024 * 1024 * 1024;
const MAX_TOTAL_BYTES: u64 = 2 * 1024 * 1024 * 1024;

/// Decodes Java's modified UTF-8 into Unicode, retaining fallback for unpaired surrogates.
fn modified_utf8(bytes: &[u8]) -> Result<String, Error> {
    let mut units = Vec::<u16>::with_capacity(bytes.len()); let mut i = 0usize;
    while i < bytes.len() {
        let first = bytes[i];
        let (value, length) = if first <= 0x7f { (first as u16, 1) }
        else if first & 0xe0 == 0xc0 {
            let second = *bytes.get(i + 1).ok_or_else(|| fail("unsupported_metadata", "truncated class string"))?;
            if second & 0xc0 != 0x80 { return Err(fail("unsupported_metadata", "invalid class string")); }
            ((((first & 0x1f) as u16) << 6) | (second & 0x3f) as u16, 2)
        } else if first & 0xf0 == 0xe0 {
            let second = *bytes.get(i + 1).ok_or_else(|| fail("unsupported_metadata", "truncated class string"))?;
            let third = *bytes.get(i + 2).ok_or_else(|| fail("unsupported_metadata", "truncated class string"))?;
            if second & 0xc0 != 0x80 || third & 0xc0 != 0x80 { return Err(fail("unsupported_metadata", "invalid class string")); }
            ((((first & 0x0f) as u16) << 12) | (((second & 0x3f) as u16) << 6) | (third & 0x3f) as u16, 3)
        } else { return Err(fail("unsupported_metadata", "unsupported class encoding")); };
        units.push(value); i += length;
    }
    String::from_utf16(&units).map_err(|_| fail("unsupported_metadata", "unpaired class surrogate"))
}

/// Reads the UTF-8 constant-pool sequence used by the legacy OptiFine detector.
fn class_utf8_constants(bytes: &[u8]) -> Result<Vec<String>, Error> {
    fn take<'a>(bytes: &'a [u8], position: &mut usize, length: usize) -> Result<&'a [u8], Error> {
        let end = position.checked_add(length).ok_or_else(|| fail("invalid_metadata", "invalid class size"))?;
        let slice = bytes.get(*position..end).ok_or_else(|| fail("invalid_metadata", "truncated class file"))?;
        *position = end; Ok(slice)
    }
    let mut pos = 0;
    if take(bytes, &mut pos, 4)? != [0xca, 0xfe, 0xba, 0xbe] { return Err(fail("invalid_metadata", "invalid class magic")); }
    take(bytes, &mut pos, 4)?;
    let count = u16::from_be_bytes(take(bytes, &mut pos, 2)?.try_into().unwrap()) as usize;
    if count == 0 { return Err(fail("invalid_metadata", "invalid constant pool")); }
    let mut index = 1usize; let mut strings = Vec::new();
    while index < count {
        let tag = take(bytes, &mut pos, 1)?[0];
        match tag {
            1 => {
                let length = u16::from_be_bytes(take(bytes, &mut pos, 2)?.try_into().unwrap()) as usize;
                let data = take(bytes, &mut pos, length)?;
                strings.push(modified_utf8(data)?);
            },
            3 | 4 | 9 | 10 | 11 | 12 | 18 => { take(bytes, &mut pos, 4)?; },
            5 | 6 => {
                take(bytes, &mut pos, 8)?; index += 1;
                if index >= count { return Err(fail("invalid_metadata", "invalid wide constant")); }
            },
            7 | 8 | 16 => { take(bytes, &mut pos, 2)?; },
            15 => { take(bytes, &mut pos, 3)?; },
            _ => return Err(fail("unsupported_metadata", "unsupported class constant")),
        }
        index += 1;
    }
    Ok(strings)
}

/// Detects OptiFine versions from a bounded class inside a stable installer.
pub(crate) fn optifine_identity(params: &Value) -> Result<Value, Error> {
    let path = path_param(params, "archive")?;
    let mut file = crate::library_check::open_candidate(&path).map_err(|_| fail("io_error", "cannot open installer"))?;
    let identity = crate::library_check::fingerprint(&file.metadata().map_err(|_| fail("io_error", "cannot inspect installer"))?)
        .ok_or_else(|| fail("unsupported_metadata", "unsupported installer identity"))?;
    if fs::metadata(&path).ok().and_then(|m| crate::library_check::fingerprint(&m)) != Some(identity) { return Err(fail("unsupported_metadata", "installer changed")); }
    let count = crate::processor_command::original_entry_count(&mut file)?;
    if count > MAX_ENTRIES { return Err(fail("archive_limit", "too many installer entries")); }
    let mut archive = ZipArchive::new(file).map_err(|_| fail("invalid_archive", "invalid installer archive"))?;
    if archive.len() != count { return Err(fail("unsupported_metadata", "ambiguous installer entries")); }
    let mut class = None;
    for name in ["Config.class", "net/optifine/Config.class", "notch/net/optifine/Config.class"] {
        match archive.by_name(name) {
            Ok(mut entry) => {
                if entry.is_dir() || entry.size() > 8 * 1024 * 1024 { return Err(fail("unsupported_metadata", "unsupported config class")); }
                let mut bytes = Vec::new(); (&mut entry).take(8 * 1024 * 1024 + 1).read_to_end(&mut bytes)
                    .map_err(|_| fail("invalid_archive", "cannot read config class"))?;
                if bytes.len() as u64 != entry.size() || bytes.len() > 8 * 1024 * 1024 { return Err(fail("invalid_archive", "invalid config class size")); }
                class = Some(bytes); break;
            },
            Err(zip::result::ZipError::FileNotFound) => (),
            Err(_) => return Err(fail("invalid_archive", "cannot inspect config class")),
        }
    }
    let constants = class_utf8_constants(&class.ok_or_else(|| fail("unsupported_metadata", "missing config class"))?)?;
    let after = |name: &str| -> Result<&str, Error> {
        let index = constants.iter().position(|value| value == name).map(|i| i + 1).unwrap_or(0);
        constants.get(index).map(String::as_str).ok_or_else(|| fail("unsupported_metadata", "missing version constant"))
    };
    let minecraft = after("MC_VERSION")?; let edition = after("OF_EDITION")?; let release = after("OF_RELEASE")?;
    let file = archive.into_inner();
    if file.metadata().ok().and_then(|m| crate::library_check::fingerprint(&m)) != Some(identity)
            || fs::metadata(&path).ok().and_then(|m| crate::library_check::fingerprint(&m)) != Some(identity) { return Err(fail("unsupported_metadata", "installer changed")); }
    Ok(json!({"minecraft":minecraft,"edition":edition,"release":release,"size":identity.size,"mtime_ns":identity.mtime_ns,
        "ctime_ns":identity.ctime_ns,"device":identity.device,"inode":identity.inode}))
}

/// Inspects the ordinary OptiFine installer entries without extracting them.
pub(crate) fn optifine_plan(params: &Value) -> Result<Value, Error> {
    let path = path_param(params, "archive")?;
    let mut file = crate::library_check::open_candidate(&path).map_err(|_| fail("io_error", "cannot open installer"))?;
    let identity = crate::library_check::fingerprint(&file.metadata().map_err(|_| fail("io_error", "cannot inspect installer"))?)
        .ok_or_else(|| fail("unsupported_metadata", "unsupported installer identity"))?;
    if fs::metadata(&path).ok().and_then(|m| crate::library_check::fingerprint(&m)) != Some(identity) {
        return Err(fail("unsupported_metadata", "installer changed"));
    }
    let count = crate::processor_command::original_entry_count(&mut file)?;
    if count > MAX_ENTRIES { return Err(fail("archive_limit", "too many installer entries")); }
    let mut archive = ZipArchive::new(file).map_err(|_| fail("invalid_archive", "invalid installer archive"))?;
    if archive.len() != count { return Err(fail("unsupported_metadata", "ambiguous installer entries")); }
    let exists = |archive: &mut ZipArchive<std::fs::File>, name: &str| -> Result<bool, Error> {
        match archive.by_name(name) {
            Ok(entry) if !entry.is_dir() => Ok(true),
            Ok(_) => Err(fail("unsupported_metadata", "unsupported installer entry")),
            Err(zip::result::ZipError::FileNotFound) => Ok(false),
            Err(_) => Err(fail("invalid_archive", "cannot inspect installer entry")),
        }
    };
    let read_text = |archive: &mut ZipArchive<std::fs::File>, name: &str| -> Result<Option<String>, Error> {
        let mut entry = match archive.by_name(name) {
            Ok(entry) => entry, Err(zip::result::ZipError::FileNotFound) => return Ok(None),
            Err(_) => return Err(fail("invalid_archive", "cannot inspect installer entry")),
        };
        if entry.is_dir() || entry.size() > 16384 { return Err(fail("unsupported_metadata", "invalid installer text")); }
        let mut bytes = Vec::new(); (&mut entry).take(16385).read_to_end(&mut bytes)
            .map_err(|_| fail("invalid_archive", "cannot read installer text"))?;
        if bytes.len() as u64 != entry.size() || bytes.len() > 16384 { return Err(fail("invalid_archive", "invalid installer text size")); }
        let value = String::from_utf8(bytes).map_err(|_| fail("unsupported_metadata", "invalid installer text encoding"))?;
        Ok(Some(value.trim_matches(|c: char| c <= '\u{20}').to_owned()))
    };
    let patcher = exists(&mut archive, "optifine/Patcher.class")?;
    let wrapper2 = exists(&mut archive, "launchwrapper-2.0.jar")?;
    let wrapper_version = read_text(&mut archive, "launchwrapper-of.txt")?;
    let wrapper_of = match &wrapper_version {
        Some(version) if !version.is_empty() && version.is_ascii()
            && version.bytes().all(|c| c.is_ascii_alphanumeric() || b"._-".contains(&c)) =>
            exists(&mut archive, &format!("launchwrapper-of-{version}.jar"))?,
        Some(_) => return Err(fail("unsupported_metadata", "unsupported wrapper version")),
        None => false,
    };
    let build = read_text(&mut archive, "buildof.txt")?;
    let file = archive.into_inner();
    if file.metadata().ok().and_then(|m| crate::library_check::fingerprint(&m)) != Some(identity)
            || fs::metadata(&path).ok().and_then(|m| crate::library_check::fingerprint(&m)) != Some(identity) {
        return Err(fail("unsupported_metadata", "installer changed"));
    }
    Ok(json!({"patcher":patcher,"wrapper2":wrapper2,"wrapper_version":wrapper_version,"wrapper_of":wrapper_of,"build":build,
        "size":identity.size,"mtime_ns":identity.mtime_ns,"ctime_ns":identity.ctime_ns,"device":identity.device,"inode":identity.inode}))
}

/// Reads canonical Forge-family installer profiles from one stable, bounded archive.
pub(crate) fn forge_profiles(params: &Value) -> Result<Value, Error> {
    let path = path_param(params, "archive")?;
    let mut file = crate::library_check::open_candidate(&path).map_err(|_| fail("io_error", "cannot open installer"))?;
    let identity = crate::library_check::fingerprint(&file.metadata().map_err(|_| fail("io_error", "cannot inspect installer"))?)
        .ok_or_else(|| fail("unsupported_metadata", "unsupported installer identity"))?;
    if fs::metadata(&path).ok().and_then(|m| crate::library_check::fingerprint(&m)) != Some(identity) {
        return Err(fail("unsupported_metadata", "installer changed"));
    }
    let count = crate::processor_command::original_entry_count(&mut file)?;
    if count > MAX_ENTRIES { return Err(fail("archive_limit", "too many installer entries")); }
    let mut archive = ZipArchive::new(file).map_err(|_| fail("invalid_archive", "invalid installer archive"))?;
    if archive.len() != count { return Err(fail("unsupported_metadata", "ambiguous installer entries")); }
    let read_entry = |archive: &mut ZipArchive<std::fs::File>, name: &str| -> Result<String, Error> {
        let mut entry = archive.by_name(name).map_err(|_| fail("unsupported_metadata", "installer profile is missing"))?;
        if entry.is_dir() || entry.size() > 768 * 1024 { return Err(fail("unsupported_metadata", "installer profile is too large")); }
        let mut bytes = Vec::new(); (&mut entry).take(768 * 1024 + 1).read_to_end(&mut bytes)
            .map_err(|_| fail("invalid_archive", "cannot read installer profile"))?;
        if bytes.len() as u64 != entry.size() || bytes.len() > 768 * 1024 { return Err(fail("invalid_archive", "invalid installer profile size")); }
        String::from_utf8(bytes).map_err(|_| fail("unsupported_metadata", "unsupported installer profile encoding"))
    };
    let profile = read_entry(&mut archive, "install_profile.json")?;
    let parsed = crate::json_unique::parse(profile.as_bytes()).map_err(|_| fail("unsupported_metadata", "ambiguous installer profile"))?;
    let (version_path, version, projection) = if let Some(version_path) = parsed.get("json").and_then(Value::as_str) {
        let relative = version_path.strip_prefix('/').unwrap_or(version_path);
        if relative.is_empty() || !relative.is_ascii() || relative.contains('\\') || relative.split('/').any(|p| p.is_empty() || p == "." || p == "..") {
            return Err(fail("unsupported_metadata", "unsupported installer version path"));
        }
        (version_path.to_owned(), read_entry(&mut archive, relative)?, crate::forge_profile::project(&profile, &parsed).ok())
    } else {
        if parsed.get("spec").is_some() || !parsed.get("install").is_some_and(Value::is_object)
                || !parsed.get("versionInfo").is_some_and(Value::is_object) {
            return Err(fail("unsupported_metadata", "missing installer version path"));
        }
        let fields: HashMap<String, Box<serde_json::value::RawValue>> = serde_json::from_str(&profile)
            .map_err(|_| fail("unsupported_metadata", "invalid legacy installer profile"))?;
        let version = fields.get("versionInfo").ok_or_else(|| fail("unsupported_metadata", "missing legacy version"))?.get().to_owned();
        let projection = parsed.get("install").and_then(|install| crate::forge_profile::legacy_install(install).ok());
        ("versionInfo".to_owned(), version, projection)
    };
    let version_projection = crate::game_manifest::project(&json!({"metadata":version,"loader_mode":true})).ok();
    let file = archive.into_inner();
    if file.metadata().ok().and_then(|m| crate::library_check::fingerprint(&m)) != Some(identity)
            || fs::metadata(&path).ok().and_then(|m| crate::library_check::fingerprint(&m)) != Some(identity) {
        return Err(fail("unsupported_metadata", "installer changed"));
    }
    Ok(json!({"profile":profile,"profile_projection_v1":projection,"version":version,"version_projection_v1":version_projection,"version_path":version_path,"size":identity.size,
        "mtime_ns":identity.mtime_ns,"ctime_ns":identity.ctime_ns,"device":identity.device,"inode":identity.inode}))
}

/// Stages only requested Forge installer entries, leaving repository publication to the caller.
pub(crate) fn stage_forge_entries(params: &Value) -> Result<Value, Error> {
    stage_forge_selected(params, true)
}

/// Stages ordinary installer data entries selected by processor configuration.
pub(crate) fn stage_forge_data(params: &Value) -> Result<Value, Error> {
    stage_forge_selected(params, false)
}

fn stage_forge_selected(params: &Value, maven_only: bool) -> Result<Value, Error> {
    let path = path_param(params, "archive")?; let parent = path_param(params, "staging_parent")?;
    if !parent.is_absolute() || !parent.is_dir() { return Err(fail("invalid_params", "invalid staging parent")); }
    let entries = params.get("entries").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing installer entries"))?;
    if entries.len() > MAX_ENTRIES { return Err(fail("archive_limit", "too many installer entries")); }
    let mut names = Vec::with_capacity(entries.len());
    for entry in entries {
        let name = entry.as_str().filter(|s| s.len() <= 4096 && (!maven_only || s.starts_with("maven/")) && s.is_ascii()
            && !s.contains('\\') && !s.contains('\0') && !s.split('/').any(|v| v.is_empty() || v == "." || v == ".."))
            .ok_or_else(|| fail("unsupported_metadata", "unsupported installer entry"))?;
        names.push(name);
    }
    let mut file = crate::library_check::open_candidate(&path).map_err(|_| fail("io_error", "cannot open installer"))?;
    let identity = crate::library_check::fingerprint(&file.metadata().map_err(|_| fail("io_error", "cannot inspect installer"))?)
        .ok_or_else(|| fail("unsupported_metadata", "unsupported installer identity"))?;
    if fs::metadata(&path).ok().and_then(|m| crate::library_check::fingerprint(&m)) != Some(identity) { return Err(fail("unsupported_metadata", "installer changed")); }
    let count = crate::processor_command::original_entry_count(&mut file)?;
    if count > MAX_ENTRIES { return Err(fail("archive_limit", "too many installer entries")); }
    let mut archive = ZipArchive::new(file).map_err(|_| fail("invalid_archive", "invalid installer archive"))?;
    if archive.len() != count { return Err(fail("unsupported_metadata", "ambiguous installer entries")); }
    let stage = Builder::new().prefix(".mfcl-forge-entries-").tempdir_in(parent).map_err(|_| fail("io_error", "cannot create staging directory"))?;
    let mut results = Vec::with_capacity(names.len()); let mut total = 0u64;
    for (index, name) in names.iter().enumerate() {
        let mut entry = match archive.by_name(name) {
            Ok(entry) => entry,
            Err(zip::result::ZipError::FileNotFound) => { results.push(json!({"index":index,"present":false})); continue; },
            Err(_) => return Err(fail("invalid_archive", "cannot read installer entry")),
        };
        if entry.is_dir() || entry.unix_mode().is_some_and(|mode| { let kind = mode & 0o170000; kind != 0 && kind != 0o100000 }) {
            return Err(fail("unsupported_metadata", "unsupported installer entry type"));
        }
        let size = entry.size(); total = total.checked_add(size).ok_or_else(|| fail("archive_limit", "installer size overflow"))?;
        if size > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES { return Err(fail("archive_limit", "installer entries too large")); }
        let target = stage.path().join(index.to_string());
        let mut out = OpenOptions::new().write(true).create_new(true).open(&target).map_err(|_| fail("io_error", "cannot stage installer entry"))?;
        let written = io::copy(&mut (&mut entry).take(size + 1), &mut out).map_err(|_| fail("invalid_archive", "cannot extract installer entry"))?;
        if written != size { return Err(fail("invalid_archive", "installer entry size mismatch")); }
        let fp = crate::library_check::fingerprint(&out.metadata().map_err(|_| fail("io_error", "cannot inspect staged entry"))?)
            .ok_or_else(|| fail("unsupported_metadata", "unsupported staged entry identity"))?;
        results.push(json!({"index":index,"present":true,"size":fp.size,"mtime_ns":fp.mtime_ns,
            "ctime_ns":fp.ctime_ns,"device":fp.device,"inode":fp.inode}));
    }
    let file = archive.into_inner();
    if file.metadata().ok().and_then(|m| crate::library_check::fingerprint(&m)) != Some(identity)
        || fs::metadata(&path).ok().and_then(|m| crate::library_check::fingerprint(&m)) != Some(identity) {
        return Err(fail("unsupported_metadata", "installer changed"));
    }
    Ok(json!({"stage":stage.keep(),"entries":results,"size":identity.size,"mtime_ns":identity.mtime_ns,
        "ctime_ns":identity.ctime_ns,"device":identity.device,"inode":identity.inode}))
}

/// Hashes selected, uniquely named ZIP entries without extracting them to disk.
pub(crate) fn hash_entries(params: &Value) -> Result<Value, Error> {
    let archive_path = path_param(params, "archive")?;
    let requested = params.get("entries").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "entry names are required"))?;
    if requested.len() > MAX_ENTRIES { return Err(fail("archive_limit", "too many requested entries")); }
    let mut names = Vec::with_capacity(requested.len()); let mut wanted = HashSet::new();
    for value in requested {
        let name = value.as_str().filter(|s| !s.is_empty() && !s.contains('\0'))
            .ok_or_else(|| fail("invalid_params", "invalid entry name"))?;
        names.push(name); wanted.insert(name);
    }
    let file = crate::library_check::open_candidate(&archive_path).map_err(|_| fail("io_error", "cannot open archive"))?;
    if !file.metadata().map_err(|_| fail("io_error", "cannot inspect archive"))?.is_file() {
        return Err(fail("invalid_params", "archive must be a regular file"));
    }
    let mut archive = ZipArchive::new(file).map_err(|_| fail("invalid_archive", "invalid ZIP archive"))?;
    if archive.len() > MAX_ENTRIES { return Err(fail("archive_limit", "too many archive entries")); }
    let mut positions = HashMap::new();
    for index in 0..archive.len() {
        let entry = archive.by_index(index).map_err(|_| fail("invalid_archive", "unable to read ZIP entry"))?;
        if wanted.contains(entry.name()) {
            if entry.is_dir() || positions.insert(entry.name().to_owned(), index).is_some() {
                return Err(fail("invalid_archive", "ambiguous requested ZIP entry"));
            }
        }
    }
    if positions.len() != wanted.len() { return Err(fail("invalid_archive", "requested ZIP entry is missing")); }
    let mut hashes = HashMap::new(); let mut total = 0u64;
    for (name, index) in positions {
        let mut entry = archive.by_index(index).map_err(|_| fail("invalid_archive", "unable to read ZIP entry"))?;
        let size = entry.size(); total = total.checked_add(size).ok_or_else(|| fail("archive_limit", "archive size overflow"))?;
        if size > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES { return Err(fail("archive_limit", "requested entries exceed limits")); }
        let mut hash = Sha1::new(); let mut buffer = [0u8; 65536]; let mut read = 0u64;
        loop {
            let count = entry.read(&mut buffer).map_err(|_| fail("invalid_archive", "cannot read requested entry"))?;
            if count == 0 { break; }
            read = read.checked_add(count as u64).ok_or_else(|| fail("archive_limit", "entry size overflow"))?;
            if read > size { return Err(fail("invalid_archive", "entry size mismatch")); }
            hash.update(&buffer[..count]);
        }
        if read != size { return Err(fail("invalid_archive", "entry size mismatch")); }
        hashes.insert(name, format!("{:x}", hash.finalize()));
    }
    let result: Vec<&str> = names.iter().map(|name| hashes.get(*name).unwrap().as_str()).collect();
    Ok(json!({"sha1":result}))
}

/// Extracts a ZIP into a new staging directory, never into an existing game directory.
pub(crate) fn extract_zip(params: &Value) -> Result<Value, Error> {
    extract(params, None)
}

/// Extracts only one ordinary relative ZIP subtree to a private staging directory.
pub(crate) fn extract_zip_prefix(params: &Value) -> Result<Value, Error> {
    let prefix = params.get("prefix").and_then(Value::as_str).filter(|s| !s.is_empty() && s.ends_with('/') && !s.contains('\\'))
        .ok_or_else(|| fail("invalid_params", "a relative directory prefix is required"))?;
    let directory = Path::new(prefix);
    if directory.is_absolute() || directory.components().any(|c| !matches!(c, Component::Normal(_)))
            || prefix.contains("//") {
        return Err(fail("invalid_params", "invalid directory prefix"));
    }
    extract(params, Some(prefix))
}

/// Extracts the whole archive or a selected subtree without publishing into the game directory.
fn extract(params: &Value, prefix: Option<&str>) -> Result<Value, Error> {
    let archive_path = path_param(params, "archive")?;
    let staging_parent = path_param(params, "staging_parent")?;
    if !staging_parent.is_dir() { return Err(fail("invalid_params", "staging parent must exist")); }
    let file = crate::library_check::open_candidate(&archive_path).map_err(|e| fail("io_error", e.to_string()))?;
    if !file.metadata().map_err(|e| fail("io_error", e.to_string()))?.is_file() {
        return Err(fail("invalid_params", "archive must be a regular file"));
    }
    let mut archive = ZipArchive::new(file).map_err(|_| fail("invalid_archive", "invalid ZIP archive"))?;
    if archive.len() > MAX_ENTRIES { return Err(fail("archive_limit", "too many archive entries")); }
    let stage = Builder::new().prefix(".mfcl-extract-").tempdir_in(staging_parent)
        .map_err(|e| fail("io_error", e.to_string()))?;
    let mut total = 0u64; let mut selected = 0usize;
    for index in 0..archive.len() {
        let mut entry = archive.by_index(index).map_err(|_| fail("invalid_archive", "unable to read ZIP entry"))?;
        if prefix.is_some_and(|p| !entry.name().starts_with(p)) { continue; }
        selected += 1;
        let path = entry.enclosed_name().ok_or_else(|| fail("unsafe_archive", "archive entry has an unsafe path"))?;
        if path.as_os_str().is_empty() || path.components().any(|c| !matches!(c, Component::Normal(_))) {
            return Err(fail("unsafe_archive", "archive entry has an unsafe path"));
        }
        if let Some(mode) = entry.unix_mode() {
            let kind = mode & 0o170000;
            if kind != 0 && kind != 0o100000 && kind != 0o040000 {
                return Err(fail("unsafe_archive", "links and special files are not supported"));
            }
        }
        let out = stage.path().join(path);
        if entry.is_dir() {
            fs::create_dir_all(&out).map_err(|_| fail("invalid_archive", "conflicting directory entry"))?;
            continue;
        }
        let size = entry.size();
        total = total.checked_add(size).ok_or_else(|| fail("archive_limit", "archive size overflow"))?;
        if size > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES { return Err(fail("archive_limit", "archive exceeds extraction limits")); }
        let parent = out.parent().ok_or_else(|| fail("unsafe_archive", "invalid file path"))?;
        fs::create_dir_all(parent).map_err(|_| fail("invalid_archive", "conflicting file path"))?;
        let mut output = OpenOptions::new().write(true).create_new(true).open(out)
            .map_err(|_| fail("invalid_archive", "duplicate or conflicting archive entry"))?;
        let actual = io::copy(&mut (&mut entry).take(size + 1), &mut output)
            .map_err(|_| fail("invalid_archive", "unable to extract or verify ZIP entry"))?;
        if actual != size { return Err(fail("invalid_archive", "ZIP entry size mismatch")); }
    }
    let path = stage.keep();
    Ok(json!({"staging_directory":path.to_string_lossy(),"entries":selected,"uncompressed_bytes":total}))
}
