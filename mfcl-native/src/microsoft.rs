// Copyright (C) 2026 MFCL contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//! Microsoft device authorization and Xbox-to-Minecraft token exchange.
//! Secrets are returned to the local caller and are never included in error messages.
use crate::{fail, Error};
use serde_json::{json, Value};
use base64::{engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD}, Engine};
use sha2::{Digest, Sha256};
use url::{Host, Url};
use std::{io::Read, time::Duration};

const AUTHORIZE: &str = "https://login.live.com/oauth20_authorize.srf";
const CODE_TOKEN: &str = "https://login.live.com/oauth20_token.srf";
const DEVICE: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
const TOKEN: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
const XBOX: &str = "https://user.auth.xboxlive.com/user/authenticate";
const XSTS: &str = "https://xsts.auth.xboxlive.com/xsts/authorize";
const LOGIN: &str = "https://api.minecraftservices.com/authentication/login_with_xbox";
const ENTITLEMENTS: &str = "https://api.minecraftservices.com/entitlements/mcstore";
const LICENSE: &str = "https://api.minecraftservices.com/entitlements/license";
const PROFILE: &str = "https://api.minecraftservices.com/minecraft/profile";
const SCOPE: &str = "XboxLive.signin offline_access";
const MAX_RESPONSE: u64 = 1024 * 1024;
const MAX_SKIN: u64 = 8 * 1024 * 1024;

struct Endpoints<'a> { authorize: &'a str, code_token: &'a str, device: &'a str, token: &'a str, xbox: &'a str, xsts: &'a str, login: &'a str, entitlements: &'a str, license: &'a str, profile: &'a str }
const PRODUCTION: Endpoints<'static> = Endpoints { authorize: AUTHORIZE, code_token: CODE_TOKEN, device: DEVICE, token: TOKEN, xbox: XBOX, xsts: XSTS, login: LOGIN, entitlements: ENTITLEMENTS, license: LICENSE, profile: PROFILE };
fn agent() -> ureq::Agent { ureq::AgentBuilder::new().redirects(0).timeout(Duration::from_secs(30)).build() }
fn required<'a>(value: &'a Value, field: &str) -> Result<&'a str, Error> {
    value.get(field).and_then(Value::as_str).filter(|s| !s.is_empty() && s.len() <= 65536)
        .ok_or_else(|| fail("invalid_response", format!("missing or invalid {field}")))
}
fn param<'a>(value: &'a Value, field: &str) -> Result<&'a str, Error> {
    value.get(field).and_then(Value::as_str).filter(|s| !s.is_empty() && s.len() <= 65536)
        .ok_or_else(|| fail("invalid_params", format!("missing or invalid {field}")))
}
fn response(result: Result<ureq::Response, ureq::Error>) -> Result<(u16, Value), Error> {
    let response = match result {
        Ok(value) => value,
        Err(ureq::Error::Status(_, value)) => value,
        Err(_) => return Err(fail("network_error", "authentication request failed")),
    };
    let status = response.status();
    let mut body=Vec::new();
    response.into_reader().take(MAX_RESPONSE+1).read_to_end(&mut body).map_err(|_| fail("network_error", "authentication response read failed"))?;
    if body.len() as u64 > MAX_RESPONSE { return Err(fail("invalid_response", "authentication response exceeds limit")); }
    let value: Value = serde_json::from_slice(&body).map_err(|_| fail("invalid_response", "invalid authentication JSON"))?;
    Ok((status,value))
}
fn post_json(agent: &ureq::Agent, endpoint: &str, body: &Value) -> Result<Value, Error> {
    let (status, value) = response(agent.post(endpoint).set("Accept", "application/json").set("Content-Type", "application/json").send_string(&body.to_string()))?;
    if status != 200 { return Err(fail("authentication_failed", format!("authentication service returned HTTP {status}"))); }
    Ok(value)
}

/// Preserves known Xbox authorization failures without forwarding response bodies or credentials.
fn post_xbox(agent: &ureq::Agent, endpoint: &str, body: &Value, allow_unauthorized: bool) -> Result<Value, Error> {
    let result = agent.post(endpoint).set("Accept", "application/json")
        .set("Content-Type", "application/json").send_string(&body.to_string());
    if matches!(&result, Err(ureq::Error::Status(400, _))) { return Err(fail("xbox_bad_request", "Xbox rejected the authorization request")); }
    let (status, value) = response(result)?;
    if status == 400 { return Err(fail("xbox_bad_request", "Xbox rejected the authorization request")); }
    if status == 401 && !allow_unauthorized { return Err(fail("authentication_failed", "Xbox authentication returned HTTP 401")); }
    if status == 200 || status == 401 {
        let code = value.get("XErr").and_then(|v| v.as_i64().or_else(|| v.as_str().and_then(|s| s.parse().ok())));
        if let Some(code) = code.filter(|v| *v != 0) {
            let redirect = value.get("Redirect").and_then(Value::as_str)
                .filter(|s| s.len() <= 4096 && !s.chars().any(char::is_control)).map(str::to_owned);
            let mut error = fail("xbox_authorization", "Xbox authorization was rejected");
            error.xbox = Some(crate::XboxError { error_code: code, redirect });
            return Err(error);
        }
    }
    if status != 200 && status != 401 { return Err(fail("authentication_failed", format!("authentication service returned HTTP {status}"))); }
    Ok(value)
}
fn post_form(agent: &ureq::Agent, endpoint: &str, fields: &[(&str, &str)]) -> Result<(u16, Value), Error> {
    response(agent.post(endpoint).set("Accept", "application/json").send_form(fields))
}
fn get_json(agent: &ureq::Agent, endpoint: &str, token: &str) -> Result<(u16, Value), Error> {
    if token.contains(['\r', '\n']) { return Err(fail("invalid_response", "invalid authentication token")); }
    response(agent.get(endpoint).set("Accept", "application/json").set("Authorization", &format!("Bearer {token}")).call())
}

