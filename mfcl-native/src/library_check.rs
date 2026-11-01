// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Batched SHA-1 inspection of existing installation libraries.
use crate::{fail, Error};
use serde_json::{json, Value};
use sha1::{Digest, Sha1};
use sha2::{Sha256, Sha512};
use md5::Md5;
use std::{collections::{HashMap, HashSet}, fs::{self, File, Metadata}, io::{self, BufReader, Read, Seek, SeekFrom, Write}, path::Path, time::{Duration, Instant, UNIX_EPOCH}};
use tempfile::Builder;
use std::sync::{atomic::{AtomicUsize, Ordering}, mpsc};

const MAX_ITEMS: usize = 20_000;
const MAX_FILE_BYTES: u64 = 1024 * 1024 * 1024;

/// Opens a candidate without blocking if a regular file is replaced with a FIFO.
pub(crate) fn open_candidate(path: &Path) -> io::Result<File> {
    let mut options = fs::OpenOptions::new(); options.read(true);
    #[cfg(unix)] { use std::os::unix::fs::OpenOptionsExt; options.custom_flags(libc::O_NONBLOCK); }
    options.open(path)
}

/// Java's attribute-preserving copy can include POSIX ACLs and other extended attributes.
/// Restrict native staging to Linux files that have no extended attributes at all.
#[cfg(target_os = "linux")]
pub(crate) fn has_no_extended_attributes(file: &File) -> bool {
    use std::os::fd::AsRawFd;
    unsafe { libc::flistxattr(file.as_raw_fd(), std::ptr::null_mut(), 0) == 0 }
}

/// Other platforms retain Java copy until equivalent attribute checks are available.
#[cfg(not(target_os = "linux"))]
pub(crate) fn has_no_extended_attributes(_file: &File) -> bool { false }

/// Copies and verifies a cache source into a caller-owned private staging directory.
pub(crate) fn stage_cache_copy(params: &Value) -> Result<Value, Error> {
    let source = params.get("source").and_then(Value::as_str).filter(|s| !s.is_empty() && !s.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "invalid cache source"))?;
    let stage = params.get("stage").and_then(Value::as_str).filter(|s| !s.is_empty() && !s.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "invalid cache staging path"))?;
    let algorithm = params.get("algorithm").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing cache algorithm"))?;
    let expected = params.get("digest").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing cache digest"))?;
    let valid = match algorithm { "SHA-1" => expected.len() == 40, "SHA-256" => expected.len() == 64,
        "SHA-512" => expected.len() == 128, "MD5" => expected.len() == 32, _ => false };
    if !valid || !expected.bytes().all(|b| b.is_ascii_hexdigit()) { return Err(fail("invalid_params", "invalid cache digest")); }
    let source = Path::new(source); let stage = Path::new(stage);
    let parent = stage.parent().filter(|p| p.is_dir()).ok_or_else(|| fail("invalid_params", "invalid cache staging directory"))?;
    let Some(signature) = fs::metadata(source).ok().and_then(|v| fingerprint(&v)) else { return Ok(json!({"unknown":true})); };
    let file = open_candidate(source).map_err(|_| fail("io_error", "cannot open cache source"))?;
    if fingerprint(&file.metadata().map_err(|_| fail("io_error", "cannot inspect cache source"))?) != Some(signature) {
        return Ok(json!({"unknown":true}));
    }
    if !has_no_extended_attributes(&file) { return Ok(json!({"unknown":true})); }
    let mut input = BufReader::new(file); let mut output = Builder::new().prefix(".mfcl-cache-copy-").tempfile_in(parent)
        .map_err(|_| fail("io_error", "cannot create cache staging file"))?;
    if !has_no_extended_attributes(output.as_file()) { return Ok(json!({"unknown":true})); }
    let mut sha1 = Sha1::new(); let mut sha256 = Sha256::new(); let mut sha512 = Sha512::new(); let mut md5 = Md5::new();
    let mut buffer = [0u8; 65536]; let mut total = 0u64;
    loop {
        let count = input.read(&mut buffer).map_err(|_| fail("io_error", "cannot read cache source"))?;
        if count == 0 { break; }
        total = total.checked_add(count as u64).ok_or_else(|| fail("io_error", "cache source size overflow"))?;
        if total > MAX_FILE_BYTES { return Ok(json!({"unknown":true})); }
        match algorithm {
            "SHA-1" => sha1.update(&buffer[..count]), "SHA-256" => sha256.update(&buffer[..count]),
            "SHA-512" => sha512.update(&buffer[..count]), "MD5" => md5.update(&buffer[..count]), _ => unreachable!(),
        }
        output.write_all(&buffer[..count]).map_err(|_| fail("io_error", "cannot write cache staging file"))?;
    }
    let actual = match algorithm {
        "SHA-1" => format!("{:x}", sha1.finalize()), "SHA-256" => format!("{:x}", sha256.finalize()),
        "SHA-512" => format!("{:x}", sha512.finalize()), "MD5" => format!("{:x}", md5.finalize()), _ => unreachable!(),
    };
    if !actual.eq_ignore_ascii_case(expected) || total != signature.size
        || fingerprint(&input.get_ref().metadata().map_err(|_| fail("io_error", "cannot inspect cache source"))?) != Some(signature)
        || fs::metadata(source).ok().and_then(|v| fingerprint(&v)) != Some(signature)
        || !has_no_extended_attributes(input.get_ref()) || !has_no_extended_attributes(output.as_file()) { return Ok(json!({"unknown":true})); }
    output.flush().map_err(|_| fail("io_error", "cannot flush cache staging file"))?;
    let staged = output.as_file().metadata().ok().and_then(|v| fingerprint(&v))
        .ok_or_else(|| fail("io_error", "cannot inspect cache staging file"))?;
    output.persist_noclobber(stage).map_err(|_| fail("io_error", "cannot publish cache staging file"))?;
    Ok(json!({"size":signature.size,"mtime_ns":signature.mtime_ns,"ctime_ns":signature.ctime_ns,
        "device":signature.device,"inode":signature.inode,"stage_inode":staged.inode}))
}

