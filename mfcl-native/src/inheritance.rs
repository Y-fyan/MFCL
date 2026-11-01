// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
use crate::{fail, Error};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};

/// Plans a single ordered inheritance chain, retaining the repeated terminal node for cycles.
pub(crate) fn plan(params: &Value) -> Result<Value, Error> {
    let instances = params.get("instances").and_then(Value::as_array)
        .ok_or_else(|| fail("invalid_params", "instances must be an array"))?;
    if instances.len() > 20_000 { return Err(fail("instance_limit", "too many instances")); }
    fn parse(entry: &Value) -> Result<(&str, Option<&str>), Error> {
        let id = entry.get("id").and_then(Value::as_str).filter(|s| !s.is_empty())
            .ok_or_else(|| fail("invalid_params", "invalid instance id"))?;
        let parent = match entry.get("parent") {
            None | Some(Value::Null) => None,
            Some(Value::String(s)) if !s.is_empty() => Some(s.as_str()),
            _ => return Err(fail("invalid_params", "invalid parent id")),
        };
        Ok((id, parent))
    }
    let start = params.get("start").ok_or_else(|| fail("invalid_params", "start is required"))?;
    let (start_id, mut parent) = parse(start)?;
    let mut lookup = HashMap::with_capacity(instances.len());
    for item in instances {
        let (id, parent) = parse(item)?;
        if lookup.insert(id, parent).is_some() { return Err(fail("invalid_params", "duplicate instance id")); }
    }
    let mut chain = vec![start_id]; let mut visited = HashSet::new();
    if parent.is_some() { visited.insert(start_id); }
    let mut cycle = false;
    while let Some(id) = parent {
        let next = lookup.get(id).ok_or_else(|| fail("missing_parent", "instance parent does not exist"))?;
        chain.push(id);
        if next.is_some() && !visited.insert(id) { cycle = true; break; }
        parent = *next;
    }
    Ok(json!({"chain":chain,"cycle":cycle}))
}

/// Finds versions whose inheritance chain reaches a missing parent. Cycles remain present.
pub(crate) fn find_invalid(params: &Value) -> Result<Value, Error> {
    let entries = params.get("instances").and_then(Value::as_array)
        .ok_or_else(|| fail("invalid_params", "instances must be an array"))?;
    if entries.len() > 20_000 { return Err(fail("instance_limit", "too many instances")); }
    let mut parents: HashMap<&str, Option<&str>> = HashMap::with_capacity(entries.len());
    let mut order = Vec::with_capacity(entries.len());
    for entry in entries {
        let id = entry.get("id").and_then(Value::as_str).filter(|s| !s.is_empty())
            .ok_or_else(|| fail("invalid_params", "invalid instance id"))?;
        let parent = match entry.get("parent") {
            None | Some(Value::Null) => None,
            Some(Value::String(s)) if !s.is_empty() => Some(s.as_str()),
            _ => return Err(fail("invalid_params", "invalid parent id")),
        };
        if parents.insert(id, parent).is_some() { return Err(fail("invalid_params", "duplicate instance id")); }
        order.push(id);
    }
    let mut valid = HashMap::<&str, bool>::with_capacity(entries.len());
    for &start in &order {
        if valid.contains_key(start) { continue; }
        let mut path = Vec::new(); let mut visited = HashSet::new(); let mut current = start;
        let result = loop {
            if let Some(&known) = valid.get(current) { break known; }
            if !visited.insert(current) { break true; }
            match parents.get(current) {
                None => break false,
                Some(None) => { path.push(current); break true; },
                Some(Some(parent)) => { path.push(current); current = parent; },
            }
        };
        for id in path { valid.insert(id, result); }
    }
    let invalid: Vec<&str> = order.into_iter().filter(|id| valid.get(id) == Some(&false)).collect();
    Ok(json!({"invalid":invalid}))
}