/// Reads the current Minecraft profile without logging the authorization header.
fn profile_read_at(params: &Value, endpoint: &str) -> Result<Value, Error> {
    let kind = param(params, "token_type")?; let token = param(params, "access_token")?;
    let user_agent = param(params, "user_agent")?; let language = param(params, "language")?;
    if [kind, token, user_agent, language].iter().any(|s| s.len() > 65_536 || !s.bytes().all(|b| (0x20..=0x7e).contains(&b))) {
        return Err(fail("invalid_params", "unsupported authentication header"));
    }
    let client = ureq::AgentBuilder::new().try_proxy_from_env(false).redirects(0).timeout(Duration::from_secs(30)).build();
    let response = match client.get(endpoint).set("Authorization", &format!("{kind} {token}"))
            .set("User-Agent", user_agent).set("Accept-Language", language).set("Accept-Encoding", "identity").call() {
        Ok(value) => value,
        Err(ureq::Error::Status(_, value)) => value,
        Err(_) => return Err(fail("network_error", "profile request failed")),
    };
    let status = response.status();
    if status != 200 { return Ok(json!({"status":status})); }
    if response.header("Content-Encoding").is_some_and(|v| !v.eq_ignore_ascii_case("identity")) {
        return Err(fail("unsupported_encoding", "unsupported profile response encoding"));
    }
    let content_type = response.header("Content-Type").map(str::to_owned);
    if content_type.as_ref().is_some_and(|v| v.len() > 4096) { return Err(fail("invalid_response", "invalid profile response header")); }
    let mut body = Vec::new();
    response.into_reader().take(MAX_RESPONSE + 1).read_to_end(&mut body).map_err(|_| fail("network_error", "profile response read failed"))?;
    if body.len() as u64 > MAX_RESPONSE { return Err(fail("invalid_response", "profile response exceeds limit")); }
    let profile = project_profile(&body);
    Ok(json!({"status":status,"body_base64":STANDARD.encode(body),"content_type":content_type,"profile_v1":profile}))
}

/// Projects canonical Minecraft profiles; ambiguous or legacy responses keep the Java parser.
fn project_profile(bytes: &[u8]) -> Option<Value> {
    let data = crate::json_unique::parse(bytes).ok()?;
    let object = data.as_object()?;
    if ["path", "errorType", "error", "errorMessage", "developerMessage"].iter()
        .any(|key| object.get(*key).is_some_and(|v| !v.is_null())) { return None; }
    let id = object.get("id")?.as_str()?;
    if id.len() != 32 || !id.bytes().all(|b| b.is_ascii_hexdigit()) { return None; }
    let name = object.get("name")?.as_str()?;
    let mut skins = Vec::new();
    for skin in object.get("skins")?.as_array()? {
        let skin = skin.as_object()?;
        let field = |key: &str| skin.get(key).and_then(Value::as_str);
        let alias = match skin.get("alias") { None | Some(Value::Null) => None, Some(Value::String(s)) => Some(s.as_str()), _ => return None };
        skins.push(json!({"id":field("id")?,"state":field("state")?,"url":field("url")?,"variant":field("variant")?,"alias":alias}));
    }
    let mut capes = Vec::new();
    for cape in object.get("capes")?.as_array()? {
        if cape.is_null() { capes.push(Value::Null); }
        else if cape.is_object() { capes.push(json!({})); }
        else { return None; }
    }
    Some(json!({"id":id,"name":name,"skins":skins,"capes":capes}))
}

