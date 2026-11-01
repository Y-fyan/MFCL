/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.microsoft;

import com.google.gson.JsonObject;
import org.glavo.uuid.UUIDs;
import org.jackhuang.hmcl.auth.AuthenticationException;
import org.jackhuang.hmcl.auth.CredentialExpiredException;
import org.jackhuang.hmcl.auth.NoSelectedCharacterException;
import org.jackhuang.hmcl.auth.ServerResponseMalformedException;
import org.jackhuang.hmcl.auth.OAuth;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/// Coordinates the device-code and refresh flows with the native authentication backend.
@NotNullByDefault
final class NativeMicrosoftAuth {
    /// Native executable selected by launcher configuration.
    private final Path executable;
    /// Existing launcher callback for device authorization.
    private final OAuth.Callback callback;

    /// Creates a coordinator bound to one executable and callback.
    ///
    /// @param executable native backend executable
    /// @param callback launcher authorization callback
    NativeMicrosoftAuth(Path executable, OAuth.Callback callback) {
        this.executable = executable;
        this.callback = callback;
    }

    /// Uploads a PNG skin once through the native backend.
    /// @param accessToken Minecraft access token
    /// @param slim whether the slim model is selected
    /// @param file source PNG file
    /// @throws IOException if the upload fails
    /// @throws InterruptedException if the request is interrupted
    void uploadSkin(String accessToken, boolean slim, Path file) throws IOException, InterruptedException {
        Logger.registerAccessToken(accessToken);
        JsonObject input = new JsonObject();
        input.addProperty("access_token", accessToken);
        input.addProperty("slim", slim);
        input.addProperty("path", file.toAbsolutePath().toString());
        JsonObject result = NativeBackend.microsoft(executable, "microsoft_upload_skin", input);
        if (!result.has("uploaded") || !result.get("uploaded").isJsonPrimitive()
                || !result.get("uploaded").getAsJsonPrimitive().isBoolean() || !result.get("uploaded").getAsBoolean())
            throw new IOException("Invalid native skin upload response");
    }

    /// Runs the browser authorization-code flow with the existing state-validating callback.
    ///
    /// The callback server owns and checks the state. The PKCE verifier and authorization code
    /// are sent over the native child standard input and must never be logged.
    ///
    /// @return a Minecraft session
    /// @throws IOException if native communication or the callback fails
    /// @throws InterruptedException if cancelled
    /// @throws AuthenticationException if authorization is rejected
    MicrosoftSession authenticateAuthorizationCode() throws IOException, InterruptedException, AuthenticationException {
        boolean combined = supportsCombinedSession("microsoft_authorization_session_v1");
        try (OAuth.Session session = callback.startServer()) {
            String verifier = session.getCodeVerifier();
            String state = session.getState();
            String redirect = session.getRedirectURI();
            Logger.registerAccessToken(verifier);
            Logger.registerAccessToken(state);
            JsonObject input = clientParams();
            input.addProperty("redirect_uri", redirect);
            input.addProperty("state", state);
            input.addProperty("code_verifier", verifier);
            JsonObject response = NativeBackend.microsoft(executable, "microsoft_authorization_url", input);
            String authorizationUrl = required(response, "authorization_url");
            try {
                URI parsed = URI.create(authorizationUrl);
                if (!"https".equalsIgnoreCase(parsed.getScheme()) || !"login.live.com".equalsIgnoreCase(parsed.getHost())
                        || !"/oauth20_authorize.srf".equals(parsed.getPath()) || parsed.getUserInfo() != null
                        || (parsed.getPort() != -1 && parsed.getPort() != 443)
                        || parsed.getFragment() != null || authorizationUrl.contains(verifier)) throw new IOException("Invalid native authorization URL");
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid native authorization URL", e);
            }
            callback.openBrowser(OAuth.GrantFlow.AUTHORIZATION_CODE, authorizationUrl);
            String code;
            try {
                code = session.waitFor();
            } catch (ExecutionException e) {
                @Nullable Throwable cause = e.getCause();
                if (cause instanceof InterruptedException interrupted) throw interrupted;
                if (cause instanceof AuthenticationException authentication) throw authentication;
                throw new IOException("Authorization callback failed");
            }
            Logger.registerAccessToken(code);
            input.remove("state");
            input.addProperty("code", code);
            if (combined) return combinedSession("microsoft_authorization_session", input);
            try {
                return exchange(NativeBackend.microsoft(executable, "microsoft_authorization_token", input));
            } catch (NativeBackend.OperationException e) {
                if ("invalid_grant".equals(e.code())) throw new CredentialExpiredException();
                throw e;
            }
        }
    }

