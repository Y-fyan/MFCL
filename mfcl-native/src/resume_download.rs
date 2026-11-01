// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Restart-safe, checksum-addressed range checkpoints.
use crate::download_digest::{Actual, Expected, Hasher};
use std::{fs::{self, File, OpenOptions}, io::{self, Read, Write}, path::{Path, PathBuf},
    sync::{atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering}, mpsc}, time::Duration};
use tempfile::Builder;
use url::Url;

/// A checkpoint attempt may be unavailable, require a serial retry, or lose its progress channel.
pub(crate) enum Failure { Unavailable, RetrySerial, Progress }

/// The verified digests and the number of checkpoint chunks reused.
pub(crate) struct Completed { pub(crate) reused: usize, pub(crate) actual: Actual }

/// Downloads fixed-size verified-at-publication chunks and retains completed chunks across restarts.
pub(crate) fn download(agent: &ureq::Agent, url: &Url, staging_parent: &Path, resume_dir: &Path, target: &Path,
    expected: &Expected, length: u64, workers: usize, etag: Option<&str>, report: bool,
    progress: &mut dyn FnMut(u64, Option<u64>) -> io::Result<()>) -> Result<Completed, Failure> {
    // Different digest lengths keep algorithms in separate checkpoint namespaces.
    let key = expected.key().ok_or(Failure::Unavailable)?;
    ensure_directory(resume_dir).map_err(|_| Failure::Unavailable)?;
    let lock_path = resume_dir.join(format!("{key}.lock"));
    let lock = open_no_follow(&lock_path, true, true).map_err(|_| Failure::Unavailable)?;
    lock.try_lock().map_err(|_| Failure::Unavailable)?;
    // The lock remains open for the entire operation. Never unlink it: another process may have it open.
    let _lock = lock;
    clean_temporary(resume_dir, key).map_err(|_| Failure::Unavailable)?;
    let chunk_size = (length.div_ceil(256)).max(512 * 1024);
    let count = length.div_ceil(chunk_size) as usize;
    if count == 0 || count > 256 { return Err(Failure::Unavailable); }
    let mut pending = Vec::new(); let mut reused = 0usize; let mut initial = 0u64;
    for index in 0..count {
        let start = index as u64 * chunk_size; let end = (start + chunk_size).min(length) - 1;
        let path = part_path(resume_dir, key, length, index);
        match fs::symlink_metadata(&path) {
            Ok(meta) if meta.file_type().is_file() && meta.len() == end - start + 1 => { reused += 1; initial += meta.len(); },
            Ok(meta) if meta.file_type().is_file() => { fs::remove_file(&path).map_err(|_| Failure::Unavailable)?; pending.push((index, start, end)); },
            Ok(_) => return Err(Failure::Unavailable),
            Err(e) if e.kind() == io::ErrorKind::NotFound => pending.push((index, start, end)),
            Err(_) => return Err(Failure::Unavailable),
        }
    }
    if report && initial > 0 { progress(initial, Some(length)).map_err(|_| Failure::Progress)?; }
    let downloaded = AtomicU64::new(initial); let next = AtomicUsize::new(0); let cancelled = AtomicBool::new(false);
    let completed = std::thread::scope(|scope| -> Result<(), Failure> {
        let (sender, receiver) = mpsc::channel();
        let active = workers.min(pending.len());
        for _ in 0..active {
            let sender = sender.clone(); let downloaded = &downloaded; let next = &next; let cancelled = &cancelled; let pending = &pending;
            scope.spawn(move || {
                loop {
                    if cancelled.load(Ordering::Relaxed) { break; }
                    let slot = next.fetch_add(1, Ordering::Relaxed);
                    if slot >= pending.len() { break; }
                    let (index, start, end) = pending[slot];
                    if fetch_part(agent, url, resume_dir, key, length, index, start, end, etag, downloaded, cancelled).is_err() {
                        cancelled.store(true, Ordering::Relaxed); break;
                    }
                }
                let _ = sender.send(());
            });
        }
        drop(sender); let mut remaining = active; let mut previous = initial; let mut progress_failed = false;
        while remaining > 0 {
            match receiver.recv_timeout(Duration::from_millis(200)) {
                Ok(()) => remaining -= 1,
                Err(mpsc::RecvTimeoutError::Timeout) => {},
                Err(mpsc::RecvTimeoutError::Disconnected) => return Err(Failure::RetrySerial),
            }
            let current = downloaded.load(Ordering::Relaxed);
            if report && current > previous && !progress_failed {
                if progress(current, Some(length)).is_err() { progress_failed = true; cancelled.store(true, Ordering::Relaxed); }
                previous = current;
            }
        }
        if progress_failed { Err(Failure::Progress) } else if cancelled.load(Ordering::Relaxed) { Err(Failure::RetrySerial) } else { Ok(()) }
    });
    completed?;
    let mut output = Builder::new().prefix(".mfcl-resumed-").tempfile_in(staging_parent).map_err(|_| Failure::Unavailable)?;
    let mut digest = Hasher::new(expected);
    let mut buffer = [0u8; 65536]; let mut copied = 0u64;
    for index in 0..count {
        let path = part_path(resume_dir, key, length, index);
        let mut input = open_no_follow(&path, false, false).map_err(|_| Failure::RetrySerial)?;
        loop {
            let n = input.read(&mut buffer).map_err(|_| Failure::RetrySerial)?;
            if n == 0 { break; }
            copied += n as u64; if copied > length { return Err(Failure::RetrySerial); }
            digest.update(&buffer[..n]);
            output.write_all(&buffer[..n]).map_err(|_| Failure::Unavailable)?;
        }
    }
    let actual = digest.finish();
    if copied != length || !expected.matches(&actual) {
        remove_parts(resume_dir, key, length, count); return Err(Failure::RetrySerial);
    }
    output.flush().map_err(|_| Failure::Unavailable)?;
    output.persist_noclobber(target).map_err(|_| Failure::Unavailable)?;
    remove_parts(resume_dir, key, length, count);
    Ok(Completed { reused, actual })
}