/// Computes both Modrinth export digests in one pass over a stable file.
pub(crate) fn modpack_hashes(params: &Value) -> Result<Value, Error> {
    let path = params.get("path").and_then(Value::as_str).filter(|p| !p.is_empty() && !p.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "invalid modpack file path"))?;
    let Some((sha1, sha512, fp)) = inspect_modpack(Path::new(path)) else { return Ok(json!({"unknown":true})); };
    Ok(json!({"sha1":sha1,"sha512":sha512,"size":fp.size,"mtime_ns":fp.mtime_ns,
        "ctime_ns":fp.ctime_ns,"device":fp.device,"inode":fp.inode}))
}

/// Rejects changes or unsupported identities so the caller can use the existing implementation.
fn inspect_modpack(path: &Path) -> Option<(String, String, Fingerprint)> {
    let signature = fingerprint(&fs::metadata(path).ok()?)?;
    let file = open_candidate(path).ok()?;
    if fingerprint(&file.metadata().ok()?)? != signature { return None; }
    let mut reader = BufReader::new(file); let mut sha1 = Sha1::new(); let mut sha512 = Sha512::new();
    let mut buffer = [0u8; 65536]; let mut total = 0u64;
    loop {
        let count = reader.read(&mut buffer).ok()?; if count == 0 { break; }
        total = total.checked_add(count as u64)?; if total > MAX_FILE_BYTES { return None; }
        sha1.update(&buffer[..count]); sha512.update(&buffer[..count]);
    }
    if total != signature.size || fingerprint(&reader.get_ref().metadata().ok()?)? != signature
        || fingerprint(&fs::metadata(path).ok()?)? != signature { return None; }
    Some((format!("{:x}", sha1.finalize()), format!("{:x}", sha512.finalize()), signature))
}

/// File identity and timestamps used to reject files changed after inspection.
#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) struct Fingerprint { pub(crate) size: u64, pub(crate) mtime_ns: i64, pub(crate) ctime_ns: i64, pub(crate) device: i64, pub(crate) inode: i64 }

/// Obtains a Unix file identity without resolving or altering the file.
#[cfg(unix)]
pub(crate) fn fingerprint(metadata: &Metadata) -> Option<Fingerprint> {
    use std::os::unix::fs::MetadataExt;
    if !metadata.is_file() || metadata.len() > MAX_FILE_BYTES { return None; }
    let mtime = metadata.modified().ok()?.duration_since(UNIX_EPOCH).ok()?.as_nanos();
    let ctime = (metadata.ctime() as i128).checked_mul(1_000_000_000)?.checked_add(metadata.ctime_nsec() as i128)?;
    Some(Fingerprint { size: metadata.len(), mtime_ns: i64::try_from(mtime).ok()?,
        ctime_ns: i64::try_from(ctime).ok()?, device: i64::try_from(metadata.dev()).ok()?, inode: i64::try_from(metadata.ino()).ok()? })
}