    /// Runs device authorization and exchanges the resulting Microsoft token.
    ///
    /// @return a Minecraft session
    /// @throws IOException if a native request or callback fails
    /// @throws InterruptedException if the login is cancelled
    /// @throws AuthenticationException if the user declines or the code expires
    MicrosoftSession authenticate() throws IOException, InterruptedException, AuthenticationException {
        String clientId = callback.getClientId();
        if (clientId != null && clientId.length() <= 1024 && java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(clientId)) {
            boolean stream = false;
            try { stream = NativeBackend.supportsCapabilities(executable, "microsoft_device_session_v2"); }
            catch (IOException | RuntimeException ignored) { }
            if (stream) return authenticateDeviceStream(clientId);
        }
        JsonObject input = clientParams();
        JsonObject device = NativeBackend.microsoft(executable, "microsoft_device_begin", input);
        String deviceCode = required(device, "device_code");
        Logger.registerAccessToken(deviceCode);
        String userCode = required(device, "user_code");
        String verificationUri = required(device, "verification_uri");
        long expires = Math.min(900, positive(device, "expires_in"));
        long interval = Math.min(900, positive(device, "interval"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(expires);
        callback.grantDeviceCode(userCode, verificationUri);
        callback.openBrowser(OAuth.GrantFlow.DEVICE, verificationUri);
        input.addProperty("device_code", deviceCode);
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) throw new NoSelectedCharacterException();
            TimeUnit.NANOSECONDS.sleep(Math.min(left, TimeUnit.SECONDS.toNanos(interval)));
            if (System.nanoTime() >= deadline) throw new NoSelectedCharacterException();
            try {
                JsonObject result = NativeBackend.microsoft(executable, "microsoft_device_poll", input);
                callback.loginCompletedDeviceCode();
                return exchange(result);
            } catch (NativeBackend.OperationException e) {
                switch (e.code()) {
                    case "authorization_pending" -> { }
                    case "slow_down" -> interval = Math.min(900, interval + 5);
                    case "expired_token", "authorization_declined" -> throw new NoSelectedCharacterException();
                    default -> throw e;
                }
            }
        }
    }

    /// Runs native device polling after the UI has displayed the user code.
    /// @param clientId configured Microsoft client ID
    /// @return authenticated Minecraft session
    /// @throws IOException if startup or polling fails
    /// @throws InterruptedException if cancelled
    /// @throws AuthenticationException if authorization is denied or expires
    private MicrosoftSession authenticateDeviceStream(String clientId) throws IOException, InterruptedException, AuthenticationException {
        try (NativeMicrosoftDevice device = NativeMicrosoftDevice.start(executable, clientId)) {
            callback.grantDeviceCode(device.userCode(), device.verificationUri());
            callback.openBrowser(OAuth.GrantFlow.DEVICE, device.verificationUri());
            try {
                JsonObject result = device.poll(callback::loginCompletedDeviceCode);
                String refreshToken = required(result, "refresh_token");
                Logger.registerAccessToken(refreshToken);
                return parseSession(result, refreshToken);
            } catch (NativeMicrosoftDevice.DeviceFailure e) {
                if ("expired_token".equals(e.code()) || "authorization_declined".equals(e.code())) throw new NoSelectedCharacterException();
                if ("license_not_found".equals(e.code())) throw new MicrosoftService.MinecraftJavaEditionLicenseNotFoundException();
                if ("profile_not_found".equals(e.code())) throw new MicrosoftService.MinecraftJavaEditionProfileNotFoundException();
                @Nullable AuthenticationException xboxError = xboxFailure(e.code(), e.xbox());
                if (xboxError != null) throw xboxError;
                throw e;
            }
        }
    }

