/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.auth.microsoft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.glavo.uuid.UUIDs;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.gson.JsonSerializable;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

public record MicrosoftSession(String tokenType, String accessToken, long notAfter, String refreshToken, User user, GameProfile profile) {

    public MicrosoftSession(String tokenType, String accessToken, long notAfter, String refreshToken, User user, GameProfile profile) {
        this.tokenType = tokenType;
        this.accessToken = accessToken;
        this.notAfter = notAfter;
        this.refreshToken = refreshToken;
        this.user = user;
        this.profile = profile;

        if (accessToken != null) Logger.registerAccessToken(accessToken);
    }

    public String getAuthorization() {
        return String.format("%s %s", tokenType(), accessToken());
    }

    /// Returns whether the stored session contains a usable Minecraft profile name.
    public boolean hasProfileName() {
        return profile != null && StringUtils.isNotBlank(profile.name());
    }

    /// Loads a Microsoft session from persisted account metadata and private data.
    public static MicrosoftSession fromStorage(JsonObject metadata, JsonObject privateData) {
        @Nullable MicrosoftSession nativeSession = tryNativeStorage(metadata, privateData);
        if (nativeSession != null) return nativeSession;
        String profileIDText = JsonUtils.getString(metadata, "profileID");
        if (profileIDText == null) {
            throw new IllegalArgumentException("profileID is missing");
        }
        UUID profileID = UUIDs.parse(profileIDText);
        String profileName = JsonUtils.getString(privateData, "profileName", "");
        String tokenType = requireStorageString(privateData, "tokenType");
        String accessToken = requireStorageString(privateData, "accessToken");
        String refreshToken = requireStorageString(privateData, "refreshToken");
        JsonElement notAfterElement = privateData.get("notAfter");
        long notAfter = notAfterElement != null
                && notAfterElement.isJsonPrimitive()
                && notAfterElement.getAsJsonPrimitive().isNumber()
                ? notAfterElement.getAsLong()
                : 0L;
        String userId = requireStorageString(privateData, "userid");
        return new MicrosoftSession(tokenType, accessToken, notAfter, refreshToken, new User(userId), new GameProfile(profileID, profileName));
    }

    /// Uses an isolated native process for canonical stored sessions, retaining legacy parsing otherwise.
    private static @Nullable MicrosoftSession tryNativeStorage(JsonObject metadata, JsonObject privateData) {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.microsoft.enabled", "true"))) return null;
        JsonObject selectedMetadata = new JsonObject(), selectedPrivate = new JsonObject();
        if (metadata.has("profileID")) selectedMetadata.add("profileID", metadata.get("profileID"));
        for (String key : new String[]{"profileName", "tokenType", "accessToken", "refreshToken", "notAfter", "userid"})
            if (privateData.has(key)) selectedPrivate.add(key, privateData.get(key));
        JsonObject params = new JsonObject(); params.add("metadata", selectedMetadata); params.add("private", selectedPrivate);
        if (!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(params.toString())) return null;
        try {
            JsonObject result = NativeBackend.microsoftStorage(Path.of(executable), params);
            String id = nativeString(result, "profileID"), name = nativeString(result, "profileName");
            String tokenType = nativeString(result, "tokenType"), token = nativeString(result, "accessToken");
            String refresh = nativeString(result, "refreshToken"), user = nativeString(result, "userid");
            JsonElement expiry = result.get("notAfter");
            if (expiry == null || !expiry.isJsonPrimitive() || !expiry.getAsJsonPrimitive().isNumber()) return null;
            return new MicrosoftSession(tokenType, token, expiry.getAsBigDecimal().longValueExact(), refresh,
                    new User(user), new GameProfile(UUIDs.parse(id), name));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return null;
        } catch (IOException | RuntimeException e) { return null; }
    }

    /// Reads a required string from the native response without coercing other JSON values.
    private static String nativeString(JsonObject object, String key) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid native session field");
        return value.getAsString();
    }

    /// Writes this session to persisted private account data.
    public void writePrivateData(JsonObject privateData) {
        requireNonNull(profile);
        requireNonNull(user);

        privateData.addProperty("profileName", profile.name());
        privateData.addProperty("tokenType", tokenType);
        privateData.addProperty("accessToken", accessToken);
        privateData.addProperty("refreshToken", refreshToken);
        privateData.addProperty("notAfter", notAfter);
        privateData.addProperty("userid", user.id);
    }

    /// Reads a required string member from account storage.
    private static String requireStorageString(JsonObject storage, String name) {
        String value = JsonUtils.getString(storage, name);
        if (value == null) {
            throw new IllegalArgumentException(name + " is missing");
        }
        return value;
    }

    public AuthInfo toAuthInfo() {
        requireNonNull(profile);

        return new AuthInfo(profile.name(), profile.id(), accessToken, AuthInfo.USER_TYPE_MSA, "{}");
    }

    @JsonSerializable
    public record User(String id) {
    }

    @JsonSerializable
    public record GameProfile(UUID id, String name) {
    }
}