/// Opens a regular file without following a final-component symbolic link.
fn open_no_follow(path: &Path, write: bool, create: bool) -> io::Result<File> {
    let mut options = OpenOptions::new(); options.read(true).write(write).create(create);
    #[cfg(unix)] { use std::os::unix::fs::OpenOptionsExt; options.custom_flags(libc::O_NOFOLLOW); }
    if let Ok(meta) = fs::symlink_metadata(path) { if !meta.file_type().is_file() { return Err(io::ErrorKind::InvalidInput.into()); } }
    let file = options.open(path)?;
    if !file.metadata()?.is_file() { return Err(io::ErrorKind::InvalidInput.into()); }
    Ok(file)
}

/// Creates a private checkpoint directory and refuses an existing symbolic link.
fn ensure_directory(path: &Path) -> io::Result<()> {
    let existed = fs::symlink_metadata(path).is_ok();
    if !existed { fs::create_dir_all(path)?; }
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.file_type().is_dir() { return Err(io::ErrorKind::InvalidInput.into()); }
    #[cfg(unix)] if !existed { use std::os::unix::fs::PermissionsExt; fs::set_permissions(path, fs::Permissions::from_mode(0o700))?; }
    Ok(())
}

/// Returns the deterministic path for a completed chunk.
fn part_path(root: &Path, key: &str, length: u64, index: usize) -> PathBuf {
    root.join(format!("{key}.{length}.{index}.part"))
}

/// Removes only stale temporary files in this checksum's private namespace while holding its lock.
fn clean_temporary(root: &Path, key: &str) -> io::Result<()> {
    let prefix = format!(".mfcl-part-{key}-");
    for entry in fs::read_dir(root)? {
        let entry = entry?; let name = entry.file_name();
        if name.to_string_lossy().starts_with(&prefix) && entry.file_type()?.is_file() { fs::remove_file(entry.path())?; }
    }
    Ok(())
}

/// Removes completed chunks after successful publication or detection of corrupt checkpoints.
fn remove_parts(root: &Path, key: &str, length: u64, count: usize) {
    for index in 0..count { let _ = fs::remove_file(part_path(root, key, length, index)); }
}

/// Best-effort cleanup after another transfer path completes; never interferes with a live owner.
pub(crate) fn cleanup_completed(root: &Path, key: &str, length: u64) {
    if !fs::symlink_metadata(root).is_ok_and(|meta| meta.file_type().is_dir()) { return; }
    let lock = match open_no_follow(&root.join(format!("{key}.lock")), true, true) { Ok(lock) => lock, Err(_) => return };
    if lock.try_lock().is_err() { return; }
    let chunk_size = length.div_ceil(256).max(512 * 1024);
    let count = length.div_ceil(chunk_size) as usize;
    if count <= 256 { remove_parts(root, key, length, count); }
    let _ = clean_temporary(root, key);
}

/// Fetches one complete range to a private file and publishes the checkpoint atomically.
fn fetch_part(agent: &ureq::Agent, url: &Url, root: &Path, key: &str, total: u64, index: usize,
    start: u64, end: u64, etag: Option<&str>, downloaded: &AtomicU64, cancelled: &AtomicBool) -> Result<(), ()> {
    let mut request = agent.get(url.as_str()).set("Accept-Encoding", "identity").set("Range", &format!("bytes={start}-{end}"));
    if let Some(etag) = etag { request = request.set("If-Range", etag); }
    let response = request.call().map_err(|_| ())?;
    if response.status() != 206 { return Err(()); }
    let expected = format!("bytes {start}-{end}/{total}");
    if !response.header("Content-Range").is_some_and(|v| v.eq_ignore_ascii_case(&expected)) { return Err(()); }
    if response.header("Content-Encoding").is_some_and(|v| !v.eq_ignore_ascii_case("identity")) { return Err(()); }
    let length = end - start + 1;
    if response.header("Content-Length").is_some_and(|v| v.parse::<u64>().ok() != Some(length)) { return Err(()); }
    let mut temp = Builder::new().prefix(&format!(".mfcl-part-{key}-")).tempfile_in(root).map_err(|_| ())?;
    let mut input = response.into_reader(); let mut buffer = [0u8; 65536]; let mut written = 0u64;
    loop {
        if cancelled.load(Ordering::Relaxed) { return Err(()); }
        let n = input.read(&mut buffer).map_err(|_| ())?; if n == 0 { break; }
        written += n as u64; if written > length { return Err(()); }
        temp.write_all(&buffer[..n]).map_err(|_| ())?; downloaded.fetch_add(n as u64, Ordering::Relaxed);
    }
    if written != length { return Err(()); }
    temp.flush().map_err(|_| ())?;
    temp.persist_noclobber(part_path(root, key, total, index)).map_err(|_| ())?;
    Ok(())
}
