// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Unix-only process replacement for launching a game with inherited streams.
use std::io;

/// Reads the launch request from standard input and replaces this process with the requested program.
#[cfg(unix)]
pub(crate) fn run(length_prefixed: bool) -> io::Result<()> {
    use serde::Deserialize;
    use std::io::Read;
    use std::os::fd::FromRawFd;
    use std::os::unix::process::CommandExt;
    use std::process::Command;

    #[derive(Deserialize)]
    #[serde(deny_unknown_fields)]
    struct Launch { command: Vec<String> }

    // Read without buffering past the newline: subsequent stdin belongs to the launched game.
    let mut stdin = unsafe { std::fs::File::from_raw_fd(0) };
    let mut request = Vec::new();
    if length_prefixed {
        let mut length = [0u8; 4]; stdin.read_exact(&mut length)?;
        let length = u32::from_be_bytes(length) as usize;
        if length == 0 || length > 1024 * 1024 { return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid launch request size")); }
        request.resize(length, 0); stdin.read_exact(&mut request)?;
    } else {
        let mut byte = [0u8; 1];
        loop {
            if stdin.read(&mut byte)? == 0 { return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "incomplete launch request")); }
            if byte[0] == b'\n' { break; }
            if request.len() >= 1024 * 1024 { return Err(io::Error::new(io::ErrorKind::InvalidData, "launch request exceeds limit")); }
            request.push(byte[0]);
        }
    }
    let launch: Launch = serde_json::from_slice(&request)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid launch request"))?;
    let (program, args) = launch.command.split_first()
        .filter(|(program, _)| !program.is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "launch executable is missing"))?;
    let error = Command::new(program).args(args).exec();
    Err(error)
}

/// Reports that process replacement is unavailable on non-Unix targets.
#[cfg(not(unix))]
pub(crate) fn run(_length_prefixed: bool) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "native process replacement is unsupported"))
}
