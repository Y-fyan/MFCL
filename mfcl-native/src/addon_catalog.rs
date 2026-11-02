// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Read-only content-store planning. Network policy and file publication remain
//! with the caller; malformed or ambiguous metadata selects the Java fallback.

use crate::{fail, json_unique, Error};
use serde::Deserialize;
use serde_json::{json, Value};

const MAX_VERSIONS: usize = 10_000;
const MAX_METADATA: usize = 768 * 1024;

/// Decode upstream metadata without accepting duplicate keys or unbounded text.
fn metadata(params: &Value) -> Result<Value, Error> {
    let source = params
        .get("metadata")
        .and_then(Value::as_str)
        .filter(|s| s.len() <= MAX_METADATA)
        .ok_or_else(|| fail("invalid_params", "invalid catalog metadata"))?;
    json_unique::parse(source.as_bytes())
        .map_err(|_| fail("unsupported_metadata", "ambiguous catalog metadata"))
}

/// Read a required string without including caller data in diagnostic messages.
fn text<'a>(value: &'a Value, key: &str) -> Result<&'a str, Error> {
    value
        .get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| fail("unsupported_metadata", "missing catalog string"))
}

/// A strict string list prevents serde/Gson coercion differences.
fn strings(value: &Value, key: &str) -> Result<Vec<String>, Error> {
    let array = value
        .get(key)
        .and_then(Value::as_array)
        .filter(|v| v.len() <= MAX_VERSIONS)
        .ok_or_else(|| fail("unsupported_metadata", "invalid catalog string list"))?;
    array
        .iter()
        .map(|item| {
            item.as_str()
                .map(str::to_owned)
                .ok_or_else(|| fail("unsupported_metadata", "invalid catalog string"))
        })
        .collect()
}

/// Build query values, not a URL: Java retains escaping, mirrors and HTTP policy.
pub(crate) fn modrinth_query(params: &Value) -> Result<Value, Error> {
    let project = text(params, "project_type")?;
    if !["mod", "modpack", "resourcepack", "shader"].contains(&project) {
        return Err(fail("invalid_params", "unsupported project type"));
    }
    let page = params
        .get("page")
        .and_then(Value::as_u64)
        .ok_or_else(|| fail("invalid_params", "invalid catalog page"))?;
    let size = params
        .get("page_size")
        .and_then(Value::as_u64)
        .filter(|v| *v > 0 && *v <= 100)
        .ok_or_else(|| fail("invalid_params", "invalid catalog page size"))?;
    let offset = page
        .checked_mul(size)
        .filter(|v| *v <= i32::MAX as u64)
        .ok_or_else(|| fail("invalid_params", "catalog offset exceeds limit"))?;
    let sort = match text(params, "sort")? {
        "RELEVANCY" => "relevance",
        "POPULARITY" => "follows",
        "DATE_CREATED" => "newest",
        "LAST_UPDATED" => "updated",
        "TOTAL_DOWNLOADS" => "downloads",
        _ => return Err(fail("invalid_params", "unsupported catalog sort")),
    };
    let mut facets = vec![vec![format!("project_type:{project}")]];
    for (key, prefix) in [("game_version", "versions"), ("category", "categories")] {
        let value = text(params, key)?;
        // Java's Unicode blank predicate is not Rust's whitespace predicate.
        // The adapter has already normalized blank values to empty strings.
        if !value.is_empty() {
            facets.push(vec![format!("{prefix}:{value}")]);
        }
    }
    Ok(json!({"query": {
        "query": text(params, "query")?, "facets": serde_json::to_string(&facets).unwrap(),
        "offset": offset.to_string(), "limit": size.to_string(), "index": sort
    }}))
}

