// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Removes selected managed files without following nested directory symlinks.
use crate::{fail, Error};
use serde_json::{json, Value};

/// Removes prevalidated relative entries under a trusted instance root on Linux.
#[cfg(target_os = "linux")]
pub(crate) fn remove(params: &Value) -> Result<Value, Error> {
    use std::ffi::CString;
    use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
    use std::os::unix::ffi::OsStrExt;
    use std::path::{Component, Path};

    let root = params.get("root").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing managed root"))?;
    let root = Path::new(root);
    if !root.is_absolute() || root.components().any(|p| matches!(p, Component::ParentDir)) {
        return Err(fail("invalid_params", "invalid managed root"));
    }
    let raw = params.get("paths").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing removal paths"))?;
    if raw.len() > 20_000 { return Err(fail("modpack_limit", "too many removal paths")); }
    let mut paths = Vec::with_capacity(raw.len());
    for value in raw {
        let text = value.as_str().filter(|v| !v.is_empty()).ok_or_else(|| fail("invalid_params", "invalid removal path"))?;
        let path = Path::new(text);
        let mut parts = Vec::new();
        for part in path.components() {
            match part {
                Component::Normal(name) => parts.push(CString::new(name.as_bytes()).map_err(|_| fail("invalid_params", "invalid path component"))?),
                _ => return Err(fail("invalid_params", "unsafe removal path")),
            }
        }
        if parts.is_empty() { return Err(fail("invalid_params", "empty removal path")); }
        paths.push(parts);
    }
    if paths.is_empty() { return Ok(json!({"removed":0})); }
    let root_name = CString::new(root.as_os_str().as_bytes()).map_err(|_| fail("invalid_params", "invalid root path"))?;
    let root_fd = unsafe { libc::open(root_name.as_ptr(), libc::O_PATH | libc::O_DIRECTORY | libc::O_CLOEXEC) };
    if root_fd < 0 { return Err(fail("io_error", "cannot open managed root")); }
    let root_fd = unsafe { OwnedFd::from_raw_fd(root_fd) };
    let mut removed = 0usize;
    for parts in paths {
        let mut opened: Vec<OwnedFd> = Vec::new();
        let mut parent_fd = root_fd.as_raw_fd();
        let mut missing = false;
        for part in &parts[..parts.len() - 1] {
            let next = unsafe { libc::openat(parent_fd, part.as_ptr(), libc::O_PATH | libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC) };
            if next < 0 {
                if std::io::Error::last_os_error().kind() == std::io::ErrorKind::NotFound { missing = true; break; }
                return Err(fail("io_error", "cannot safely open managed directory"));
            }
            let fd = unsafe { OwnedFd::from_raw_fd(next) };
            parent_fd = fd.as_raw_fd(); opened.push(fd);
        }
        if missing { continue; }
        let name = &parts[parts.len() - 1];
        let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
        if unsafe { libc::fstatat(parent_fd, name.as_ptr(), stat.as_mut_ptr(), libc::AT_SYMLINK_NOFOLLOW) } != 0 {
            if std::io::Error::last_os_error().kind() == std::io::ErrorKind::NotFound { continue; }
            return Err(fail("io_error", "cannot inspect managed entry"));
        }
        let stat = unsafe { stat.assume_init() };
        let flags = if stat.st_mode & libc::S_IFMT == libc::S_IFDIR { libc::AT_REMOVEDIR } else { 0 };
        if unsafe { libc::unlinkat(parent_fd, name.as_ptr(), flags) } != 0 {
            if std::io::Error::last_os_error().kind() == std::io::ErrorKind::NotFound { continue; }
            return Err(fail("io_error", "cannot remove managed entry"));
        }
        removed += 1;
    }
    Ok(json!({"removed":removed}))
}

/// Other systems retain the Java removal implementation.
#[cfg(not(target_os = "linux"))]
pub(crate) fn remove(_params: &Value) -> Result<Value, Error> {
    Err(fail("unsupported_platform", "safe removal requires Linux"))
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    use std::fs;
    use std::os::unix::fs::symlink;
    #[test]
    fn rejects_traversal_before_mutation_and_never_follows_parent_links() {
        let temp = tempfile::tempdir().unwrap(); let root = temp.path().join("game"); let outside = temp.path().join("outside");
        fs::create_dir(&root).unwrap(); fs::create_dir(&outside).unwrap();
        fs::write(root.join("keep"), b"safe").unwrap(); fs::write(outside.join("secret"), b"outside").unwrap();
        let traversal = json!({"root":root.to_str().unwrap(),"paths":["keep","../outside/secret"]});
        assert!(remove(&traversal).is_err()); assert!(root.join("keep").exists());
        symlink(&outside, root.join("redirect")).unwrap();
        let linked = json!({"root":root.to_str().unwrap(),"paths":["redirect/secret"]});
        assert!(remove(&linked).is_err()); assert!(outside.join("secret").exists());
        let good = json!({"root":root.to_str().unwrap(),"paths":["keep"]});
        assert!(remove(&good).is_ok()); assert!(!root.join("keep").exists());
    }
}
