// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
use serde_json::{json, Value};
use std::{fs, io::Write, process::{Command, Stdio}, time::{SystemTime, UNIX_EPOCH}};

fn call(requests: &[Value]) -> Vec<Value> {
    let mut child = Command::new(env!("CARGO_BIN_EXE_mfcl-native"))
        .stdin(Stdio::piped()).stdout(Stdio::piped()).spawn().unwrap();
    { let stdin = child.stdin.as_mut().unwrap(); for req in requests { writeln!(stdin, "{req}").unwrap(); } }
    let out = child.wait_with_output().unwrap();
    assert!(out.status.success());
    String::from_utf8(out.stdout).unwrap().lines().map(|line| serde_json::from_str(line).unwrap()).collect()
}
fn temp() -> std::path::PathBuf {
    let path = std::env::temp_dir().join(format!("mfcl-native-test-{}-{}", std::process::id(), SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()));
    fs::create_dir_all(&path).unwrap(); path
}
#[test]
fn protocol_keeps_ids_and_handles_errors() {
    let output = call(&[
        json!({"version":1,"id":1,"method":"health"}),
        json!({"version":2,"id":"next","method":"health"}),
        json!({"version":1,"id":3,"method":"missing"}),
        json!({"version":1,"id":4,"method":"sha256","params":{}}),
    ]);
    assert_eq!(output.len(), 4); assert_eq!(output[0]["result"]["read_only"], false);
    assert_eq!(output[1]["id"], "next"); assert_eq!(output[1]["error"]["code"], "unsupported_version");
    assert_eq!(output[2]["error"]["code"], "unknown_method"); assert_eq!(output[3]["error"]["code"], "invalid_params");
}

#[test]
fn jvm_defaults_respect_external_overrides_and_java_version() {
    let out = call(&[json!({"version":1,"id":1,"method":"plan_jvm_defaults","params":{
        "raw":["java","-XX:+UseZGC"],"external":["-XX:MaxInlineLevel=4","-Dfml.ignorePatchDiscrepancies=false"],
        "java_version":8,"is_64_bit":false,"memory_large":false,"optimize":true}})]);
    let args = out[0]["result"]["arguments"].as_array().unwrap();
    assert!(!args.contains(&json!("-XX:+UseG1GC"))); assert!(!args.contains(&json!("-XX:MaxInlineLevel=15")));
    assert!(!args.contains(&json!("-Dfml.ignorePatchDiscrepancies=true"))); assert!(args.contains(&json!("-Xss1m")));
}

#[test]
fn jvm_initial_defaults_respect_heap_and_user_overrides() {
    let out = call(&[json!({"version":1,"id":1,"method":"plan_jvm_initial","params":{
        "raw":["java"],"external":["-Xmx1g","-XX:-UseG1GC"],"defaults":["-Xmx2g","-XX:+UseG1GC","-Dcustom=a b"],
        "java_version":17,"min_memory":256,"max_memory":2048,"metaspace":128}})]);
    assert_eq!(out[0]["result"]["arguments"], json!([
        {"argument":"-Xms256m","parse":true},{"argument":"-XX:MetaspaceSize=128m","parse":true},
        {"argument":"-Dcustom=a b","parse":false}]));
}

#[test]
fn jvm_platform_defaults_respect_existing_game_proxy() {
    let out = call(&[json!({"version":1,"id":1,"method":"plan_jvm_platform","params":{
        "raw":["java","-DsocksProxyHost=custom"],"external":[],"client_jar":"/game.jar",
        "dock_name":null,"dock_icon":null,"user_home":"/game","proxy":{"kind":"default"},
        "java_version":17,"is_64_bit":true,"memory_large":false,"optimize":false}})]);
    let args = out[0]["result"]["arguments"].as_array().unwrap();
    assert_eq!(args[0], "-Dminecraft.client.jar=/game.jar");
    assert!(!args.contains(&json!("-Djava.net.useSystemProxies=true")));
}

#[cfg(unix)]
#[test]
fn bootstrap_ignore_matches_filenames_and_preserves_order() {
    let out = call(&[json!({"version":1,"id":1,"method":"plan_bootstrap_ignore","params":{
        "library_directory":"/game/libraries", "classpath":["/game/libraries/org/asm/asm-9.jar", "/other/bootstrap.jar", "/game/libraries/safe.jar"],
        "ignore_lists":["asm,bootstrap,", "", ",,"]}})]);
    assert_eq!(out[0]["result"]["ignore_lists"], json!([
        "${primary_jar},${library_directory}${file_separator}org${file_separator}asm${file_separator}asm-9.jar,/other/bootstrap.jar",
        "${primary_jar},${library_directory}${file_separator}org${file_separator}asm${file_separator}asm-9.jar,/other/bootstrap.jar,${library_directory}${file_separator}safe.jar",
        "${primary_jar}"]));
}

#[test]
fn runtime_paths_allow_internal_links_and_reject_escapes() {
    let requests = [
        json!({"version":1,"id":1,"method":"validate_runtime_paths","params":{"entries":[
            {"path":"legal/base/LICENSE"},{"path":"legal/compiler/LICENSE","target":"../base/LICENSE"}]}}),
        json!({"version":1,"id":2,"method":"validate_runtime_paths","params":{"entries":[{"path":"../outside"}]}}),
        json!({"version":1,"id":3,"method":"validate_runtime_paths","params":{"entries":[{"path":"bin/java","target":"../../outside"}]}}),
        json!({"version":1,"id":4,"method":"validate_runtime_paths","params":{"entries":[{"path":"bin","target":"lib"},{"path":"bin/java"}]}}),
        json!({"version":1,"id":5,"method":"validate_runtime_paths","params":{"entries":[{"path":"a","target":"b"},{"path":"b","target":"c"}]}}),
        json!({"version":1,"id":6,"method":"validate_runtime_paths","params":{"entries":[{"path":"link","target":"alias/../target"}]}}),
    ];
    let out = call(&requests); assert_eq!(out[0]["result"]["validated"], true);
    for entry in &out[1..] { assert_eq!(entry["error"]["code"], "unsafe_runtime_path"); }
}

#[cfg(target_os = "linux")]
#[test]
fn runtime_lzma_stages_verified_output_only() {
    use sha1::{Digest, Sha1};
    let dir = temp(); let source = dir.join("runtime.lzma"); let stage = dir.join("payload"); let probe = dir.join("probe");
    fs::write(&probe, b"").unwrap();
    let data = b"MFCL isolated runtime LZMA fixture";
    let mut compressed = Vec::new();
    lzma_rs::lzma_compress(&mut std::io::Cursor::new(data), &mut compressed).unwrap();
    fs::write(&source, compressed).unwrap(); let hash = format!("{:x}", Sha1::digest(data));
    let request = |id, sha1: &str, size| json!({"version":1,"id":id,"method":"stage_runtime_lzma","params":{
        "source":source,"stage":stage,"probe":probe,"sha1":sha1,"size":size}});
    let output = call(&[request(1, &hash, data.len())]);
    assert_eq!(output[0]["result"]["sha1"], hash); assert_eq!(fs::read(&stage).unwrap(), data);
    fs::remove_file(&stage).unwrap();
    let output = call(&[request(2, "0000000000000000000000000000000000000000", data.len())]);
    assert_eq!(output[0]["error"]["code"], "checksum_mismatch"); assert!(!stage.exists());
    let output = call(&[request(3, &hash, data.len() - 1)]);
    assert!(output[0].get("error").is_some()); assert!(!stage.exists());
    use std::os::fd::AsRawFd;
    let probe_file = fs::File::open(&probe).unwrap();
    let attr = std::ffi::CString::new("user.mfcl-test").unwrap();
    if unsafe { libc::fsetxattr(probe_file.as_raw_fd(), attr.as_ptr(), b"x".as_ptr().cast(), 1, 0) } == 0 {
        let output = call(&[request(5, &hash, data.len())]);
        assert_eq!(output[0]["error"]["code"], "unsupported_file"); assert!(!stage.exists());
        assert_eq!(unsafe { libc::fremovexattr(probe_file.as_raw_fd(), attr.as_ptr()) }, 0);
    }
    fs::write(&source, b"not lzma").unwrap();
    let output = call(&[request(4, &hash, data.len())]);
    assert_eq!(output[0]["error"]["code"], "invalid_archive"); assert!(!stage.exists());
    assert_eq!(fs::read_dir(&dir).unwrap().count(), 2); fs::remove_dir_all(dir).unwrap();
}

