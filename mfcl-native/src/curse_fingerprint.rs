// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! CurseForge's whitespace-filtered MurmurHash2 fingerprint, with bounded memory.
use crate::{
    fail,
    library_check::{fingerprint, open_candidate},
    path_param, Error,
};
use serde_json::{json, Value};
use std::{
    fs,
    io::{Read, Seek, SeekFrom},
};

const BUFFER_SIZE: usize = 64 * 1024;
const MULTIPLIER: u32 = 0x5bd1e995;

/// Exactly these four byte values are ignored by the CurseForge algorithm.
fn retained(byte: u8) -> bool {
    !matches!(byte, 9 | 10 | 13 | 32)
}

/// Streaming MurmurHash2: tail bytes bridge arbitrary read boundaries.
struct Murmur2 {
    hash: u32,
    tail: u32,
    tail_len: u32,
}

impl Murmur2 {
    /// Seed-one initialization requires the final filtered length in advance.
    fn new(length: u64) -> Self {
        Self {
            hash: 1 ^ length as u32,
            tail: 0,
            tail_len: 0,
        }
    }

    /// Mixes arbitrary input chunks while retaining an incomplete four-byte tail.
    fn update(&mut self, bytes: &[u8]) {
        for &byte in bytes {
            self.tail |= (byte as u32) << (8 * self.tail_len);
            self.tail_len += 1;
            if self.tail_len == 4 {
                let mut block = self.tail.wrapping_mul(MULTIPLIER);
                block ^= block >> 24;
                self.hash = self.hash.wrapping_mul(MULTIPLIER) ^ block.wrapping_mul(MULTIPLIER);
                self.tail = 0;
                self.tail_len = 0;
            }
        }
    }

    /// Finalizes the tail and avalanche, returning an unsigned 32-bit lookup key.
    fn finish(mut self) -> u32 {
        if self.tail_len != 0 {
            self.hash = (self.hash ^ self.tail).wrapping_mul(MULTIPLIER);
        }
        self.hash ^= self.hash >> 13;
        self.hash = self.hash.wrapping_mul(MULTIPLIER);
        self.hash ^ (self.hash >> 15)
    }
}

/// Read twice because MurmurHash2 incorporates the filtered length BEFORE bytes.
/// Only stable Unix regular-file observations are accepted; other cases defer.
pub(crate) fn inspect(params: &Value) -> Result<Value, Error> {
    let path = path_param(params, "path")?;
    let unknown = || Ok(json!({"unknown":true}));
    let Some(before) = fs::metadata(&path).ok().and_then(|v| fingerprint(&v)) else {
        return unknown();
    };
    let mut file =
        open_candidate(&path).map_err(|_| fail("io_error", "cannot open fingerprint source"))?;
    if file.metadata().ok().and_then(|v| fingerprint(&v)) != Some(before) {
        return unknown();
    }
    let mut buffer = [0u8; BUFFER_SIZE];
    let mut length = 0u64;
    let mut total = 0u64;
    loop {
        let count = file
            .read(&mut buffer)
            .map_err(|_| fail("io_error", "cannot read fingerprint source"))?;
        if count == 0 {
            break;
        }
        total += count as u64;
        if total > before.size {
            return unknown();
        }
        length += buffer[..count].iter().filter(|&&b| retained(b)).count() as u64;
    }
    if total != before.size {
        return unknown();
    }
    file.seek(SeekFrom::Start(0))
        .map_err(|_| fail("io_error", "cannot rewind fingerprint source"))?;
    let mut hash = Murmur2::new(length);
    let mut filtered_total = 0u64;
    total = 0;
    loop {
        let count = file
            .read(&mut buffer)
            .map_err(|_| fail("io_error", "cannot read fingerprint source"))?;
        if count == 0 {
            break;
        }
        total += count as u64;
        if total > before.size {
            return unknown();
        }
        let mut kept = 0;
        for index in 0..count {
            if retained(buffer[index]) {
                buffer[kept] = buffer[index];
                kept += 1;
            }
        }
        filtered_total += kept as u64;
        hash.update(&buffer[..kept]);
    }
    if total != before.size
        || filtered_total != length
        || file.metadata().ok().and_then(|v| fingerprint(&v)) != Some(before)
        || fs::metadata(&path).ok().and_then(|v| fingerprint(&v)) != Some(before)
    {
        return unknown();
    }
    Ok(
        json!({"fingerprint":hash.finish(), "size":before.size, "mtime_ns":before.mtime_ns,
        "ctime_ns":before.ctime_ns,"device":before.device,"inode":before.inode}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn streaming_boundaries_preserve_unsigned_murmur2() {
        assert_eq!(Murmur2::new(0).finish(), 1540447798);
        for length in 0..130 {
            let bytes: Vec<_> = (0..length).map(|v| v as u8).collect();
            for size in 1..9 {
                let mut parts = Murmur2::new(bytes.len() as u64);
                for chunk in bytes.chunks(size) {
                    parts.update(chunk);
                }
                let mut whole = Murmur2::new(bytes.len() as u64);
                whole.update(&bytes);
                assert_eq!(parts.finish(), whole.finish());
            }
        }
    }
}
