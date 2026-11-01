// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Dedicated Microsoft device-code polling process with a private control stream.
use crate::microsoft;
use serde_json::{json, Value};
use std::{io::{self, BufRead, BufReader, Read, Write}, time::{Duration, Instant}};

const FRAME_LIMIT: u64 = 1024 * 1024;

/// Reads a bounded JSON control frame without echoing its contents.
fn read_frame<R: BufRead>(input: &mut R) -> io::Result<Value> {
    let mut line = Vec::new();
    if input.take(FRAME_LIMIT + 1).read_until(b'\n', &mut line)? == 0 || line.len() as u64 > FRAME_LIMIT || !line.ends_with(b"\n") {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid device control frame"));
    }
    serde_json::from_slice(&line).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid device control frame"))
}

/// Writes one private control frame.
fn send<W: Write>(output: &mut W, value: &Value) -> io::Result<()> {
    serde_json::to_writer(&mut *output, value)?; output.write_all(b"\n")?; output.flush()
}

/// Requests a device code, waits for UI readiness and polls until completion or cancellation.
pub(crate) fn run(exchange_session: bool) -> io::Result<()> {
    let stdin = io::stdin(); let mut input = BufReader::new(stdin.lock());
    let stdout = io::stdout(); let mut output = stdout.lock();
    let config = read_frame(&mut input)?;
    let client = config.get("client_id").and_then(Value::as_str).filter(|s| !s.is_empty() && s.len() <= 65_536)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid client"))?.to_owned();
    let device = match microsoft::device_begin(&json!({"client_id":client})) {
        Ok(value) => value,
        Err(error) => { send(&mut output, &json!({"event":"error","code":error.code}))?; return Ok(()); },
    };
    let text = |key: &str| device.get(key).and_then(Value::as_str).filter(|s| !s.is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid device response"));
    let device_code = text("device_code")?.to_owned(); let user_code = text("user_code")?;
    let verification_uri = text("verification_uri")?;
    let expires = device.get("expires_in").and_then(Value::as_u64).filter(|v| *v > 0).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid device expiration"))?.min(900);
    let mut interval = device.get("interval").and_then(Value::as_u64).filter(|v| *v > 0).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid polling interval"))?.min(900);
    let deadline = Instant::now() + Duration::from_secs(expires);
    send(&mut output, &json!({"event":"device","user_code":user_code,"verification_uri":verification_uri}))?;
    let ready = read_frame(&mut input)?;
    if ready.get("event").and_then(Value::as_str) != Some("continue") { return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid device continuation")); }
    drop(input);
    let (sender, receiver) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let stdin = io::stdin(); let mut input = BufReader::new(stdin.lock());
        loop {
            match read_frame(&mut input) {
                Ok(frame) => if sender.send(frame).is_err() { return; },
                // The parent is gone; stop even if an authentication request is in flight.
                Err(_) => std::process::exit(0),
            }
        }
    });
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() { send(&mut output, &json!({"event":"error","code":"expired_token"}))?; return Ok(()); }
        match receiver.recv_timeout(remaining.min(Duration::from_secs(interval))) {
            Ok(_) | Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => return Err(io::Error::new(io::ErrorKind::InvalidInput, "unexpected device control frame")),
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => (),
        }
        if Instant::now() >= deadline { send(&mut output, &json!({"event":"error","code":"expired_token"}))?; return Ok(()); }
        match microsoft::device_poll(&json!({"client_id":client,"device_code":device_code})) {
            Ok(tokens) => {
                if !exchange_session { send(&mut output, &json!({"event":"tokens","tokens":tokens}))?; return Ok(()); }
                // The UI can close the device-code prompt before the Xbox/Minecraft exchange begins.
                send(&mut output, &json!({"event":"authorized"}))?;
                let exchange = receiver.recv().map_err(|_| io::Error::new(io::ErrorKind::UnexpectedEof, "device parent closed"))?;
                if exchange.get("event").and_then(Value::as_str) != Some("exchange") {
                    return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid exchange continuation"));
                }
                let access = tokens.get("access_token").and_then(Value::as_str).filter(|s| !s.is_empty())
                    .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid token response"))?;
                let refresh = tokens.get("refresh_token").and_then(Value::as_str).filter(|s| !s.is_empty())
                    .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid token response"))?;
                match microsoft::exchange(&json!({"access_token":access})) {
                    Ok(mut session) => {
                        session.as_object_mut().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid session response"))?
                            .insert("refresh_token".to_owned(), json!(refresh));
                        send(&mut output, &json!({"event":"session","session":session}))?;
                    },
                    Err(error) => send(&mut output, &json!({"event":"error","code":error.code,"xbox":error.xbox}))?,
                }
                return Ok(());
            },
            Err(error) if error.code == "authorization_pending" => (),
            Err(error) if error.code == "slow_down" => interval = (interval + 5).min(900),
            Err(error) => { send(&mut output, &json!({"event":"error","code":error.code}))?; return Ok(()); },
        }
    }
}