    /// Refreshes a saved Microsoft session using its refresh token.
    ///
    /// @param oldSession current saved session
    /// @return refreshed Minecraft session
    /// @throws IOException on protocol or network failure
    /// @throws InterruptedException if interrupted
    /// @throws AuthenticationException if the refresh credential has expired
    MicrosoftSession refresh(MicrosoftSession oldSession) throws IOException, InterruptedException, AuthenticationException {
        JsonObject input = clientParams();
        input.addProperty("refresh_token", oldSession.refreshToken());
        if (supportsCombinedSession("microsoft_refresh_session_v1")) return combinedSession("microsoft_refresh_session", input);
        try {
            return exchange(NativeBackend.microsoft(executable, "microsoft_refresh", input));
        } catch (NativeBackend.OperationException e) {
            if ("invalid_grant".equals(e.code())) throw new CredentialExpiredException();
            throw e;
        }
    }

    /// Checks combined-session support before a one-time credential is sent.
    /// @param capability required native capability
    /// @return true when the backend supports the combined operation
    /// @throws InterruptedException if interrupted
    private boolean supportsCombinedSession(String capability) throws InterruptedException {
        try { return NativeBackend.supportsCapabilities(executable, capability); }
        catch (IOException | RuntimeException e) { return false; }
    }

    /// Runs a complete native token and Minecraft session exchange without replay.
    /// @param method combined native operation
    /// @param input authorization or refresh parameters
    /// @return authenticated session
    /// @throws IOException on transport or response failure
    /// @throws InterruptedException if interrupted
    /// @throws AuthenticationException on expired credentials or missing license/profile
    private MicrosoftSession combinedSession(String method, JsonObject input) throws IOException, InterruptedException, AuthenticationException {
        JsonObject result;
        try { result = NativeBackend.microsoft(executable, method, input); }
        catch (NativeBackend.OperationException e) {
            if ("invalid_grant".equals(e.code())) throw new CredentialExpiredException();
            if ("license_not_found".equals(e.code())) throw new MicrosoftService.MinecraftJavaEditionLicenseNotFoundException();
            if ("profile_not_found".equals(e.code())) throw new MicrosoftService.MinecraftJavaEditionProfileNotFoundException();
            @Nullable AuthenticationException xboxError = xboxFailure(e.code(), e.xbox());
            if (xboxError != null) throw xboxError;
            throw e;
        }
        String refreshToken = required(result, "refresh_token");
        Logger.registerAccessToken(refreshToken);
        return parseSession(result, refreshToken);
    }

    /// Exchanges Microsoft tokens for a validated Minecraft session.
    ///
    /// @param tokens native token response, including the refresh token
    /// @return the resulting Minecraft session
    /// @throws IOException if exchange or response parsing fails
    /// @throws InterruptedException if interrupted
    /// @throws AuthenticationException if the profile or license is missing
    private MicrosoftSession exchange(JsonObject tokens) throws IOException, InterruptedException, AuthenticationException {
        String liveToken = required(tokens, "access_token");
        String refreshToken = required(tokens, "refresh_token");
        Logger.registerAccessToken(liveToken);
        Logger.registerAccessToken(refreshToken);
        JsonObject input = new JsonObject();
        input.addProperty("access_token", liveToken);
        JsonObject result;
        try {
            result = NativeBackend.microsoft(executable, "microsoft_exchange", input);
        } catch (NativeBackend.OperationException e) {
            if ("license_not_found".equals(e.code())) throw new MicrosoftService.MinecraftJavaEditionLicenseNotFoundException();
            if ("profile_not_found".equals(e.code())) throw new MicrosoftService.MinecraftJavaEditionProfileNotFoundException();
            @Nullable AuthenticationException xboxError = xboxFailure(e.code(), e.xbox());
            if (xboxError != null) throw xboxError;
            throw e;
        }
        return parseSession(result, refreshToken);
    }

