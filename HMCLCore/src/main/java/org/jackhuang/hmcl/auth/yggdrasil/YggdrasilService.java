/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.auth.yggdrasil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.glavo.url.WebURL;
import org.glavo.uuid.UUIDs;
import org.jackhuang.hmcl.auth.AuthenticationException;
import org.jackhuang.hmcl.auth.ServerDisconnectException;
import org.jackhuang.hmcl.auth.ServerResponseMalformedException;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.gson.ValidationTypeAdapterFactory;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.io.HttpMultipartRequest;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jackhuang.hmcl.util.javafx.ObservableOptionalCache;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.unmodifiableList;
import static org.jackhuang.hmcl.util.Lang.mapOf;
import static org.jackhuang.hmcl.util.Lang.threadPool;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;
import static org.jackhuang.hmcl.util.Pair.pair;

public class YggdrasilService {

    private static final ThreadPoolExecutor POOL = threadPool("YggdrasilProfileProperties", true, 2, 10, TimeUnit.SECONDS);

    private final YggdrasilProvider provider;
    private final ObservableOptionalCache<UUID, CompleteGameProfile, AuthenticationException> profileRepository;

    public YggdrasilService(YggdrasilProvider provider) {
        this.provider = provider;
        this.profileRepository = new ObservableOptionalCache<>(
                uuid -> {
                    LOG.info("Fetching properties of " + uuid + " from " + provider);
                    return getCompleteGameProfile(uuid);
                },
                (uuid, e) -> LOG.warning("Failed to fetch properties of " + uuid + " from " + provider, e),
                POOL);
    }

    public ObservableOptionalCache<UUID, CompleteGameProfile, AuthenticationException> getProfileRepository() {
        return profileRepository;
    }

    public YggdrasilSession authenticate(String username, String password, String clientToken) throws AuthenticationException {
        Objects.requireNonNull(username);
        Objects.requireNonNull(password);
        Objects.requireNonNull(clientToken);

        Map<String, Object> request = new HashMap<>();
        request.put("agent", mapOf(
                pair("name", "Minecraft"),
                pair("version", 1)
        ));
        request.put("username", username);
        request.put("password", password);
        request.put("clientToken", clientToken);
        request.put("requestUser", true);

        return handleAuthenticationResponse(request(provider.getAuthenticationURL(), request), clientToken);
    }

    private static Map<String, Object> createRequestWithCredentials(String accessToken, String clientToken) {
        Map<String, Object> request = new HashMap<>();
        request.put("accessToken", accessToken);
        request.put("clientToken", clientToken);
        return request;
    }

    public YggdrasilSession refresh(String accessToken, String clientToken, GameProfile characterToSelect) throws AuthenticationException {
        Objects.requireNonNull(accessToken);
        Objects.requireNonNull(clientToken);

        Map<String, Object> request = createRequestWithCredentials(accessToken, clientToken);
        request.put("requestUser", true);

        if (characterToSelect != null) {
            request.put("selectedProfile", mapOf(
                    pair("id", UUIDs.toCompactString(characterToSelect.getId())),
                    pair("name", characterToSelect.getName())));
        }

        YggdrasilSession response = handleAuthenticationResponse(request(provider.getRefreshmentURL(), request), clientToken);

        if (characterToSelect != null) {
            if (response.getSelectedProfile() == null ||
                    !response.getSelectedProfile().getId().equals(characterToSelect.getId())) {
                throw new ServerResponseMalformedException("Failed to select character");
            }
        }

        return response;
    }

    public boolean validate(String accessToken) throws AuthenticationException {
        return validate(accessToken, null);
    }

    public boolean validate(String accessToken, String clientToken) throws AuthenticationException {
        Objects.requireNonNull(accessToken);

        try {
            requireEmpty(request(provider.getValidationURL(), createRequestWithCredentials(accessToken, clientToken)));
            return true;
        } catch (RemoteAuthenticationException e) {
            if ("ForbiddenOperationException".equals(e.getRemoteName())) {
                return false;
            }
            throw e;
        }
    }

    public void invalidate(String accessToken) throws AuthenticationException {
        invalidate(accessToken, null);
    }

    public void invalidate(String accessToken, String clientToken) throws AuthenticationException {
        Objects.requireNonNull(accessToken);

        requireEmpty(request(provider.getInvalidationURL(), createRequestWithCredentials(accessToken, clientToken)));
    }

