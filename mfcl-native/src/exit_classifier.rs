// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Classifies game exit status using pre-normalized error lines.
use crate::{fail, Error};
use serde_json::{json, Value};
use regex::Regex;
use std::sync::OnceLock;

/// Matches the existing Log4j level heuristic before exit messages are inspected.
fn is_error_line(line: &str) -> bool {
    static LOGGER: OnceLock<Regex> = OnceLock::new();
    static CATEGORY: OnceLock<Regex> = OnceLock::new();
    let logger = LOGGER.get_or_init(|| Regex::new(r"\[[0-9:]+\] \[[^/]+/(?P<level>[^\]]+)\]").unwrap());
    let category = CATEGORY.get_or_init(|| Regex::new(r"\[[0-9:]+\] \[[^/]+/[^\]]+\] \[(?P<category>[^\]]+)\]").unwrap());
    let mut level = None;
    if let Some(found) = logger.captures(line) {
        level = match found.name("level").map(|m| m.as_str()) {
            Some("FATAL") => Some(1), Some("ERROR") => Some(2), Some("WARN") => Some(3),
            Some("INFO") => Some(4), Some("DEBUG") => Some(5), Some("TRACE") => Some(6), _ => None,
        };
        if let Some(found) = category.captures(line) {
            match found.name("category").map(|m| m.as_str()) {
                Some("STDOUT") => level = Some(4), Some("STDERR") => level = Some(2), _ => (),
            }
        }
        if line.contains("STDERR]") || line.contains("[STDERR/]") { level = Some(2); }
    } else {
        if ["[INFO]", "[CONFIG]", "[FINE]", "[FINER]", "[FINEST]"].iter().any(|s| line.contains(s)) { level = Some(4); }
        if line.contains("[SEVERE]") || line.contains("[STDERR]") { level = Some(2); }
        if line.contains("[WARNING]") { level = Some(3); }
        if line.contains("[DEBUG]") { level = Some(5); }
    }
    if line.contains("overwriting existing") { level = Some(1); }
    level.is_some_and(|v| v <= 2)
}

pub(crate) fn classify(params: &Value) -> Result<Value, Error> {
    let code = params.get("exit_code").and_then(Value::as_i64)
        .and_then(|v| i32::try_from(v).ok()).ok_or_else(|| fail("invalid_params", "invalid exit code"))?;
    let linux = params.get("linux_or_bsd").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "invalid platform"))?;
    let lines = params.get("lines").and_then(Value::as_array).filter(|v| v.len() <= 20_000)
        .ok_or_else(|| fail("invalid_params", "invalid error lines"))?;
    let original = match params.get("original_lines") {
        None => None,
        Some(Value::Array(values)) if values.len() == lines.len() => Some(values),
        _ => return Err(fail("invalid_params", "invalid original lines")),
    };
    let mut jvm = false; let mut crash = false;
    for (index, line) in lines.iter().enumerate() {
        let text = line.as_str().ok_or_else(|| fail("invalid_params", "invalid error line"))?;
        if let Some(original) = original {
            let original = original[index].as_str().ok_or_else(|| fail("invalid_params", "invalid original line"))?;
            if !is_error_line(original) { continue; }
        }
        jvm |= ["could not create the java virtual machine.", "error occurred during initialization of vm",
            "a fatal exception has occurred. program will exit."].iter().any(|needle| text.contains(needle));
        crash |= ["crash report saved to", "could not save crash report to", "this crash report has been saved to:",
            "unable to launch", "an exception was thrown, the game will display an error screen and halt."].iter().any(|needle| text.contains(needle));
    }
    let result = if code != 0 && jvm { "jvm_error" }
        else if code != 0 || crash { if code == 137 && linux { "sigkill" } else { "application_error" } }
        else { "normal" };
    Ok(json!({"exit_type":result}))
}
