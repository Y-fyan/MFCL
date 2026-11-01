// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Rewrites legacy BootstrapLauncher substring ignore lists using classpath filenames.
use crate::{fail, Error};
use serde_json::{json, Value};
use std::path::{Component, Path};

/// Accepts only ordinary absolute paths, leaving unusual platform syntax to Java.
fn checked_path(value: &str) -> Result<&Path, Error> {
    let path = Path::new(value);
    if value.contains('\0') || !path.is_absolute()
        || path.components().any(|c| matches!(c, Component::CurDir | Component::ParentDir)) {
        return Err(fail("unsupported_path", "unsupported classpath path"));
    }
    let normalized: std::path::PathBuf = path.components().collect();
    if normalized.to_str() != Some(value) { return Err(fail("unsupported_path", "unsupported path encoding")); }
    Ok(path)
}

/// Rewrites a bounded batch while preserving classpath and input ordering.
pub(crate) fn plan(params: &Value) -> Result<Value, Error> {
    let root = checked_path(params.get("library_directory").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing library directory"))?)?;
    let paths = params.get("classpath").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing classpath"))?;
    let lists = params.get("ignore_lists").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "missing ignore lists"))?;
    if paths.len() > 20_000 || lists.len() > 2_000 { return Err(fail("argument_limit", "too many bootstrap arguments")); }
    let mut files = Vec::with_capacity(paths.len());
    for value in paths {
        let text = value.as_str().ok_or_else(|| fail("invalid_params", "invalid classpath entry"))?;
        let path = checked_path(text)?;
        let name = path.file_name().and_then(|v| v.to_str()).ok_or_else(|| fail("unsupported_path", "invalid classpath filename"))?;
        let representation = if let Ok(relative) = path.strip_prefix(root) {
            format!("${{library_directory}}${{file_separator}}{}", relative.to_str().ok_or_else(|| fail("unsupported_path", "invalid library path"))?
                .replace(std::path::MAIN_SEPARATOR, "${file_separator}"))
        } else { text.to_owned() };
        files.push((name, representation));
    }
    let mut output = Vec::with_capacity(lists.len()); let mut total = 0usize;
    for value in lists {
        let list = value.as_str().ok_or_else(|| fail("invalid_params", "invalid ignore list"))?;
        let mut tokens: Vec<&str> = list.split(',').collect();
        if !list.is_empty() { while tokens.last() == Some(&"") { tokens.pop(); } }
        let mut entries = vec!["${primary_jar}".to_owned()];
        for (name, path) in &files {
            if tokens.iter().any(|token| name.contains(*token)) {
                entries.push(path.split_once(',').map_or(path.as_str(), |(before, _)| before).to_owned());
            }
        }
        let rewritten = entries.join(","); total = total.saturating_add(rewritten.len());
        if total > 4 * 1024 * 1024 { return Err(fail("argument_limit", "bootstrap arguments too large")); }
        output.push(rewritten);
    }
    Ok(json!({"ignore_lists":output}))
}