/// Decodes canonical persisted account data without emitting credentials to logs.
pub(crate) fn storage(params: &Value) -> Result<Value, Error> {
    let metadata = params.get("metadata").and_then(Value::as_object).ok_or_else(|| fail("invalid_params", "invalid account metadata"))?;
    let private = params.get("private").and_then(Value::as_object).ok_or_else(|| fail("invalid_params", "invalid account data"))?;
    let id = metadata.get("profileID").and_then(Value::as_str).ok_or_else(|| fail("unsupported_metadata", "invalid profile ID"))?;
    let uuid_ok = if id.len() == 32 { id.bytes().all(|b| b.is_ascii_hexdigit()) }
        else if id.len() == 36 { id.bytes().enumerate().all(|(i, b)| if [8, 13, 18, 23].contains(&i) { b == b'-' } else { b.is_ascii_hexdigit() }) }
        else { false };
    if !uuid_ok { return Err(fail("unsupported_metadata", "unsupported profile ID")); }
    let required = |key: &str| private.get(key).and_then(Value::as_str).ok_or_else(|| fail("unsupported_metadata", "invalid account field"));
    let name = match private.get("profileName") { None | Some(Value::Null) => "", Some(Value::String(v)) => v.as_str(),
        _ => return Err(fail("unsupported_metadata", "invalid profile name")) };
    let not_after = match private.get("notAfter") { None | Some(Value::Null) | Some(Value::String(_)) | Some(Value::Bool(_)) => 0,
        Some(Value::Number(v)) => v.as_i64().ok_or_else(|| fail("unsupported_metadata", "invalid session expiration"))?,
        _ => 0 };
    Ok(json!({"profileID":id,"profileName":name,"tokenType":required("tokenType")?,"accessToken":required("accessToken")?,
        "refreshToken":required("refreshToken")?,"notAfter":not_after,"userid":required("userid")?}))
}

#[cfg(test)]
mod profile_projection_tests {
    use super::project_profile;

