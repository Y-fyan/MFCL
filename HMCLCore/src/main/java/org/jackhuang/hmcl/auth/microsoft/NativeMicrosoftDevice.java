/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.auth.microsoft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jackhuang.hmcl.nativebridge.NativeBackend;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Runs one Microsoft device authorization in an isolated native process.
@NotNullByDefault
final class NativeMicrosoftDevice implements AutoCloseable {
    /// Maximum native response frame size.
    private static final int FRAME_LIMIT = 1024 * 1024;
    /// Native authorization process.
    private final Process process;
    /// Child control streams.
    private final InputStream input;
    private final OutputStream output;
    /// User-facing device code and verification URL.
    private final String userCode, verificationUri;

    /// An error code returned by the native device flow without remote details.
    @NotNullByDefault
    static final class DeviceFailure extends IOException {
        /// Native protocol error code.
        private final String code;
        private final @Nullable NativeBackend.XboxFailure xbox;
        /// Creates a sanitized device failure.
        /// @param code native error code
        private DeviceFailure(String code, @Nullable NativeBackend.XboxFailure xbox) {
            super("Native device authorization failed: " + code); this.code = code; this.xbox = xbox;
        }
        /// Returns the sanitized error code.
        /// @return native error code
        String code() { return code; }
        @Nullable NativeBackend.XboxFailure xbox() { return xbox; }
    }

    /// Starts authorization before displaying the device code.
    /// @param executable native backend executable
    /// @param clientId configured Microsoft client ID
    /// @return active authorization session
    /// @throws IOException if startup fails
    /// @throws InterruptedException if interrupted
    static NativeMicrosoftDevice start(Path executable, String clientId) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(executable.toString(), "--microsoft-device-session-child")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            InputStream input = process.getInputStream(); OutputStream output = process.getOutputStream();
            JsonObject config = new JsonObject(); config.addProperty("client_id", clientId); writeFrame(output, config);
            FutureTask<JsonObject> startup = new FutureTask<>(() -> readFrame(input));
            Thread reader = new Thread(startup, "MFCL-native-device-start"); reader.setDaemon(true); reader.start();
            JsonObject event;
            try { event = startup.get(40, TimeUnit.SECONDS); }
            catch (ExecutionException | TimeoutException e) { throw new IOException("Native device authorization startup failed"); }
            finally { startup.cancel(true); }
            if ("error".equals(eventName(event))) throw failure(event);
            try {
                if (!"device".equals(eventName(event))) throw new IOException("Invalid native device event");
                String code = required(event, "user_code"), uri = required(event, "verification_uri");
                if (!process.isAlive()) throw new IOException("Native device process stopped");
                return new NativeMicrosoftDevice(process, input, output, code, uri);
            } catch (RuntimeException e) { throw new IOException("Invalid native device event"); }
        } catch (IOException | InterruptedException | RuntimeException e) {
            process.destroyForcibly(); throw e;
        }
    }

    /// Creates an active device authorization session.
    private NativeMicrosoftDevice(Process process, InputStream input, OutputStream output, String userCode, String verificationUri) {
        this.process = process; this.input = input; this.output = output;
        this.userCode = userCode; this.verificationUri = verificationUri;
    }

    /// Returns the user-facing code.
    String userCode() { return userCode; }
    /// Returns the user-facing verification URI.
    String verificationUri() { return verificationUri; }

    /// Runs native polling and exchange without replaying the authorization.
    /// @param authorized callback fired after device authorization succeeds
    /// @return completed Minecraft session fields and refresh token
    /// @throws IOException if polling or exchange fails
    /// @throws InterruptedException if cancelled
    JsonObject poll(Runnable authorized) throws IOException, InterruptedException {
        JsonObject ready = new JsonObject(); ready.addProperty("event", "continue"); writeFrame(output, ready);
        JsonObject event = awaitEvent();
        if ("error".equals(eventName(event))) throw failure(event);
        if (!"authorized".equals(eventName(event))) throw new IOException("Invalid native device authorization event");
        authorized.run();
        JsonObject exchange = new JsonObject(); exchange.addProperty("event", "exchange"); writeFrame(output, exchange);
        event = awaitEvent();
        if ("error".equals(eventName(event))) throw failure(event);
        if (!"session".equals(eventName(event)) || !event.has("session") || !event.get("session").isJsonObject())
            throw new IOException("Invalid native device session");
        return event.getAsJsonObject("session");
    }

    /// Waits interruptibly for one native event.
    /// @return decoded event
    /// @throws IOException if the native stream fails
    /// @throws InterruptedException if cancelled
    private JsonObject awaitEvent() throws IOException, InterruptedException {
        FutureTask<JsonObject> wait = new FutureTask<>(() -> readFrame(input));
        Thread reader = new Thread(wait, "MFCL-native-device-result"); reader.setDaemon(true); reader.start();
        try {
            try { return wait.get(); }
            catch (ExecutionException e) { throw new IOException("Native device authorization failed"); }
        } finally { wait.cancel(true); }
    }

    /// Reads a bounded control frame.
    private static JsonObject readFrame(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value < 0 || bytes.size() >= FRAME_LIMIT) throw new IOException("Native device stream ended or exceeded its limit");
            if (value == '\n') break;
            bytes.write(value);
        }
        try { return JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("Invalid native device frame"); }
    }

    /// Writes a bounded control frame.
    private static void writeFrame(OutputStream output, JsonObject frame) throws IOException {
        byte[] bytes = (frame + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > FRAME_LIMIT) throw new IOException("Native device frame exceeds its limit");
        output.write(bytes); output.flush();
    }

    /// Reads a nonempty event string without including its value in errors.
    private static String required(JsonObject event, String key) throws IOException {
        try {
            String value = event.get(key).getAsString();
            if (value.isBlank()) throw new IOException("Invalid native device field");
            return value;
        } catch (RuntimeException e) { throw new IOException("Invalid native device field"); }
    }

    /// Reads the event kind.
    private static String eventName(JsonObject event) throws IOException { return required(event, "event"); }

    /// Creates a failure containing only a bounded protocol code.
    private static DeviceFailure failure(JsonObject event) throws IOException {
        String code = required(event, "code");
        if (!code.matches("[a-z_]{1,64}")) throw new IOException("Invalid native device error");
        return new DeviceFailure(code, NativeBackend.parseXboxFailure(event, code));
    }

    @Override public void close() {
        process.destroyForcibly();
        try { output.close(); } catch (IOException ignored) { }
        try { input.close(); } catch (IOException ignored) { }
    }
}
