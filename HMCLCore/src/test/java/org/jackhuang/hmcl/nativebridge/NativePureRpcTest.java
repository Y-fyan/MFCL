/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.nativebridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Tests persistent pure requests, legacy backends and cancellation.
@NotNullByDefault
@EnabledOnOs({OS.LINUX, OS.MAC})
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class NativePureRpcTest {
    /// Test-owned files and executable wrappers.
    @TempDir Path temp;
    /// Original pool setting.
    private final @Nullable String previousEnabled = System.getProperty("mfcl.native.pool.enabled");

    /// Stops idle workers and restores global settings.
    @AfterEach void cleanup() {
        NativeDownloadPool.clearIdle();
        if (previousEnabled == null) System.clearProperty("mfcl.native.pool.enabled"); else System.setProperty("mfcl.native.pool.enabled", previousEnabled);
    }

    /// Two literal batches reuse a single actual Rust process.
    @Test void reusesActualRustWorker() throws Exception {
        @Nullable String binary = System.getenv("MFCL_NATIVE_TEST_EXECUTABLE"); assumeTrue(binary != null && !binary.isBlank());
        System.clearProperty("mfcl.native.pool.enabled"); NativeDownloadPool.clearIdle();
        Path wrapper = temp.resolve("backend");
        Files.writeString(wrapper, "#!/bin/sh\nprintf '%s\\n' \"$$\" >> \"$0.pids\"\nexec '" + binary.replace("'", "'\\''") + "' \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        assertEquals("one", NativeBackend.expandProcessorLiterals(wrapper, params("one"), () -> false).getAsJsonArray("values").get(0).getAsString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, content.length);
            try (var output = exchange.getResponseBody()) { output.write(content); }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            assertEquals(3, NativeTransfer.download(wrapper, url, temp.resolve("download"),
                    "a9993e364706816aba3e25717850c26c9cd0d89d", 3, (done, total) -> { }, () -> false, true));
        } finally { server.stop(0); }
        var hashes = NativeBackend.checkFileHashes(wrapper, List.of(new NativeBackend.FileHashInput(0,
                temp.resolve("download"), "a9993e364706816aba3e25717850c26c9cd0d89d")), () -> false);
        assertTrue(hashes.get(0).matches());
        @Nullable NativeBackend.LegacyChecksumResult legacy = NativeBackend.checkLegacyLibrary(wrapper, temp.resolve("download"),
                List.of("a9993e364706816aba3e25717850c26c9cd0d89d"), false);
        assertNotNull(legacy); assertTrue(legacy.valid());
        @Nullable var modpackHashes = NativeBackend.hashModpackFile(wrapper, temp.resolve("download"), () -> false);
        assertNotNull(modpackHashes);
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", modpackHashes.get("sha1"));
        assertEquals("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f", modpackHashes.get("sha512"));
        assertNull(NativeBackend.hashModpackFile(wrapper, temp.resolve("absent"), () -> false));
        JsonObject options = new JsonObject(); JsonArray groups = new JsonArray(); JsonArray args = new JsonArray();
        args.add("--side"); args.add("client"); groups.add(args);
        options.add("groups", groups); options.add("variables", new JsonObject()); options.add("artifacts", new JsonObject());
        assertEquals("client", NativeBackend.planProcessorOptions(wrapper, options, () -> false)
                .getAsJsonArray("groups").get(0).getAsJsonArray().get(0).getAsJsonObject().get("value").getAsString());
        JsonObject command = new JsonObject(); command.addProperty("jar", temp.resolve("download").toString());
        command.add("libraries", new JsonArray()); command.addProperty("separator", java.io.File.pathSeparator);
        assertEquals(temp.resolve("download").toString(), NativeBackend.prepareProcessorCommand(wrapper, "processor_classpath", command, () -> false)
                .get("classpath").getAsString());
        JsonObject runtimeList = new JsonObject();
        runtimeList.addProperty("metadata", "{\"linux\":{\"java\":[{\"availability\":{\"group\":0,\"progress\":100},"
                + "\"manifest\":{\"url\":\"https://example.test/manifest\",\"sha1\":\"abc\",\"size\":1},"
                + "\"version\":{\"name\":\"17\",\"released\":\"now\"}}]}}");
        runtimeList.addProperty("platform", "linux"); runtimeList.addProperty("component", "java"); runtimeList.addProperty("major", 17);
        assertEquals("17", NativeBackend.selectJavaRuntime(wrapper, runtimeList, () -> false)
                .getAsJsonObject("selected").getAsJsonObject("version").get("name").getAsString());
        JsonObject manifest = new JsonObject(); manifest.addProperty("metadata", "{\"files\":{\"bin\":{\"type\":\"directory\"}}}");
        assertEquals("bin", NativeBackend.parseJavaRuntimeManifest(wrapper, manifest, () -> false)
                .getAsJsonArray("entries").get(0).getAsJsonObject().get("path").getAsString());
        assertTrue(NativeBackend.projectInstanceManifest(wrapper, "{\"id\":\"first\"}").get("loader_v1").getAsBoolean());
        assertTrue(NativeBackend.projectInstanceManifest(wrapper, "{\"id\":\"second\",\"inheritsFrom\":\"first\"}")
                .get("loader_v1").getAsBoolean());
        JsonObject ignores = new JsonObject(); ignores.addProperty("library_directory", temp.toAbsolutePath().toString());
        ignores.add("classpath", new JsonArray()); JsonArray ignoreLists = new JsonArray(); ignoreLists.add("asm"); ignores.add("ignore_lists", ignoreLists);
        assertEquals("${primary_jar}", NativeBackend.planBootstrapIgnores(wrapper, ignores).getAsJsonArray("ignore_lists").get(0).getAsString());
        assertEquals("${primary_jar}", NativeBackend.planBootstrapIgnores(wrapper, ignores).getAsJsonArray("ignore_lists").get(0).getAsString());
        var library = new org.jackhuang.hmcl.game.Library("org.example", "library", "1");
        assertArrayEquals(new boolean[]{true}, NativeBackend.selectLibraryApplicability(wrapper, List.of(library)));
        assertArrayEquals(new boolean[]{true}, NativeBackend.selectLibraryApplicability(wrapper, List.of(library)));
        var layout = new org.jackhuang.hmcl.game.DefaultGameRepositoryLayout(temp.resolve("classpath-game"));
        var instance = new org.jackhuang.hmcl.game.GameInstanceID("fixture");
        Path libraryFile = layout.getLibraryFile(instance, library);
        Files.createDirectories(libraryFile.getParent()); Files.writeString(libraryFile, "fixture");
        assertEquals(List.of(libraryFile.toAbsolutePath().normalize().toString()),
                NativeBackend.planClasspath(wrapper, layout, instance, List.of(library), false, false));
        assertEquals(List.of(libraryFile.toAbsolutePath().normalize().toString()),
                NativeBackend.planClasspath(wrapper, layout, instance, List.of(library), false, false));
        assertEquals("two", NativeBackend.expandProcessorLiterals(wrapper, params("two"), () -> false).getAsJsonArray("values").get(0).getAsString());
        assertEquals(1, Files.readAllLines(Path.of(wrapper + ".pids")).size());
        NativeDownloadPool.clearIdle(); System.setProperty("mfcl.native.pool.enabled", "false");
        assertEquals("three", NativeBackend.expandProcessorLiterals(wrapper, params("three"), () -> false).getAsJsonArray("values").get(0).getAsString());
        assertEquals("four", NativeBackend.expandProcessorLiterals(wrapper, params("four"), () -> false).getAsJsonArray("values").get(0).getAsString());
        assertEquals(3, Files.readAllLines(Path.of(wrapper + ".pids")).size());
    }

    /// A malformed pooled response is retried once in a fresh worker.
    @Test void bootstrapPlanningRetriesAfterBrokenWorker() throws Exception {
        System.clearProperty("mfcl.native.pool.enabled"); NativeDownloadPool.clearIdle();
        Path wrapper = temp.resolve("broken-bootstrap");
        Files.writeString(wrapper, "#!/usr/bin/env python3\nimport json,pathlib,sys\n"
                + "pooled=False\nfor line in sys.stdin:\n r=json.loads(line)\n"
                + " if r['method']=='health':\n  pooled=True\n  result={'name':'mfcl-native','protocol':1,'capabilities':['bootstrap_ignore_stream_v1']}\n"
                + " else:\n  result={'ignore_lists':['ok']}\n  pathlib.Path(__file__+'.requests').open('a').write(('pool' if pooled else 'fresh')+'\\n')\n"
                + " if pooled and r['method']!='health': r['id']+=1\n"
                + " print(json.dumps({'version':1,'id':r['id'],'result':result}),flush=True)\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        assertEquals("ok", NativeBackend.planBootstrapIgnores(wrapper, new JsonObject()).getAsJsonArray("ignore_lists").get(0).getAsString());
        assertEquals(List.of("pool", "fresh"), Files.readAllLines(Path.of(wrapper + ".requests")));
    }

    /// A legacy backend lacking the streaming capability uses the original one-shot request.
    @Test void fallsBackToLegacyBackend() throws Exception {
        System.clearProperty("mfcl.native.pool.enabled"); NativeDownloadPool.clearIdle();
        Path wrapper = temp.resolve("legacy");
        Files.writeString(wrapper, "#!/bin/sh\nprintf '%s\\n' run >> \"$0.pids\"\nread line\ncase \"$line\" in\n"
                + "*health*) printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"name\":\"mfcl-native\",\"protocol\":1,\"capabilities\":[]}}' ;;\n"
                + "*) printf '%s\\n' '{\"version\":1,\"id\":1,\"result\":{\"values\":[\"old\"]}}' ;;\nesac\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        assertEquals("old", NativeBackend.expandProcessorLiterals(wrapper, params("old"), () -> false).getAsJsonArray("values").get(0).getAsString());
        assertEquals(List.of("run", "run"), Files.readAllLines(Path.of(wrapper + ".pids")));
    }

    /// A malformed response is rejected and its worker cannot contaminate the next request.
    @Test void rejectsWrongResponseIdAndDiscardsWorker() throws Exception {
        System.clearProperty("mfcl.native.pool.enabled"); NativeDownloadPool.clearIdle();
        Path wrapper = temp.resolve("wrong-id");
        Files.writeString(wrapper, "#!/usr/bin/env python3\nimport json,os,pathlib,sys\n"
                + "with open(__file__+'.pids','a') as f: f.write(str(os.getpid())+'\\n')\n"
                + "for line in sys.stdin:\n r=json.loads(line)\n if r['method']=='health':\n  value={'name':'mfcl-native','protocol':1,'capabilities':['processor_literal_stream_v1']}\n"
                + " else:\n  r['id']+=1\n  value={'values':['wrong']}\n"
                + " print(json.dumps({'version':1,'id':r['id'],'result':value}),flush=True)\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        assertThrows(java.io.IOException.class, () -> NativeBackend.expandProcessorLiterals(wrapper, params("x"), () -> false));
        assertThrows(java.io.IOException.class, () -> NativeBackend.expandProcessorLiterals(wrapper, params("y"), () -> false));
        assertEquals(2, Files.readAllLines(Path.of(wrapper + ".pids")).size());
    }

    /// A failed read-only pooled processor request can safely retry in an isolated worker.
    @Test void processorCommandRetriesAfterBrokenPooledResponse() throws Exception {
        System.clearProperty("mfcl.native.pool.enabled"); NativeDownloadPool.clearIdle();
        Path wrapper = temp.resolve("broken-processor-worker");
        Files.writeString(wrapper, """
                #!/usr/bin/env python3
                import json, pathlib, sys
                flag = pathlib.Path(__file__ + '.pooled')
                for line in sys.stdin:
                    request = json.loads(line)
                    if request['method'] == 'health':
                        flag.touch()
                        result = {'name':'mfcl-native','protocol':1,'capabilities':['processor_combined_command_v1']}
                    else:
                        result = {'main_class':'example.Main','classpath':'fixture'}
                        if flag.exists() and not pathlib.Path(__file__ + '.failed').exists():
                            pathlib.Path(__file__ + '.failed').touch(); request['id'] += 1
                    print(json.dumps({'version':1,'id':request['id'],'result':result}), flush=True)
                """);
        assertTrue(wrapper.toFile().setExecutable(true));
        JsonObject result = NativeBackend.prepareProcessorCommand(wrapper, "processor_command", new JsonObject(), () -> false);
        assertEquals("example.Main", result.get("main_class").getAsString());
        assertTrue(Files.exists(Path.of(wrapper + ".failed")));
    }

    /// Cancelling a blocked request terminates its test-owned worker and reports interruption.
    @Test void cancellationTerminatesWorker() throws Exception {
        System.clearProperty("mfcl.native.pool.enabled"); NativeDownloadPool.clearIdle();
        Path wrapper = temp.resolve("blocked");
        Files.writeString(wrapper, "#!/usr/bin/env python3\nimport json,os,pathlib,sys,time\n"
                + "for line in sys.stdin:\n r=json.loads(line)\n if r['method']=='health':\n  print(json.dumps({'version':1,'id':r['id'],'result':{'name':'mfcl-native','protocol':1,'capabilities':['processor_literal_stream_v1']}}),flush=True)\n"
                + " else:\n  pathlib.Path(__file__+'.pid').write_text(str(os.getpid()))\n  time.sleep(30)\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        AtomicBoolean cancelled = new AtomicBoolean(); AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { NativeBackend.expandProcessorLiterals(wrapper, params("x"), cancelled::get); failure.set(new AssertionError("Expected cancellation")); }
            catch (InterruptedException expected) { }
            catch (Throwable t) { failure.set(t); }
        });
        thread.setDaemon(true); thread.start(); Path pidFile = Path.of(wrapper + ".pid");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(pidFile) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile)); long pid = Long.parseLong(Files.readString(pidFile));
            cancelled.set(true); thread.join(5000); assertFalse(thread.isAlive()); assertNull(failure.get());
            ProcessHandle.of(pid).ifPresent(process -> assertDoesNotThrow(() -> process.onExit().get(3, TimeUnit.SECONDS)));
        } finally { cancelled.set(true); thread.interrupt(); thread.join(5000); }
    }

    /// Creates a single literal request.
    /// @param value value to expand
    /// @return request parameters
    private static JsonObject params(String value) {
        JsonObject params = new JsonObject(); JsonArray literals = new JsonArray(); literals.add(value);
        params.add("literals", literals); params.add("variables", new JsonObject()); params.add("artifacts", new JsonObject()); return params;
    }
}
