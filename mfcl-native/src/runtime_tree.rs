// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Creates runtime directories and links after lexical and filesystem preflight.
use crate::{fail, runtime_paths, Error};
use serde_json::{json, Value};
use std::fs;
use std::path::{Component, Path, PathBuf};

/// Rejects existing symlinks along the entry, except a link being replaced.
#[cfg(target_os = "linux")]
fn check_existing(root: &Path, relative: &Path, allow_final_link: bool) -> Result<(), Error> {
    let count = relative.components().count();
    let mut current = root.to_path_buf();
    for (i, component) in relative.components().enumerate() {
        current.push(component.as_os_str());
        match fs::symlink_metadata(&current) {
            Ok(meta) if meta.file_type().is_symlink() && !(allow_final_link && i + 1 == count) =>
                return Err(fail("unsafe_runtime_path", "runtime entry contains a symbolic link")),
            Ok(_) => (),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => (),
            Err(_) => return Err(fail("runtime_io", "cannot inspect runtime entry")),
        }
    }
    Ok(())
}

/// Creates the directory and link entries in manifest order; file writes remain separate tasks.
#[cfg(target_os = "linux")]
pub(crate) fn prepare(params: &Value) -> Result<Value, Error> {
    use std::os::unix::fs::symlink;
    runtime_paths::validate(params)?;
    let root = params.get("root").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing runtime root"))?;
    let root = PathBuf::from(root);
    if !root.is_absolute() || root.components().any(|c| matches!(c, Component::ParentDir)) {
        return Err(fail("unsafe_runtime_path", "invalid runtime root"));
    }
    match fs::symlink_metadata(&root) {
        Ok(meta) if meta.file_type().is_symlink() => return Err(fail("unsafe_runtime_path", "runtime root is a symbolic link")),
        Ok(_) => (),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => (),
        Err(_) => return Err(fail("runtime_io", "cannot inspect runtime root")),
    }
    let entries = params["entries"].as_array().ok_or_else(|| fail("invalid_params", "missing runtime entries"))?;
    for entry in entries {
        let kind = entry.get("kind").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing runtime entry kind"))?;
        let path = Path::new(entry["path"].as_str().ok_or_else(|| fail("invalid_params", "missing runtime path"))?);
        if !matches!(kind, "file" | "directory" | "link") || (kind == "link") != entry.get("target").is_some_and(Value::is_string) {
            return Err(fail("invalid_params", "invalid runtime entry kind"));
        }
        check_existing(&root, path, kind == "link")?;
    }
    for entry in entries {
        let kind = entry["kind"].as_str().unwrap_or("");
        let relative = Path::new(entry["path"].as_str().unwrap_or(""));
        let destination = root.join(relative);
        check_existing(&root, relative, kind == "link")?;
        match kind {
            "directory" => fs::create_dir_all(&destination).map_err(|_| fail("runtime_io", "failed to create runtime directory"))?,
            "link" => {
                match fs::symlink_metadata(&destination) {
                    Ok(meta) => {
                        if meta.file_type().is_dir() { fs::remove_dir(&destination) } else { fs::remove_file(&destination) }
                            .map_err(|_| fail("runtime_io", "failed to replace runtime link"))?;
                    },
                    Err(e) if e.kind() == std::io::ErrorKind::NotFound => (),
                    Err(_) => return Err(fail("runtime_io", "failed to inspect runtime link")),
                }
                symlink(entry["target"].as_str().unwrap_or(""), &destination).map_err(|_| fail("runtime_io", "failed to create runtime link"))?;
            },
            _ => (),
        }
    }
    Ok(json!({"prepared":true}))
}

/// Other platforms retain Java filesystem handling.
#[cfg(not(target_os = "linux"))]
pub(crate) fn prepare(_params: &Value) -> Result<Value, Error> {
    Err(fail("unsupported_platform", "runtime tree preparation requires Linux"))
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::*;
    #[test]
    fn prepares_directories_and_links_without_following_unsafe_ancestors() {
        let dir = tempfile::tempdir().unwrap(); let root = dir.path().join("runtime");
        let root_text = root.to_str().unwrap();
        let params = json!({"root":root_text,"entries":[
            {"path":"bin","kind":"directory"},
            {"path":"bin/java","kind":"file"},
            {"path":"bin/java-link","kind":"link","target":"java"}]});
        assert!(prepare(&params).is_ok());
        assert!(root.join("bin").is_dir());
        assert_eq!(fs::read_link(root.join("bin/java-link")).unwrap(), PathBuf::from("java"));
        assert!(prepare(&params).is_ok());
        let unsafe_params = json!({"root":root_text,"entries":[
            {"path":"bin/java-link/escape","kind":"directory"}]});
        assert!(prepare(&unsafe_params).is_err());
        assert!(!root.join("bin/java-link/escape").exists());
    }
}
