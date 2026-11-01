// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
use crate::{download_digest::{Expected, Hasher}, fail, path_param, range_download, resume_download, Error};
use serde_json::{json, Value};
use std::{io::{Read, Write}, net::IpAddr, path::Path, sync::OnceLock, time::{Duration, Instant}};
use tempfile::Builder;
use url::{Host, Url};
use std::{collections::HashSet, sync::{atomic::{AtomicUsize, Ordering}, mpsc}};

const MAX_DOWNLOAD: u64 = 1024 * 1024 * 1024;

/// Downloads independent, SHA-1 verified files into a caller-owned staging directory.
/// Each item retains its own ordered source fallback and the returned results retain input order.
pub(crate) fn batch(params: &Value, progress: &mut dyn FnMut(u64, Option<u64>) -> std::io::Result<()>) -> Result<Value, Error> {
    let root = path_param(params, "stage_directory")?;
    if !root.is_absolute() || !root.is_dir() { return Err(fail("invalid_params", "invalid staging directory")); }
    let items = params.get("items").and_then(Value::as_array).filter(|v| v.len() <= 512)
        .ok_or_else(|| fail("invalid_params", "invalid batch items"))?;
    let allow_http = params.get("allow_http_loopback").and_then(Value::as_bool).unwrap_or(false);
    let mut jobs = Vec::with_capacity(items.len()); let mut indices = HashSet::new(); let mut names = HashSet::new();
    for item in items {
        let index = item.get("index").and_then(Value::as_u64).ok_or_else(|| fail("invalid_params", "invalid batch index"))?;
        let name = item.get("name").and_then(Value::as_str).filter(|s| !s.is_empty() && *s != "." && *s != ".."
            && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_'))
            .ok_or_else(|| fail("invalid_params", "invalid staged filename"))?;
        if !indices.insert(index) || !names.insert(name) { return Err(fail("invalid_params", "duplicate batch item")); }
        let sha1 = item.get("sha1").and_then(Value::as_str).filter(|h| h.len() == 40 && h.bytes().all(|c| c.is_ascii_hexdigit()))
            .ok_or_else(|| fail("invalid_params", "invalid batch SHA-1"))?;
        let max = item.get("max_bytes").and_then(Value::as_u64).filter(|n| *n > 0 && *n <= MAX_DOWNLOAD)
            .ok_or_else(|| fail("invalid_params", "invalid batch size"))?;
        let urls = item.get("urls").and_then(Value::as_array).filter(|u| !u.is_empty() && u.len() <= 8)
            .ok_or_else(|| fail("invalid_params", "invalid batch sources"))?;
        let mut sources = Vec::with_capacity(urls.len());
        for source in urls {
            let text = source.as_str().ok_or_else(|| fail("invalid_params", "invalid batch source"))?;
            let parsed = Url::parse(text).map_err(|_| fail("invalid_params", "invalid batch source"))?;
            if !allowed_url(&parsed, allow_http) { return Err(fail("invalid_params", "unsupported batch source")); }
            sources.push(text.to_owned());
        }
        let target = root.join(name);
        if target.exists() { return Err(fail("invalid_params", "batch destination already exists")); }
        jobs.push((index, target, sha1.to_owned(), max, sources));
    }
    let next = AtomicUsize::new(0);
    let concurrency = match params.get("concurrency") {
        None => 4,
        Some(value) => value.as_u64().filter(|n| (1..=4).contains(n)).ok_or_else(|| fail("invalid_params", "invalid batch concurrency"))? as usize,
    };
    let count = std::thread::available_parallelism().map(|v| v.get()).unwrap_or(1).min(concurrency).min(jobs.len());
    let results = std::thread::scope(|scope| -> Result<Vec<Value>, Error> {
        let (sender, receiver) = mpsc::channel();
        for _ in 0..count {
            let jobs = &jobs; let next = &next; let sender = sender.clone();
            scope.spawn(move || loop {
                let position = next.fetch_add(1, Ordering::Relaxed);
                let Some((index, target, sha1, max, urls)) = jobs.get(position) else { break; };
                let mut result = json!({"index":index,"ok":false,"error":"network_error"});
                for url in urls {
                    let request = json!({"url":url,"destination":target,"sha1":sha1,"max_bytes":max,
                        "allow_http_loopback":allow_http,"follow_redirects":false});
                    match download(&request, &mut |_, _| Ok(())) {
                        Ok(value) => { result = json!({"index":index,"ok":true,"bytes_written":value["bytes_written"]}); break; },
                        Err(error) => result = json!({"index":index,"ok":false,"error":error.code}),
                    }
                }
                if sender.send((position, result)).is_err() { break; }
            });
        }
        drop(sender);
        let mut results = vec![Value::Null; jobs.len()]; let mut completed = 0usize; let mut failed = false;
        for (position, result) in receiver {
            results[position] = result; completed += 1;
            if !failed && progress(completed as u64, Some(jobs.len() as u64)).is_err() {
                failed = true; next.store(jobs.len(), Ordering::Relaxed);
            }
        }
        if failed { return Err(fail("io_error", "could not report progress")); }
        if completed != jobs.len() { return Err(fail("io_error", "batch download did not complete")); }
        if jobs.is_empty() { progress(0, Some(0)).map_err(|_| fail("io_error", "could not report progress"))?; }
        Ok(results)
    })?;
    Ok(json!({"results":results}))
}

/// Restricts network requests and every redirect to HTTPS, except explicit loopback tests.
fn allowed_url(url: &Url, allow_http_loopback: bool) -> bool {
    let loopback_http = allow_http_loopback && url.scheme() == "http"
        && match url.host() { Some(Host::Ipv4(ip)) => ip.is_loopback(), Some(Host::Ipv6(ip)) => IpAddr::V6(ip).is_loopback(), _ => false };
    (url.scheme() == "https" || loopback_http) && url.username().is_empty()
        && url.password().is_none() && url.fragment().is_none()
}

/// Downloads a single file, verifying the supplied supported digests.
/// The destination must not exist and its parent must already be a directory.
pub(crate) fn download(params: &Value, progress: &mut dyn FnMut(u64, Option<u64>) -> std::io::Result<()>) -> Result<Value, Error> {
    let raw_url = params.get("url").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "url is required"))?;
    let mut url = Url::parse(raw_url).map_err(|_| fail("invalid_params", "invalid URL"))?;
    let allow_http_loopback = params.get("allow_http_loopback").and_then(Value::as_bool).unwrap_or(false);
    if !allowed_url(&url, allow_http_loopback) {
        return Err(fail("invalid_params", "URL must use HTTPS without embedded credentials or fragments"));
    }
    let expected = Expected::parse(params)?;
    let max_bytes = params.get("max_bytes").and_then(Value::as_u64).filter(|v| *v > 0 && *v <= MAX_DOWNLOAD)
        .ok_or_else(|| fail("invalid_params", "max_bytes must be between 1 and 1073741824"))?;
    let target = path_param(params, "destination")?;
    let parent = target.parent().filter(|p| !p.as_os_str().is_empty()).unwrap_or_else(|| Path::new("."));
    if target.file_name().is_none() || !parent.is_dir() { return Err(fail("invalid_params", "destination parent must exist")); }
    let follow_redirects = params.get("follow_redirects").and_then(Value::as_bool).unwrap_or(true);
    let metadata = params.get("metadata").and_then(Value::as_bool).unwrap_or(false);
    if expected.has_non_sha1() && metadata { return Err(fail("invalid_params", "non-SHA-1 digests are unsupported for metadata requests")); }
    let chunks = match params.get("parallel_chunks") {
        None | Some(Value::Null) => 0,
        Some(value) => value.as_u64().filter(|n| *n == 0 || (2..=4).contains(n))
            .ok_or_else(|| fail("invalid_params", "parallel_chunks must be zero or between two and four"))? as usize,
    };
    let resume_dir = match params.get("resume_directory") {
        None | Some(Value::Null) => None,
        Some(Value::String(value)) if !value.is_empty() => Some(std::path::PathBuf::from(value)),
        _ => return Err(fail("invalid_params", "invalid resume directory")),
    };
    let etag = match params.get("if_none_match") {
        None | Some(Value::Null) => None,
        Some(Value::String(value)) if metadata && value.len() <= 4096 && !value.chars().any(|c| c.is_control()) => Some(value.as_str()),
        _ => return Err(fail("invalid_params", "invalid conditional request header")),
    };
    // The Java adapter selects only direct routes. Do not let environment proxy settings
    // silently override that decision if ureq's proxy-from-env feature becomes enabled.
    static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
    let agent = AGENT.get_or_init(|| ureq::AgentBuilder::new().try_proxy_from_env(false).redirects(0).timeout(Duration::from_secs(60)).build());
    let mut redirects = 0;
    let mut response = loop {
        let mut request = agent.get(url.as_str()).set("Accept-Encoding", "identity");
        if let Some(value) = etag { request = request.set("If-None-Match", value); }
        let response = match request.call() {
            Ok(response) => response,
            Err(ureq::Error::Status(status, _)) => return Err(fail("http_status", format!("HTTP status {status}"))),
            Err(_) => return Err(fail("network_error", "request failed")),
        };
        match response.status() {
            200 => break response,
            304 if metadata && etag.is_some() => return Ok(json!({"status":304,"headers":metadata_headers(&response)?,"bytes_written":0})),
            301 | 302 | 303 | 307 | 308 => {
                if !follow_redirects { return Err(fail("redirect_not_allowed", "redirects are disabled for this request")); }
                if redirects >= 5 { return Err(fail("redirect_limit", "too many redirects")); }
                let location = response.header("Location").ok_or_else(|| fail("invalid_redirect", "missing redirect location"))?;
                let next = url.join(location).map_err(|_| fail("invalid_redirect", "invalid redirect location"))?;
                if !allowed_url(&next, allow_http_loopback) || (url.scheme() == "https" && next.scheme() != "https") {
                    return Err(fail("invalid_redirect", "redirect target is not permitted"));
                }
                url = next;
                redirects += 1;
            },
            status => return Err(fail("http_status", format!("unexpected HTTP status {status}"))),
        }
    };
    let headers = if metadata { Some(metadata_headers(&response)?) } else { None };
    let mut content_length = response_length(&response, max_bytes)?;
    let report_progress = params.get("progress").and_then(Value::as_bool).unwrap_or(false);
    if chunks > 1 && !metadata && expected.key().is_some() && content_length.is_some_and(|size| size >= 1024 * 1024)
        && response.header("Accept-Ranges").is_some_and(|value| value.eq_ignore_ascii_case("bytes")) {
        let length = content_length.unwrap();
        let validator = response.header("ETag").filter(|value| value.len() >= 2 && value.starts_with('"') && value.ends_with('"') && value.len() <= 4096
            && !value.chars().any(char::is_control)).map(str::to_owned);
        drop(response);
        let mut retry_serial = false;
        if let Some(directory) = resume_dir.as_ref() {
            match resume_download::download(agent, &url, parent, directory, &target, &expected, length, chunks,
                    validator.as_deref(), report_progress, progress) {
                Ok(done) => return Ok(json!({"bytes_written":length,"sha1":done.actual.sha1,"sha256":done.actual.sha256,
                    "sha512":done.actual.sha512,"md5":done.actual.md5,"parallel":true,"resumed_chunks":done.reused})),
                Err(resume_download::Failure::Progress) => return Err(fail("io_error", "could not report progress")),
                Err(resume_download::Failure::RetrySerial) => retry_serial = true,
                Err(resume_download::Failure::Unavailable) => {},
            }
        }
        let attempt = if retry_serial { Err(range_download::Failure::RetrySerial) }
            else { range_download::download(agent, &url, parent, length, chunks, validator.as_deref(), report_progress, progress) };
        match attempt {
            Ok(mut temporary) => {
                let mut input = temporary.reopen().map_err(|e| fail("io_error", e.to_string()))?;
                let mut digest = Hasher::new(&expected);
                let mut buffer = [0u8; 65536]; let mut read_total = 0u64;
                loop {
                    let n = input.read(&mut buffer).map_err(|e| fail("io_error", e.to_string()))?;
                    if n == 0 { break; } digest.update(&buffer[..n]);
                    read_total += n as u64;
                }
                let actual = digest.finish();
                if read_total != length || !expected.matches(&actual) {
                    return Err(fail("checksum_mismatch", "download checksum does not match"));
                }
                temporary.flush().map_err(|e| fail("io_error", e.to_string()))?;
                temporary.persist_noclobber(&target).map_err(|e| fail("publish_failed", e.error.to_string()))?;
                if let (Some(directory), Some(key)) = (resume_dir.as_ref(), expected.key()) {
                    resume_download::cleanup_completed(directory, key, length);
                }
                return Ok(json!({"bytes_written":length,"sha1":actual.sha1,"sha256":actual.sha256,
                    "sha512":actual.sha512,"md5":actual.md5,"parallel":true}));
            },
            Err(range_download::Failure::Progress) => return Err(fail("io_error", "could not report progress")),
            Err(range_download::Failure::RetrySerial) => {
                response = agent.get(url.as_str()).set("Accept-Encoding", "identity").call()
                    .map_err(|_| fail("network_error", "serial retry failed"))?;
                if response.status() != 200 { return Err(fail("http_status", "serial retry returned unexpected status")); }
                content_length = response_length(&response, max_bytes)?;
            },
        }
    }
    let mut last_progress = Instant::now();
    let mut temporary = Builder::new().prefix(".mfcl-download-").tempfile_in(parent)
        .map_err(|e| fail("io_error", e.to_string()))?;
    let mut input = response.into_reader();
    let mut digest = Hasher::new(&expected);
    let mut buffer = [0u8; 65536]; let mut total = 0u64;
    loop {
        let n = input.read(&mut buffer).map_err(|_| fail("network_error", "response body failed"))?;
        if n == 0 { break; }
        total += n as u64;
        if total > max_bytes { return Err(fail("size_limit", "download exceeds max_bytes")); }
        digest.update(&buffer[..n]);
        temporary.write_all(&buffer[..n]).map_err(|e| fail("io_error", e.to_string()))?;
        if report_progress && last_progress.elapsed() >= Duration::from_millis(200) {
            progress(total, content_length).map_err(|_| fail("io_error", "could not report progress"))?;
            last_progress = Instant::now();
        }
    }
    if report_progress {
        progress(total, content_length).map_err(|_| fail("io_error", "could not report progress"))?;
    }
    let actual = digest.finish();
    if !expected.matches(&actual) { return Err(fail("checksum_mismatch", "download checksum does not match")); }
    temporary.flush().map_err(|e| fail("io_error", e.to_string()))?;
    temporary.persist_noclobber(&target).map_err(|e| fail("publish_failed", e.error.to_string()))?;
    if let (Some(directory), Some(key)) = (resume_dir.as_ref(), expected.key()) {
        resume_download::cleanup_completed(directory, key, total);
    }
    if metadata { Ok(json!({"bytes_written": total, "sha1": actual.sha1, "status":200, "headers":headers})) }
    else { Ok(json!({"bytes_written": total, "sha1": actual.sha1, "sha256":actual.sha256, "sha512":actual.sha512, "md5":actual.md5})) }
}