#[cfg(target_os = "linux")]
#[test]
fn official_runtime_lzma_sample_matches_published_hash() {
    let Ok(source) = std::env::var("MFCL_RUNTIME_LZMA_SAMPLE") else { return; };
    let dir = temp(); let stage = dir.join("payload"); let probe = dir.join("probe"); fs::write(&probe, b"").unwrap();
    let output = call(&[json!({"version":1,"id":1,"method":"stage_runtime_lzma","params":{
        "source":source,"stage":stage,"probe":probe,"sha1":"7fb67e217c53a685cb9314001592b5bd50b5fbb9","size":647}})]);
    assert_eq!(output[0]["result"]["size"], 647); assert_eq!(fs::metadata(&stage).unwrap().len(), 647);
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn hashes_known_content() {
    let dir=temp(); let path=dir.join("data"); fs::write(&path, b"abc").unwrap();
    let output=call(&[json!({"version":1,"id":1,"method":"sha256","params":{"path":path}})]);
    assert_eq!(output[0]["result"]["sha256"], "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assert_eq!(output[0]["result"]["bytes_read"], 3); fs::remove_dir_all(dir).unwrap();
}

#[cfg(unix)]
#[test]
fn modpack_hashes_in_one_pass_and_rejects_missing_files() {
    let dir = temp(); let file = dir.join("mod.jar"); fs::write(&file, b"abc").unwrap();
    let out = call(&[
        json!({"version":1,"id":1,"method":"hash_modpack_file","params":{"path":file}}),
        json!({"version":1,"id":2,"method":"hash_modpack_file","params":{"path":dir.join("missing")}})
    ]);
    assert_eq!(out[0]["result"]["sha1"], "a9993e364706816aba3e25717850c26c9cd0d89d");
    assert_eq!(out[0]["result"]["sha512"], "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f");
    assert_eq!(out[0]["result"]["size"], 3); assert_eq!(out[1]["result"]["unknown"], true);
    fs::remove_dir_all(dir).unwrap();
}

#[cfg(target_os = "linux")]
#[test]
fn cache_copy_stages_only_verified_content() {
    let dir = temp(); let source = dir.join("source"); let stage = dir.join("payload");
    fs::write(&source, b"abc").unwrap();
    let hash = call(&[json!({"version":1,"id":9,"method":"hash_cache_sha1","params":{"path":source}})]);
    assert_eq!(hash[0]["result"]["sha1"], "a9993e364706816aba3e25717850c26c9cd0d89d");
    let request = |id, digest: &str| json!({"version":1,"id":id,"method":"stage_cache_copy","params":{
        "source":source,"stage":stage,"algorithm":"SHA-1","digest":digest}});
    let out = call(&[request(1, "0000000000000000000000000000000000000000")]);
    assert_eq!(out[0]["result"]["unknown"], true); assert!(!stage.exists());
    let out = call(&[request(2, "a9993e364706816aba3e25717850c26c9cd0d89d")]);
    assert_eq!(out[0]["result"]["size"], 3); assert!(out[0]["result"]["stage_inode"].as_i64().is_some());
    assert_eq!(fs::read(&stage).unwrap(), b"abc"); fs::remove_file(&stage).unwrap();
    for (algorithm, digest) in [("SHA-256", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            ("MD5", "900150983cd24fb0d6963f7d28e17f72")] {
        let out = call(&[json!({"version":1,"id":10,"method":"check_cache_digest","params":{
            "path":source,"algorithm":algorithm,"digest":digest}}),
            json!({"version":1,"id":11,"method":"stage_cache_copy","params":{
                "source":source,"stage":stage,"algorithm":algorithm,"digest":digest}})]);
        assert_eq!(out[0]["result"]["matches"], true); assert_eq!(fs::read(&stage).unwrap(), b"abc");
        assert_eq!(out[1]["result"]["size"], 3); fs::remove_file(&stage).unwrap();
    }
    use std::os::fd::AsRawFd;
    let file = fs::File::open(&source).unwrap();
    let name = std::ffi::CString::new("user.mfcl-test").unwrap();
    if unsafe { libc::fsetxattr(file.as_raw_fd(), name.as_ptr(), b"x".as_ptr().cast(), 1, 0) } == 0 {
        let out = call(&[request(3, "a9993e364706816aba3e25717850c26c9cd0d89d")]);
        assert_eq!(out[0]["result"]["unknown"], true); assert!(!stage.exists());
    }
    fs::remove_dir_all(dir).unwrap();
}

#[cfg(unix)]
#[test]
fn asset_install_plan_inspects_existing_and_missing_files_without_writes() {
    use sha1::{Digest, Sha1};
    let dir = temp(); let root = dir.join("assets"); let index = dir.join("index.json");
    let good = format!("{:x}", Sha1::digest(b"abc"));
    let bad = format!("{:x}", Sha1::digest(b"expected"));
    let missing = format!("{:x}", Sha1::digest(b"missing"));
    for (hash, content) in [(&good, b"abc".as_slice()), (&bad, b"wrong".as_slice())] {
        let folder = root.join("objects").join(&hash[..2]); fs::create_dir_all(&folder).unwrap(); fs::write(folder.join(hash), content).unwrap();
    }
    fs::write(&index, json!({"objects":{"a":{"hash":good,"size":3},"b":{"hash":good,"size":3},
        "c":{"hash":bad,"size":8},"d":{"hash":missing,"size":7}}}).to_string()).unwrap();
    let out = call(&[json!({"version":1,"id":1,"method":"plan_asset_install","params":{
        "index_path":index,"asset_directory":root,"integrity":true}})]);
    let progress = out.iter().filter(|frame| frame.get("event") == Some(&json!("progress"))).last().unwrap();
    assert_eq!(progress["downloaded"], 4);
    let entries = out.last().unwrap()["result"]["objects"].as_array().unwrap(); assert_eq!(entries.len(), 4);
    let matched = |hash: &str| entries.iter().find(|entry| entry["hash"] == hash).unwrap();
    assert_eq!(matched(&good)["inspection"]["observation"]["matches"], true);
    assert_eq!(matched(&bad)["inspection"]["observation"]["matches"], false);
    assert_eq!(matched(&missing)["inspection"]["state"], "missing");
    assert!(!root.join("objects").join(&missing[..2]).join(&missing).exists());
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn asset_plan_rejects_duplicate_json_keys_for_java_fallback() {
    let dir = temp(); let index = dir.join("index.json");
    fs::write(&index, r#"{"objects":{"entry":{"hash":"0000000000000000000000000000000000000000","hash":"a9993e364706816aba3e25717850c26c9cd0d89d","size":3}}}"#).unwrap();
    let out = call(&[json!({"version":1,"id":1,"method":"plan_assets","params":{"index_path":index}})]);
    assert_eq!(out[0]["error"]["code"], "invalid_index"); fs::remove_dir_all(dir).unwrap();
}

#[cfg(unix)]
#[test]
fn index_and_archive_operations_reject_fifo_without_waiting_for_a_writer() {
    use std::os::unix::ffi::OsStrExt;
    let dir = temp(); let fifo = dir.join("input");
    let name = std::ffi::CString::new(fifo.as_os_str().as_bytes()).unwrap();
    assert_eq!(unsafe { libc::mkfifo(name.as_ptr(), 0o600) }, 0);
    let frames = call(&[
        json!({"version":1,"id":1,"method":"plan_assets","params":{"index_path":fifo}}),
        json!({"version":1,"id":2,"method":"hash_zip_entries","params":{"archive":fifo,"entries":[]}}),
        json!({"version":1,"id":3,"method":"extract_zip","params":{"archive":fifo,"staging_parent":dir}}),
    ]);
    assert_eq!(frames.len(), 3);
    for frame in frames { assert_eq!(frame["error"]["code"], "invalid_params"); }
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn processor_literals_expand_variables_escapes_and_artifacts() {
    let output = call(&[json!({"version":1,"id":1,"method":"expand_processor_literals","params":{
        "literals":["{SIDE}","{missing}","'literal'","'","[g:a:1]","prefix-{SIDE}-'x'","a\\{SIDE\\}","v-{empty}","中文 🦀"],
        "variables":{"SIDE":"client","empty":null},"artifacts":{"[g:a:1]":"/tmp/a jar"}}})]);
    assert_eq!(output[0]["result"]["values"], json!(["client",null,"literal","'","/tmp/a jar","prefix-client-x","a{SIDE}","v-null","中文 🦀"]));
}

#[test]
fn processor_literals_resolve_canonical_artifacts_in_rust() {
    let root = temp();
    let request = json!({"version":1,"id":1,"method":"expand_processor_literals_v2","params":{
        "artifact_root":root,"literals":["[com.example:lib:1.0]","[org.demo:core:2:client@zip]","{SIDE}"],
        "variables":{"SIDE":"client"}}});
    let output = call(&[request]);
    let values = output[0]["result"]["values"].as_array().unwrap();
    assert_eq!(values[0],root.join("com/example/lib/1.0/lib-1.0.jar").to_str().unwrap());
    assert_eq!(values[1],root.join("org/demo/core/2/core-2-client.zip").to_str().unwrap());
    assert_eq!(values[2],"client");
    let invalid = call(&[json!({"version":1,"id":2,"method":"expand_processor_literals_v2","params":{
        "artifact_root":root,"literals":["[bad/group:lib:1]"],"variables":{}}})]);
    assert_eq!(invalid[0]["error"]["code"],"unsupported_metadata");
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn processor_literal_errors_preserve_protocol_stream() {
    let params = |literal| json!({"literals":[literal],"variables":{},"artifacts":{}});
    let output = call(&[
        json!({"version":1,"id":1,"method":"expand_processor_literals","params":params("prefix-{missing}")}),
        json!({"version":1,"id":2,"method":"expand_processor_literals","params":params("bad\\")}),
        json!({"version":1,"id":3,"method":"expand_processor_literals","params":params("[g:a:1]")}),
        json!({"version":1,"id":4,"method":"health"})]);
    assert_eq!(output[0]["error"]["code"], "invalid_literal");
    assert_eq!(output[1]["error"]["code"], "invalid_literal");
    assert_eq!(output[2]["error"]["code"], "invalid_params");
    assert_eq!(output[3]["result"]["protocol"], 1);
}

#[test]
fn processor_literal_limits_intermediate_expansion() {
    let output = call(&[json!({"version":1,"id":1,"method":"expand_processor_literals","params":{
        "literals":[format!("x{}", "{large}".repeat(300))],
        "variables":{"large":"a".repeat(16_000)},"artifacts":{}}})]);
    assert_eq!(output[0]["error"]["code"], "argument_limit");
}

#[test]
fn processor_options_preserve_order_duplicates_empty_and_null_values() {
    let output = call(&[json!({"version":1,"id":1,"method":"plan_processor_options","params":{
        "groups":[["ignored-{missing}","--task","'DOWNLOAD_MOJMAPS'","--side","{SIDE}","--flag","--dup","first","--dup","second","--missing","{missing}","--path","[g:a:1]","--trailing"],[]],
        "variables":{"SIDE":"client"},"artifacts":{"[g:a:1]":"/tmp/a jar"}}})]);
    assert_eq!(output[0]["result"]["groups"],json!([[{"name":"task","value":"DOWNLOAD_MOJMAPS"},
        {"name":"side","value":"client"},{"name":"flag","value":""},{"name":"dup","value":"first"},
        {"name":"dup","value":"second"},{"name":"missing","value":null},{"name":"path","value":"/tmp/a jar"},
        {"name":"trailing","value":""}],[]]));
    let invalid = call(&[json!({"version":1,"id":1,"method":"plan_processor_options","params":{
        "groups":[["--value","prefix-{missing}"]],"variables":{},"artifacts":{}}})]);
    assert_eq!(invalid[0]["error"]["code"],"invalid_literal");
}

#[test]
fn processor_options_resolve_artifact_paths_without_expanding_ignored_tokens() {
    let root = temp();
    let output = call(&[json!({"version":1,"id":1,"method":"plan_processor_options_v2","params":{
        "artifact_root":root,"groups":[["ignored-{missing}","--path","[com.demo:core:1:client@zip]","--flag"],[]],"variables":{}}})]);
    assert_eq!(output[0]["result"]["groups"], json!([[{"name":"path","value":root.join("com/demo/core/1/core-1-client.zip").to_str().unwrap()},
        {"name":"flag","value":""}],[]]));
    fs::remove_dir_all(root).unwrap();
}

#[test]
fn yggdrasil_rejects_unsupported_schemes_and_redacts_credentials() {
    let output = call(&[json!({"version":1,"id":1,"method":"yggdrasil_request","params":{
        "url":"ftp://127.0.0.1:1/auth","body":"fixture-secret","user_agent":"MFCL","language":"en-US"}})]);
    assert_eq!(output[0]["error"]["code"], "invalid_params");
    assert!(!output[0].to_string().contains("fixture-secret"));
}

#[test]
fn yggdrasil_session_plan_preserves_nested_json_and_classifies_responses() {
    let nested = r#"{"id":"0123456789abcdef0123456789abcdef","name":"old","name":"Steve","extra":1e2}"#;
    let response = format!(r#"{{"clientToken":"fixture-client","accessToken":"fixture-access","selectedProfile":{nested}}}"#);
    let output = call(&[
        json!({"version":1,"id":1,"method":"yggdrasil_plan_response","params":{"response":response,"client_token":"fixture-client"}}),
        json!({"version":1,"id":2,"method":"yggdrasil_plan_response","params":{"response":"{\"error\":\"ForbiddenOperationException\",\"errorMessage\":\"expired\"}","client_token":"fixture-client"}}),
        json!({"version":1,"id":3,"method":"yggdrasil_plan_response","params":{"response":"{\"clientToken\":\"other\"}","client_token":"fixture-client"}}),
        json!({"version":1,"id":4,"method":"yggdrasil_plan_response","params":{"response":"{\"clientToken\":\"fixture-client\",\"clientToken\":\"secret\"}","client_token":"fixture-client"}}),
        json!({"version":1,"id":5,"method":"yggdrasil_plan_response","params":{"response":
            "{\"clientToken\":\"fixture-client\",\"selectedProfile\":{\"id\":\"0123456789ABCDEF0123456789ABCDEF\",\"name\":\"Alex\"},\"availableProfiles\":[null,{\"id\":\"01234567-89ab-cdef-0123-456789abcdef\",\"name\":\"Steve\"}],\"user\":{\"id\":\"fixture\",\"properties\":[{\"name\":\"key\",\"value\":\"first\"},{\"name\":\"key\",\"value\":\"second\"}]}}",
            "client_token":"fixture-client"}})]);
    assert_eq!(output[0]["result"]["outcome"], "ok"); assert_eq!(output[0]["result"]["selected_profile"], nested);
    assert_eq!(output[0]["result"]["selected_decoded"]["state"], "fallback");
    assert_eq!(output[1]["result"]["outcome"], "remote_error");
    assert_eq!(output[2]["result"]["outcome"], "token_mismatch");
    assert_eq!(output[3]["error"]["code"], "unsupported_response"); assert!(!output[3].to_string().contains("secret"));
    assert_eq!(output[4]["result"]["selected_decoded"]["decoded"]["id"], "01234567-89ab-cdef-0123-456789abcdef");
    assert_eq!(output[4]["result"]["available_decoded"]["decoded"][0], Value::Null);
    assert_eq!(output[4]["result"]["user_decoded"]["decoded"]["properties"].as_array().unwrap().len(), 2);
}

#[test]
fn yggdrasil_complete_profile_plans_ordered_properties() {
    let response = r#"{"id":"0123456789ABCDEF0123456789ABCDEF","name":"Alex","properties":[null,7,{"name":"textures","value":"first"},{"name":"other","value":"middle"},{"name":"textures","value":"second"}]}"#;
    let output = call(&[
        json!({"version":1,"id":1,"method":"yggdrasil_plan_profile","params":{"response":response}}),
        json!({"version":1,"id":2,"method":"yggdrasil_plan_profile","params":{"response":"{\"id\":\"secret\",\"name\":\"Alex\",\"properties\":null}"}})]);
    assert_eq!(output[0]["result"]["profile"]["id"], "01234567-89ab-cdef-0123-456789abcdef");
    assert_eq!(output[0]["result"]["profile"]["properties"].as_array().unwrap().len(), 3);
    assert_eq!(output[1]["error"]["code"], "unsupported_response"); assert!(!output[1].to_string().contains("secret"));
}

#[test]
fn yggdrasil_texture_plan_preserves_order_and_rejects_unknown_types() {
    use base64::{engine::general_purpose::STANDARD, Engine};
    let textures = STANDARD.encode(r#"{"textures":{"SKIN":{"url":"https://example.invalid/skin","metadata":{"model":"slim","optional":null}},"CAPE":null}}"#);
    let unsupported = STANDARD.encode(r#"{"textures":{"ELYTRA":{"url":"https://example.invalid"}}}"#);
    let absent = STANDARD.encode(r#"{"timestamp":1}"#);
    let output = call(&[
        json!({"version":1,"id":1,"method":"yggdrasil_plan_textures","params":{"textures":textures}}),
        json!({"version":1,"id":2,"method":"yggdrasil_plan_textures","params":{"textures":unsupported}}),
        json!({"version":1,"id":3,"method":"yggdrasil_plan_textures","params":{"textures":absent}})]);
    assert_eq!(output[0]["result"]["present"], true);
    assert_eq!(output[0]["result"]["textures"][0]["type"], "SKIN");
    assert_eq!(output[0]["result"]["textures"][0]["texture"]["metadata"][1]["value"], Value::Null);
    assert_eq!(output[0]["result"]["textures"][1]["texture"], Value::Null);
    assert_eq!(output[1]["error"]["code"], "unsupported_response");
    assert_eq!(output[2]["result"]["present"], false);
}
#[test]
fn scans_conventional_fallback_and_bad_manifests_without_modifying_files() {
    let dir=temp(); let versions=dir.join("versions");
    for id in ["alpha", "beta", "bad", "ambiguous"] { fs::create_dir_all(versions.join(id)).unwrap(); }
    fs::write(versions.join("alpha/alpha.json"), r#"{"id":"another"}"#).unwrap();
    fs::write(versions.join("beta/custom.json"), r#"{"id":"beta"}"#).unwrap();
    fs::write(versions.join("bad/bad.json"), b"{").unwrap();
    fs::write(versions.join("ambiguous/a.json"), b"{}").unwrap(); fs::write(versions.join("ambiguous/b.json"), b"{}").unwrap();
    let output=call(&[json!({"version":1,"id":"scan","method":"scan_instances","params":{"game_directory":dir}})]);
    let found=output[0]["result"]["instances"].as_array().unwrap();
    assert_eq!(found.len(), 2); assert_eq!(found[0]["id"], "alpha"); assert_eq!(found[0]["manifest_id"], "another"); assert_eq!(found[1]["id"], "beta");
    assert_eq!(output[0]["result"]["warnings"].as_array().unwrap().len(), 2);
    assert_eq!(fs::read_to_string(versions.join("alpha/alpha.json")).unwrap(), r#"{"id":"another"}"#);
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn missing_directory_returns_an_error_without_creating_it() {
    let parent = temp();
    let missing = parent.join("missing");
    let output = call(&[json!({"version":1,"id":8,"method":"scan_instances","params":{"game_directory":missing}})]);
    assert_eq!(output[0]["error"]["code"], "invalid_directory");
    assert!(!missing.exists());
    fs::remove_dir_all(parent).unwrap();
}

#[test]
fn instance_directory_enumeration_lists_only_directories() {
    let dir = temp();
    fs::create_dir(dir.join("alpha")).unwrap(); fs::create_dir(dir.join("玩家")).unwrap();
    fs::write(dir.join("not-a-directory"), b"x").unwrap();
    let output = call(&[json!({"version":1,"id":1,"method":"list_instance_dirs","params":{"versions_directory":dir}})]);
    let names = output[0]["result"]["directories"].as_array().unwrap();
    assert_eq!(names.len(), 2); assert!(names.contains(&json!("alpha"))); assert!(names.contains(&json!("玩家")));
    fs::remove_dir_all(dir).unwrap();
}

#[cfg(unix)]
#[test]
fn instance_directory_enumeration_rejects_non_utf8_without_partial_results() {
    use std::{ffi::OsString, os::unix::ffi::OsStringExt};
    let dir = temp(); fs::create_dir(dir.join(OsString::from_vec(vec![0xff]))).unwrap();
    let output = call(&[json!({"version":1,"id":1,"method":"list_instance_dirs","params":{"versions_directory":dir}})]);
    assert_eq!(output[0]["error"]["code"], "unsupported_path");
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn inheritance_prunes_missing_chains_but_keeps_cycles() {
    let instances = json!([
        {"id":"base","parent":null}, {"id":"child","parent":"base"},
        {"id":"orphan","parent":"absent"}, {"id":"grandchild","parent":"orphan"},
        {"id":"cycle-a","parent":"cycle-b"}, {"id":"cycle-b","parent":"cycle-a"},
        {"id":"cycle-child","parent":"cycle-a"}, {"id":"self","parent":"self"}
    ]);
    let out = call(&[json!({"version":1,"id":1,"method":"find_invalid_inheritance","params":{"instances":instances}})]);
    assert_eq!(out[0]["result"]["invalid"], json!(["orphan", "grandchild"]));
    let duplicate = call(&[json!({"version":1,"id":1,"method":"find_invalid_inheritance","params":{"instances":[{"id":"a"},{"id":"a"}]}})]);
    assert_eq!(duplicate[0]["error"]["code"], "invalid_params");
}

#[test]
fn inheritance_plan_handles_roots_cycles_and_missing_parents() {
    let instances = json!([{"id":"base","parent":null},{"id":"child","parent":"base"},
        {"id":"a","parent":"b"},{"id":"b","parent":"a"}]);
    for (start, chain, cycle) in [
        (json!({"id":"child","parent":"base"}), json!(["child","base"]), false),
        (json!({"id":"a","parent":"b"}), json!(["a","b","a"]), true),
        (json!({"id":"a","parent":"base"}), json!(["a","base"]), false),
    ] {
        let out = call(&[json!({"version":1,"id":1,"method":"plan_inheritance","params":{"start":start,"instances":instances}})]);
        assert_eq!(out[0]["result"]["chain"], chain); assert_eq!(out[0]["result"]["cycle"], cycle);
    }
    let out = call(&[json!({"version":1,"id":1,"method":"plan_inheritance","params":{"start":{"id":"x","parent":"missing"},"instances":instances}})]);
    assert_eq!(out[0]["error"]["code"], "missing_parent");
}

#[test]
fn manifest_merge_preserves_field_and_collection_order() {
    let base = json!({"id":1,"minecraft_arguments":2,"main_class":3,"jar":4,"asset_index":null,"assets":null,
        "compliance":5,"java_version":null,"downloads":6,"logging":null,"type":null,"time":null,"release_time":null,
        "hidden":null,"self_patch":null,"minimum":14,"libraries":[7,8],"rules":[9],"patches":[10],
        "arguments":{"game":[11],"jvm":null}});
    let child = json!({"id":12,"minecraft_arguments":null,"main_class":13,"jar":null,"asset_index":null,"assets":null,
        "compliance":null,"java_version":null,"downloads":null,"logging":null,"type":null,"time":null,"release_time":null,
        "hidden":14,"self_patch":15,"minimum":5,"libraries":[16],"rules":[17],"patches":[18],
        "arguments":{"game":[19],"jvm":[20]}});
    let out=call(&[json!({"version":1,"id":1,"method":"merge_manifest","params":{"child":child,"parent":base}})]);
    let result=&out[0]["result"];
    assert_eq!(result["id"],12); assert_eq!(result["main_class"],13); assert_eq!(result["minecraft_arguments"],2);
    assert!(result["compliance"].is_null()); assert_eq!(result["minimum"],14);
    assert_eq!(result["libraries"],json!([16,7,8])); assert_eq!(result["rules"],json!([9,17]));
    assert_eq!(result["patches"],json!([10,15,18])); assert_eq!(result["arguments"]["game"],json!([11,19]));
    assert_eq!(result["arguments"]["jvm"],json!([20]));
}

#[test]
fn manifest_patches_apply_stable_priority_and_append_collections() {
    let base = json!({"id":1,"minecraft_arguments":null,"main_class":2,"asset_index":null,"assets":null,"compliance":null,
        "java_version":null,"downloads":null,"logging":null,"type":null,"time":null,"release_time":null,
        "minimum":8,"libraries":[3],"rules":[4],"arguments":null});
    let patch = |priority, main, library| json!({"priority":priority,"minecraft_arguments":null,"main_class":main,
        "asset_index":null,"assets":null,"compliance":null,"java_version":null,"downloads":null,"logging":null,
        "type":null,"time":null,"release_time":null,"minimum":null,"libraries":[library],"rules":null,"arguments":null});
    let out = call(&[json!({"version":1,"id":1,"method":"apply_manifest_patches","params":{
        "base":base,"patches":[patch(20,5,6),patch(10,7,8),patch(20,9,10)]}})]);
    let result=&out[0]["result"];
    assert_eq!(result["main_class"],9); assert_eq!(result["libraries"],json!([3,8,6,10]));
    assert_eq!(result["rules"],json!([4])); assert_eq!(result["minimum"],8);
    let ordered = call(&[json!({"version":1,"id":1,"method":"apply_manifest_patches_ordered","params":{
        "base":base,"patches":[patch(20,5,6),patch(10,7,8),patch(20,9,10)]}})]);
    assert_eq!(ordered[0]["result"]["libraries"],json!([3,6,8,10]));
}

#[test]
fn fabric_family_loader_plan_preserves_library_and_coordinate_order() {
    let metadata = json!({"loader":{"maven":"net.fabricmc:fabric-loader:0.15.11"},
        "intermediary":{"maven":"net.fabricmc:intermediary:1.20.1"},
        "launcherMeta":{"mainClass":{"client":"net.fabricmc.loader.impl.launch.knot.KnotClient"},
            "launchwrapper":{"tweakers":{"client":["example.Tweaker"]}},
            "libraries":{"common":[{"name":"org.test:first:1"}],"server":[{"name":"org.test:second:2"}],"client":[{"name":"org.test:ignored:3"}]}}});
    for kind in ["fabric", "legacyfabric", "quilt"] {
        let out = call(&[json!({"version":1,"id":1,"method":"plan_loader","params":{"kind":kind,"metadata":metadata.to_string()}})]);
        let plan = &out[0]["result"];
        assert_eq!(plan["main_class"],"net.fabricmc.loader.impl.launch.knot.KnotClient");
        assert_eq!(plan["tweaker"],"example.Tweaker");
        assert_eq!(plan["libraries"].as_array().unwrap().len(),2);
        assert_eq!(plan["libraries"][0]["name"],"org.test:first:1");
        assert_eq!(plan["libraries"][1]["name"],"org.test:second:2");
        assert_eq!(plan["coordinates"],json!(["net.fabricmc:intermediary:1.20.1","net.fabricmc:fabric-loader:0.15.11"]));
    }
}

#[test]
fn quilt_loader_allows_missing_intermediary_and_rejects_floats() {
    let metadata = json!({"loader":{"maven":"org.quiltmc:quilt-loader:1"},
        "launcherMeta":{"mainClass":"example.Main","libraries":{"common":[],"server":[]}}});
    let out = call(&[json!({"version":1,"id":1,"method":"plan_loader","params":{"kind":"quilt","metadata":metadata.to_string()}})]);
    assert_eq!(out[0]["result"]["coordinates"],json!(["org.quiltmc:quilt-loader:1"]));
    let mut bad=metadata.clone(); bad["launcherMeta"]["libraries"]["common"]=json!([{"name":"a:b:c","size":1.5}]);
    let out=call(&[json!({"version":1,"id":1,"method":"plan_loader","params":{"kind":"quilt","metadata":bad.to_string()}})]);
    assert_eq!(out[0]["error"]["code"],"invalid_metadata");
}
#[test]
fn malformed_request_does_not_break_following_requests() {
    let mut child = Command::new(env!("CARGO_BIN_EXE_mfcl-native"))
        .stdin(Stdio::piped()).stdout(Stdio::piped()).spawn().unwrap();
    { let input = child.stdin.as_mut().unwrap(); writeln!(input, "{{bad json").unwrap(); writeln!(input, "{}", json!({"version":1,"id":7,"method":"health"})).unwrap(); }
    let output = child.wait_with_output().unwrap();
    let lines: Vec<Value> = String::from_utf8(output.stdout).unwrap().lines().map(|line| serde_json::from_str(line).unwrap()).collect();
    assert_eq!(lines[0]["error"]["code"], "invalid_request");
    assert_eq!(lines[1]["id"], 7); assert_eq!(lines[1]["result"]["protocol"], 1);
}
#[test]
fn oversized_request_closes_the_stream() {
    let mut child = Command::new(env!("CARGO_BIN_EXE_mfcl-native"))
        .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().unwrap();
    { let input = child.stdin.as_mut().unwrap(); input.write_all(&vec![b'x'; 1024 * 1024 + 1]).unwrap(); }
    let output = child.wait_with_output().unwrap();
    assert!(output.status.success());
    assert!(output.stdout.is_empty());
    assert!(String::from_utf8(output.stderr).unwrap().contains("exceeds 1 MiB"));
}
#[test]
fn oversized_manifest_is_skipped() {
    let dir = temp(); let version_dir = dir.join("versions/large"); fs::create_dir_all(&version_dir).unwrap();
    let file = fs::File::create(version_dir.join("large.json")).unwrap(); file.set_len(16 * 1024 * 1024 + 1).unwrap();
    let output=call(&[json!({"version":1,"id":1,"method":"scan_instances","params":{"game_directory":dir}})]);
    assert!(output[0]["result"]["instances"].as_array().unwrap().is_empty());
    assert!(output[0]["result"]["warnings"][0].as_str().unwrap().contains("exceeds 16 MiB"));
    fs::remove_dir_all(dir).unwrap();
}

/// Serves a single synthetic HTTP response on loopback for an isolated download test.
fn serve(response: &'static [u8]) -> (String, std::thread::JoinHandle<()>) {
    use std::io::Read;
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let thread = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap();
        let mut buffer = [0; 2048];
        let _ = socket.read(&mut buffer);
        let _ = socket.write_all(response);
    });
    (format!("http://{address}/file"), thread)
}

#[test]
fn download_publishes_only_verified_content_without_overwrite() {
    let dir = temp(); let target = dir.join("file.jar");
    let hash = "a9993e364706816aba3e25717850c26c9cd0d89d";
    let (url, server) = serve(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc");
    let request = json!({"version":1,"id":10,"method":"download","params":{"url":url,"destination":target,"sha1":hash,"max_bytes":3,"allow_http_loopback":true}});
    let output = call(&[request]); server.join().unwrap();
    assert_eq!(output[0]["result"]["bytes_written"], 3); assert_eq!(fs::read(&target).unwrap(), b"abc");
    let (url, server) = serve(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc");
    let output = call(&[json!({"version":1,"id":11,"method":"download","params":{"url":url,"destination":target,"sha1":hash,"max_bytes":3,"allow_http_loopback":true}})]); server.join().unwrap();
    assert_eq!(output[0]["error"]["code"], "publish_failed"); assert_eq!(fs::read(&target).unwrap(), b"abc");
    assert_eq!(fs::read_dir(&dir).unwrap().count(), 1); fs::remove_dir_all(dir).unwrap();
}

#[test]
fn download_sha512_verifies_before_publication() {
    let dir = temp();
    let digest = "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f";
    for (name, hash, succeeds) in [("valid", digest.to_owned(), true), ("invalid", "0".repeat(128), false)] {
        let target = dir.join(name);
        let (url, server) = serve(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc");
        let output = call(&[json!({"version":1,"id":1,"method":"download","params":{
            "url":url,"destination":target,"sha512":hash,"max_bytes":3,"allow_http_loopback":true}})]);
        server.join().unwrap();
        if succeeds {
            assert_eq!(output[0]["result"]["sha512"], digest);
            assert_eq!(fs::read(target).unwrap(), b"abc");
        } else {
            assert_eq!(output[0]["error"]["code"], "checksum_mismatch");
            assert!(!target.exists());
        }
    }
    assert_eq!(fs::read_dir(&dir).unwrap().count(), 1); fs::remove_dir_all(dir).unwrap();
}

#[test]
fn download_ignores_environment_proxy_for_direct_route() {
    use std::{io::Read, thread, time::{Duration, Instant}};
    let dir = temp(); let target = dir.join("file");
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let address = listener.local_addr().unwrap();
    let server = thread::spawn(move || {
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            match listener.accept() {
                Ok((mut socket, _)) => {
                    let mut buffer = [0; 2048]; let _ = socket.read(&mut buffer);
                    socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc").unwrap();
                    return true;
                },
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock && Instant::now() < deadline => thread::sleep(Duration::from_millis(10)),
                _ => return false,
            }
        }
    });
    let mut child = Command::new(env!("CARGO_BIN_EXE_mfcl-native"))
        .env("HTTP_PROXY", "http://127.0.0.1:1").env("http_proxy", "http://127.0.0.1:1")
        .env("HTTPS_PROXY", "http://127.0.0.1:1").env("https_proxy", "http://127.0.0.1:1")
        .env("ALL_PROXY", "http://127.0.0.1:1").env("all_proxy", "http://127.0.0.1:1")
        .env("NO_PROXY", "").env("no_proxy", "")
        .stdin(Stdio::piped()).stdout(Stdio::piped()).spawn().unwrap();
    let request = json!({"version":1,"id":1,"method":"download","params":{"url":format!("http://{address}/file"),"destination":target,"sha1":"a9993e364706816aba3e25717850c26c9cd0d89d","max_bytes":3,"allow_http_loopback":true}});
    writeln!(child.stdin.as_mut().unwrap(), "{request}").unwrap();
    let output = child.wait_with_output().unwrap();
    assert!(server.join().unwrap());
    let response: Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(response["result"]["bytes_written"], 3);
    assert_eq!(fs::read(target).unwrap(), b"abc"); fs::remove_dir_all(dir).unwrap();
}

/// Runs a bounded loopback HTTP origin supporting valid, ignored or corrupt byte ranges.
fn range_origin(data: Vec<u8>, mode: &'static str) -> (String, std::sync::Arc<std::sync::atomic::AtomicBool>, std::sync::Arc<std::sync::atomic::AtomicUsize>, std::thread::JoinHandle<()>) {
    use std::{io::Read, sync::{Arc, atomic::{AtomicBool, AtomicUsize, Ordering}}, thread, time::{Duration, Instant}};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap(); listener.set_nonblocking(true).unwrap();
    let address = listener.local_addr().unwrap(); let stop = Arc::new(AtomicBool::new(false));
    let ranges = Arc::new(AtomicUsize::new(0)); let worker_stop = stop.clone(); let worker_ranges = ranges.clone();
    let plain = Arc::new(AtomicUsize::new(0));
    let data = Arc::new(data);
    let worker = thread::spawn(move || {
        let deadline = Instant::now() + Duration::from_secs(15); let mut handlers = Vec::new();
        while !worker_stop.load(Ordering::Relaxed) && Instant::now() < deadline {
            match listener.accept() {
                Ok((mut socket, _)) => {
                    let data = data.clone(); let ranges = worker_ranges.clone(); let plain = plain.clone();
                    handlers.push(thread::spawn(move || {
                        let mut request = Vec::new(); let mut buffer = [0u8; 1024];
                        while !request.windows(4).any(|chunk| chunk == b"\r\n\r\n") && request.len() < 8192 {
                            match socket.read(&mut buffer) { Ok(0) | Err(_) => return, Ok(n) => request.extend_from_slice(&buffer[..n]) }
                        }
                        let request = String::from_utf8_lossy(&request); let mut range = None;
                        for line in request.lines() {
                            if let Some((name, value)) = line.split_once(':') {
                                if name.eq_ignore_ascii_case("range") {
                                    range = value.trim().strip_prefix("bytes=").and_then(|s| s.split_once('-'))
                                        .and_then(|(a,b)| Some((a.parse::<usize>().ok()?,b.parse::<usize>().ok()?)));
                                }
                            }
                        }
                        if let Some((start, end)) = range {
                            ranges.fetch_add(1, Ordering::Relaxed);
                            let request_lower = request.to_ascii_lowercase();
                            if mode == "unquoted-etag" {
                                if request_lower.contains("if-range:") { return; }
                            } else if !request_lower.contains("if-range: \"fixture\"") { return; }
                            if mode == "resume-fail" && end + 1 == data.len() {
                                thread::sleep(Duration::from_millis(300));
                                let _ = socket.write_all(b"HTTP/1.1 500 Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"); return;
                            }
                            if mode != "ignore" {
                                let mut body = data[start..=end].to_vec(); if mode == "corrupt" && start == 0 { body[0] ^= 1; }
                                let declared_start = if mode == "bad-range" { start + 1 } else { start };
                                let headers = format!("HTTP/1.1 206 Partial Content\r\nContent-Length: {}\r\nContent-Range: bytes {}-{}/{}\r\nConnection: close\r\n\r\n", body.len(),declared_start,end,data.len());
                                let _ = socket.write_all(headers.as_bytes()); let _ = socket.write_all(&body); return;
                            }
                        }
                        if range.is_none() && plain.fetch_add(1, Ordering::Relaxed) > 0 && mode == "resume-fail" {
                            let _ = socket.write_all(b"HTTP/1.1 500 Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"); return;
                        }
                        let etag = if mode == "unquoted-etag" { "0xunquoted" } else { "\"fixture\"" };
                        let headers = format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nAccept-Ranges: bytes\r\nETag: {}\r\nConnection: close\r\n\r\n",data.len(),etag);
                        let _ = socket.write_all(headers.as_bytes()); let _ = socket.write_all(&data);
                    }));
                },
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => thread::sleep(Duration::from_millis(5)),
                Err(_) => break,
            }
        }
        for handler in handlers { let _ = handler.join(); }
    });
    (format!("http://{address}/file"),stop,ranges,worker)
}

#[test]
fn parallel_ranges_verify_and_publish_exact_content() {
    use sha1::{Digest, Sha1}; use std::sync::atomic::Ordering;
    let data: Vec<u8> = (0..(1024 * 1024 + 137)).map(|i| (i % 251) as u8).collect();
    let hash = format!("{:x}", Sha1::digest(&data)); let dir=temp(); let target=dir.join("output");
    let (url, stop, ranges, server) = range_origin(data.clone(), "valid");
    let out=call(&[json!({"version":1,"id":1,"method":"download","params":{"url":url,"destination":target,"sha1":hash,"max_bytes":data.len(),"allow_http_loopback":true,"parallel_chunks":4,"progress":true}})]);
    stop.store(true,Ordering::Relaxed); server.join().unwrap();
    assert_eq!(out.last().unwrap()["result"]["parallel"],true); assert_eq!(ranges.load(Ordering::Relaxed),4);
    assert_eq!(fs::read(&target).unwrap(),data); assert_eq!(fs::read_dir(&dir).unwrap().count(),1); fs::remove_dir_all(dir).unwrap();
}

#[test]
fn unquoted_etag_is_not_used_as_if_range() {
    use sha1::{Digest, Sha1}; use std::sync::atomic::Ordering;
    let data: Vec<u8> = (0..(1024 * 1024 + 21)).map(|i| (i % 251) as u8).collect();
    let hash=format!("{:x}",Sha1::digest(&data)); let dir=temp(); let target=dir.join("output");
    let (url,stop,ranges,server)=range_origin(data.clone(),"unquoted-etag");
    let out=call(&[json!({"version":1,"id":1,"method":"download","params":{"url":url,"destination":target,"sha1":hash,"max_bytes":data.len(),"allow_http_loopback":true,"parallel_chunks":4}})]);
    stop.store(true,Ordering::Relaxed);server.join().unwrap();
    assert_eq!(out[0]["result"]["parallel"],true);assert_eq!(ranges.load(Ordering::Relaxed),4);
    assert_eq!(fs::read(target).unwrap(),data);fs::remove_dir_all(dir).unwrap();
}

#[test]
fn rejected_ranges_fall_back_to_serial_and_corruption_is_not_published() {
    use sha1::{Digest, Sha1}; use std::sync::atomic::Ordering;
    let data: Vec<u8> = (0..(1024 * 1024 + 31)).map(|i| (i % 251) as u8).collect();
    let hash = format!("{:x}", Sha1::digest(&data));
    for mode in ["ignore", "bad-range", "corrupt"] {
        let dir=temp(); let target=dir.join("output"); let (url, stop, ranges, server)=range_origin(data.clone(), mode);
        let out=call(&[json!({"version":1,"id":1,"method":"download","params":{"url":url,"destination":target,"sha1":hash,"max_bytes":data.len(),"allow_http_loopback":true,"parallel_chunks":4}})]);
        stop.store(true,Ordering::Relaxed); server.join().unwrap(); assert!(ranges.load(Ordering::Relaxed)>0);
        if mode != "corrupt" { assert_eq!(out[0]["result"]["bytes_written"],data.len()); assert_eq!(fs::read(&target).unwrap(),data); }
        else { assert_eq!(out[0]["error"]["code"],"checksum_mismatch"); assert!(!target.exists()); assert_eq!(fs::read_dir(&dir).unwrap().count(),0); }
        fs::remove_dir_all(dir).unwrap();
    }
}

#[test]
fn completed_chunks_survive_failure_and_are_reused_by_a_new_process() {
    use sha1::{Digest, Sha1}; use std::sync::atomic::Ordering;
    let data: Vec<u8> = (0..(3 * 512 * 1024 + 137)).map(|i| (i % 251) as u8).collect();
    let hash = format!("{:x}", Sha1::digest(&data)); let dir=temp(); let resume=dir.join("resume"); let target=dir.join("output");
    let (url, stop, _, server) = range_origin(data.clone(), "resume-fail");
    let out=call(&[json!({"version":1,"id":1,"method":"download","params":{"url":url,"destination":target,"sha1":hash,"max_bytes":data.len(),"allow_http_loopback":true,"parallel_chunks":4,"resume_directory":resume}})]);
    stop.store(true,Ordering::Relaxed); server.join().unwrap();
    assert!(out[0].get("error").is_some()); assert!(!target.exists());
    let parts=fs::read_dir(&resume).unwrap().filter_map(Result::ok).filter(|entry| entry.path().extension().is_some_and(|ext| ext == "part")).count();
    assert!(parts > 0 && parts < 4, "completed checkpoints must survive a failed attempt");
    let (url, stop, ranges, server) = range_origin(data.clone(), "valid");
    let out=call(&[json!({"version":1,"id":2,"method":"download","params":{"url":url,"destination":target,"sha1":hash,"max_bytes":data.len(),"allow_http_loopback":true,"parallel_chunks":4,"resume_directory":resume}})]);
    stop.store(true,Ordering::Relaxed); server.join().unwrap();
    assert_eq!(out[0]["result"]["resumed_chunks"],parts); assert!(ranges.load(Ordering::Relaxed)<4);
    assert_eq!(fs::read(&target).unwrap(),data);
    assert_eq!(fs::read_dir(&resume).unwrap().filter_map(Result::ok).filter(|e| e.path().extension().is_some_and(|ext| ext == "part")).count(),0);
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn sha512_ranges_resume_and_reject_corrupted_checkpoints() {
    use sha2::{Digest, Sha512}; use std::sync::atomic::Ordering;
    let data: Vec<u8> = (0..(3 * 512 * 1024 + 137)).map(|i| (i % 251) as u8).collect();
    let hash = format!("{:x}", Sha512::digest(&data)); let dir = temp(); let resume = dir.join("resume"); let target = dir.join("output");
    let (url, stop, _, server) = range_origin(data.clone(), "resume-fail");
    let out = call(&[json!({"version":1,"id":1,"method":"download","params":{"url":url,"destination":target,
        "sha512":hash,"max_bytes":data.len(),"allow_http_loopback":true,"parallel_chunks":4,"resume_directory":resume}})]);
    stop.store(true, Ordering::Relaxed); server.join().unwrap();
    assert!(out[0].get("error").is_some()); assert!(!target.exists());
    let parts = fs::read_dir(&resume).unwrap().filter_map(Result::ok).filter(|e| e.path().extension().is_some_and(|v| v == "part")).count();
    assert!(parts > 0 && parts < 4);
    let (url, stop, ranges, server) = range_origin(data.clone(), "valid");
    let out = call(&[json!({"version":1,"id":2,"method":"download","params":{"url":url,"destination":target,
        "sha512":hash,"max_bytes":data.len(),"allow_http_loopback":true,"parallel_chunks":4,"resume_directory":resume}})]);
    stop.store(true, Ordering::Relaxed); server.join().unwrap();
    assert_eq!(out[0]["result"]["resumed_chunks"], parts); assert_eq!(out[0]["result"]["sha512"], hash);
    assert!(ranges.load(Ordering::Relaxed) < 4); assert_eq!(fs::read(&target).unwrap(), data);
    fs::remove_file(&target).unwrap();
    let corrupt = resume.join(format!("{hash}.{}.0.part", data.len())); fs::write(&corrupt, vec![0u8; 512 * 1024]).unwrap();
    let (url, stop, _, server) = range_origin(data.clone(), "valid");
    let out = call(&[json!({"version":1,"id":3,"method":"download","params":{"url":url,"destination":target,
        "sha512":hash,"max_bytes":data.len(),"allow_http_loopback":true,"parallel_chunks":4,"resume_directory":resume}})]);
    stop.store(true, Ordering::Relaxed); server.join().unwrap();
    assert_eq!(out[0]["result"]["sha512"], hash); assert!(out[0]["result"].get("resumed_chunks").is_none());
    assert!(!corrupt.exists()); assert_eq!(fs::read(target).unwrap(), data); fs::remove_dir_all(dir).unwrap();
}

#[test]
fn sha256_and_md5_ranges_resume_and_reject_wrong_digests() {
    use sha2::{Digest, Sha256}; use md5::Md5; use std::sync::atomic::Ordering;
    let data: Vec<u8> = (0..(3 * 512 * 1024 + 137)).map(|i| (i % 251) as u8).collect();
    for (field, hash) in [("sha256", format!("{:x}", Sha256::digest(&data))), ("md5", format!("{:x}", Md5::digest(&data)))] {
        let dir = temp(); let resume = dir.join("resume"); let target = dir.join("output");
        let (url, stop, _, server) = range_origin(data.clone(), "resume-fail");
        let mut params = json!({"url":url,"destination":target,"max_bytes":data.len(),"allow_http_loopback":true,
            "parallel_chunks":4,"resume_directory":resume}); params[field] = json!(hash);
        let out = call(&[json!({"version":1,"id":1,"method":"download","params":params})]);
        stop.store(true, Ordering::Relaxed); server.join().unwrap();
        assert!(out[0].get("error").is_some()); assert!(!target.exists());
        let parts = fs::read_dir(&resume).unwrap().filter_map(Result::ok).filter(|e| e.path().extension().is_some_and(|v| v == "part")).count();
        assert!(parts > 0 && parts < 4);
        let (url, stop, ranges, server) = range_origin(data.clone(), "valid");
        let mut params = json!({"url":url,"destination":target,"max_bytes":data.len(),"allow_http_loopback":true,
            "parallel_chunks":4,"resume_directory":resume}); params[field] = json!(hash);
        let out = call(&[json!({"version":1,"id":2,"method":"download","params":params})]);
        stop.store(true, Ordering::Relaxed); server.join().unwrap();
        assert_eq!(out[0]["result"]["resumed_chunks"], parts); assert_eq!(out[0]["result"][field], hash);
        assert!(ranges.load(Ordering::Relaxed) < 4); assert_eq!(fs::read(&target).unwrap(), data);
        fs::remove_file(&target).unwrap();
        let (url, stop, _, server) = range_origin(data.clone(), "corrupt");
        let mut params = json!({"url":url,"destination":target,"max_bytes":data.len(),"allow_http_loopback":true,"parallel_chunks":4});
        params[field] = json!(hash);
        let out = call(&[json!({"version":1,"id":3,"method":"download","params":params})]);
        stop.store(true, Ordering::Relaxed); server.join().unwrap();
        assert_eq!(out[0]["error"]["code"], "checksum_mismatch"); assert!(!target.exists());
        fs::remove_dir_all(dir).unwrap();
    }
}

#[test]
fn corrupt_checkpoint_is_discarded_before_serial_retry() {
    use sha1::{Digest, Sha1}; use std::sync::atomic::Ordering;
    let data: Vec<u8> = (0..(2 * 512 * 1024 + 137)).map(|i| (i % 251) as u8).collect();
    let hash = format!("{:x}", Sha1::digest(&data)); let dir=temp(); let resume=dir.join("resume"); fs::create_dir(&resume).unwrap();
    let corrupt=resume.join(format!("{hash}.{}.0.part",data.len())); fs::write(&corrupt,vec![0u8;512*1024]).unwrap();
    let target=dir.join("output"); let (url,stop,_,server)=range_origin(data.clone(),"valid");
    let out=call(&[json!({"version":1,"id":1,"method":"download","params":{"url":url,"destination":target,"sha1":hash,"max_bytes":data.len(),"allow_http_loopback":true,"parallel_chunks":4,"resume_directory":resume}})]);
    stop.store(true,Ordering::Relaxed); server.join().unwrap();
    assert_eq!(out[0]["result"]["bytes_written"],data.len()); assert!(out[0]["result"].get("resumed_chunks").is_none());
    assert_eq!(fs::read(&target).unwrap(),data); assert!(!corrupt.exists()); fs::remove_dir_all(dir).unwrap();
}

#[test]
fn locked_checkpoint_uses_isolated_nonresumable_transfer() {
    use sha1::{Digest, Sha1}; use std::sync::atomic::Ordering;
    let data: Vec<u8> = (0..(1024 * 1024 + 17)).map(|i| (i % 251) as u8).collect();
    let hash = format!("{:x}", Sha1::digest(&data)); let dir=temp(); let resume=dir.join("resume"); fs::create_dir(&resume).unwrap();
    let lock = fs::File::create(resume.join(format!("{hash}.lock"))).unwrap(); lock.try_lock().unwrap();
    let target=dir.join("output"); let (url,stop,ranges,server)=range_origin(data.clone(),"valid");
    let out=call(&[json!({"version":1,"id":1,"method":"download","params":{"url":url,"destination":target,"sha1":hash,"max_bytes":data.len(),"allow_http_loopback":true,"parallel_chunks":4,"resume_directory":resume}})]);
    stop.store(true,Ordering::Relaxed); server.join().unwrap();
    assert_eq!(out[0]["result"]["parallel"],true); assert!(out[0]["result"].get("resumed_chunks").is_none());
    assert_eq!(ranges.load(Ordering::Relaxed),4); assert_eq!(fs::read(&target).unwrap(),data);
    drop(lock); fs::remove_dir_all(dir).unwrap();
}

#[cfg(unix)]
#[test]
fn checkpoint_symlinks_are_not_followed() {
    use sha1::{Digest, Sha1}; use std::{os::unix::fs::symlink, sync::atomic::Ordering};
    let data: Vec<u8> = (0..(1024 * 1024 + 17)).map(|i| (i % 251) as u8).collect();
    let hash = format!("{:x}", Sha1::digest(&data));
    for mode in ["directory", "lock"] {
        let dir=temp(); let outside=dir.join("outside"); fs::create_dir(&outside).unwrap();
        let sentinel=outside.join("sentinel"); fs::write(&sentinel,b"unchanged").unwrap();
        let resume=dir.join("resume");
        if mode == "directory" { symlink(&outside,&resume).unwrap(); }
        else { fs::create_dir(&resume).unwrap(); symlink(&sentinel,resume.join(format!("{hash}.lock"))).unwrap(); }
        let target=dir.join("output"); let (url,stop,_,server)=range_origin(data.clone(),"valid");
        let out=call(&[json!({"version":1,"id":1,"method":"download","params":{"url":url,"destination":target,"sha1":hash,"max_bytes":data.len(),"allow_http_loopback":true,"parallel_chunks":4,"resume_directory":resume}})]);
        stop.store(true,Ordering::Relaxed); server.join().unwrap();
        assert_eq!(out[0]["result"]["parallel"],true); assert!(out[0]["result"].get("resumed_chunks").is_none());
        assert_eq!(fs::read(&sentinel).unwrap(),b"unchanged"); assert_eq!(fs::read_dir(&outside).unwrap().count(),1);
        assert_eq!(fs::read(&target).unwrap(),data); fs::remove_dir_all(dir).unwrap();
    }
}
#[test]
fn download_rejects_bad_hash_and_size_without_leaving_files() {
    let dir=temp(); let target=dir.join("download");
    for (max, checksum, expected) in [(3, "0000000000000000000000000000000000000000", "checksum_mismatch"), (2, "a9993e364706816aba3e25717850c26c9cd0d89d", "size_limit")] {
        let (url, server) = serve(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc");
        let out=call(&[json!({"version":1,"id":1,"method":"download","params":{"url":url,"destination":target,"sha1":checksum,"max_bytes":max,"allow_http_loopback":true}})]); server.join().unwrap();
        assert_eq!(out[0]["error"]["code"], expected); assert!(!target.exists()); assert_eq!(fs::read_dir(&dir).unwrap().count(), 0);
    }
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn download_without_sha1_requires_explicit_independent_verification() {
    let dir = temp(); let target = dir.join("file");
    let hash = "a9993e364706816aba3e25717850c26c9cd0d89d";
    let rejected = call(&[json!({"version":1,"id":1,"method":"download","params":{"url":"http://127.0.0.1:1/file","destination":target,"max_bytes":3,"allow_http_loopback":true}})]);
    assert_eq!(rejected[0]["error"]["code"], "invalid_params");
    let malformed = call(&[json!({"version":1,"id":1,"method":"download","params":{"url":"http://127.0.0.1:1/file","destination":target,"sha1":"bad","allow_unverified":true,"max_bytes":3,"allow_http_loopback":true}})]);
    assert_eq!(malformed[0]["error"]["code"], "invalid_params");
    let (url, server) = serve(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc");
    let result = call(&[json!({"version":1,"id":1,"method":"download","params":{"url":url,"destination":target,"allow_unverified":true,"max_bytes":3,"allow_http_loopback":true}})]);
    server.join().unwrap(); assert_eq!(result[0]["result"]["sha1"], hash);
    assert_eq!(fs::read(target).unwrap(), b"abc"); fs::remove_dir_all(dir).unwrap();
}

#[test]
fn metadata_fetch_preserves_headers_and_conditional_not_modified() {
    use std::io::Read;
    let dir = temp(); let target = dir.join("metadata");
    let (url, server) = serve(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Type: text/plain; charset=iso-8859-1\r\nETag: \"v1\"\r\nCache-Control: max-age=60\r\nConnection: close\r\n\r\nabc");
    let out = call(&[json!({"version":1,"id":1,"method":"download","params":{"url":url,"destination":target,"metadata":true,"allow_unverified":true,"max_bytes":100,"allow_http_loopback":true}})]);
    server.join().unwrap();
    assert_eq!(out[0]["result"]["status"], 200);
    assert_eq!(out[0]["result"]["headers"]["etag"], json!(["\"v1\""]));
    assert_eq!(out[0]["result"]["headers"]["content-type"], json!(["text/plain; charset=iso-8859-1"]));
    assert_eq!(fs::read(&target).unwrap(), b"abc");
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap(); let address = listener.local_addr().unwrap();
    let server = std::thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap(); let mut buffer = [0u8; 4096];
        let count = socket.read(&mut buffer).unwrap();
        let request = String::from_utf8_lossy(&buffer[..count]).to_ascii_lowercase();
        assert!(request.contains("if-none-match: \"v1\""));
        socket.write_all(b"HTTP/1.1 304 Not Modified\r\nETag: \"v1\"\r\nConnection: close\r\n\r\n").unwrap();
    });
    let missing = dir.join("not-modified");
    let out = call(&[json!({"version":1,"id":1,"method":"download","params":{"url":format!("http://{address}/"),"destination":missing,"metadata":true,"if_none_match":"\"v1\"","allow_unverified":true,"max_bytes":100,"allow_http_loopback":true}})]);
    server.join().unwrap(); assert_eq!(out[0]["result"]["status"], 304); assert!(!missing.exists());
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn ruled_arguments_match_simple_os_names_on_multiple_platforms() {
    let cases = [
        ("linux", "LINUX", true), ("linux", "FREEBSD", true), ("freebsd", "LINUX", false),
        ("windows 11", "WINDOWS", true), ("Darwin", "MACOS", true), ("osx", "LINUX", false),
        ("solaris", "FREEBSD", true), ("unknown", "WINDOWS", true), ("", "LINUX", true),
    ];
    for (name, system, expected) in cases {
        let request = json!({"version":1,"id":1,"method":"expand_ruled_arguments","params":{
            "groups":[{"features":{},"arguments":[{"rules":[{"os_name":name,"system_os":system,"action":"allow","features":null}],"values":["selected"]}]}],"keys":{}}});
        let out = call(&[request]);
        assert_eq!(out[0]["result"]["groups"][0].as_array().unwrap().len() == 1, expected, "{name} on {system}");
    }
}
#[test]
fn download_rejects_insecure_remote_urls_before_network_access() {
    let out=call(&[json!({"version":1,"id":1,"method":"download","params":{"url":"http://example.com/file","destination":"x","sha1":"a9993e364706816aba3e25717850c26c9cd0d89d","max_bytes":3}})]);
    assert_eq!(out[0]["error"]["code"], "invalid_params");
}
#[test]
fn download_rejects_unsafe_redirects_and_truncated_bodies() {
    let dir=temp(); let target=dir.join("download");
    for (response, expected) in [(&b"HTTP/1.1 302 Found\r\nLocation: http://example.com\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"[..], "invalid_redirect"), (&b"HTTP/1.1 200 OK\r\nContent-Length: 9\r\nConnection: close\r\n\r\nabc"[..], "network_error")] {
        let (url, server) = serve(response);
        let out=call(&[json!({"version":1,"id":1,"method":"download","params":{"url":url,"destination":target,"sha1":"a9993e364706816aba3e25717850c26c9cd0d89d","max_bytes":20,"allow_http_loopback":true}})]); server.join().unwrap();
        assert_eq!(out[0]["error"]["code"], expected); assert!(!target.exists()); assert_eq!(fs::read_dir(&dir).unwrap().count(), 0);
    }
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn offline_profile_matches_java_uuid_for_ascii_and_unicode() {
    let responses=call(&[
        json!({"version":1,"id":1,"method":"offline_profile","params":{"username":"Steve"}}),
        json!({"version":1,"id":2,"method":"offline_profile","params":{"username":"玩家"}}),
        json!({"version":1,"id":3,"method":"offline_profile","params":{"username":"   "}}),
    ]);
    assert_eq!(responses[0]["result"]["uuid"], "5627dd98-e6be-3c21-b8a8-e92344183641");
    assert_eq!(responses[1]["result"]["uuid"], "511a6918-f669-3b23-ba72-decf60a9cb59");
    assert_eq!(responses[2]["error"]["code"], "invalid_params");
}

#[test]
fn download_follows_safe_relative_redirect() {
    use std::io::Read;
    let dir = temp(); let target = dir.join("download");
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let server = std::thread::spawn(move || {
        for response in [&b"HTTP/1.1 302 Found\r\nLocation: /next\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"[..], &b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc"[..]] {
            let (mut socket, _) = listener.accept().unwrap(); let mut buf = [0; 2048];
            let _ = socket.read(&mut buf); socket.write_all(response).unwrap();
        }
    });
    let out = call(&[json!({"version":1,"id":1,"method":"download","params":{"url":format!("http://{address}/file"),"destination":target,"sha1":"a9993e364706816aba3e25717850c26c9cd0d89d","max_bytes":3,"allow_http_loopback":true}})]);
    server.join().unwrap(); assert_eq!(out[0]["result"]["bytes_written"], 3); assert_eq!(fs::read(target).unwrap(), b"abc"); fs::remove_dir_all(dir).unwrap();
}

#[test]
fn download_rejects_unexpected_content_encoding() {
    let dir=temp(); let target=dir.join("download");
    let (url, server) = serve(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Encoding: gzip\r\nConnection: close\r\n\r\nabc");
    let out = call(&[json!({"version":1,"id":1,"method":"download","params":{"url":url,"destination":target,"sha1":"a9993e364706816aba3e25717850c26c9cd0d89d","max_bytes":3,"allow_http_loopback":true}})]);
    server.join().unwrap();
    assert_eq!(out[0]["error"]["code"], "unsupported_encoding");
    assert!(!target.exists()); assert_eq!(fs::read_dir(&dir).unwrap().count(), 0); fs::remove_dir_all(dir).unwrap();
}
#[test]
fn download_rejects_credentials_in_url_without_echoing_them() {
    let request = json!({"version":1,"id":1,"method":"download","params":{"url":"https://user:supersecret@example.invalid/file","destination":"unused","sha1":"a9993e364706816aba3e25717850c26c9cd0d89d","max_bytes":3}});
    let response = call(&[request]);
    assert_eq!(response[0]["error"]["code"], "invalid_params");
    assert!(!response[0].to_string().contains("supersecret"));
}

/// Writes a ZIP fixture containing only the given file entries.
fn make_zip(path: &std::path::Path, entries: &[(&str, &[u8])]) {
    let file = fs::File::create(path).unwrap();
    let mut zip = zip::ZipWriter::new(file);
    for (name, contents) in entries {
        zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(contents).unwrap();
    }
    zip.finish().unwrap();
}

#[cfg(unix)]
#[test]
fn forge_profiles_read_bounded_files_and_reject_ambiguous_paths() {
    let dir = temp(); let jar = dir.join("installer.jar");
    make_zip(&jar, &[("install_profile.json", br#"{"json":"/version.json"}"#), ("version.json", br#"{"id":"fixture"}"#)]);
    let request = |path: &std::path::Path| json!({"version":1,"id":1,"method":"read_forge_profiles","params":{"archive":path}});
    let out = call(&[request(&jar)]);
    assert_eq!(out[0]["result"]["version_path"], "/version.json");
    assert_eq!(out[0]["result"]["version"], "{\"id\":\"fixture\"}");
    let bad = dir.join("ambiguous.jar");
    make_zip(&bad, &[("install_profile.json", br#"{"json":"/version.json","json":"/other.json"}"#), ("version.json", b"{}")]);
    let out = call(&[request(&bad)]); assert_eq!(out[0]["error"]["code"], "unsupported_metadata");
    fs::remove_dir_all(dir).unwrap();
}

#[cfg(unix)]
#[test]
fn forge_entries_stage_only_requested_files_and_reject_traversal() {
    let dir = temp(); let jar = dir.join("installer.jar"); let parent = dir.join("staging"); fs::create_dir(&parent).unwrap();
    make_zip(&jar, &[("maven/g/a/1/a-1.jar", b"fixture"), ("maven/unrelated", b"other")]);
    let params = |entries: Value| json!({"archive":jar,"staging_parent":parent,"entries":entries});
    let out = call(&[json!({"version":1,"id":1,"method":"stage_forge_entries","params":params(json!(["maven/g/a/1/a-1.jar","maven/missing"]))})]);
    let result = &out[0]["result"]; let stage = std::path::PathBuf::from(result["stage"].as_str().unwrap());
    assert_eq!(result["entries"][0]["present"], true); assert_eq!(result["entries"][1]["present"], false);
    assert_eq!(fs::read(stage.join("0")).unwrap(), b"fixture"); assert!(!stage.join("1").exists());
    assert_eq!(fs::read_dir(&stage).unwrap().count(), 1); fs::remove_dir_all(stage).unwrap();
    let bad = call(&[json!({"version":1,"id":2,"method":"stage_forge_entries","params":params(json!(["maven/../escape"]))})]);
    assert_eq!(bad[0]["error"]["code"], "unsupported_metadata"); assert_eq!(fs::read_dir(&parent).unwrap().count(), 0);
    fs::remove_dir_all(dir).unwrap();
}

#[cfg(unix)]
#[test]
fn forge_data_stages_only_safe_requested_entries() {
    let dir = temp(); let jar = dir.join("installer.jar"); let parent = dir.join("staging"); fs::create_dir(&parent).unwrap();
    make_zip(&jar, &[("data/client.bin", b"patch"), ("data/other.bin", b"other")]);
    let request = |entries: Value| json!({"version":1,"id":1,"method":"stage_forge_data",
        "params":{"archive":jar,"staging_parent":parent,"entries":entries}});
    let out = call(&[request(json!(["data/client.bin"]))]);
    let stage = std::path::PathBuf::from(out[0]["result"]["stage"].as_str().unwrap());
    assert_eq!(fs::read(stage.join("0")).unwrap(), b"patch"); assert_eq!(fs::read_dir(&stage).unwrap().count(), 1);
    fs::remove_dir_all(stage).unwrap();
    let out = call(&[request(json!(["data/../outside"]))]);
    assert_eq!(out[0]["error"]["code"], "unsupported_metadata"); assert_eq!(fs::read_dir(&parent).unwrap().count(), 0);
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn processor_command_prepares_main_class_and_ordered_classpath() {
    let dir = temp(); let jar = dir.join("processor.jar"); let dependency = dir.join("dependency with space.jar");
    make_zip(&jar, &[("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\nMain-Class: example.Main\r\n\r\n")]);
    fs::write(&dependency, b"fixture").unwrap();
    let output = call(&[
        json!({"version":1,"id":1,"method":"processor_main_class","params":{"jar":jar}}),
        json!({"version":1,"id":2,"method":"processor_classpath","params":{"jar":jar,"libraries":[dependency],"separator":":"}}),
        json!({"version":1,"id":3,"method":"processor_command","params":{"jar":jar,"libraries":[dependency],"separator":":",
            "arguments":{"literals":["--side","{SIDE}","{missing}"],"variables":{"SIDE":"client"},"artifacts":{}}}})]);
    assert_eq!(output[0]["result"]["main_class"], "example.Main");
    assert_eq!(output[1]["result"]["classpath"], format!("{}:{}", dependency.display(), jar.display()));
    assert_eq!(output[2]["result"]["main_class"], "example.Main");
    assert_eq!(output[2]["result"]["classpath"], format!("{}:{}", dependency.display(), jar.display()));
    assert_eq!(output[2]["result"]["arguments"]["values"], json!(["--side","client",null]));
    let libraries = dir.join("libraries"); let resolved = libraries.join("org/example/core/1/core-1.jar");
    fs::create_dir_all(resolved.parent().unwrap()).unwrap(); fs::write(&resolved, b"fixture").unwrap();
    let output = call(&[json!({"version":1,"id":4,"method":"processor_command_v3","params":{
        "jar":jar,"libraries_root":libraries,"coordinates":["org.example:core:1"],"separator":":",
        "arguments":{"artifact_root":libraries,"literals":["[org.example:core:1]"],"variables":{}}}})]);
    assert_eq!(output[0]["result"]["library_paths"], json!([resolved]));
    assert_eq!(output[0]["result"]["arguments"]["values"], json!([resolved]));
    let folded = dir.join("folded.jar");
    make_zip(&folded, &[("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\nMain-Class: example.\r\n Main\r\n\r\n")]);
    let output = call(&[json!({"version":1,"id":3,"method":"processor_main_class","params":{"jar":folded}})]);
    assert_eq!(output[0]["result"]["main_class"], "example.Main");
    let sections = dir.join("sections.jar");
    make_zip(&sections, &[("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\nMain-Class: example.Main\r\n\r\nName: x\r\nValue: y\r\n\r\n")]);
    let output = call(&[json!({"version":1,"id":4,"method":"processor_main_class","params":{"jar":sections}})]);
    assert_eq!(output[0]["error"]["code"], "invalid_archive");
    let duplicate = dir.join("duplicate.jar");
    make_zip(&duplicate, &[("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\nMain-Class: example.First\r\n\r\n"),
        ("META-INF/MANIFEST.MG", b"Manifest-Version: 1.0\r\nMain-Class: example.Second\r\n\r\n")]);
    let mut bytes = fs::read(&duplicate).unwrap(); let from = b"META-INF/MANIFEST.MG"; let to = b"META-INF/MANIFEST.MF";
    let mut replaced = 0;
    for index in 0..=bytes.len() - from.len() {
        if &bytes[index..index + from.len()] == from { bytes[index..index + to.len()].copy_from_slice(to); replaced += 1; }
    }
    assert_eq!(replaced, 2); fs::write(&duplicate, bytes).unwrap();
    let output = call(&[json!({"version":1,"id":5,"method":"processor_main_class","params":{"jar":duplicate}})]);
    assert_eq!(output[0]["error"]["code"], "invalid_archive");
    fs::remove_dir_all(dir).unwrap();
}

#[cfg(unix)]
#[test]
fn legacy_library_checks_direct_and_embedded_hashes_and_defers_signed_layouts() {
    use sha1::{Digest, Sha1};
    let dir = temp(); let jar = dir.join("legacy.jar");
    let listing = b"a9993e364706816aba3e25717850c26c9cd0d89d data.bin\n";
    let listing_hash = format!("{:x}", Sha1::digest(listing));
    make_zip(&jar, &[("data.bin", b"abc"), ("checksums.sha1", listing)]);
    let request = |path: &std::path::Path, checksums: Value| json!({"version":1,"id":1,"method":"check_legacy_library",
        "params":{"path":path,"jar":true,"checksums":checksums}});
    let output = call(&[request(&jar, json!([listing_hash]))]);
    assert_eq!(output[0]["result"]["valid"], true);
    let output = call(&[request(&jar, json!(["0000000000000000000000000000000000000000"]))]);
    assert_eq!(output[0]["result"]["valid"], false);
    let direct = format!("{:x}", Sha1::digest(fs::read(&jar).unwrap()));
    let output = call(&[request(&jar, json!([direct]))]); assert_eq!(output[0]["result"]["valid"], true);
    let signed = dir.join("signed.jar");
    make_zip(&signed, &[("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n\n"), ("checksums.sha1", listing)]);
    let output = call(&[request(&signed, json!([listing_hash]))]); assert_eq!(output[0]["result"]["unknown"], true);
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn extracts_zip_only_to_new_staging_directory() {
    let parent=temp(); let archive=parent.join("fixture.zip");
    make_zip(&archive, &[("versions/test/test.json", b"{}"), ("mods/example.txt", b"abc")]);
    let out=call(&[json!({"version":1,"id":1,"method":"extract_zip","params":{"archive":archive,"staging_parent":parent}})]);
    let result=&out[0]["result"]; let stage=std::path::PathBuf::from(result["staging_directory"].as_str().unwrap());
    assert_eq!(stage.parent(), Some(parent.as_path())); assert_eq!(result["entries"], 2);
    assert_eq!(fs::read(stage.join("mods/example.txt")).unwrap(), b"abc");
    assert_eq!(fs::read(stage.join("versions/test/test.json")).unwrap(), b"{}");
    assert!(!parent.join("mods").exists()); fs::remove_dir_all(parent).unwrap();
}

#[test]
fn extracts_only_selected_modpack_subtree() {
    let parent = temp(); let archive = parent.join("fixture.zip");
    make_zip(&archive, &[("overrides/config/a.txt", b"abc"), ("client-overrides/mods/b.jar", b"other"), ("unrelated/file", b"ignored")]);
    let out = call(&[json!({"version":1,"id":1,"method":"extract_zip_prefix",
        "params":{"archive":archive,"staging_parent":parent,"prefix":"overrides/"}})]);
    let stage = std::path::PathBuf::from(out[0]["result"]["staging_directory"].as_str().unwrap());
    assert_eq!(out[0]["result"]["entries"], 1);
    assert_eq!(fs::read(stage.join("overrides/config/a.txt")).unwrap(), b"abc");
    assert!(!stage.join("client-overrides").exists()); assert!(!stage.join("unrelated").exists());
    let out = call(&[json!({"version":1,"id":2,"method":"extract_zip_prefix",
        "params":{"archive":archive,"staging_parent":parent,"prefix":"../"}})]);
    assert_eq!(out[0]["error"]["code"], "invalid_params");
    fs::remove_dir_all(parent).unwrap();
}

#[test]
fn hashes_selected_zip_entries_in_requested_order() {
    let parent = temp(); let archive = parent.join("pack.zip");
    make_zip(&archive, &[("overrides/a", b"abc"), ("overrides/b", b""), ("unused", b"ignored")]);
    let out = call(&[json!({"version":1,"id":1,"method":"hash_zip_entries",
        "params":{"archive":archive,"entries":["overrides/b", "overrides/a", "overrides/b"]}}),
        json!({"version":1,"id":2,"method":"hash_zip_entries","params":{"archive":archive,"entries":["missing"]}})]);
    assert_eq!(out[0]["result"]["sha1"], json!(["da39a3ee5e6b4b0d3255bfef95601890afd80709",
        "a9993e364706816aba3e25717850c26c9cd0d89d", "da39a3ee5e6b4b0d3255bfef95601890afd80709"]));
    assert_eq!(out[1]["error"]["code"], "invalid_archive"); fs::remove_dir_all(parent).unwrap();
}
#[test]
fn rejects_zip_traversal_and_cleans_staging() {
    let parent=temp(); let archive=parent.join("fixture.zip");
    make_zip(&archive, &[("legitimate/file", b"ok"), ("../escape", b"bad")]);
    let out=call(&[json!({"version":1,"id":1,"method":"extract_zip","params":{"archive":archive,"staging_parent":parent}})]);
    assert_eq!(out[0]["error"]["code"], "unsafe_archive");
    assert!(!parent.join("escape").exists()); assert_eq!(fs::read_dir(&parent).unwrap().count(), 1);
    fs::remove_dir_all(parent).unwrap();
}
#[test]
fn rejects_conflicting_zip_paths_and_cleans_staging() {
    let parent=temp(); let archive=parent.join("fixture.zip");
    make_zip(&archive, &[("file", b"a"), ("file/child", b"b")]);
    let out=call(&[json!({"version":1,"id":1,"method":"extract_zip","params":{"archive":archive,"staging_parent":parent}})]);
    assert_eq!(out[0]["error"]["code"], "invalid_archive");
    assert_eq!(fs::read_dir(&parent).unwrap().count(), 1); fs::remove_dir_all(parent).unwrap();
}
#[test]
fn rejects_zip_symlinks_and_cleans_staging() {
    let parent=temp(); let archive=parent.join("fixture.zip");
    let file=fs::File::create(&archive).unwrap(); let mut zip=zip::ZipWriter::new(file);
    zip.add_symlink("link", "outside", zip::write::SimpleFileOptions::default()).unwrap(); zip.finish().unwrap();
    let out=call(&[json!({"version":1,"id":1,"method":"extract_zip","params":{"archive":archive,"staging_parent":parent}})]);
    assert_eq!(out[0]["error"]["code"], "unsafe_archive"); assert_eq!(fs::read_dir(&parent).unwrap().count(), 1); fs::remove_dir_all(parent).unwrap();
}
#[test]
fn rejects_corrupt_zip_data_and_cleans_staging() {
    let parent=temp(); let archive=parent.join("fixture.zip");
    let file=fs::File::create(&archive).unwrap(); let mut zip=zip::ZipWriter::new(file);
    zip.start_file("file", zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored)).unwrap();
    zip.write_all(b"unique-content-123").unwrap(); zip.finish().unwrap();
    let mut bytes=fs::read(&archive).unwrap(); let start=bytes.windows(18).position(|w| w == b"unique-content-123").unwrap(); bytes[start] ^= 1; fs::write(&archive, bytes).unwrap();
    let out=call(&[json!({"version":1,"id":1,"method":"extract_zip","params":{"archive":archive,"staging_parent":parent}})]);
    assert_eq!(out[0]["error"]["code"], "invalid_archive"); assert_eq!(fs::read_dir(&parent).unwrap().count(), 1); fs::remove_dir_all(parent).unwrap();
}
#[test]
fn microsoft_operations_validate_parameters_before_network_access() {
    let requests=[
        json!({"version":1,"id":1,"method":"microsoft_device_begin","params":{}}),
        json!({"version":1,"id":2,"method":"microsoft_device_poll","params":{"client_id":"example"}}),
        json!({"version":1,"id":3,"method":"microsoft_refresh","params":{"client_id":"example"}}),
        json!({"version":1,"id":4,"method":"microsoft_exchange","params":{}}),
    ];
    let out=call(&requests);
    for item in out { assert_eq!(item["error"]["code"],"invalid_params"); }
}
#[test]
fn download_reports_progress_before_final_result() {
    let dir=temp(); let target=dir.join("download");
    let (url, server) = serve(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc");
    let out=call(&[json!({"version":1,"id":"progress-id","method":"download","params":{"url":url,"destination":target,"sha1":"a9993e364706816aba3e25717850c26c9cd0d89d","max_bytes":3,"allow_http_loopback":true,"progress":true}})]);
    server.join().unwrap();
    assert_eq!(out.len(),2); assert_eq!(out[0]["id"],"progress-id"); assert_eq!(out[0]["event"],"progress");
    assert_eq!(out[0]["downloaded"],3); assert_eq!(out[0]["total"],3); assert_eq!(out[1]["result"]["bytes_written"],3);
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn download_progress_handles_unknown_content_length() {
    let dir=temp(); let target=dir.join("download");
    let (url, server) = serve(b"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nabc");
    let out=call(&[json!({"version":1,"id":2,"method":"download","params":{"url":url,"destination":target,"sha1":"a9993e364706816aba3e25717850c26c9cd0d89d","max_bytes":3,"allow_http_loopback":true,"progress":true}})]);
    server.join().unwrap(); assert_eq!(out[0]["downloaded"],3); assert!(out[0]["total"].is_null()); assert_eq!(out[1]["result"]["bytes_written"],3);
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn download_can_reject_redirects_for_proxy_compatibility() {
    let dir=temp(); let target=dir.join("download");
    let (url,server)=serve(b"HTTP/1.1 302 Found\r\nLocation: /other\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    let out=call(&[json!({"version":1,"id":1,"method":"download","params":{"url":url,"destination":target,"sha1":"a9993e364706816aba3e25717850c26c9cd0d89d","max_bytes":3,"allow_http_loopback":true,"follow_redirects":false}})]);
    server.join().unwrap(); assert_eq!(out[0]["error"]["code"],"redirect_not_allowed"); assert!(!target.exists()); fs::remove_dir_all(dir).unwrap();
}
#[test]
fn asset_plan_preserves_logical_entries_and_sizes() {
    let dir=temp(); let path=dir.join("index.json");
    let hash="a9993e364706816aba3e25717850c26c9cd0d89d";
    fs::write(&path, json!({"objects":{"a":{"hash":hash,"size":3},"b":{"hash":hash,"size":0},"c":{"hash":"0000000000000000000000000000000000000000"}}}).to_string()).unwrap();
    let out=call(&[json!({"version":1,"id":1,"method":"plan_assets","params":{"index_path":path}})]);
    let objects=out[0]["result"]["objects"].as_array().unwrap();
    assert_eq!(objects.len(),3); assert_eq!(objects.iter().filter(|v|v["hash"]==hash).count(),2);
    assert!(objects.iter().any(|v|v["size"]==3)); assert!(objects.iter().any(|v|v["size"]==0));
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn asset_plan_rejects_invalid_hashes_without_modifying_index() {
    let dir=temp(); let path=dir.join("index.json"); let input=r#"{"objects":{"a":{"hash":"../../bad","size":3}}}"#;
    fs::write(&path,input).unwrap(); let out=call(&[json!({"version":1,"id":1,"method":"plan_assets","params":{"index_path":path}})]);
    assert_eq!(out[0]["error"]["code"],"invalid_index"); assert_eq!(fs::read_to_string(path).unwrap(),input); fs::remove_dir_all(dir).unwrap();
}
#[cfg(unix)]
#[test]
fn native_exec_replaces_process_and_preserves_streams_and_environment() {
    use std::io::Read;
    let dir=temp();
    let mut child=Command::new(env!("CARGO_BIN_EXE_mfcl-native"))
        .arg("--exec-child").current_dir(&dir).env("MFCL_TEST_ENV","fixture")
        .stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().unwrap();
    let pid=child.id();
    let script="IFS= read -r line; printf '%s\\n%s\\n%s\\n%s\\n' \"$$\" \"$PWD\" \"$MFCL_TEST_ENV\" \"$line\"; printf 'fixture-error\\n' >&2; exit 7";
    let request=json!({"command":["/bin/sh","-c",script]});
    { let input=child.stdin.as_mut().unwrap(); write!(input,"{request}\nhello-after-request\n").unwrap(); }
    let mut stdout=String::new(); let mut stderr=String::new();
    child.stdout.take().unwrap().read_to_string(&mut stdout).unwrap(); child.stderr.take().unwrap().read_to_string(&mut stderr).unwrap();
    assert_eq!(child.wait().unwrap().code(),Some(7)); let lines:Vec<_>=stdout.lines().collect();
    assert_eq!(lines[0],pid.to_string()); assert_eq!(lines[1],dir.to_str().unwrap()); assert_eq!(lines[2],"fixture"); assert_eq!(lines[3],"hello-after-request");
    assert_eq!(stderr.trim(),"fixture-error"); fs::remove_dir_all(dir).unwrap();
}
#[cfg(unix)]
#[test]
fn native_exec_length_protocol_keeps_following_stdin_for_game() {
    let mut child=Command::new(env!("CARGO_BIN_EXE_mfcl-native"))
        .arg("--exec-child-v2").stdin(Stdio::piped()).stdout(Stdio::piped()).spawn().unwrap();
    let pid=child.id();
    let request=json!({"command":["/bin/sh","-c","IFS= read -r line; printf '%s %s\\n' \"$$\" \"$line\""]}).to_string();
    { let input=child.stdin.as_mut().unwrap(); input.write_all(&(request.len() as u32).to_be_bytes()).unwrap();
      input.write_all(request.as_bytes()).unwrap(); input.write_all("后续输入\n".as_bytes()).unwrap(); }
    let output=child.wait_with_output().unwrap(); assert!(output.status.success());
    assert_eq!(String::from_utf8(output.stdout).unwrap().trim(), format!("{pid} 后续输入"));
}
#[cfg(unix)]
#[test]
fn native_exec_failure_does_not_echo_sensitive_arguments() {
    let mut child=Command::new(env!("CARGO_BIN_EXE_mfcl-native")).arg("--exec-child").stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn().unwrap();
    writeln!(child.stdin.take().unwrap(),"{}",json!({"command":["/does-not-exist","sensitive-token"]})).unwrap();
    let out=child.wait_with_output().unwrap(); assert_eq!(out.status.code(),Some(127)); assert!(!String::from_utf8(out.stderr).unwrap().contains("sensitive-token"));
}
#[test]
fn expands_launch_argument_groups_like_java() {
    let out=call(&[json!({"version":1,"id":1,"method":"expand_arguments","params":{
        "groups":[["--name","${name}",null,"${a}/${b}","${unknown}","${bad\nname}","${name}-${name}",""],["--token=${token}"]],
        "keys":{"${name}":"玩家", "${a}":"${b}","${b}":"done","${token}":"s $ \\\""}}})]);
    assert_eq!(out[0]["result"]["groups"][0],json!(["--name","玩家","done/done","${unknown}","${bad\nname}","玩家-玩家",""]));
    assert_eq!(out[0]["result"]["groups"][1][0],"--token=s $ \\\"");
}
#[test]
fn expansion_rejects_excessive_output_without_exposing_values() {
    let out=call(&[json!({"version":1,"id":1,"method":"expand_arguments","params":{
        "groups":[["${a}".repeat(1000)]],"keys":{"${a}":"secret".repeat(1000)}}})]);
    assert_eq!(out[0]["error"]["code"],"argument_limit"); assert!(!out[0].to_string().contains("secret"));
}
/// Creates a minimal non-native library descriptor for classpath fixtures.
fn classpath_library(group:&str,name:&str,version:&str)->Value {
    json!({"group":group,"name":name,"version":version,"extension":"jar","original_classifier":null,"effective_classifier":null,"hint":null,"filename":null,"download_path":null,"applicable":true,"native":false})
}
/// Creates a file and any missing parent directories.
fn fixture_file(path:&std::path::Path) {
    fs::create_dir_all(path.parent().unwrap()).unwrap(); fs::write(path,b"x").unwrap();
}
#[test]
fn classpath_plan_preserves_order_filters_and_deduplicates() {
    let dir=temp(); let libs=dir.join("libraries"); let instance=dir.join("versions/test"); fs::create_dir_all(&libs).unwrap(); fs::create_dir_all(&instance).unwrap();
    let standard=libs.join("org/example/core/1/core-1.jar"); let custom=libs.join("custom/file.jar"); let local=instance.join("libraries/local.jar");
    fixture_file(&standard); fixture_file(&custom); fixture_file(&local);
    let mut override_lib=classpath_library("a","b","1"); override_lib["download_path"]=json!("custom/file.jar");
    let mut local_lib=classpath_library("a","c","2"); local_lib["hint"]=json!("local"); local_lib["filename"]=json!("local.jar");
    let mut excluded=classpath_library("a","excluded","1"); excluded["applicable"]=json!(false);
    let mut native=classpath_library("a","native","1"); native["native"]=json!(true);
    let out=call(&[json!({"version":1,"id":1,"method":"plan_classpath","params":{"libraries_root":libs,"instance_root":instance,"process_optifine":false,"modlauncher":false,"libraries":[classpath_library("org.example","core","1"),override_lib,local_lib,classpath_library("org.example","core","1"),excluded,native]}})]);
    assert_eq!(out[0]["result"]["classpath"],json!([standard,custom,local])); fs::remove_dir_all(dir).unwrap();
}
#[test]
fn classpath_plan_handles_optifine_installer_order() {
    let dir=temp(); let libs=dir.join("libraries"); let instance=dir.join("versions/test"); fs::create_dir_all(&libs).unwrap(); fs::create_dir_all(&instance).unwrap();
    let forge=libs.join("net/minecraftforge/forge/1/forge-1.jar"); let installer=libs.join("optifine/OptiFine/X/OptiFine-X-installer.jar");
    let wrapper=libs.join("optifine/launchwrapper-of/2/launchwrapper-of-2.jar");
    fixture_file(&forge); fixture_file(&installer); fixture_file(&wrapper);
    let libraries=json!([classpath_library("optifine","OptiFine","X"),classpath_library("optifine","launchwrapper-of","2"),classpath_library("net.minecraftforge","forge","1")]);
    let request=|modlauncher|json!({"version":1,"id":1,"method":"plan_classpath","params":{"libraries_root":libs,"instance_root":instance,"process_optifine":true,"modlauncher":modlauncher,"libraries":libraries}});
    let out=call(&[request(false),request(true)]);
    assert_eq!(out[0]["result"]["classpath"],json!([forge,installer])); assert_eq!(out[1]["result"]["classpath"],json!([forge]));
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn ruled_argument_selection_respects_order_os_and_features() {
    let args=json!([
        "always-${name}",
        {"rules":null,"values":["no-rules",null]},
        {"rules":[{"os_matches":false,"action":"allow","features":null}],"values":["wrong-os"]},
        {"rules":[{"os_matches":true,"action":"allow","features":{"custom":true}}],"values":["enabled-${name}"]},
        {"rules":[{"os_matches":true,"action":"allow","features":null},{"os_matches":true,"action":"disallow","features":null}],"values":["last-rule-denies"]},
        {"rules":[{"os_matches":true,"action":"disallow","features":null},{"os_matches":true,"action":"allow","features":{"missing":null}}],"values":["null-feature-matches"]},
        {"rules":[],"values":null}, null
    ]);
    let out=call(&[json!({"version":1,"id":1,"method":"expand_ruled_arguments","params":{"groups":[{"arguments":args,"features":{"custom":true}}],"keys":{"${name}":"Steve"}}})]);
    assert_eq!(out[0]["result"]["groups"][0],json!(["always-Steve","no-rules","enabled-Steve","null-feature-matches"]));
}
#[test]
fn ruled_argument_rejects_invalid_actions() {
    let out=call(&[json!({"version":1,"id":1,"method":"expand_ruled_arguments","params":{"groups":[{"arguments":[{"rules":[{"os_matches":true,"action":"other"}],"values":["x"]}],"features":{}}],"keys":{}}})]);
    assert_eq!(out[0]["error"]["code"],"invalid_params");
}
/// Sends an authorization URL request with an externally supplied test-endpoint override.
fn authorization_url_with_test_override() -> Value {
    let mut child=Command::new(env!("CARGO_BIN_EXE_mfcl-native"))
        .env("MFCL_AUTH_TEST_BASE","http://example.com:80/")
        .stdin(Stdio::piped()).stdout(Stdio::piped()).spawn().unwrap();
    let request=json!({"version":1,"id":1,"method":"microsoft_authorization_url","params":{
        "client_id":"client","state":"state","redirect_uri":"http://localhost:29111/auth-response",
        "code_verifier":"dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"}});
    writeln!(child.stdin.take().unwrap(),"{request}").unwrap();
    let out=child.wait_with_output().unwrap(); assert!(out.status.success()); serde_json::from_slice(&out.stdout).unwrap()
}
#[cfg(not(feature = "auth-test-endpoints"))]
#[test]
fn production_auth_ignores_test_endpoint_environment() {
    let result=authorization_url_with_test_override();
    assert!(result["result"]["authorization_url"].as_str().unwrap().starts_with("https://login.live.com/"));
}
#[cfg(feature = "auth-test-endpoints")]
#[test]
fn test_auth_endpoint_rejects_non_loopback_hosts() {
    let result=authorization_url_with_test_override();
    assert_eq!(result["error"]["code"],"invalid_params");
}
#[test]
fn batch_download_preserves_order_and_retries_candidates() {
    let dir=temp();
    let (bad_url, bad_server)=serve(b"HTTP/1.1 500 Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    let (good_url, good_server)=serve(b"HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: close\r\n\r\nabc");
    let hash="a9993e364706816aba3e25717850c26c9cd0d89d";
    let frames=call(&[json!({"version":1,"id":1,"method":"download_batch","params":{
        "stage_directory":dir,"allow_http_loopback":true,"items":[
          {"index":9,"name":"a","sha1":hash,"max_bytes":100,"urls":[bad_url,good_url]},
          {"index":2,"name":"b","sha1":hash,"max_bytes":100,"urls":["http://127.0.0.1:1/"]}]}})]);
    bad_server.join().unwrap(); good_server.join().unwrap();
    let results=frames.last().unwrap()["result"]["results"].as_array().unwrap();
    assert_eq!(results[0]["index"],9); assert_eq!(results[0]["ok"],true);
    assert_eq!(results[1]["index"],2); assert_eq!(results[1]["ok"],false);
    assert_eq!(fs::read(dir.join("a")).unwrap(),b"abc"); assert!(!dir.join("b").exists());
    fs::remove_dir_all(dir).unwrap();
}
#[cfg(unix)]
#[test]
fn library_hash_batch_reports_matches_mismatches_and_missing_files() {
    let dir=temp(); let good=dir.join("good.jar"); let bad=dir.join("bad.jar"); let missing=dir.join("missing.jar");
    fs::write(&good,b"abc").unwrap(); fs::write(&bad,b"bad").unwrap();
    let hash="a9993e364706816aba3e25717850c26c9cd0d89d";
    let out=call(&[json!({"version":1,"id":1,"method":"check_libraries","params":{"items":[
        {"index":3,"path":good,"sha1":hash},{"index":4,"path":bad,"sha1":hash},{"index":5,"path":missing,"sha1":hash}
    ]}})]);
    let values=out[0]["result"]["results"].as_array().unwrap();
    assert_eq!(values[0]["index"],3); assert_eq!(values[0]["matches"],true); assert_eq!(values[0]["size"],3); assert!(values[0]["mtime_ns"].as_i64().unwrap()>0);
    assert_eq!(values[1]["matches"],false); assert_eq!(values[2]["unknown"],true);
    fs::remove_dir_all(dir).unwrap();
}
#[cfg(unix)]
#[test]
fn library_hash_batch_preserves_input_order_with_many_files() {
    let dir=temp(); let hash="a9993e364706816aba3e25717850c26c9cd0d89d";
    let mut items=Vec::new();
    for i in 0..32 {
        let path=dir.join(format!("file-{i}"));
        if i % 3 != 0 { fs::write(&path,if i % 2 == 0 { b"abc" } else { b"bad" }).unwrap(); }
        items.push(json!({"index":31-i,"path":path,"sha1":hash}));
    }
    let frames=call(&[json!({"version":1,"id":1,"method":"check_libraries","params":{"items":items}})]);
    let results=frames[0]["result"]["results"].as_array().unwrap();
    assert_eq!(results.len(),32);
    for (i, result) in results.iter().enumerate() {
        assert_eq!(result["index"],31-i);
        if i % 3 == 0 { assert_eq!(result["unknown"],true); }
        else { assert_eq!(result["matches"],i % 2 == 0); }
    }
    fs::remove_dir_all(dir).unwrap();
}
#[test]
fn library_hash_batch_rejects_invalid_digest_without_changing_files() {
    let dir=temp(); let path=dir.join("file"); fs::write(&path,b"abc").unwrap();
    let out=call(&[json!({"version":1,"id":1,"method":"check_libraries","params":{"items":[{"index":0,"path":path,"sha1":"bad"}]}})]);
    assert_eq!(out[0]["error"]["code"],"invalid_params"); assert_eq!(fs::read(path).unwrap(),b"abc"); fs::remove_dir_all(dir).unwrap();
}
#[cfg(unix)]
#[test]
fn library_hash_batch_emits_progress_then_results() {
    let dir=temp(); let path=dir.join("library"); fs::write(&path,b"abc").unwrap();
    let out=call(&[json!({"version":1,"id":"batch","method":"check_libraries","params":{"progress":true,
        "items":[{"index":0,"path":path,"sha1":"a9993e364706816aba3e25717850c26c9cd0d89d"}]}})]);
    assert_eq!(out.len(),2); assert_eq!(out[0]["id"],"batch"); assert_eq!(out[0]["event"],"progress");
    assert_eq!(out[0]["downloaded"],1); assert_eq!(out[0]["total"],1); assert_eq!(out[1]["result"]["results"][0]["matches"],true);
    fs::remove_dir_all(dir).unwrap();
}
#[cfg(unix)]
#[test]
fn generic_file_hash_operation_reports_stable_identity() {
    let dir=temp(); let path=dir.join("asset"); fs::write(&path,b"abc").unwrap();
    let out=call(&[json!({"version":1,"id":1,"method":"check_files","params":{"items":[
        {"index":8,"path":path,"sha1":"a9993e364706816aba3e25717850c26c9cd0d89d"}]}})]);
    let result=&out[0]["result"]["results"][0];
    assert_eq!(result["index"],8); assert_eq!(result["matches"],true);
    assert!(result["mtime_ns"].as_i64().unwrap()>0); assert!(result["ctime_ns"].as_i64().unwrap()>0);
    assert!(result["device"].as_i64().unwrap()>=0); assert!(result["inode"].as_i64().unwrap()>0);
    fs::remove_dir_all(dir).unwrap();
}
