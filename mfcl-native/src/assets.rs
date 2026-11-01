// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Parses Mojang asset indexes into a bounded installation work list.
use crate::{fail, path_param, Error};
use serde_json::{json, Value};
use std::{collections::HashMap, fs, io::{self, Read}, path::PathBuf,
    sync::{atomic::{AtomicUsize, Ordering}, mpsc}, time::{Duration, Instant}};

const MAX_INDEX_BYTES: u64 = 64 * 1024 * 1024;
const MAX_ASSETS: usize = 50_000;

/// Parses an asset index without changing files or deduplicating logical entries.
pub(crate) fn plan(params: &Value) -> Result<Value, Error> {
    let path = path_param(params, "index_path")?;
    let file = crate::library_check::open_candidate(&path).map_err(|_| fail("io_error", "could not open asset index"))?;
    if !file.metadata().map_err(|_| fail("io_error", "could not inspect asset index"))?.is_file() {
        return Err(fail("invalid_params", "asset index must be a regular file"));
    }
    let mut bytes = Vec::new();
    file.take(MAX_INDEX_BYTES + 1).read_to_end(&mut bytes).map_err(|_| fail("io_error", "could not read asset index"))?;
    if bytes.len() as u64 > MAX_INDEX_BYTES { return Err(fail("index_limit", "asset index exceeds size limit")); }
    let data = crate::json_unique::parse(&bytes).map_err(|_| fail("invalid_index", "invalid or ambiguous asset index JSON"))?;
    let entries = data.get("objects").and_then(Value::as_object).ok_or_else(|| fail("invalid_index", "asset index objects are missing"))?;
    if entries.len() > MAX_ASSETS { return Err(fail("index_limit", "asset index exceeds object limit")); }
    let mut objects = Vec::with_capacity(entries.len());
    for item in entries.values() {
        let hash = item.get("hash").and_then(Value::as_str)
            .filter(|h| h.len() == 40 && h.bytes().all(|c| c.is_ascii_hexdigit()))
            .ok_or_else(|| fail("invalid_index", "asset object has invalid SHA-1"))?;
        let size = match item.get("size") {
            None | Some(Value::Null) => 0,
            Some(v) => v.as_u64().filter(|v| *v <= i64::MAX as u64).ok_or_else(|| fail("invalid_index", "asset object has invalid size"))?,
        };
        objects.push(json!({"hash":hash, "size":size}));
    }
    Ok(json!({"objects":objects}))
}

/// Plans asset installation and captures stable checksums without modifying the repository.
pub(crate) fn plan_install(params: &Value, progress: &mut dyn FnMut(u64, Option<u64>) -> io::Result<()>) -> Result<Value, Error> {
    let root = params.get("asset_directory").and_then(Value::as_str).filter(|p| !p.is_empty() && !p.contains('\0'))
        .ok_or_else(|| fail("invalid_params", "asset directory is required"))?;
    let root = PathBuf::from(root);
    if !root.is_absolute() { return Err(fail("invalid_params", "asset directory must be absolute")); }
    let integrity = params.get("integrity").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "integrity setting is required"))?;
    let parsed = plan(params)?;
    let objects = parsed.get("objects").and_then(Value::as_array).ok_or_else(|| fail("internal_error", "invalid asset plan"))?;
    let mut positions = HashMap::<&str, usize>::new();
    let mut hashes = Vec::<&str>::new(); let mut counts = Vec::<usize>::new(); let mut object_indices = Vec::with_capacity(objects.len());
    for object in objects {
        let hash = object.get("hash").and_then(Value::as_str).ok_or_else(|| fail("internal_error", "invalid asset hash"))?;
        let index = *positions.entry(hash).or_insert_with(|| { let index = hashes.len(); hashes.push(hash); counts.push(0); index });
        counts[index] += 1; object_indices.push(index);
    }
    let next = AtomicUsize::new(0);
    let worker_count = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1).min(4).min(hashes.len());
    let states = std::thread::scope(|scope| -> Result<Vec<Value>, Error> {
        let (sender, receiver) = mpsc::channel();
        for _ in 0..worker_count {
            let sender = sender.clone(); let hashes = &hashes; let next = &next; let root = &root;
            scope.spawn(move || loop {
                let index = next.fetch_add(1, Ordering::Relaxed);
                let Some(hash) = hashes.get(index) else { break; };
                let path = root.join("objects").join(&hash[..2]).join(hash);
                let state = match fs::metadata(&path) {
                    Ok(metadata) if !metadata.is_file() => json!({"state":"missing"}),
                    Err(error) if error.kind() == io::ErrorKind::NotFound => json!({"state":"missing"}),
                    Ok(_) if integrity => match crate::library_check::asset_observation(&path, hash) {
                        Some(observation) => json!({"state":"observed","observation":observation}),
                        None => json!({"state":"unknown"}),
                    },
                    _ => json!({"state":"unknown"}),
                };
                if sender.send((index, state)).is_err() { break; }
            });
        }
        drop(sender);
        let mut states = vec![Value::Null; hashes.len()]; let mut completed = 0usize; let mut inspected = 0usize;
        let mut last_progress = Instant::now(); let mut progress_failed = false;
        for (index, state) in receiver {
            states[index] = state; inspected += 1; completed += counts[index];
            if !progress_failed && last_progress.elapsed() >= Duration::from_millis(200) {
                progress_failed = progress(completed as u64, Some(objects.len() as u64)).is_err();
                if progress_failed { next.store(hashes.len(), Ordering::Relaxed); }
                last_progress = Instant::now();
            }
        }
        if progress_failed || progress(completed as u64, Some(objects.len() as u64)).is_err() {
            return Err(fail("io_error", "could not report progress"));
        }
        if inspected != hashes.len() { return Err(fail("io_error", "asset inspection did not complete")); }
        Ok(states)
    })?;
    let output: Vec<_> = objects.iter().zip(object_indices).map(|(object, index)|
        json!({"hash":object["hash"],"size":object["size"],"inspection":states[index]})).collect();
    Ok(json!({"objects":output}))
}