/// Plan CurseForge queries without receiving authentication headers or credentials.
pub(crate) fn curse_query(params: &Value) -> Result<Value, Error> {
    let section = params
        .get("section")
        .and_then(Value::as_i64)
        .filter(|v| *v >= 0 && *v <= i32::MAX as i64)
        .ok_or_else(|| fail("invalid_params", "invalid content section"))?;
    let category = params
        .get("category_id")
        .and_then(Value::as_i64)
        .filter(|v| *v >= i32::MIN as i64 && *v <= i32::MAX as i64)
        .ok_or_else(|| fail("invalid_params", "invalid category ID"))?;
    let page = params
        .get("page")
        .and_then(Value::as_u64)
        .ok_or_else(|| fail("invalid_params", "invalid catalog page"))?;
    let size = params
        .get("page_size")
        .and_then(Value::as_u64)
        .filter(|v| *v > 0 && *v <= 100)
        .ok_or_else(|| fail("invalid_params", "invalid catalog page size"))?;
    let offset = page
        .checked_mul(size)
        .filter(|v| *v <= i32::MAX as u64)
        .ok_or_else(|| fail("invalid_params", "catalog offset exceeds limit"))?;
    let sort = match text(params, "sort")? {
        "RELEVANCY" => "1",
        "POPULARITY" => "2",
        "DATE_CREATED" => "11",
        "LAST_UPDATED" => "3",
        "TOTAL_DOWNLOADS" => "6",
        _ => return Err(fail("invalid_params", "unsupported catalog sort")),
    };
    let order = match text(params, "order")? {
        "ASC" => "asc",
        "DESC" => "desc",
        _ => return Err(fail("invalid_params", "unsupported catalog order")),
    };
    let mut query = json!({"gameId":"432", "classId":section.to_string(),
        "gameVersion":text(params, "game_version")?, "searchFilter":text(params, "query")?,
        "sortField":sort, "sortOrder":order, "index":offset.to_string(), "pageSize":size.to_string()});
    if category != 0 {
        query["categoryId"] = json!(category.to_string());
    }
    Ok(json!({"query":query}))
}

/// Project each version once, preserving source ordering and first-file behavior.
pub(crate) fn modrinth_versions(params: &Value) -> Result<Value, Error> {
    let source = metadata(params)?;
    let single = params
        .get("single")
        .and_then(Value::as_bool)
        .ok_or_else(|| fail("invalid_params", "missing catalog shape"))?;
    let input = if single {
        vec![&source]
    } else {
        source
            .as_array()
            .filter(|v| v.len() <= MAX_VERSIONS)
            .ok_or_else(|| fail("unsupported_metadata", "invalid catalog versions"))?
            .iter()
            .collect()
    };
    let mut versions = Vec::new();
    for value in input {
        let kind = match text(value, "version_type")? {
            "beta" => "Beta",
            "alpha" => "Alpha",
            _ => "Release",
        };
        let files = value
            .get("files")
            .and_then(Value::as_array)
            .ok_or_else(|| fail("unsupported_metadata", "invalid catalog files"))?;
        // The existing repository skips fileless versions, even with incomplete fields.
        let Some(file) = files.first() else { continue };
        let hashes = file
            .get("hashes")
            .and_then(Value::as_object)
            .filter(|map| map.values().all(Value::is_string))
            .ok_or_else(|| fail("unsupported_metadata", "invalid catalog hashes"))?;
        let dependencies = value
            .get("dependencies")
            .and_then(Value::as_array)
            .filter(|v| v.len() <= MAX_VERSIONS)
            .ok_or_else(|| fail("unsupported_metadata", "invalid catalog dependencies"))?;
        let mut mapped_dependencies = Vec::new();
        for dependency in dependencies {
            if dependency.get("project_id").is_none_or(Value::is_null) {
                mapped_dependencies.push(json!({"type":"BROKEN"}));
                continue;
            }
            let kind = match text(dependency, "dependency_type")? {
                "required" => "REQUIRED",
                "optional" => "OPTIONAL",
                "embedded" => "EMBEDDED",
                "incompatible" => "INCOMPATIBLE",
                _ => return Err(fail("unsupported_metadata", "unsupported dependency kind")),
            };
            mapped_dependencies.push(json!({"type":kind, "id":text(dependency, "project_id")?}));
        }
        let date = match value.get("date_published") {
            None | Some(Value::Null) => Value::Null,
            Some(Value::String(date)) => json!(date),
            _ => return Err(fail("unsupported_metadata", "invalid catalog timestamp")),
        };
        versions.push(json!({
            "versionId":text(value, "id")?, "projectId":text(value, "project_id")?,
            "name":text(value, "name")?, "version":text(value, "version_number")?,
            "datePublished":date, "versionType":kind,
            "file":{"hashes":hashes, "url":text(file, "url")?, "filename":text(file, "filename")?},
            "dependencies":mapped_dependencies, "gameVersions":strings(value, "game_versions")?,
            "loaders":strings(value, "loaders")?
        }));
    }
    Ok(json!({"versions":versions}))
}

/// Filter category metadata by project type while preserving upstream order.
pub(crate) fn modrinth_categories(params: &Value) -> Result<Value, Error> {
    let source = metadata(params)?;
    let project = text(params, "project_type")?;
    let array = source
        .as_array()
        .filter(|v| v.len() <= MAX_VERSIONS)
        .ok_or_else(|| fail("unsupported_metadata", "invalid catalog categories"))?;
    let mut selected = Vec::new();
    for value in array {
        if text(value, "project_type")? == project {
            // Keep icon and other extension fields for existing typed category data.
            text(value, "name")?;
            selected.push(value);
        }
    }
    Ok(json!({"categories":selected}))
}