    /// Uploads a selected skin once through Rust when the provider configuration is supported.
    /// @param uuid selected profile ID
    /// @param accessToken provider access token, or null for legacy Java behavior
    /// @param isSlim whether the slim model is selected
    /// @param file selected skin file
    /// @throws AuthenticationException if the upload fails
    /// @throws UnsupportedOperationException if the provider does not support uploads
    public void uploadSkin(UUID uuid, @Nullable String accessToken, boolean isSlim, Path file) throws AuthenticationException, UnsupportedOperationException {
        try {
            WebURL url = provider.getSkinUploadURL(uuid);
            @Nullable String nativeResponse = NativeYggdrasilTransport.tryUpload(url, accessToken, isSlim, file);
            if (nativeResponse != null) {
                requireEmpty(nativeResponse);
                return;
            }
            HttpURLConnection con = NetworkUtils.createHttpConnection(url);
            con.setRequestMethod("PUT");
            con.setRequestProperty("Authorization", "Bearer " + accessToken);
            con.setDoOutput(true);
            try (HttpMultipartRequest request = new HttpMultipartRequest(con)) {
                request.param("model", isSlim ? "slim" : "");
                try (InputStream fis = Files.newInputStream(file)) {
                    request.file("file", FileUtils.getName(file), "image/" + FileUtils.getExtension(file), fis);
                }
            }
            requireEmpty(NetworkUtils.readFullyAsString(con));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthenticationException(e);
        } catch (IOException e) {
            throw new AuthenticationException(e);
        }
    }

    /// Fetches and parses the full profile, including its texture properties.
    /// @param uuid selected profile ID
    /// @return the complete profile when the provider returns one
    /// @throws AuthenticationException if the provider request or response fails
    public Optional<CompleteGameProfile> getCompleteGameProfile(UUID uuid) throws AuthenticationException {
        Objects.requireNonNull(uuid);
        String response = request(provider.getProfilePropertiesURL(uuid), null);
        @Nullable CompleteGameProfile nativeProfile = NativeYggdrasilProfile.tryParse(response);
        return Optional.ofNullable(nativeProfile == null ? fromJson(response, CompleteGameProfile.class) : nativeProfile);
    }

    /// Decodes the profile's optional skin and cape texture map.
    /// @param profile complete provider profile
    /// @return texture map when present
    /// @throws ServerResponseMalformedException if the encoded response is invalid
    public static Optional<Map<@Nullable TextureType, @Nullable Texture>> getTextures(CompleteGameProfile profile) throws ServerResponseMalformedException {
        Objects.requireNonNull(profile);

        @Nullable String encodedTextures = profile.getProperties().get("textures");

        if (encodedTextures != null) {
            @Nullable NativeYggdrasilTextures.Result nativeResult = NativeYggdrasilTextures.tryParse(encodedTextures);
            if (nativeResult != null) return Optional.ofNullable(nativeResult.textures());
            byte[] decodedBinary;
            try {
                decodedBinary = Base64.getDecoder().decode(encodedTextures);
            } catch (IllegalArgumentException e) {
                throw new ServerResponseMalformedException(e);
            }
            TextureResponse texturePayload = fromJson(new String(decodedBinary, UTF_8), TextureResponse.class);
            return Optional.ofNullable(texturePayload.textures);
        } else {
            return Optional.empty();
        }
    }

