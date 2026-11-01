// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
mod archive;
mod arguments;
mod assets;
mod classpath;
mod download;
mod download_digest;
mod exec_child;
mod offline;
mod microsoft;
mod library_check;
mod inheritance;
mod manifest_merge;
mod loader_plan;
mod range_download;
mod resume_download;
mod forge_literals;
mod forge_profile;
mod launch_extras;
mod launch_assembly;
mod processor_command;
mod yggdrasil;
mod jvm_options;
mod bootstrap_ignore;
mod runtime_paths;
mod runtime_lzma;
mod runtime_list;
mod game_versions;
mod game_manifest;
mod runtime_manifest;
mod runtime_tree;
mod native_libraries;
mod modrinth_select;
mod managed_remove;
mod curse_select;
mod oauth_callback;
mod oauth_server;
mod device_flow;
mod json_unique;
mod exit_classifier;

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{fs::{self, File}, io::{self, BufRead, BufReader, Read, Write}, path::{Path, PathBuf}};

const PROTOCOL: u32 = 1;
const MAX_REQUEST: u64 = 1024 * 1024;
const MAX_MANIFEST: u64 = 16 * 1024 * 1024;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request { version: u32, id: Value, method: String, #[serde(default)] params: Value }
#[derive(Serialize)]
struct Response { version: u32, id: Value, #[serde(skip_serializing_if = "Option::is_none")] result: Option<Value>, #[serde(skip_serializing_if = "Option::is_none")] error: Option<Error> }
#[derive(Serialize)]
struct Error { code: &'static str, message: String, #[serde(skip_serializing_if = "Option::is_none")] xbox: Option<XboxError> }
#[derive(Serialize)]
struct XboxError { error_code: i64, redirect: Option<String> }
fn fail(code: &'static str, message: impl Into<String>) -> Error { Error { code, message: message.into(), xbox: None } }
fn path_param(params: &Value, key: &str) -> Result<PathBuf, Error> {
    let text = params.get(key).and_then(Value::as_str).filter(|s| !s.is_empty()).ok_or_else(|| fail("invalid_params", format!("{key} must be a non-empty path")))?;
    Ok(PathBuf::from(text))
}
fn handle(req: Request, progress: &mut dyn FnMut(u64, Option<u64>) -> io::Result<()>) -> Response {
    let result = if req.version != PROTOCOL { Err(fail("unsupported_version", "supported protocol version: 1")) } else {
        match req.method.as_str() {
            "health" => {
                let mut capabilities = vec!["health", "sha256", "scan_instances", "list_instance_dirs", "find_invalid_inheritance", "plan_inheritance", "merge_manifest", "apply_manifest_patches", "apply_manifest_patches_ordered", "plan_loader", "expand_processor_literals", "download", "offline_profile", "extract_zip", "plan_assets", "expand_arguments", "plan_classpath", "check_libraries", "check_files", "expand_ruled_arguments", "microsoft_authorization_url", "microsoft_authorization_token", "microsoft_device_begin", "microsoft_device_poll", "microsoft_refresh", "microsoft_exchange"];
                capabilities.push("microsoft_upload_skin");
                capabilities.push("microsoft_storage_v1");
                capabilities.push("download_batch_v1");
                capabilities.push("microsoft_profile_read");
                #[cfg(feature = "auth-test-endpoints")]
                capabilities.push("auth_test_endpoints");
                capabilities.push("parse_oauth_callback");
                capabilities.push("oauth_callback_server_v1");
                capabilities.push("microsoft_device_stream_v1");
                capabilities.push("microsoft_device_session_v2");
                capabilities.push("microsoft_authorization_session_v1");
                capabilities.push("microsoft_refresh_session_v1");
                capabilities.push("plan_launch_extras");
                capabilities.push("assemble_launch_sections");
                capabilities.push("processor_literal_stream_v1");
                capabilities.push("file_hash_stream_v1");
                capabilities.push("processor_options_stream_v1");
                capabilities.push("legacy_checksum_stream_v1");
                capabilities.push("plan_asset_install");
                capabilities.push("processor_command_stream_v1");
                capabilities.push("processor_combined_command_v1");
                capabilities.push("processor_artifact_paths_v1");
                capabilities.push("processor_options_paths_v1");
                capabilities.push("forge_profiles_v1");
                capabilities.push("optifine_plan_v1");
                capabilities.push("optifine_identity_v1");
                capabilities.push("forge_entries_v1");
                capabilities.push("forge_data_entries_v1");
                capabilities.push("processor_coordinate_classpath_v1");
                capabilities.push("modpack_hash_stream_v1");
                capabilities.push("download_sha512");
                capabilities.push("extract_zip_prefix");
                capabilities.push("hash_zip_entries");
                capabilities.push("plan_jvm_defaults");
                capabilities.push("plan_jvm_initial");
                capabilities.push("plan_jvm_platform");
                capabilities.push("plan_bootstrap_ignore");
                capabilities.push("bootstrap_ignore_stream_v1");
                capabilities.push("classpath_plan_stream_v1");
                capabilities.push("classpath_rules_combined_v1");
                capabilities.push("classify_game_exit_v1");
                capabilities.push("stage_cache_copy");
                capabilities.push("cache_stage_stream_v1");
                capabilities.push("cache_hash_stream_v1");
                capabilities.push("cache_digest_stream_v1");
                capabilities.push("download_multi_digest_v1");
                capabilities.push("validate_runtime_paths");
                capabilities.push("runtime_lzma_stream_v1");
                capabilities.push("library_rule_stream_v1");
                capabilities.push("native_library_stream_v1");
                capabilities.push("native_archive_plan_stream_v1");
                capabilities.push("native_archive_rules_combined_v1");
                capabilities.push("modrinth_select_stream_v1");
                capabilities.push("modrinth_plan_stream_v1");
                capabilities.push("modrinth_update_stream_v1");
                if cfg!(target_os = "linux") { capabilities.push("remove_modrinth_files_v1"); capabilities.push("remove_managed_files_v1"); }
                capabilities.push("curse_select_stream_v1");
                capabilities.push("curse_plan_stream_v1");
                capabilities.push("runtime_metadata_stream_v1");
                capabilities.push("parse_game_versions");
                capabilities.push("liteloader_catalog_v1");
                capabilities.push("liteloader_patch_v1");
                capabilities.push("project_game_manifest");
                capabilities.push("instance_manifest_stream_v1");
                capabilities.push("select_java_runtime");
                capabilities.push("parse_java_runtime_manifest");
                if cfg!(target_os = "linux") { capabilities.push("prepare_java_runtime_tree"); }
                capabilities.push("yggdrasil_request");
                capabilities.push("yggdrasil_get");
                capabilities.push("yggdrasil_skin_upload");
                capabilities.push("yggdrasil_plan_response");
                capabilities.push("yggdrasil_plan_profile");
                capabilities.push("authlib_metadata_v1");
                capabilities.push("authlib_locate_header_v1");
                capabilities.push("yggdrasil_plan_textures");
                if cfg!(unix) { capabilities.push("launch_exec"); capabilities.push("launch_exec_length_v1"); }
                Ok(json!({"name":"mfcl-native", "protocol": PROTOCOL, "capabilities":capabilities, "read_only":false}))
            },
            "microsoft_authorization_url" => microsoft::authorization_url(&req.params),
            "microsoft_authorization_token" => microsoft::authorization_token(&req.params),
            "microsoft_authorization_session" => microsoft::authorization_session(&req.params),
            "microsoft_device_begin" => microsoft::device_begin(&req.params),
            "microsoft_device_poll" => microsoft::device_poll(&req.params),
            "microsoft_refresh" => microsoft::refresh(&req.params),
            "microsoft_refresh_session" => microsoft::refresh_session(&req.params),
            "microsoft_exchange" => microsoft::exchange(&req.params),
            "microsoft_upload_skin" => microsoft::upload_skin(&req.params),
            "microsoft_profile_read" => microsoft::profile_read(&req.params),
            "microsoft_storage" => microsoft::storage(&req.params),
            "yggdrasil_request" => yggdrasil::request(&req.params),
            "yggdrasil_plan_response" => yggdrasil::plan_response(&req.params),
            "yggdrasil_plan_profile" => yggdrasil::plan_complete_profile(&req.params),
            "plan_authlib_metadata" => yggdrasil::plan_authlib_metadata(&req.params),
            "yggdrasil_plan_textures" => yggdrasil::plan_textures(&req.params),
            "plan_launch_extras" => launch_extras::plan(&req.params),
            "assemble_launch_sections" => launch_assembly::plan(&req.params),
            "plan_jvm_defaults" => jvm_options::plan(&req.params),
            "plan_jvm_initial" => jvm_options::initial(&req.params),
            "plan_jvm_platform" => jvm_options::platform(&req.params),
            "plan_bootstrap_ignore" => bootstrap_ignore::plan(&req.params),
            "validate_runtime_paths" => runtime_paths::validate(&req.params),
            "stage_runtime_lzma" => runtime_lzma::stage(&req.params),
            "select_java_runtime" => runtime_list::select(&req.params),
            "parse_game_versions" => game_versions::parse(&req.params),
            "parse_liteloader_catalog" => loader_plan::liteloader_catalog(&req.params),
            "plan_liteloader_patch" => loader_plan::liteloader_patch(&req.params),
            "project_game_manifest" => game_manifest::project(&req.params),
            "project_instance_manifest" => {
                let mut params = req.params.clone();
                if let Some(object) = params.as_object_mut() { object.insert("loader_mode".into(), true.into()); }
                game_manifest::project(&params)
            },
            "parse_java_runtime_manifest" => runtime_manifest::parse(&req.params),
            "prepare_java_runtime_tree" => runtime_tree::prepare(&req.params),
            "check_libraries" | "check_files" => library_check::check(&req.params, progress),
            "check_legacy_library" => library_check::check_legacy(&req.params),
            "stage_cache_copy" => library_check::stage_cache_copy(&req.params),
            "hash_cache_sha1" => library_check::hash_cache_sha1(&req.params),
            "check_cache_digest" => library_check::check_cache_digest(&req.params),
            "hash_modpack_file" => library_check::modpack_hashes(&req.params),
            "plan_classpath" => classpath::plan(&req.params),
            "plan_classpath_with_rules" => classpath::plan_with_rules(&req.params),
            "classify_game_exit" => exit_classifier::classify(&req.params),
            "expand_ruled_arguments" | "select_library_rules" => arguments::expand_ruled(&req.params),
            "select_native_libraries" => native_libraries::select(&req.params),
            "plan_native_archives" => native_libraries::plan(&req.params),
            "plan_native_archives_with_rules" => native_libraries::plan_with_rules(&req.params),
            "select_modrinth_files" => modrinth_select::select(&req.params),
            "plan_modrinth_files" => modrinth_select::plan(&req.params),
            "select_modrinth_removed" => modrinth_select::removed(&req.params),
            "remove_modrinth_files" | "remove_managed_files" => managed_remove::remove(&req.params),
            "parse_oauth_callback" => oauth_callback::parse(&req.params),
            "select_curse_files" => curse_select::select(&req.params),
            "plan_curse_downloads" => curse_select::plan(&req.params),
            "expand_arguments" => arguments::expand(&req.params),
            "plan_assets" => assets::plan(&req.params),
            "plan_asset_install" => assets::plan_install(&req.params, progress),
            "extract_zip" => archive::extract_zip(&req.params),
            "read_forge_profiles" => archive::forge_profiles(&req.params),
            "plan_optifine_installer" => archive::optifine_plan(&req.params),
            "identify_optifine_installer" => archive::optifine_identity(&req.params),
            "stage_forge_entries" => archive::stage_forge_entries(&req.params),
            "stage_forge_data" => archive::stage_forge_data(&req.params),
            "extract_zip_prefix" => archive::extract_zip_prefix(&req.params),
            "hash_zip_entries" => archive::hash_entries(&req.params),
            "offline_profile" => offline::profile(&req.params),
            "download" => download::download(&req.params, progress),
            "download_batch" => download::batch(&req.params, progress),
            "sha256" => path_param(&req.params, "path").and_then(|p| hash_file(&p)),
            "scan_instances" => path_param(&req.params, "game_directory").and_then(|p| scan(&p)),
            "list_instance_dirs" => path_param(&req.params, "versions_directory").and_then(|p| list_instance_dirs(&p)),
            "find_invalid_inheritance" => inheritance::find_invalid(&req.params),
            "plan_inheritance" => inheritance::plan(&req.params),
            "merge_manifest" => manifest_merge::merge(&req.params),
            "apply_manifest_patches" => manifest_merge::apply_patches(&req.params, true),
            "apply_manifest_patches_ordered" => manifest_merge::apply_patches(&req.params, false),
            "plan_loader" => loader_plan::plan(&req.params),
            "expand_processor_literals" => forge_literals::expand(&req.params),
            "plan_processor_options" => forge_literals::plan_options(&req.params),
            "processor_main_class" => processor_command::main_class(&req.params),
            "processor_classpath" => processor_command::classpath(&req.params),
            "processor_command" => processor_command::command(&req.params),
            "processor_command_v2" => processor_command::command_v2(&req.params),
            "processor_command_v3" => processor_command::command_v3(&req.params),
            "expand_processor_literals_v2" => forge_literals::expand_v2(&req.params),
            "plan_processor_options_v2" => forge_literals::plan_options_v2(&req.params),
            _ => Err(fail("unknown_method", "unknown method")),
        }
    };
    match result { Ok(result) => Response { version: PROTOCOL, id: req.id, result: Some(result), error: None }, Err(error) => Response { version: PROTOCOL, id: req.id, result: None, error: Some(error) } }
}
/// Enumerates only candidate directories; manifest parsing and publication remain with the caller.
fn list_instance_dirs(path: &Path) -> Result<Value, Error> {
    let entries = fs::read_dir(path).map_err(|e| fail("io_error", e.to_string()))?;
    let mut names = Vec::new(); let mut bytes = 0usize;
    for entry in entries {
        let entry = entry.map_err(|e| fail("io_error", e.to_string()))?;
        if !entry.path().is_dir() { continue; }
        let name = entry.file_name().into_string().map_err(|_| fail("unsupported_path", "non-UTF-8 directory name"))?;
        bytes = bytes.checked_add(name.len()).ok_or_else(|| fail("directory_limit", "too many directory names"))?;
        if names.len() >= 20_000 || bytes > 1024 * 1024 { return Err(fail("directory_limit", "too many directory names")); }
        names.push(name);
    }
    Ok(json!({"directories":names}))
}
fn hash_file(path: &Path) -> Result<Value, Error> {
    let file = File::open(path).map_err(|e| fail("io_error", e.to_string()))?;
    if !file.metadata().map_err(|e| fail("io_error", e.to_string()))?.is_file() { return Err(fail("invalid_file", "path must name a regular file")); }
    let mut reader = BufReader::new(file); let mut hash = Sha256::new(); let mut buf = [0u8; 65536]; let mut size = 0u64;
    loop { let n = reader.read(&mut buf).map_err(|e| fail("io_error", e.to_string()))?; if n == 0 { break; } hash.update(&buf[..n]); size += n as u64; }
    Ok(json!({"sha256": format!("{:x}", hash.finalize()), "bytes_read": size}))
}
fn scan(root: &Path) -> Result<Value, Error> {
    if !root.is_dir() { return Err(fail("invalid_directory", "game directory does not exist or is not a directory")); }
    let versions = root.join("versions");
    if !versions.exists() { return Ok(json!({"instances": [], "warnings": []})); }
    let dirs = fs::read_dir(versions).map_err(|e| fail("io_error", e.to_string()))?;
    let mut instances = Vec::new(); let mut warnings = Vec::new();
    for entry in dirs {
        let entry = match entry { Ok(x) => x, Err(e) => { warnings.push(format!("directory entry: {e}")); continue; } };
        let dir = entry.path(); if !dir.is_dir() { continue; }
        let Some(id) = entry.file_name().to_str().map(str::to_owned) else { warnings.push("non-UTF-8 instance name skipped".to_owned()); continue; };
        if id.trim().is_empty() || id.contains('\\') { warnings.push(format!("invalid instance id: {id}")); continue; }
        let conventional = dir.join(format!("{id}.json"));
        let manifest = if conventional.is_file() { conventional } else {
            let entries = match fs::read_dir(&dir) { Ok(x) => x, Err(e) => { warnings.push(format!("{id}: {e}")); continue; } };
            let mut candidates = Vec::new(); let mut failed = false;
            for item in entries { match item { Ok(e) => { let p=e.path(); if p.is_file() && p.extension().is_some_and(|x| x == "json") { candidates.push(p); } }, Err(e) => { warnings.push(format!("{id}: {e}")); failed=true; } } }
            if failed || candidates.len() != 1 { warnings.push(format!("{id}: no unique manifest")); continue; } candidates.remove(0)
        };
        let read = (|| -> Result<Value, String> { let file=File::open(&manifest).map_err(|e| e.to_string())?; let mut bytes=Vec::new(); file.take(MAX_MANIFEST+1).read_to_end(&mut bytes).map_err(|e| e.to_string())?; if bytes.len() as u64 > MAX_MANIFEST { return Err("manifest exceeds 16 MiB".into()); } serde_json::from_slice(&bytes).map_err(|e| e.to_string()) })();
        match read { Ok(value) if value.is_object() => instances.push(json!({"id":id,"manifest_path":manifest.to_string_lossy(),"manifest_id":value.get("id")})), Ok(_) => warnings.push(format!("{id}: manifest is not an object")), Err(e) => warnings.push(format!("{id}: {e}")) }
    }
    instances.sort_by(|a,b| a["id"].as_str().cmp(&b["id"].as_str())); warnings.sort();
    Ok(json!({"instances":instances, "warnings":warnings}))
}
fn main() -> io::Result<()> {
    let mut arguments = std::env::args_os();
    let _program = arguments.next();
    if let Some(mode) = arguments.next() {
        if mode == "--microsoft-device-child" && arguments.next().is_none() {
            if device_flow::run(false).is_err() { std::process::exit(1); }
            return Ok(());
        }
        if mode == "--microsoft-device-session-child" && arguments.next().is_none() {
            if device_flow::run(true).is_err() { std::process::exit(1); }
            return Ok(());
        }
        if mode == "--oauth-callback-child" && arguments.next().is_none() {
            if oauth_server::run().is_err() { std::process::exit(1); }
            return Ok(());
        }
        if (mode == "--exec-child" || mode == "--exec-child-v2") && arguments.next().is_none() {
            if let Err(error) = exec_child::run(mode == "--exec-child-v2") {
                eprintln!("Native launch failed: {error}");
                std::process::exit(127);
            }
            return Ok(());
        }
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "unknown native backend mode"));
    }
    let stdin=io::stdin(); let mut input=stdin.lock(); let stdout=io::stdout(); let mut output=stdout.lock();
    loop {
        let mut line=Vec::new(); let n=(&mut input).take(MAX_REQUEST+1).read_until(b'\n', &mut line)?;
        if n==0 { break; }
        if line.len() as u64 > MAX_REQUEST { eprintln!("request exceeds 1 MiB; closing protocol stream"); break; }
        let response = match serde_json::from_slice::<Request>(&line) {
            Ok(req) => {
                let id = req.id.clone();
                let mut progress = |downloaded: u64, total: Option<u64>| -> io::Result<()> {
                    serde_json::to_writer(&mut output, &json!({"version":PROTOCOL,"id":id,"event":"progress","downloaded":downloaded,"total":total}))?;
                    output.write_all(b"\n")?;
                    output.flush()
                };
                handle(req, &mut progress)
            },
            Err(e) => Response { version:PROTOCOL, id:Value::Null, result:None, error:Some(fail("invalid_request", e.to_string())) },
        };
        serde_json::to_writer(&mut output, &response)?; output.write_all(b"\n")?; output.flush()?;
    }
    Ok(())
}