/// Timestamp tuples avoid rounding nanoseconds through JSON floating point.
#[derive(Deserialize, Eq, PartialEq, Ord, PartialOrd)]
struct Timestamp {
    seconds: i64,
    nanos: u32,
}

/// Opaque version observations; returned indices refer to caller-owned objects.
#[derive(Deserialize)]
struct VersionObservation {
    game_versions: Vec<String>,
    loaders: Vec<String>,
    date: Option<Timestamp>,
}

/// Select versions for either store without round-tripping domain objects.
pub(crate) fn select_versions(params: &Value) -> Result<Value, Error> {
    let game = text(params, "game_version")?;
    let loader = text(params, "loader")?;
    let values = params
        .get("versions")
        .and_then(Value::as_array)
        .filter(|v| v.len() <= MAX_VERSIONS)
        .ok_or_else(|| fail("invalid_params", "invalid version observations"))?;
    let mut selected = Vec::new();
    if !game.is_ascii() || !loader.is_ascii() {
        return Err(fail(
            "unsupported_metadata",
            "non-ASCII comparison requires caller",
        ));
    }
    for (index, value) in values.iter().enumerate() {
        let version: VersionObservation = serde_json::from_value(value.clone())
            .map_err(|_| fail("invalid_params", "invalid version observation"))?;
        if version
            .game_versions
            .iter()
            .chain(&version.loaders)
            .any(|v| !v.is_ascii())
            || version
                .date
                .as_ref()
                .is_some_and(|v| v.nanos >= 1_000_000_000)
        {
            return Err(fail(
                "unsupported_metadata",
                "unsupported version observation",
            ));
        }
        let game_matches = game.is_empty()
            || version.game_versions.is_empty()
            || version
                .game_versions
                .iter()
                .any(|v| v.eq_ignore_ascii_case(game));
        let loader_matches = loader.is_empty()
            || version
                .loaders
                .iter()
                .any(|v| v.eq_ignore_ascii_case(loader));
        if game_matches && loader_matches {
            selected.push((index, version.date));
        }
    }
    // Preserve Java's stable reversed(nullsLast(naturalOrder())) comparator:
    // undated entries come FIRST, then newest dates, ties in source order.
    selected.sort_by(|a, b| match (&a.1, &b.1) {
        (None, None) => std::cmp::Ordering::Equal,
        (None, Some(_)) => std::cmp::Ordering::Less,
        (Some(_), None) => std::cmp::Ordering::Greater,
        (Some(a), Some(b)) => b.cmp(a),
    });
    Ok(json!({"indices":selected.iter().map(|v| v.0).collect::<Vec<_>>()}))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn query_keeps_unicode_and_facet_values_without_building_urls() {
        let result = modrinth_query(&json!({"project_type":"mod", "query":"钠 & 光影",
            "game_version":"1.20.1", "category":"a\"b", "page":2, "page_size":20,
            "sort":"TOTAL_DOWNLOADS"}))
        .ok()
        .unwrap();
        assert_eq!(result["query"]["query"], "钠 & 光影");
        assert_eq!(result["query"]["offset"], "40");
        assert_eq!(result["query"]["index"], "downloads");
        let facets: Value =
            serde_json::from_str(result["query"]["facets"].as_str().unwrap()).unwrap();
        assert_eq!(
            facets,
            json!([
                ["project_type:mod"],
                ["versions:1.20.1"],
                ["categories:a\"b"]
            ])
        );
    }

    #[test]
    fn selection_preserves_null_dates_ties_and_empty_game_lists() {
        let result = select_versions(
            &json!({"game_version":"1.20.1", "loader":"fabric", "versions":[
                {"game_versions":["1.20.1"],"loaders":["Fabric"],"date":{"seconds":2,"nanos":1}},
                {"game_versions":[],"loaders":["fabric"],"date":null},
                {"game_versions":["1.20.1"],"loaders":["fabric"],"date":{"seconds":2,"nanos":1}},
                {"game_versions":["1.20.1"],"loaders":["forge"],"date":null},
                {"game_versions":["1.21"],"loaders":["fabric"],"date":null}
            ]}),
        )
        .ok()
        .unwrap();
        assert_eq!(result["indices"], json!([1, 0, 2]));
    }

    #[test]
    fn projection_rejects_ambiguous_input_and_skips_fileless_versions() {
        assert!(modrinth_versions(
            &json!({"metadata":"[{\"files\":[],\"files\":[]}]", "single":false})
        )
        .is_err());
        assert_eq!(
            modrinth_versions(
                &json!({"metadata":"[{\"version_type\":\"release\",\"files\":[]}]", "single":false})
            )
            .ok()
            .unwrap(),
            json!({"versions":[]})
        );
    }
}