/// Other platforms retain Java verification until equivalent file identity is implemented.
#[cfg(not(unix))]
pub(crate) fn fingerprint(_metadata: &Metadata) -> Option<Fingerprint> { None }

/// Inspects a single path; read errors and unstable files are left to Java fallback.
fn inspect_sha1(path: &Path) -> Option<(String, Fingerprint)> {
    let before=fs::metadata(path).ok()?; let signature=fingerprint(&before)?;
    let file=open_candidate(path).ok()?;
    if fingerprint(&file.metadata().ok()?)? != signature { return None; }
    let mut reader=BufReader::new(file); let mut sha=Sha1::new(); let mut buffer=[0u8;65536]; let mut total=0u64;
    loop {
        let count=reader.read(&mut buffer).ok()?; if count==0 { break; }
        total=total.checked_add(count as u64)?; if total>MAX_FILE_BYTES { return None; }
        sha.update(&buffer[..count]);
    }
    if total != signature.size || fingerprint(&reader.get_ref().metadata().ok()?)? != signature
            || fingerprint(&fs::metadata(path).ok()?)? != signature { return None; }
    Some((format!("{:x}",sha.finalize()),signature))
}

/// Compares a stable SHA-1 observation with an expected value.
fn inspect(path: &Path, expected: &str) -> Option<(bool, Fingerprint)> {
    let (actual, signature) = inspect_sha1(path)?;
    Some((actual.eq_ignore_ascii_case(expected), signature))
}

/// Computes an unknown cache file's SHA-1 with a stable file identity.
pub(crate) fn hash_cache_sha1(params: &Value) -> Result<Value, Error> {
    let path = params.get("path").and_then(Value::as_str).filter(|s| !s.is_empty() && !s.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "invalid cache file path"))?;
    let Some((hash, fp)) = inspect_sha1(Path::new(path)) else { return Ok(json!({"unknown":true})); };
    Ok(json!({"sha1":hash,"size":fp.size,"mtime_ns":fp.mtime_ns,"ctime_ns":fp.ctime_ns,
        "device":fp.device,"inode":fp.inode}))
}

/// Checks SHA-256 or MD5 against a stable cache file identity.
pub(crate) fn check_cache_digest(params: &Value) -> Result<Value, Error> {
    let path = params.get("path").and_then(Value::as_str).filter(|s| !s.is_empty() && !s.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "invalid cache file path"))?;
    let algorithm = params.get("algorithm").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing cache algorithm"))?;
    let expected = params.get("digest").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing cache digest"))?;
    if !match algorithm { "SHA-256" => expected.len() == 64, "MD5" => expected.len() == 32, _ => false }
        || !expected.bytes().all(|b| b.is_ascii_hexdigit()) { return Err(fail("invalid_params", "invalid cache digest")); }
    let Some((matches, fp)) = inspect_cache_digest(Path::new(path), algorithm, expected) else { return Ok(json!({"unknown":true})); };
    Ok(json!({"matches":matches,"size":fp.size,"mtime_ns":fp.mtime_ns,"ctime_ns":fp.ctime_ns,
        "device":fp.device,"inode":fp.inode}))
}

/// Hashes a regular file and rejects changed identities before returning an observation.
fn inspect_cache_digest(path: &Path, algorithm: &str, expected: &str) -> Option<(bool, Fingerprint)> {
    let signature = fingerprint(&fs::metadata(path).ok()?)?;
    let file = open_candidate(path).ok()?;
    if fingerprint(&file.metadata().ok()?)? != signature { return None; }
    let mut reader = BufReader::new(file); let mut sha256 = Sha256::new(); let mut md5 = Md5::new();
    let mut buffer = [0u8; 65536]; let mut total = 0u64;
    loop {
        let count = reader.read(&mut buffer).ok()?; if count == 0 { break; }
        total = total.checked_add(count as u64)?; if total > MAX_FILE_BYTES { return None; }
        if algorithm == "SHA-256" { sha256.update(&buffer[..count]); } else { md5.update(&buffer[..count]); }
    }
    if total != signature.size || fingerprint(&reader.get_ref().metadata().ok()?)? != signature
        || fingerprint(&fs::metadata(path).ok()?)? != signature { return None; }
    let actual = if algorithm == "SHA-256" { format!("{:x}", sha256.finalize()) } else { format!("{:x}", md5.finalize()) };
    Some((actual.eq_ignore_ascii_case(expected), signature))
}

