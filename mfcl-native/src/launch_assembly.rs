// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Assembles ordered launch argument sections and preserves script parsing flags.
use crate::{fail, Error};
use serde_json::{json, Value};

/// Builds the sections surrounding optional launch extras.
pub(crate) fn plan(params: &Value) -> Result<Value, Error> {
    let groups = params.get("groups").and_then(Value::as_array).filter(|groups| groups.len() == 7)
        .ok_or_else(|| fail("invalid_params", "invalid launch argument groups"))?;
    let agents = params.get("agents").and_then(Value::as_array).ok_or_else(|| fail("invalid_params", "invalid Java agents"))?;
    let main = params.get("main").and_then(Value::as_str).ok_or_else(|| fail("invalid_params", "missing main class"))?;
    let mut count = agents.len(); let mut before = Vec::new(); let mut after = Vec::new();
    let mut group_values = Vec::with_capacity(groups.len());
    for group in groups {
        let values = group.as_array().ok_or_else(|| fail("invalid_params", "invalid launch argument group"))?;
        count = count.checked_add(values.len()).ok_or_else(|| fail("argument_limit", "too many launch arguments"))?;
        if count > 20_000 || values.iter().any(|value| !value.is_string()) { return Err(fail("invalid_params", "invalid launch arguments")); }
        group_values.push(values);
    }
    if agents.iter().any(|agent| !agent.is_string()) { return Err(fail("invalid_params", "invalid Java agent")); }
    for value in group_values[0].iter().chain(group_values[1].iter()) { before.push(json!({"value":value,"parse":true})); }
    for agent in agents { before.push(json!({"value":format!("-javaagent:{}", agent.as_str().unwrap_or("")),"parse":true})); }
    before.push(json!({"value":main,"parse":true}));
    for group in &group_values[2..6] { for value in *group { before.push(json!({"value":value,"parse":true})); } }
    for value in group_values[6] { after.push(json!({"value":value,"parse":false})); }
    Ok(json!({"before":before,"after":after}))
}
