// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Bounded direct range downloads into a private temporary file.
use std::{io::{Read, Seek, SeekFrom, Write}, path::Path, sync::{atomic::{AtomicBool, AtomicU64, Ordering}, mpsc}, time::Duration};
use tempfile::{Builder, NamedTempFile};
use url::Url;

/// A failed range attempt either requests a serial retry or reports a broken progress channel.
pub(crate) enum Failure { RetrySerial, Progress }

/// Downloads fixed ranges concurrently; callers must verify the final content hash before publishing.
pub(crate) fn download(agent: &ureq::Agent, url: &Url, parent: &Path, length: u64, chunks: usize,
    etag: Option<&str>, report: bool, progress: &mut dyn FnMut(u64, Option<u64>) -> std::io::Result<()>) -> Result<NamedTempFile, Failure> {
    let file = Builder::new().prefix(".mfcl-range-").tempfile_in(parent).map_err(|_| Failure::RetrySerial)?;
    file.as_file().set_len(length).map_err(|_| Failure::RetrySerial)?;
    let bytes = AtomicU64::new(0); let cancelled = AtomicBool::new(false);
    let finished = std::thread::scope(|scope| -> Result<(), Failure> {
        let (sender, receiver) = mpsc::channel();
        for index in 0..chunks {
            let start = length * index as u64 / chunks as u64;
            let end = length * (index as u64 + 1) / chunks as u64 - 1;
            let output = file.reopen().map_err(|_| Failure::RetrySerial)?;
            let sender = sender.clone(); let bytes = &bytes; let cancelled = &cancelled;
            scope.spawn(move || {
                let result = fetch_range(agent, url, output, start, end, length, etag, bytes, cancelled);
                if result.is_err() { cancelled.store(true, Ordering::Relaxed); }
                let _ = sender.send(result);
            });
        }
        drop(sender);
        let mut remaining = chunks; let mut failed = false; let mut progress_failed = false; let mut previous = 0;
        while remaining > 0 {
            match receiver.recv_timeout(Duration::from_millis(200)) {
                Ok(result) => { remaining -= 1; if result.is_err() { failed = true; } },
                Err(mpsc::RecvTimeoutError::Timeout) => {},
                Err(mpsc::RecvTimeoutError::Disconnected) => { failed = true; break; },
            }
            let current = bytes.load(Ordering::Relaxed);
            if report && current > previous && !progress_failed {
                if progress(current, Some(length)).is_err() { progress_failed = true; cancelled.store(true, Ordering::Relaxed); }
                previous = current;
            }
        }
        if progress_failed { Err(Failure::Progress) } else if failed { Err(Failure::RetrySerial) } else { Ok(()) }
    });
    finished?;
    if bytes.load(Ordering::Relaxed) != length { return Err(Failure::RetrySerial); }
    Ok(file)
}

/// Streams one range into its non-overlapping section of the staging file.
fn fetch_range(agent: &ureq::Agent, url: &Url, mut output: std::fs::File, start: u64, end: u64, total: u64,
    etag: Option<&str>, bytes: &AtomicU64, cancelled: &AtomicBool) -> Result<(), ()> {
    let mut request = agent.get(url.as_str()).set("Accept-Encoding", "identity").set("Range", &format!("bytes={start}-{end}"));
    if let Some(etag) = etag { request = request.set("If-Range", etag); }
    let response = request.call().map_err(|_| ())?;
    if response.status() != 206 { return Err(()); }
    let expected = format!("bytes {start}-{end}/{total}");
    if !response.header("Content-Range").is_some_and(|value| value.eq_ignore_ascii_case(&expected)) { return Err(()); }
    if response.header("Content-Encoding").is_some_and(|value| !value.eq_ignore_ascii_case("identity")) { return Err(()); }
    let length = end - start + 1;
    if response.header("Content-Length").is_some_and(|v| v.parse::<u64>().ok() != Some(length)) { return Err(()); }
    output.seek(SeekFrom::Start(start)).map_err(|_| ())?;
    let mut input = response.into_reader(); let mut buffer = [0u8; 65536]; let mut written = 0u64;
    loop {
        if cancelled.load(Ordering::Relaxed) { return Err(()); }
        let read = input.read(&mut buffer).map_err(|_| ())?;
        if read == 0 { break; }
        written += read as u64;
        if written > length { return Err(()); }
        output.write_all(&buffer[..read]).map_err(|_| ())?;
        bytes.fetch_add(read as u64, Ordering::Relaxed);
    }
    if written != length { return Err(()); }
    output.flush().map_err(|_| ())?;
    Ok(())
}