/// Captures a stable asset checksum observation without exposing platform-specific metadata types.
pub(crate) fn asset_observation(path: &Path, expected: &str) -> Option<Value> {
    let (matches, fp) = inspect(path, expected)?;
    Some(json!({"matches":matches,"size":fp.size,"mtime_ns":fp.mtime_ns,
        "ctime_ns":fp.ctime_ns,"device":fp.device,"inode":fp.inode}))
}
/// Inspects a bounded batch of paths; it performs no writes or ZIP/Forge checks.
pub(crate) fn check(params: &Value, progress: &mut dyn FnMut(u64, Option<u64>) -> std::io::Result<()>) -> Result<Value, Error> {
    let items=params.get("items").and_then(Value::as_array).ok_or_else(||fail("invalid_params","library items are required"))?;
    if items.len()>MAX_ITEMS { return Err(fail("library_limit","too many library items")); }
    let mut jobs = Vec::with_capacity(items.len());
    for item in items {
        let index=item.get("index").and_then(Value::as_u64).ok_or_else(||fail("invalid_params","invalid library index"))?;
        let path=item.get("path").and_then(Value::as_str).filter(|v|!v.is_empty()&&!v.contains('\0')).ok_or_else(||fail("invalid_params","invalid library path"))?;
        let hash=item.get("sha1").and_then(Value::as_str).filter(|h|h.len()==40&&h.bytes().all(|b|b.is_ascii_hexdigit())).ok_or_else(||fail("invalid_params","invalid library SHA-1"))?;
        jobs.push((index, path, hash));
    }
    let report = params.get("progress").and_then(Value::as_bool).unwrap_or(false);
    let worker_count = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1).min(4).min(jobs.len());
    let next = AtomicUsize::new(0);
    let results = std::thread::scope(|scope| -> Result<Vec<Value>, Error> {
        let (sender, receiver) = mpsc::channel();
        for _ in 0..worker_count {
            let sender = sender.clone(); let jobs = &jobs; let next = &next;
            scope.spawn(move || loop {
                let position = next.fetch_add(1, Ordering::Relaxed);
                let Some((index, path, hash)) = jobs.get(position) else { break; };
                let result = match inspect(Path::new(path), hash) {
                    Some((matches,fp)) => json!({"index":index,"matches":matches,"size":fp.size,"mtime_ns":fp.mtime_ns,
                        "ctime_ns":fp.ctime_ns,"device":fp.device,"inode":fp.inode}),
                    None => json!({"index":index,"unknown":true}),
                };
                if sender.send((position, result)).is_err() { break; }
            });
        }
        drop(sender);
        let mut ordered = vec![Value::Null; jobs.len()];
        let mut completed = 0usize; let mut last_progress = Instant::now(); let mut progress_failed = false;
        for (position, result) in receiver {
            ordered[position] = result; completed += 1;
            if report && !progress_failed && last_progress.elapsed() >= Duration::from_millis(200) {
                progress_failed = progress(completed as u64, Some(jobs.len() as u64)).is_err();
                if progress_failed { next.store(jobs.len(), Ordering::Relaxed); }
                last_progress = Instant::now();
            }
        }
        if progress_failed || (report && progress(completed as u64, Some(jobs.len() as u64)).is_err()) {
            return Err(fail("io_error", "could not report progress"));
        }
        if completed != jobs.len() { return Err(fail("io_error", "library inspection did not complete")); }
        Ok(ordered)
    })?;
    Ok(json!({"results":results}))
}

/// Validates direct and legacy embedded checksums, returning unknown for ambiguous JAR formats.
pub(crate) fn check_legacy(params: &Value) -> Result<Value, Error> {
    let path = params.get("path").and_then(Value::as_str).filter(|p| !p.is_empty() && !p.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "invalid library path"))?;
    let is_jar = params.get("jar").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "missing archive type"))?;
    let values = params.get("checksums").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing checksum list"))?;
    if values.is_empty() { return Err(fail("invalid_params", "empty checksum list")); }
    if values.len() > MAX_ITEMS { return Err(fail("library_limit", "too many checksums")); }
    if values.iter().any(|v| !v.is_string() && !v.is_null()) { return Err(fail("invalid_params", "invalid checksum list")); }
    let checksums: HashSet<&str> = values.iter().filter_map(Value::as_str).collect();
    let Some((valid, fp)) = inspect_legacy(Path::new(path), is_jar, &checksums) else { return Ok(json!({"unknown":true})); };
    Ok(json!({"valid":valid,"size":fp.size,"mtime_ns":fp.mtime_ns,"ctime_ns":fp.ctime_ns,"device":fp.device,"inode":fp.inode}))
}

