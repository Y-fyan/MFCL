/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.auth.AuthenticationException;
import org.jackhuang.hmcl.auth.OAuth;
import org.jackhuang.hmcl.util.Lang;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Hosts an OAuth callback in a dedicated native process with a private control channel.
@NotNullByDefault
final class NativeOAuthSession implements OAuth.Session {
    /// Maximum control frame size.
    private static final int FRAME_LIMIT = 256 * 1024;
    /// Dedicated callback process.
    private final Process process;
    /// Parent-to-child control stream.
    private final OutputStream output;
    /// Child-to-parent control stream.
    private final InputStream input;
    /// Session state and PKCE verifier.
    private final String state, verifier;
    /// Listening port.
    private final int port;
    /// Callback outcome.
    private final CompletableFuture<String> result = new CompletableFuture<>();
    /// Optional identity token returned with the code.
    private volatile @Nullable String idToken;

    /// Starts a loopback callback process before a browser is opened.
    /// @param executable native backend executable
    /// @param bind loopback IP address used by the Java callback
    /// @param ports candidate ports, or a single zero for an ephemeral test port
    /// @param page localized response page supplier
    /// @return running callback session
    /// @throws IOException if startup fails
    /// @throws InterruptedException if interrupted
    static NativeOAuthSession start(Path executable, String bind, List<Integer> ports, Callable<String> page)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(executable.toString(), "--oauth-callback-child")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            OutputStream output = process.getOutputStream(); InputStream input = process.getInputStream();
            JsonObject config = new JsonObject(); config.addProperty("bind", bind);
            JsonArray encodedPorts = new JsonArray(); ports.forEach(encodedPorts::add); config.add("ports", encodedPorts);
            writeFrame(output, config);
            FutureTask<JsonObject> startup = new FutureTask<>(() -> readFrame(input));
            Thread reader = new Thread(startup, "MFCL-native-oauth-start"); reader.setDaemon(true); reader.start();
            JsonObject ready;
            try { ready = startup.get(10, TimeUnit.SECONDS); }
            catch (ExecutionException | TimeoutException e) { throw new IOException("Native callback startup failed", e); }
            finally { startup.cancel(true); }
            try {
                if (!"ready".equals(ready.get("event").getAsString())) throw new IOException("Invalid native callback startup");
                int port = ready.get("port").getAsInt(); String state = ready.get("state").getAsString();
                String verifier = ready.get("verifier").getAsString();
                if (port < 1 || port > 65535 || (!ports.contains(port) && !ports.contains(0))
                        || !state.matches("[A-Za-z0-9_-]{43}") || !verifier.matches("[A-Za-z0-9_-]{86}") || !process.isAlive())
                    throw new IOException("Invalid native callback startup");
                return new NativeOAuthSession(process, input, output, port, state, verifier, page);
            } catch (RuntimeException e) { throw new IOException("Invalid native callback startup"); }
        } catch (IOException | InterruptedException | RuntimeException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    /// Creates a running session and starts the callback reader.
    private NativeOAuthSession(Process process, InputStream input, OutputStream output, int port,
            String state, String verifier, Callable<String> page) {
        this.process = process; this.input = input; this.output = output;
        this.port = port; this.state = state; this.verifier = verifier;
        Thread reader = new Thread(() -> {
            try { result.complete(awaitCallback(page)); }
            catch (Throwable e) {
                result.completeExceptionally(e);
                if (e instanceof Error error) throw error;
            }
        }, "MFCL-native-oauth-callback");
        reader.setDaemon(true); reader.start();
    }

    /// Receives and validates the callback and supplies the browser response.
    private String awaitCallback(Callable<String> page) throws Exception {
        JsonObject callback = readFrame(input);
        try {
            if (!"callback".equals(callback.get("event").getAsString())) throw new IOException("Invalid callback event");
            JsonElement raw = callback.get("query");
            @Nullable String query = raw == null || raw.isJsonNull() ? null : raw.getAsString();
            @Nullable Map<String, String> fields = null;
            if (callback.has("fields") && callback.get("fields").isJsonObject())
                fields = NativeOAuthQuery.fromResponse(callback.getAsJsonObject("fields"));
            boolean parsedByNative = fields != null;
            Map<String, String> javaFields = Lang.mapOf(NetworkUtils.parseQuery(query));
            if (fields == null) fields = javaFields;
            else for (String key : List.of("code", "state", "id_token")) {
                if (fields.containsKey(key) != javaFields.containsKey(key)
                        || !java.util.Objects.equals(fields.get(key), javaFields.get(key)))
                    throw new IOException("Native callback fields do not match the request");
            }
            @Nullable String code = fields.get("code");
            String decision = code == null ? "missing_code" : !state.equals(fields.get("state"))
                    ? (fields.containsKey("state") ? "invalid_state" : "missing_state") : "ok";
            if (parsedByNative && (!callback.has("decision") || !callback.get("decision").isJsonPrimitive()
                    || !callback.get("decision").getAsJsonPrimitive().isString()
                    || !decision.equals(callback.get("decision").getAsString()))) throw new IOException("Invalid native callback decision");
            @Nullable AuthenticationException failure = switch (decision) {
                case "missing_code" -> new AuthenticationException("Failed to authenticate: missing authorization code");
                case "missing_state" -> new AuthenticationException("Failed to authenticate: missing state");
                case "invalid_state" -> new AuthenticationException("Failed to authenticate: invalid state");
                default -> null;
            };
            if (failure == null) idToken = fields.get("id_token");
            JsonObject response = new JsonObject();
            try { response.addProperty("status", 200); response.addProperty("html", java.util.Objects.requireNonNull(page.call())); }
            catch (Exception | LinkageError e) { response.addProperty("status", 500); response.addProperty("html", ""); }
            try {
                writeFrame(output, response);
                JsonObject acknowledged = readFrame(input);
                if (!"responded".equals(acknowledged.get("event").getAsString())) throw new IOException("Invalid callback acknowledgment");
            } catch (IOException | RuntimeException ignored) {
                // Authentication was already determined; do not repeat a one-time callback.
            }
            if (failure != null) throw failure;
            return java.util.Objects.requireNonNull(code);
        } catch (RuntimeException e) { throw new IOException("Invalid native callback event"); }
    }

    /// Reads one bounded JSON control frame.
    private static JsonObject readFrame(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0 || bytes.size() >= FRAME_LIMIT) throw new IOException("Native callback stream ended or exceeded its limit");
            if (value == '\n') break;
            bytes.write(value);
        }
        try { return JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("Invalid native callback frame"); }
    }

    /// Writes one bounded JSON control frame.
    private static void writeFrame(OutputStream output, JsonObject frame) throws IOException {
        byte[] bytes = (frame + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > FRAME_LIMIT) throw new IOException("Native callback frame exceeds its limit");
        output.write(bytes); output.flush();
    }

    @Override public String getState() { return state; }
    @Override public String getCodeVerifier() { return verifier; }
    @Override public String getRedirectURI() { return "http://localhost:" + port + "/auth-response"; }
    @Override public String waitFor() throws InterruptedException, ExecutionException { return result.get(); }
    @Override public @Nullable String getIdToken() { return idToken; }
    @Override public void close() {
        result.completeExceptionally(new AuthenticationException("OAuth server is closing"));
        process.destroyForcibly();
        try { output.close(); } catch (IOException ignored) { }
        try { input.close(); } catch (IOException ignored) { }
    }
}