    /// Builds a session using native response planning when supported.
    /// @param responseText provider response JSON
    /// @param clientToken token supplied in the original request
    /// @return authenticated session
    /// @throws AuthenticationException if authentication or validation fails
    private static YggdrasilSession handleAuthenticationResponse(String responseText, String clientToken) throws AuthenticationException {
        @Nullable NativeYggdrasilResponse.Plan plan = NativeYggdrasilResponse.tryPlan(responseText, clientToken);
        if (plan == null) return handleAuthenticationResponseJava(responseText, clientToken);
        @Nullable GameProfile selected;
        @Nullable List<@Nullable GameProfile> available;
        @Nullable @Unmodifiable Map<String, String> userProperties;
        try {
            selected = plan.selectedState().decoded() ? plan.selectedState().value()
                    : GSON.fromJson(plan.selectedProfile(), GameProfile.class);
            available = plan.availableState().decoded() ? plan.availableState().value()
                    : GSON.fromJson(plan.availableProfiles(), org.jackhuang.hmcl.util.gson.JsonUtils.listTypeOf(GameProfile.class));
            if (plan.userState().decoded()) userProperties = plan.userState().properties();
            else {
                @Nullable User user = GSON.fromJson(plan.user(), User.class);
                userProperties = user == null ? null : user.properties();
            }
        } catch (RuntimeException e) {
            // Preserve the original parser's field order and exception behavior for malformed nested data.
            return handleAuthenticationResponseJava(responseText, clientToken);
        }
        if ("remote_error".equals(plan.outcome()))
            throw new RemoteAuthenticationException(Objects.requireNonNull(plan.error()), plan.errorMessage(), plan.cause());
        if ("token_mismatch".equals(plan.outcome()))
            throw new AuthenticationException("Client token changed from " + clientToken + " to " + plan.clientToken());
        return new YggdrasilSession(Objects.requireNonNull(plan.clientToken()), plan.accessToken(), selected,
                available == null ? null : unmodifiableList(available), userProperties);
    }

    /// Parses an authentication response using the original Java implementation.
    /// @param responseText provider response JSON
    /// @param clientToken token supplied in the original request
    /// @return authenticated session
    /// @throws AuthenticationException if authentication or validation fails
    private static YggdrasilSession handleAuthenticationResponseJava(String responseText, String clientToken) throws AuthenticationException {
        AuthenticationResponse response = fromJson(responseText, AuthenticationResponse.class);
        handleErrorMessage(response);

        if (!clientToken.equals(response.clientToken))
            throw new AuthenticationException("Client token changed from " + clientToken + " to " + response.clientToken);

        return new YggdrasilSession(
                response.clientToken,
                response.accessToken,
                response.selectedProfile,
                response.availableProfiles == null ? null : unmodifiableList(response.availableProfiles),
                response.user == null ? null : response.user.properties());
    }

    private static void requireEmpty(String response) throws AuthenticationException {
        if (StringUtils.isBlank(response))
            return;

        handleErrorMessage(fromJson(response, ErrorResponse.class));
    }

    private static void handleErrorMessage(ErrorResponse response) throws AuthenticationException {
        if (!StringUtils.isBlank(response.error)) {
            throw new RemoteAuthenticationException(response.error, response.errorMessage, response.cause);
        }
    }

    /// Performs a GET, or posts JSON through Rust when the provider's network configuration allows it.
    /// @param url provider endpoint
    /// @param payload request payload, or null for GET
    /// @return response text
    /// @throws AuthenticationException if the request fails
    private static String request(WebURL url, @Nullable Object payload) throws AuthenticationException {
        try {
            if (payload == null) {
                @Nullable String nativeResponse = NativeYggdrasilTransport.tryGet(url);
                return nativeResponse == null ? NetworkUtils.doGet(url) : nativeResponse;
            }
            String body = payload instanceof String ? (String) payload : GSON.toJson(payload);
            @Nullable String nativeResponse = NativeYggdrasilTransport.tryPost(url, body);
            if (nativeResponse != null) return nativeResponse;
            return NetworkUtils.doPost(url, body, "application/json");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServerDisconnectException(e);
        } catch (IOException e) {
            throw new ServerDisconnectException(e);
        }
    }

    private static <T> T fromJson(String text, Class<T> typeOfT) throws ServerResponseMalformedException {
        try {
            return GSON.fromJson(text, typeOfT);
        } catch (JsonParseException e) {
            throw new ServerResponseMalformedException(text, e);
        }
    }

    private final static class TextureResponse {
        public Map<TextureType, Texture> textures;
    }

    private final static class AuthenticationResponse extends ErrorResponse {
        public String accessToken;
        public String clientToken;
        public GameProfile selectedProfile;
        public List<GameProfile> availableProfiles;
        public User user;
    }

    private static class ErrorResponse {
        public String error;
        public String errorMessage;
        public String cause;
    }

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapterFactory(ValidationTypeAdapterFactory.INSTANCE)
            .create();

    public static final String PURCHASE_URL = "https://www.xbox.com/games/store/minecraft-java-bedrock-edition-for-pc/9nxp44l49shj";
}