/// Checks the response's encoding and declared size before reading any bytes.
fn response_length(response: &ureq::Response, max_bytes: u64) -> Result<Option<u64>, Error> {
    if response.header("Content-Encoding").is_some_and(|value| !value.eq_ignore_ascii_case("identity")) {
        return Err(fail("unsupported_encoding", "server returned an unsupported content encoding"));
    }
    match response.header("Content-Length") {
        Some(length) => {
            let length: u64 = length.parse().map_err(|_| fail("network_error", "invalid Content-Length"))?;
            if length > max_bytes { return Err(fail("size_limit", "download exceeds max_bytes")); }
            Ok(Some(length))
        },
        None => Ok(None),
    }
}

/// Keeps the response headers required by the launcher's cache and text decoding logic.
fn metadata_headers(response: &ureq::Response) -> Result<Value, Error> {
    let mut headers = serde_json::Map::new(); let mut bytes = 0usize;
    for name in ["content-type", "content-length", "content-encoding", "cache-control", "etag", "expires", "last-modified", "x-bmclapi-hash"] {
        let values = response.all(name);
        for value in &values { bytes = bytes.saturating_add(value.len()); }
        if bytes > 16 * 1024 { return Err(fail("invalid_response", "response headers too large")); }
        if !values.is_empty() { headers.insert(name.to_owned(), json!(values)); }
    }
    Ok(Value::Object(headers))
}