/// Computes the exact file digest and, when needed, safe legacy JAR entry digests.
fn inspect_legacy(path: &Path, is_jar: bool, checksums: &HashSet<&str>) -> Option<(bool, Fingerprint)> {
    let signature = fingerprint(&fs::metadata(path).ok()?)?;
    // A library can be replaced after metadata inspection; do not block on a FIFO.
    let file = open_candidate(path).ok()?;
    if fingerprint(&file.metadata().ok()?)? != signature { return None; }
    let mut reader = BufReader::new(file); let mut hash = Sha1::new(); let mut buffer = [0u8; 65536]; let mut total = 0u64;
    loop {
        let n = reader.read(&mut buffer).ok()?; if n == 0 { break; }
        total = total.checked_add(n as u64)?; if total > MAX_FILE_BYTES { return None; }
        hash.update(&buffer[..n]);
    }
    if total != signature.size { return None; }
    let digest = format!("{:x}", hash.finalize());
    let valid = if checksums.contains(digest.as_str()) { true }
        else if !is_jar { false }
        else { reader.seek(SeekFrom::Start(0)).ok()?; inspect_legacy_jar(&mut reader, checksums)? };
    if fingerprint(&reader.get_ref().metadata().ok()?)? != signature || fingerprint(&fs::metadata(path).ok()?)? != signature { return None; }
    Some((valid, signature))
}

/// Streams simple unsigned JARs; unusual archives retain the Java verifier.
fn inspect_legacy_jar<R: Read>(reader: &mut R, checksums: &HashSet<&str>) -> Option<bool> {
    let mut files = HashMap::<String, String>::new(); let mut names = HashSet::<String>::new();
    let mut checksum_text: Option<String> = None; let mut count = 0usize; let mut name_bytes = 0usize; let mut total = 0u64;
    while let Some(mut entry) = zip::read::read_zipfile_from_stream(reader).ok()? {
        count += 1; if count > MAX_ITEMS || entry.size() > 128 * 1024 * 1024 { return None; }
        let name = std::str::from_utf8(entry.name_raw()).ok()?.to_owned();
        name_bytes = name_bytes.checked_add(name.len())?; if name_bytes > 8 * 1024 * 1024 { return None; }
        if !names.insert(name.clone()) { return None; }
        // JarInputStream has special manifest and signature behavior; preserve Java for those.
        if name.to_ascii_uppercase().starts_with("META-INF/") && !name.ends_with('/') { return None; }
        let mut hash = Sha1::new(); let mut buffer = [0u8; 65536]; let mut entry_size = 0u64; let mut checksum_bytes = Vec::new();
        loop {
            let n = entry.read(&mut buffer).ok()?; if n == 0 { break; }
            entry_size = entry_size.checked_add(n as u64)?; total = total.checked_add(n as u64)?;
            if entry_size > 128 * 1024 * 1024 || total > MAX_FILE_BYTES { return None; }
            hash.update(&buffer[..n]);
            if name == "checksums.sha1" {
                if checksum_bytes.len() + n > 4 * 1024 * 1024 { return None; }
                checksum_bytes.extend_from_slice(&buffer[..n]);
            }
        }
        if entry_size != entry.size() { return None; }
        if name == "checksums.sha1" { checksum_text = Some(String::from_utf8(checksum_bytes).ok()?); }
        if !name.ends_with('/') { files.insert(name, format!("{:x}", hash.finalize())); }
    }
    let Some(text) = checksum_text else { return Some(false); };
    if !checksums.contains(files.get("checksums.sha1")?.as_str()) { return Some(false); }
    for line in text.split('\n') {
        if line.trim_matches(|c: char| c <= '\u{20}').is_empty() { continue; }
        if let Some((expected, target)) = line.split_once(' ') {
            if files.get(target).map(String::as_str) != Some(expected) { return Some(false); }
        }
    }
    Some(true)
}