    /// Maps sanitized native Xbox errors to the launcher's existing user-facing error types.
    private static @Nullable AuthenticationException xboxFailure(String code, @Nullable NativeBackend.XboxFailure details) {
        if ("xbox_authorization".equals(code) && details != null)
            return new MicrosoftService.XboxAuthorizationException(details.errorCode(), details.redirect());
        long xboxCode = switch (code) {
            case "xbox_banned" -> MicrosoftService.XboxAuthorizationException.BANNED;
            case "xbox_missing_account" -> MicrosoftService.XboxAuthorizationException.MISSING_XBOX_ACCOUNT;
            case "xbox_country_unavailable" -> MicrosoftService.XboxAuthorizationException.COUNTRY_UNAVAILABLE;
            case "xbox_family_required" -> MicrosoftService.XboxAuthorizationException.ADD_FAMILY;
            default -> 0L;
        };
        if (xboxCode != 0) return new MicrosoftService.XboxAuthorizationException(xboxCode, null);
        return switch (code) {
            case "xbox_bad_request" -> new MicrosoftService.XBox400Exception();
            case "xbox_missing_user_hash" -> new MicrosoftService.NoXuiException();
            case "identity_mismatch" -> new ServerResponseMalformedException("uhs mismatched");
            default -> null;
        };
    }

    /// Builds the Java session from validated native exchange fields.
    /// @param result native Minecraft session fields
    /// @param refreshToken associated Microsoft refresh token
    /// @return launcher session
    /// @throws IOException on malformed fields
    private static MicrosoftSession parseSession(JsonObject result, String refreshToken) throws IOException {
        try {
            String token = required(result, "access_token");
            long notAfter = Math.addExact(System.currentTimeMillis(), Math.multiplyExact(positive(result, "expires_in"), 1000L));
            JsonObject profile = result.getAsJsonObject("profile");
            return new MicrosoftSession(required(result, "token_type"), token, notAfter, refreshToken,
                    new MicrosoftSession.User(required(result, "user_id")),
                    new MicrosoftSession.GameProfile(UUIDs.parse(required(profile, "id")), required(profile, "name")));
        } catch (RuntimeException e) {
            throw new IOException("Invalid native Minecraft session", e);
        }
    }

    /// Builds common parameters containing the configured OAuth client ID.
    ///
    /// @return new request parameter object
    private JsonObject clientParams() {
        JsonObject result = new JsonObject();
        result.addProperty("client_id", callback.getClientId());
        return result;
    }

    /// Reads a required nonempty string without exposing response contents on failure.
    ///
    /// @param object response object
    /// @param field field name
    /// @return field value
    /// @throws IOException if the field is missing or invalid
    private static String required(JsonObject object, String field) throws IOException {
        try {
            String value = object.get(field).getAsString();
            if (value.isBlank()) throw new IOException("Invalid native authentication response");
            return value;
        } catch (RuntimeException e) {
            throw new IOException("Invalid native authentication response", e);
        }
    }

    /// Reads a positive integer from a native response.
    ///
    /// @param object response object
    /// @param field field name
    /// @return positive integer
    /// @throws IOException if the field is missing or invalid
    private static long positive(JsonObject object, String field) throws IOException {
        try {
            long value = object.get(field).getAsLong();
            if (value <= 0) throw new IOException("Invalid native authentication response");
            return value;
        } catch (RuntimeException e) {
            throw new IOException("Invalid native authentication response", e);
        }
    }
}