    #[test]
    fn projects_valid_profiles_and_rejects_ambiguous_fields() {
        let valid = br#"{"id":"00112233445566778899aabbccddeeff","name":"Steve","skins":[{"id":"skin","state":"ACTIVE","url":"https://example.test/skin","variant":"SLIM"}],"capes":[{},null]}"#;
        let profile = project_profile(valid).unwrap();
        assert_eq!(profile["name"], "Steve"); assert_eq!(profile["skins"][0]["alias"], serde_json::Value::Null);
        assert_eq!(profile["capes"].as_array().unwrap().len(), 2);
        assert!(project_profile(br#"{"id":"00112233445566778899aabbccddeeff","name":"a","name":"b","skins":[],"capes":[]}"#).is_none());
        assert!(project_profile(br#"{"id":"bad","name":"Steve","skins":[],"capes":[]}"#).is_none());
    }
}

/// Uploads one skin without logging credentials or retrying a potentially completed request.
fn upload_skin_at(params: &Value, endpoint: &str) -> Result<Value, Error> {
    let token = param(params, "access_token")?;
    if token.contains(['\r', '\n']) { return Err(fail("invalid_params", "invalid authentication token")); }
    let path = param(params, "path")?;
    let slim = params.get("slim").and_then(Value::as_bool).ok_or_else(|| fail("invalid_params", "missing skin variant"))?;
    let mut options = std::fs::OpenOptions::new(); options.read(true);
    #[cfg(unix)] {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NONBLOCK);
    }
    let mut file = options.open(path).map_err(|_| fail("io_error", "cannot open skin file"))?;
    let metadata = file.metadata().map_err(|_| fail("io_error", "cannot inspect skin file"))?;
    if !metadata.is_file() || metadata.len() > MAX_SKIN { return Err(fail("invalid_params", "unsupported skin file")); }
    let mut bytes = Vec::new();
    (&mut file).take(MAX_SKIN + 1).read_to_end(&mut bytes).map_err(|_| fail("io_error", "cannot read skin file"))?;
    if bytes.len() as u64 > MAX_SKIN { return Err(fail("invalid_params", "unsupported skin file")); }
    let digest = format!("{:x}", Sha256::digest(&bytes));
    let mut boundary = format!("mfcl-{}", &digest[..40]);
    for _ in 0..16 {
        if !bytes.windows(boundary.len()).any(|part| part == boundary.as_bytes()) { break; }
        boundary.push('x');
    }
    if bytes.windows(boundary.len()).any(|part| part == boundary.as_bytes()) {
        return Err(fail("invalid_params", "cannot encode skin file"));
    }
    let mut body = format!("--{boundary}\r\nContent-Disposition: form-data; name=\"variant\"\r\n\r\n{}\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\nContent-Type: image/png\r\n\r\n", if slim { "slim" } else { "classic" }).into_bytes();
    body.extend_from_slice(&bytes); body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());
    let response = match agent().post(endpoint).set("Authorization", &format!("Bearer {token}"))
        .set("Content-Type", &format!("multipart/form-data; boundary={boundary}")).send_bytes(&body) {
        Ok(value) => value,
        Err(ureq::Error::Status(_, value)) => value,
        Err(_) => return Err(fail("network_error", "skin upload request failed")),
    };
    let status = response.status(); let mut result = Vec::new();
    response.into_reader().take(MAX_RESPONSE + 1).read_to_end(&mut result).map_err(|_| fail("network_error", "skin upload response read failed"))?;
    if result.len() as u64 > MAX_RESPONSE { return Err(fail("invalid_response", "skin upload response exceeds limit")); }
    if !(200..300).contains(&status) { return Err(fail("authentication_failed", format!("skin upload returned HTTP {status}"))); }
    if !result.iter().all(u8::is_ascii_whitespace) {
        let value: Value = serde_json::from_slice(&result).map_err(|_| fail("invalid_response", "invalid skin upload response"))?;
        if !value.is_object() { return Err(fail("invalid_response", "invalid skin upload response")); }
        if value.get("errorMessage").and_then(Value::as_str).is_some_and(|v| !v.trim().is_empty()) {
            return Err(fail("authentication_failed", "skin upload was rejected"));
        }
    }
    Ok(json!({"uploaded":true}))
}
/// Validates an OAuth redirect URI without allowing plaintext non-loopback redirects.
fn validate_redirect(value: &str) -> Result<(), Error> {
    let url=Url::parse(value).map_err(|_|fail("invalid_params","invalid redirect URI"))?;
    let loopback=match url.host() {
        Some(Host::Domain(name)) => name.eq_ignore_ascii_case("localhost"),
        Some(Host::Ipv4(ip)) => ip.is_loopback(),
        Some(Host::Ipv6(ip)) => ip.is_loopback(),
        None => false,
    };
    if (url.scheme() != "https" && !(url.scheme()=="http" && loopback))
        || url.host().is_none() || !url.username().is_empty() || url.password().is_some() || url.fragment().is_some() {
        return Err(fail("invalid_params","unsupported redirect URI"));
    }
    Ok(())
}
/// Validates the RFC 7636 verifier produced by the existing callback session.
fn verifier(params: &Value) -> Result<&str, Error> {
    let value=param(params,"code_verifier")?;
    if !(43..=128).contains(&value.len()) || !value.bytes().all(|b|b.is_ascii_alphanumeric() || matches!(b,b'-'|b'.'|b'_'|b'~')) {
        return Err(fail("invalid_params","invalid PKCE verifier"));
    }
    Ok(value)
}
/// Builds the browser URL without exposing the verifier in the URL.
fn authorization_url_at(params: &Value, endpoints: &Endpoints<'_>) -> Result<Value, Error> {
    let client=param(params,"client_id")?; let redirect=param(params,"redirect_uri")?; let state=param(params,"state")?;
    validate_redirect(redirect)?; let challenge=URL_SAFE_NO_PAD.encode(Sha256::digest(verifier(params)?.as_bytes()));
    let mut url=Url::parse(endpoints.authorize).map_err(|_|fail("internal_error","invalid authorization endpoint"))?;
    url.query_pairs_mut().append_pair("client_id",client).append_pair("response_type","code")
        .append_pair("redirect_uri",redirect).append_pair("scope",SCOPE).append_pair("prompt","select_account")
        .append_pair("code_challenge",&challenge).append_pair("state",state).append_pair("code_challenge_method","S256");
    Ok(json!({"authorization_url":url.as_str()}))
}
/// Exchanges an authorization code for Microsoft access and refresh tokens.
fn authorization_token_at(params: &Value, endpoints: &Endpoints<'_>) -> Result<Value, Error> {
    let client=param(params,"client_id")?; let code=param(params,"code")?; let redirect=param(params,"redirect_uri")?;
    validate_redirect(redirect)?; let verifier=verifier(params)?;
    let (status,v)=post_form(&agent(),endpoints.code_token,&[("client_id",client),("code",code),
        ("grant_type","authorization_code"),("code_verifier",verifier),("redirect_uri",redirect),("scope",SCOPE)])?;
    if status != 200 {
        let code=if v.get("error").and_then(Value::as_str)==Some("invalid_grant") { "invalid_grant" } else { "authentication_failed" };
        return Err(fail(code,"Microsoft authorization code exchange failed"));
    }
    Ok(json!({"access_token":required(&v,"access_token")?,"refresh_token":required(&v,"refresh_token")?}))
}
fn device_begin_at(params: &Value, endpoints: &Endpoints<'_>) -> Result<Value, Error> {
    let client_id = param(params, "client_id")?;
    let (status, v) = post_form(&agent(), endpoints.device, &[("client_id",client_id),("scope",SCOPE)])?;
    if status != 200 { return Err(fail("authentication_failed", format!("device authorization returned HTTP {status}"))); }
    let expires = v.get("expires_in").and_then(Value::as_u64).filter(|v| *v > 0).ok_or_else(|| fail("invalid_response", "invalid device expiration"))?;
    let interval = v.get("interval").and_then(Value::as_u64).filter(|v| *v > 0).ok_or_else(|| fail("invalid_response", "invalid polling interval"))?;
    Ok(json!({"device_code":required(&v,"device_code")?,"user_code":required(&v,"user_code")?,"verification_uri":required(&v,"verification_uri")?,"expires_in":expires,"interval":interval}))
}
fn token_at(params: &Value, endpoints: &Endpoints<'_>, refresh: bool) -> Result<Value, Error> {
    let client = param(params,"client_id")?;
    let key = if refresh { "refresh_token" } else { "device_code" };
    let secret = param(params,key)?;
    let grant = if refresh { "refresh_token" } else { "urn:ietf:params:oauth:grant-type:device_code" };
    let secret_key = if refresh { "refresh_token" } else { "device_code" };
    let (status,v)=post_form(&agent(), endpoints.token, &[("client_id",client),("grant_type",grant),(secret_key,secret)])?;
    if status != 200 {
        let code = match v.get("error").and_then(Value::as_str) {
            Some("authorization_pending") => "authorization_pending", Some("slow_down") => "slow_down",
            Some("expired_token") => "expired_token", Some("authorization_declined") => "authorization_declined",
            Some("invalid_grant") => "invalid_grant", _ => "authentication_failed",
        };
        return Err(fail(code,"Microsoft authorization did not complete"));
    }
    Ok(json!({"access_token":required(&v,"access_token")?,"refresh_token":required(&v,"refresh_token")?}))
}
fn xbox_fields(v: &Value) -> Result<(&str,&str), Error> {
    let user=v.get("DisplayClaims").and_then(|v|v.get("xui")).and_then(Value::as_array).and_then(|v|v.first()).and_then(|v|v.get("uhs")).and_then(Value::as_str).filter(|v|!v.is_empty())
        .ok_or_else(|| fail("xbox_missing_user_hash", "missing Xbox user hash"))?;
    let token=required(v,"Token")?;
    Ok((token,user))
}
fn exchange_at(params: &Value, e: &Endpoints<'_>) -> Result<Value, Error> {
    let access=param(params,"access_token")?;
    let client=agent();
    let xbox=post_xbox(&client,e.xbox,&json!({"Properties":{"AuthMethod":"RPS","SiteName":"user.auth.xboxlive.com","RpsTicket":format!("d={access}")},"RelyingParty":"http://auth.xboxlive.com","TokenType":"JWT"}),false)?;
    let (xbox_token, uhs)=xbox_fields(&xbox)?;
    let xsts=post_xbox(&client,e.xsts,&json!({"Properties":{"SandboxId":"RETAIL","UserTokens":[xbox_token]},"RelyingParty":"rp://api.minecraftservices.com/","TokenType":"JWT"}),true)?;
    let (xsts_token,xsts_uhs)=xbox_fields(&xsts)?;
    if uhs != xsts_uhs { return Err(fail("identity_mismatch","Xbox user hashes do not match")); }
    let game=post_json(&client,e.login,&json!({"identityToken":format!("XBL3.0 x={uhs};{xsts_token}")}))?;
    let game_token=required(&game,"access_token")?; let token_type=required(&game,"token_type")?;
    let username=required(&game,"username")?;
    if !token_type.eq_ignore_ascii_case("bearer") { return Err(fail("invalid_response","unsupported token type")); }
    let expires=game.get("expires_in").and_then(Value::as_u64).filter(|v| *v > 0).ok_or_else(|| fail("invalid_response","invalid token lifetime"))?;
    let (status,_)=get_json(&client,e.entitlements,game_token)?;
    if status != 200 { return Err(fail("entitlement_check_failed",format!("entitlement service returned HTTP {status}"))); }
    let (status,profile)=get_json(&client,e.profile,game_token)?;
    if status == 404 {
        let (license_status, license) = get_json(&client,e.license,game_token)?;
        if license_status != 200 { return Err(fail("entitlement_check_failed",format!("license service returned HTTP {license_status}"))); }
        let has_license = license.get("items").and_then(Value::as_array).is_some_and(|items|
            items.iter().any(|item| item.get("name").and_then(Value::as_str) == Some("game_minecraft")));
        return if has_license { Err(fail("profile_not_found","Minecraft profile was not found")) }
            else { Err(fail("license_not_found","Minecraft Java Edition license was not found")) };
    }
    if status != 200 { return Err(fail("authentication_failed",format!("profile service returned HTTP {status}"))); }
    let id=required(&profile,"id")?; let name=required(&profile,"name")?;
    if id.len()!=32 || !id.bytes().all(|c|c.is_ascii_hexdigit()) { return Err(fail("invalid_response","invalid Minecraft profile ID")); }
    Ok(json!({"access_token":game_token,"token_type":token_type,"expires_in":expires,"user_id":username,"profile":{"id":id,"name":name}}))
}
/// Uses fixed production endpoints; a separately compiled test feature can target numeric loopback.
fn with_endpoints<F>(operation: F) -> Result<Value, Error>
where F: FnOnce(&Endpoints<'_>) -> Result<Value, Error> {
    #[cfg(feature = "auth-test-endpoints")]
    if let Ok(raw) = std::env::var("MFCL_AUTH_TEST_BASE") {
        let url = Url::parse(&raw).map_err(|_| fail("invalid_params", "invalid test endpoint"))?;
        let loopback = match url.host() { Some(Host::Ipv4(ip)) => ip.is_loopback(), Some(Host::Ipv6(ip)) => ip.is_loopback(), _ => false };
        if url.scheme() != "http" || !loopback || url.port().is_none() || url.path() != "/"
            || !url.username().is_empty() || url.password().is_some() || url.query().is_some() || url.fragment().is_some() {
            return Err(fail("invalid_params", "test endpoint must be a numeric loopback HTTP origin"));
        }
        let base = raw.trim_end_matches('/');
        let code_token = format!("{base}/code-token"); let device = format!("{base}/device");
        let token = format!("{base}/token"); let xbox = format!("{base}/xbox");
        let xsts = format!("{base}/xsts"); let login = format!("{base}/login");
        let entitlements = format!("{base}/entitlements"); let license = format!("{base}/license");
        let profile = format!("{base}/profile");
        let endpoints = Endpoints { authorize: AUTHORIZE, code_token: &code_token, device: &device,
            token: &token, xbox: &xbox, xsts: &xsts, login: &login, entitlements: &entitlements,
            license: &license, profile: &profile };
        return operation(&endpoints);
    }
    operation(&PRODUCTION)
}
/// Exchanges Microsoft tokens into a final session without returning intermediate access tokens.
fn session_from_tokens(tokens: &Value, endpoints: &Endpoints<'_>) -> Result<Value, Error> {
    let access = required(tokens, "access_token")?;
    let refresh = required(tokens, "refresh_token")?;
    let mut result = exchange_at(&json!({"access_token":access}), endpoints)?;
    result.as_object_mut().ok_or_else(|| fail("invalid_response", "invalid Minecraft session"))?
        .insert("refresh_token".to_owned(), json!(refresh));
    Ok(result)
}

/// Exchanges a one-time authorization code and produces a complete Minecraft session.
pub(crate) fn authorization_session(p: &Value) -> Result<Value, Error> {
    with_endpoints(|endpoints| session_from_tokens(&authorization_token_at(p, endpoints)?, endpoints))
}

/// Refreshes Microsoft credentials and produces a complete Minecraft session.
pub(crate) fn refresh_session(p: &Value) -> Result<Value, Error> {
    with_endpoints(|endpoints| session_from_tokens(&token_at(p, endpoints, true)?, endpoints))
}

pub(crate) fn authorization_url(p:&Value)->Result<Value,Error>{with_endpoints(|e|authorization_url_at(p,e))}
pub(crate) fn authorization_token(p:&Value)->Result<Value,Error>{with_endpoints(|e|authorization_token_at(p,e))}
pub(crate) fn device_begin(p:&Value)->Result<Value,Error>{with_endpoints(|e|device_begin_at(p,e))}
pub(crate) fn device_poll(p:&Value)->Result<Value,Error>{with_endpoints(|e|token_at(p,e,false))}
pub(crate) fn refresh(p:&Value)->Result<Value,Error>{with_endpoints(|e|token_at(p,e,true))}
pub(crate) fn exchange(p:&Value)->Result<Value,Error>{with_endpoints(|e|exchange_at(p,e))}
pub(crate) fn upload_skin(p:&Value)->Result<Value,Error>{with_endpoints(|e|upload_skin_at(p,&format!("{}/skins",e.profile)))}
pub(crate) fn profile_read(p:&Value)->Result<Value,Error>{with_endpoints(|e|profile_read_at(p,e.profile))}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{io::{Read, Write}, net::TcpListener, sync::mpsc, thread};

    /// Serves fixed JSON replies while recording complete HTTP requests.
    fn fixture(replies: Vec<(u16, &'static str)>) -> (String, mpsc::Receiver<Vec<String>>, thread::JoinHandle<()>) {
        let listener=TcpListener::bind("127.0.0.1:0").unwrap();
        let addr=listener.local_addr().unwrap(); let (sender,receiver)=mpsc::channel();
        let handle=thread::spawn(move || {
            let mut requests=Vec::new();
            for (status,body) in replies {
                let (mut stream,_)=listener.accept().unwrap();
                stream.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                let mut bytes=Vec::new(); let mut buffer=[0u8;4096];
                let header_end=loop { let n=stream.read(&mut buffer).unwrap(); assert!(n>0); bytes.extend_from_slice(&buffer[..n]); if let Some(pos)=bytes.windows(4).position(|v|v==b"\r\n\r\n") { break pos+4; } };
                let headers=String::from_utf8_lossy(&bytes[..header_end]);
                let length=headers.lines().find_map(|line|line.split_once(':').filter(|(k,_)|k.eq_ignore_ascii_case("content-length")).and_then(|(_,v)|v.trim().parse::<usize>().ok())).unwrap_or(0);
                while bytes.len()<header_end+length { let n=stream.read(&mut buffer).unwrap(); assert!(n>0); bytes.extend_from_slice(&buffer[..n]); }
                requests.push(String::from_utf8(bytes).unwrap());
                write!(stream,"HTTP/1.1 {status} OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).unwrap();
            }
            sender.send(requests).unwrap();
        });
        (format!("http://{addr}/"),receiver,handle)
    }
    fn endpoints<'a>(url:&'a str)->Endpoints<'a>{Endpoints{authorize:url,code_token:url,device:url,token:url,xbox:url,xsts:url,login:url,entitlements:url,license:url,profile:url}}

    /// Profile reads forward authorization without exposing it in protocol responses.
    #[test]
    fn profile_read_preserves_status_and_redacts_credentials() {
        let (url, rx, server) = fixture(vec![(200, r#"{"name":"Steve"}"#), (401, "denied")]);
        let params = json!({"token_type":"Bearer","access_token":"private-token","user_agent":"MFCL","language":"en"});
        let success = profile_read_at(&params, &url).unwrap_or_else(|_| panic!("profile read failed"));
        assert_eq!(success["status"], 200);
        assert_eq!(STANDARD.decode(success["body_base64"].as_str().unwrap()).unwrap(), br#"{"name":"Steve"}"#);
        let denied = profile_read_at(&params, &url).unwrap_or_else(|_| panic!("profile status failed"));
        assert_eq!(denied["status"], 401); assert!(!denied.to_string().contains("private-token"));
        let requests = rx.recv_timeout(Duration::from_secs(5)).unwrap(); server.join().unwrap();
        assert!(requests[0].to_ascii_lowercase().contains("authorization: bearer private-token"));
    }
    #[test]
    fn authorization_url_uses_pkce_without_exposing_verifier() {
        let e=endpoints("http://127.0.0.1/");
        let verifier="dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        let result=authorization_url_at(&json!({"client_id":"client","redirect_uri":"http://localhost:29111/auth-response","state":"state+value","code_verifier":verifier}),&e).unwrap_or_else(|_|panic!("authorization URL failed"));
        let raw=result["authorization_url"].as_str().unwrap();
        assert!(!raw.contains(verifier));
        let url=Url::parse(raw).unwrap(); let query:std::collections::HashMap<_,_>=url.query_pairs().into_owned().collect();
        assert_eq!(query["client_id"],"client"); assert_eq!(query["state"],"state+value");
        assert_eq!(query["redirect_uri"],"http://localhost:29111/auth-response");
        assert_eq!(query["code_challenge"],"E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        assert_eq!(query["code_challenge_method"],"S256");
    }
    #[test]
    fn authorization_rejects_insecure_redirect_and_bad_verifier() {
        let e=endpoints("http://127.0.0.1/"); let good="dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        let invalid=authorization_url_at(&json!({"client_id":"client","redirect_uri":"http://example.com/callback","state":"x","code_verifier":good}),&e).err().unwrap();
        assert_eq!(invalid.code,"invalid_params");
        let invalid=authorization_url_at(&json!({"client_id":"client","redirect_uri":"http://localhost:29111/auth-response","state":"x","code_verifier":"short"}),&e).err().unwrap();
        assert_eq!(invalid.code,"invalid_params");
    }
    #[test]
    fn authorization_code_exchange_sends_expected_form_and_redacts_errors() {
        let (url,rx,server)=fixture(vec![
            (200,r#"{"access_token":"live-secret","refresh_token":"refresh-secret"}"#),
            (400,r#"{"error":"invalid_grant","error_description":"private-token"}"#),
        ]);
        let e=endpoints(&url); let request=json!({"client_id":"client","redirect_uri":"http://localhost:29111/auth-response","code":"code-secret","code_verifier":"dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"});
        let result=authorization_token_at(&request,&e).unwrap_or_else(|_|panic!("exchange failed")); assert_eq!(result["access_token"],"live-secret");
        let error=authorization_token_at(&request,&e).err().unwrap(); assert_eq!(error.code,"invalid_grant"); assert!(!error.message.contains("private-token"));
        let requests=rx.recv_timeout(Duration::from_secs(5)).unwrap();server.join().unwrap();
        assert!(requests[0].contains("grant_type=authorization_code")); assert!(requests[0].contains("code=code-secret"));
        assert!(requests[0].contains("code_verifier=dBjftJeZ4CVP")); assert!(requests[0].contains("redirect_uri=http%3A%2F%2Flocalhost%3A29111%2Fauth-response"));
    }
    #[test]
    fn device_and_token_flow_uses_correct_forms() {
        let (url,rx,server)=fixture(vec![
            (200,r#"{"device_code":"device-secret","user_code":"ABCD","verification_uri":"https://microsoft.com/devicelogin","expires_in":900,"interval":5}"#),
            (400,r#"{"error":"authorization_pending","error_description":"do not expose device-secret"}"#),
            (200,r#"{"access_token":"live-secret","refresh_token":"refresh-secret"}"#),
            (200,r#"{"access_token":"new-secret","refresh_token":"new-refresh"}"#),
        ]);
        let e=endpoints(&url);
        let device=device_begin_at(&json!({"client_id":"client-1"}),&e).unwrap_or_else(|_|panic!("device failed"));
        assert_eq!(device["user_code"],"ABCD");
        let pending=token_at(&json!({"client_id":"client-1","device_code":"device-secret"}),&e,false).err().unwrap();
        assert_eq!(pending.code,"authorization_pending"); assert!(!pending.message.contains("device-secret"));
        assert_eq!(token_at(&json!({"client_id":"client-1","device_code":"device-secret"}),&e,false).unwrap_or_else(|_|panic!("poll failed"))["access_token"],"live-secret");
        assert_eq!(token_at(&json!({"client_id":"client-1","refresh_token":"refresh-secret"}),&e,true).unwrap_or_else(|_|panic!("refresh failed"))["refresh_token"],"new-refresh");
        let requests=rx.recv_timeout(Duration::from_secs(5)).unwrap(); server.join().unwrap();
        assert!(requests[0].contains("client_id=client-1")); assert!(requests[0].contains("XboxLive.signin"));
        assert!(requests[1].contains("device_code=device-secret")); assert!(requests[3].contains("refresh_token=refresh-secret"));
    }
    #[test]
    fn exchange_checks_identity_and_fetches_profile() {
        let (url,rx,server)=fixture(vec![
            (200,r#"{"Token":"xbox-secret","DisplayClaims":{"xui":[{"uhs":"user-hash"}]}}"#),
            (200,r#"{"Token":"xsts-secret","DisplayClaims":{"xui":[{"uhs":"user-hash"}]}}"#),
            (200,r#"{"access_token":"game-secret","token_type":"Bearer","expires_in":3600,"username":"xbox-user"}"#),
            (200,r#"{"items":[{"name":"game_minecraft"}]}"#),
            (200,r#"{"id":"0123456789abcdef0123456789abcdef","name":"Steve"}"#),
        ]);
        let result=exchange_at(&json!({"access_token":"live-secret"}),&endpoints(&url)).unwrap_or_else(|_|panic!("exchange failed"));
        assert_eq!(result["profile"]["name"],"Steve"); assert_eq!(result["access_token"],"game-secret"); assert_eq!(result["user_id"],"xbox-user");
        let requests=rx.recv_timeout(Duration::from_secs(5)).unwrap();server.join().unwrap();
        assert!(requests[0].contains("d=live-secret")); assert!(requests[1].contains("xbox-secret"));
        assert!(requests[2].contains("XBL3.0 x=user-hash;xsts-secret"));
        assert!(requests[3].to_ascii_lowercase().contains("authorization: bearer game-secret"));
        assert!(requests[4].to_ascii_lowercase().contains("authorization: bearer game-secret"));
    }
    #[test]
    fn missing_profile_distinguishes_missing_license() {
        for (license, expected) in [
            (r#"{"items":[{"name":"game_minecraft"}]}"#, "profile_not_found"),
            (r#"{"items":[]}"#, "license_not_found"),
        ] {
            let (url,rx,server)=fixture(vec![
                (200,r#"{"Token":"xbox-secret","DisplayClaims":{"xui":[{"uhs":"user-hash"}]}}"#),
                (200,r#"{"Token":"xsts-secret","DisplayClaims":{"xui":[{"uhs":"user-hash"}]}}"#),
                (200,r#"{"access_token":"game-secret","token_type":"Bearer","expires_in":3600,"username":"xbox-user"}"#),
                (200,r#"{"items":[]}"#), (404,r#"{}"#), (200,license),
            ]);
            let err=exchange_at(&json!({"access_token":"live-secret"}),&endpoints(&url)).err().unwrap();
            assert_eq!(err.code,expected); assert_eq!(rx.recv_timeout(Duration::from_secs(5)).unwrap().len(),6); server.join().unwrap();
        }
    }
    #[test]
    fn exchange_rejects_mismatched_xbox_user_hash() {
        let (url,rx,server)=fixture(vec![
            (200,r#"{"Token":"xbox-secret","DisplayClaims":{"xui":[{"uhs":"one"}]}}"#),
            (200,r#"{"Token":"xsts-secret","DisplayClaims":{"xui":[{"uhs":"two"}]}}"#),
        ]);
        let err=exchange_at(&json!({"access_token":"live-secret"}),&endpoints(&url)).err().unwrap();
        assert_eq!(err.code,"identity_mismatch"); assert!(!err.message.contains("secret"));
        assert_eq!(rx.recv_timeout(Duration::from_secs(5)).unwrap().len(),2);server.join().unwrap();
    }
    #[test]
    fn skin_upload_sends_multipart_once_and_redacts_rejections() {
        let mut skin = tempfile::NamedTempFile::new().unwrap(); skin.write_all(b"skin-fixture").unwrap();
        let params = json!({"access_token":"game-secret","slim":true,"path":skin.path()});
        let (url, rx, server) = fixture(vec![(204, "")]);
        assert_eq!(upload_skin_at(&params, &url).unwrap_or_else(|_| panic!("skin upload failed"))["uploaded"], true);
        let requests = rx.recv_timeout(Duration::from_secs(5)).unwrap(); server.join().unwrap();
        assert_eq!(requests.len(), 1);
        assert!(requests[0].to_ascii_lowercase().contains("authorization: bearer game-secret"));
        assert!(requests[0].contains("name=\"variant\"\r\n\r\nslim"));
        assert!(requests[0].contains("filename=\"skin.png\""));
        assert!(requests[0].contains("skin-fixture"));
        let (url, rx, server) = fixture(vec![(403, "{\"errorMessage\":\"secret\"}")]);
        let error = upload_skin_at(&params, &url).err().unwrap();
        assert_eq!(error.code, "authentication_failed"); assert!(!error.message.contains("secret"));
        assert_eq!(rx.recv_timeout(Duration::from_secs(5)).unwrap().len(), 1); server.join().unwrap();
    }
}
