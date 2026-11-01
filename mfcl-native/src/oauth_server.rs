// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Loopback OAuth callback listener with a private JSON-lines control channel.
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde::{Deserialize, Serialize};
use std::{io::{self, BufRead, BufReader, Read, Write}, net::{IpAddr, SocketAddr, TcpListener, TcpStream}, time::Duration};

const MAX_CONTROL: usize = 256 * 1024;
const MAX_HEADERS: usize = 32 * 1024;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Config { bind: IpAddr, ports: Vec<u16> }

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Page { status: u16, html: String }

#[derive(Serialize)]
struct Ready { event: &'static str, port: u16, state: String, verifier: String }

/// Reads a bounded control frame without exposing its contents in errors.
fn read_frame<R: BufRead>(input: &mut R) -> io::Result<Vec<u8>> {
    let mut line = Vec::new();
    let n = input.take((MAX_CONTROL + 1) as u64).read_until(b'\n', &mut line)?;
    if n == 0 || line.len() > MAX_CONTROL || !line.ends_with(b"\n") { return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid control frame")); }
    Ok(line)
}

/// Emits a control frame to the private parent stream.
fn write_frame<W: Write, T: Serialize>(output: &mut W, value: &T) -> io::Result<()> {
    serde_json::to_writer(&mut *output, value)?; output.write_all(b"\n")?; output.flush()
}

/// Sends a bounded response to one loopback client.
fn response(stream: &mut TcpStream, status: u16, body: &str) -> io::Result<()> {
    let status = match status { 200 => "200 OK", 404 => "404 Not Found", _ => "500 Internal Server Error" };
    write!(stream, "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len())?;
    stream.write_all(body.as_bytes())?; stream.flush()
}

/// Receives a bounded request header and returns method and raw request target.
fn request_line(stream: &mut TcpStream) -> io::Result<(String, String)> {
    stream.set_read_timeout(Some(Duration::from_secs(10)))?;
    stream.set_write_timeout(Some(Duration::from_secs(10)))?;
    let mut bytes = Vec::new(); let mut buffer = [0u8; 1024];
    while !bytes.windows(4).any(|w| w == b"\r\n\r\n") {
        let n = stream.read(&mut buffer)?;
        if n == 0 || bytes.len() + n > MAX_HEADERS { return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid HTTP request")); }
        bytes.extend_from_slice(&buffer[..n]);
    }
    let end = bytes.windows(2).position(|w| w == b"\r\n").ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid request line"))?;
    let line = std::str::from_utf8(&bytes[..end]).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid request line"))?;
    let fields: Vec<&str> = line.split(' ').collect();
    if fields.len() != 3 || !matches!(fields[2], "HTTP/1.0" | "HTTP/1.1") || !fields[1].is_ascii() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid request line"));
    }
    Ok((fields[0].to_owned(), fields[1].to_owned()))
}

/// Runs until a callback is delivered or the parent closes the process.
pub(crate) fn run() -> io::Result<()> {
    let stdin = io::stdin(); let mut input = BufReader::new(stdin.lock());
    let stdout = io::stdout(); let mut output = stdout.lock();
    let config: Config = serde_json::from_slice(&read_frame(&mut input)?).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid callback configuration"))?;
    if !config.bind.is_loopback() || config.ports.is_empty() || config.ports.len() > 5 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid callback configuration"));
    }
    let listener = config.ports.iter().find_map(|port| TcpListener::bind(SocketAddr::new(config.bind, *port)).ok())
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrInUse, "callback ports unavailable"))?;
    let port = listener.local_addr()?.port();
    let mut state = [0u8; 32]; let mut verifier = [0u8; 64];
    getrandom::getrandom(&mut state).map_err(|_| io::Error::new(io::ErrorKind::Other, "secure random unavailable"))?;
    getrandom::getrandom(&mut verifier).map_err(|_| io::Error::new(io::ErrorKind::Other, "secure random unavailable"))?;
    let state = URL_SAFE_NO_PAD.encode(state);
    write_frame(&mut output, &Ready { event:"ready", port, state:state.clone(), verifier:URL_SAFE_NO_PAD.encode(verifier) })?;
    // EOF on the private control stream means the launcher has exited. Read the
    // eventual page on a dedicated thread so an idle listener can notice EOF.
    drop(input);
    let (sender, receiver) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let stdin = io::stdin(); let mut input = BufReader::new(stdin.lock());
        let _ = sender.send(read_frame(&mut input));
    });
    listener.set_nonblocking(true)?;
    loop {
        match receiver.try_recv() {
            Ok(_) | Err(std::sync::mpsc::TryRecvError::Disconnected) => return Ok(()),
            Err(std::sync::mpsc::TryRecvError::Empty) => (),
        }
        let mut stream = match listener.accept() {
            Ok((stream, _)) => stream,
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => { std::thread::sleep(Duration::from_millis(25)); continue; },
            Err(error) => return Err(error),
        };
        let (method, target) = match request_line(&mut stream) { Ok(request) => request, Err(_) => { let _ = response(&mut stream, 500, ""); continue; } };
        let (path, query) = target.split_once('?').map_or((target.as_str(), None), |(path, query)| (path, Some(query)));
        if path != "/auth-response" || !matches!(method.as_str(), "GET" | "POST") {
            let _ = response(&mut stream, 404, ""); continue;
        }
        let parsed = query.and_then(|value| crate::oauth_callback::parse(&serde_json::json!({"query":value})).ok());
        let decision = parsed.as_ref().and_then(|fields| crate::oauth_callback::classify(fields, &state));
        write_frame(&mut output, &serde_json::json!({"event":"callback","query":query,"fields":parsed,"decision":decision}))?;
        let page_bytes = receiver.recv().map_err(|_| io::Error::new(io::ErrorKind::UnexpectedEof, "callback parent closed"))??;
        let page: Page = serde_json::from_slice(&page_bytes).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid callback page"))?;
        response(&mut stream, page.status, &page.html)?;
        write_frame(&mut output, &serde_json::json!({"event":"responded"}))?;
        return Ok(());
    }
}
