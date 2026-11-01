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
package org.jackhuang.hmcl.launch;

import org.glavo.uuid.UUIDs;
import org.jackhuang.hmcl.addon.mod.ModLoaderType;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.game.*;
import org.jackhuang.hmcl.nativebridge.NativeBackend;
import org.jackhuang.hmcl.nativebridge.NativeLaunch;
import org.jackhuang.hmcl.util.Lang;
import org.jackhuang.hmcl.util.ServerAddress;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.io.FileUtils;
import org.jackhuang.hmcl.util.io.Unzipper;
import org.jackhuang.hmcl.util.platform.*;
import org.jackhuang.hmcl.util.platform.hardware.GraphicsCard;
import org.jackhuang.hmcl.util.platform.hardware.HardwareVendor;
import org.jackhuang.hmcl.util.platform.macos.HomebrewUtils;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.jackhuang.hmcl.game.GameComponentType.*;
import static org.jackhuang.hmcl.util.Lang.mapOf;
import static org.jackhuang.hmcl.util.Pair.pair;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// @author huangyuhui
public class DefaultLauncher extends Launcher {

    public DefaultLauncher(GameInstance instance, GameInstanceManifest manifest, AuthInfo authInfo, LaunchOptions options, ProcessListener listener, boolean daemon) {
        super(instance, manifest, authInfo, options, listener, daemon);
    }

    private Command generateCommandLine(Path nativeFolder) throws IOException {
        CommandBuilder res = new CommandBuilder();

        switch (options.getProcessPriority()) {
            case HIGH:
                if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                    // res.add("cmd", "/C", "start", "unused title", "/B", "/high");
                } else if (OperatingSystem.CURRENT_OS.isLinuxOrBSD() || OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
                    res.addAll("nice", "-n", "-5");
                }
                break;
            case ABOVE_NORMAL:
                if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                    // res.add("cmd", "/C", "start", "unused title", "/B", "/abovenormal");
                } else if (OperatingSystem.CURRENT_OS.isLinuxOrBSD() || OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
                    res.addAll("nice", "-n", "-1");
                }
                break;
            case NORMAL:
                // do nothing
                break;
            case BELOW_NORMAL:
                if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                    // res.add("cmd", "/C", "start", "unused title", "/B", "/belownormal");
                } else if (OperatingSystem.CURRENT_OS.isLinuxOrBSD() || OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
                    res.addAll("nice", "-n", "1");
                }
                break;
            case LOW:
                if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                    // res.add("cmd", "/C", "start", "unused title", "/B", "/low");
                } else if (OperatingSystem.CURRENT_OS.isLinuxOrBSD() || OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
                    res.addAll("nice", "-n", "5");
                }
                break;
        }

        // Executable
        if (StringUtils.isNotBlank(options.getWrapper()))
            res.addAllWithoutParsing(StringUtils.tokenize(options.getWrapper(), getEnvVars(nativeFolder)));

        res.add(options.getJavaExecutable().toString());

        res.addAllWithoutParsingAndReadExternal(options.getOverrideJavaArguments());

        @Nullable List<NativeJvmDefaults.Addition> initialDefaults = NativeJvmDefaults.tryPlanInitial(res,
                options.getJava().getParsedVersion(), options.getMinMemory(), options.getMaxMemory(),
                options.getMetaspace(), options.getJavaArguments());
        if (initialDefaults == null) {
            appendInitialJvmDefaults(res, options.getJava().getParsedVersion(), options.getMinMemory(),
                    options.getMaxMemory(), options.getMetaspace(), options.getJavaArguments());
        } else for (NativeJvmDefaults.Addition addition : initialDefaults) {
            if (addition.parse()) res.add(addition.argument()); else res.addWithoutParsing(addition.argument());
        }

        Charset encoding = OperatingSystem.NATIVE_CHARSET;
        String fileEncoding = res.addDefault("-Dfile.encoding=", encoding.name());
        if (fileEncoding != null && !"-Dfile.encoding=COMPAT".equals(fileEncoding)) {
            try {
                encoding = Charset.forName(fileEncoding.substring("-Dfile.encoding=".length()));
            } catch (Throwable ex) {
                LOG.warning("Bad file encoding", ex);
            }
        }

        if (options.getJava().getParsedVersion() < 19) {
            res.addDefault("-Dsun.stdout.encoding=", encoding.name());
            res.addDefault("-Dsun.stderr.encoding=", encoding.name());
        } else {
            res.addDefault("-Dstdout.encoding=", encoding.name());
            res.addDefault("-Dstderr.encoding=", encoding.name());
        }

        // Fix RCE vulnerability of log4j2
        res.addDefault("-Djava.rmi.server.useCodebaseOnly=", "true");
        res.addDefault("-Dcom.sun.jndi.rmi.object.trustURLCodebase=", "false");
        res.addDefault("-Dcom.sun.jndi.cosnaming.object.trustURLCodebase=", "false");

        String formatMsgNoLookups = res.addDefault("-Dlog4j2.formatMsgNoLookups=", "true");
        if (isUsingLog4j() && (options.isEnableDebugLogOutput() || !"-Dlog4j2.formatMsgNoLookups=false".equals(formatMsgNoLookups))) {
            res.addDefault("-Dlog4j.configurationFile=", FileUtils.getAbsolutePath(getLog4jConfigurationFile()));
        }

        // Default JVM Args
        if (!options.isNoGeneratedJVMArgs()) {
            appendJvmArgs(res);

            String clientJar = FileUtils.getAbsolutePath(instance.getInstanceJarFile());
            @Nullable String dockName = null;
            @Nullable String dockIcon = null;
            if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
                dockName = "Minecraft " + manifest.id();
                dockIcon = instance.getAssetObject(manifest.getAssetIndex().getId(), "icons/minecraft.icns")
                        .map(FileUtils::getAbsolutePath).orElse(null);
            }
            @Nullable String userHome = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                    ? null : options.getGameDir().toAbsolutePath().getParent().toString();
            @Nullable ProxyOption proxy = options.getProxyOption();
            int javaVersion = options.getJava().getParsedVersion();
            boolean is64bit = options.getJava().getBits() == Bits.BIT_64;
            boolean optimize = !options.isNoGeneratedOptimizingJVMArgs();
            boolean memoryLarge = optimize && is64bit && SystemInfo.getTotalMemorySize() > 4L * 1024 * 1024 * 1024;
            @Nullable List<String> nativeDefaults = NativeJvmDefaults.tryPlanPlatform(res, clientJar, dockName, dockIcon,
                    userHome, proxy, javaVersion, is64bit, memoryLarge, optimize);
            if (nativeDefaults != null) res.addAll(nativeDefaults);
            else appendPlatformJvmDefaults(res, clientJar, dockName, dockIcon, userHome, proxy, javaVersion, is64bit, memoryLarge, optimize);
        }

        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                && options.getRenderer() instanceof Renderer.Driver renderer
                && renderer.mesaDriverName() != null) {
            res.addDefault("-Dorg.glavo.mesa.loader.nativeDir=", FileUtils.getAbsolutePath(nativeFolder.resolve("mesa-loader")));
        }

        if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS
                && options.getJava().getArchitecture() == Architecture.SYSTEM_ARCH
                && options.getRenderer() instanceof Renderer.Vulkan vulkanDriver
                && vulkanDriver.icdFile() != null) {
            if (Files.isRegularFile(HomebrewUtils.LIB_VULKAN)) {
                res.addDefault("-Dorg.lwjgl.vulkan.libname=", FileUtils.getAbsolutePath(HomebrewUtils.LIB_VULKAN));
            }
        }

        // Library classpath used both for -cp and for rewriting old BootstrapLauncher ignore lists.
        Set<String> libraryClasspath = getClasspath();

        if (instance.hasComponent(GameComponentType.CLEANROOM)) {
            libraryClasspath.removeIf(c -> c.contains("2.9.4-nightly-20150209"));
            libraryClasspath.removeIf(c -> c.contains("platform-3.4.0"));
            libraryClasspath.removeIf(c -> c.contains("icu4j-core-mojang"));
        }

        Path jar = instance.getInstanceJarFile();
        if (!Files.isRegularFile(jar))
            throw new IOException("Minecraft jar does not exist");
        Set<String> classpath = new LinkedHashSet<>(libraryClasspath);
        classpath.add(FileUtils.getAbsolutePath(jar));

        // Provided Minecraft arguments
        Path gameAssets = instance.getActualAssetDirectory(manifest.getAssetIndex().getId());
        Map<String, String> configuration = getConfigurations();
        configuration.put("${classpath}", String.join(File.pathSeparator, classpath));
        configuration.put("${game_assets}", FileUtils.getAbsolutePath(gameAssets));
        configuration.put("${assets_root}", FileUtils.getAbsolutePath(gameAssets));

        Optional<String> gameVersion = findGameVersion();

        // lwjgl assumes path to native libraries encoded by ASCII.
        // Here is a workaround for this issue: https://github.com/HMCL-dev/HMCL/issues/1141.
        String nativeFolderPath = FileUtils.getAbsolutePath(nativeFolder);
        Path tempNativeFolder = null;
        if ((OperatingSystem.CURRENT_OS == OperatingSystem.LINUX || OperatingSystem.CURRENT_OS == OperatingSystem.MACOS)
                && !StringUtils.isASCII(nativeFolderPath)
                && gameVersion.isPresent() && GameVersionNumber.compare(gameVersion.get(), "1.19") < 0) {
            tempNativeFolder = Paths.get("/", "tmp", "hmcl-natives-" + UUID.randomUUID());
            nativeFolderPath = tempNativeFolder + File.pathSeparator + nativeFolderPath;
        }
        configuration.put("${natives_directory}", nativeFolderPath);

        Path javaNativeFolder = FileUtils.toAbsolute(nativeFolder);
        @Nullable List<Argument> jvmArguments = Optional.ofNullable(manifest.arguments()).map(Arguments::jvm).orElse(null);
        if (jvmArguments != null) {
            jvmArguments = rewriteUnsafeBootstrapLauncherIgnoreList(jvmArguments, libraryClasspath);
        }

        if (jvmArguments != null) {
            for (Argument jvmArgument : jvmArguments) {
                if (jvmArgument instanceof StringArgument stringArgument
                        && stringArgument.argument().startsWith("-Djava.library.path=")) {

                    // We conservatively handle parameters like "-Djava.library.path=${natives_directory}/java"
                    // to avoid extracting native libraries to unexpected locations.

                    String prefix = "-Djava.library.path=${natives_directory}/";
                    if (stringArgument.argument().startsWith(prefix)) {
                        try {
                            String subDir = stringArgument.argument().substring(prefix.length());
                            Path actualNativeFolder = FileUtils.toAbsolute(javaNativeFolder.resolve(subDir));

                            if (actualNativeFolder.startsWith(javaNativeFolder)) {
                                javaNativeFolder = actualNativeFolder;
                            }
                        } catch (IllegalArgumentException ignored) {
                        }
                    }

                    break;
                }
            }
        }

        List<Argument> effectiveJvmArguments = Objects.requireNonNullElseGet(jvmArguments, this::getDefaultJVMArguments);
        @Nullable Arguments argumentsFromAuthInfo = authInfo.getLaunchArguments(options);
        List<Argument> authJvmArguments = argumentsFromAuthInfo == null || argumentsFromAuthInfo.jvm() == null
                ? Collections.emptyList() : argumentsFromAuthInfo.jvm();
        List<Argument> gameArguments = Optional.ofNullable(manifest.arguments()).map(Arguments::game).orElseGet(Collections::emptyList);
        List<Argument> defaultGameArguments = manifest.minecraftArguments() == null ? Collections.emptyList() : getDefaultGameArguments();
        List<Argument> authGameArguments = argumentsFromAuthInfo == null || argumentsFromAuthInfo.game() == null
                ? Collections.emptyList() : argumentsFromAuthInfo.game();
        List<String> legacyArguments = Optional.ofNullable(manifest.minecraftArguments()).map(StringUtils::tokenize).orElseGet(ArrayList::new);
        Map<String, Boolean> features = getFeatures();
        List<List<String>> expandedArguments = expandLaunchArgumentGroups(effectiveJvmArguments, authJvmArguments,
                legacyArguments, gameArguments, defaultGameArguments, authGameArguments, options.getGameArguments(), configuration, features);

        if (manifest.mainClass() == null) {
            throw new IllegalStateException("Main class is null for instance " + manifest.id());
        }
        @Nullable NativeLaunchAssembly.Plan assembly = NativeLaunchAssembly.tryPlan(expandedArguments, options.getJavaAgents(), manifest.mainClass());
        if (assembly == null) {
            res.addAll(expandedArguments.get(0));
            res.addAll(expandedArguments.get(1));
            for (String javaAgent : options.getJavaAgents()) res.add("-javaagent:" + javaAgent);
            res.add(manifest.mainClass());
            res.addAll(expandedArguments.get(2));
            res.addAll(expandedArguments.get(3));
            res.addAll(expandedArguments.get(4));
            res.addAll(expandedArguments.get(5));
        } else {
            res.addAll(assembly.before());
        }

        @Nullable String graphicsArgument = options.getGraphicsBackend() != GraphicsAPI.DEFAULT
                && gameVersion.isPresent() && GameVersionNumber.compare(gameVersion.get(), "26.2-snapshot-2") >= 0
                ? options.getGraphicsBackend().getMinecraftArg() : null;
        @Nullable List<String> nativeExtras = options.getQuickPlayOption() == null && !options.isFullscreen()
                && !(options.getProxyOption() instanceof ProxyOption.Socks) && graphicsArgument == null
                ? List.of() : NativeLaunchExtras.tryPlan(options.getQuickPlayOption(),
                        options.getQuickPlayOption() != null && World.supportQuickPlay(GameVersionNumber.asGameVersion(gameVersion)),
                        options.isFullscreen(), options.getProxyOption(), graphicsArgument);
        if (nativeExtras != null) {
            res.addAll(nativeExtras);
        } else {
            if (options.getQuickPlayOption() instanceof QuickPlayOption.MultiPlayer multiPlayer) {
                String address = multiPlayer.serverIP();

                try {
                    ServerAddress parsed = ServerAddress.parse(address);
                    if (World.supportQuickPlay(GameVersionNumber.asGameVersion(gameVersion))) {
                        res.add("--quickPlayMultiplayer");
                        res.add(parsed.port() >= 0 ? address : parsed.host() + ":25565");
                    } else {
                        res.add("--server");
                        res.add(parsed.host());
                        res.add("--port");
                        res.add(parsed.port() >= 0 ? String.valueOf(parsed.port()) : "25565");
                    }
                } catch (IllegalArgumentException e) {
                    LOG.warning("Invalid server address: " + address, e);
                }
            } else if (options.getQuickPlayOption() instanceof QuickPlayOption.SinglePlayer singlePlayer
                    && World.supportQuickPlay(GameVersionNumber.asGameVersion(gameVersion))) {
                res.add("--quickPlaySingleplayer");
                res.add(singlePlayer.worldFolderName());
            } else if (options.getQuickPlayOption() instanceof QuickPlayOption.Realm realm
                    && World.supportQuickPlay(GameVersionNumber.asGameVersion(gameVersion))) {
                res.add("--quickPlayRealms");
                res.add(realm.realmID());
            }

            if (options.isFullscreen())
                res.add("--fullscreen");

            // https://github.com/HMCL-dev/HMCL/issues/774
            if (options.getProxyOption() instanceof ProxyOption.Socks socksProxy) {
                res.add("--proxyHost");
                res.add(socksProxy.host());
                res.add("--proxyPort");
                res.add(String.valueOf(socksProxy.port()));
                if (StringUtils.isNotBlank(socksProxy.username())) {
                    res.add("--proxyUser");
                    res.add(socksProxy.username());
                    res.add("--proxyPass");
                    res.add(Objects.requireNonNullElse(socksProxy.password(), ""));
                }
            }

            if (options.getGraphicsBackend() != GraphicsAPI.DEFAULT
                    && gameVersion.isPresent() && GameVersionNumber.compare(gameVersion.get(), "26.2-snapshot-2") >= 0) {
                res.add("--graphicsBackend");
                res.add(options.getGraphicsBackend().getMinecraftArg());
            }
        }
        res.addAllWithoutParsing(assembly == null ? expandedArguments.get(6) : assembly.after());

        res.removeIf(it -> getForbiddens().containsKey(it) && getForbiddens().get(it).get());
        return new Command(res, tempNativeFolder, javaNativeFolder, encoding);
    }

    public Map<String, Boolean> getFeatures() {
        return Collections.singletonMap(
                "has_custom_resolution",
                options.getHeight() != null && options.getHeight() != 0 && options.getWidth() != null && options.getWidth() != 0
        );
    }

    /// Applies platform, game proxy and optimization JVM defaults using the Java compatibility path.
    /// @param res current command builder
    /// @param clientJar game JAR path
    /// @param dockName macOS dock label, or null
    /// @param dockIcon macOS dock icon path, or null
    /// @param userHome home path on non-Windows systems, or null
    /// @param proxy selected game proxy, or null for default
    /// @param javaVersion Java major version
    /// @param is64bit whether Java is 64-bit
    /// @param memoryLarge whether system memory exceeds four GiB
    /// @param optimize whether optimizing defaults are enabled
    static void appendPlatformJvmDefaults(CommandBuilder res, String clientJar, @Nullable String dockName,
            @Nullable String dockIcon, @Nullable String userHome, @Nullable ProxyOption proxy, int javaVersion,
            boolean is64bit, boolean memoryLarge, boolean optimize) {
        res.addDefault("-Dminecraft.client.jar=", clientJar);
        if (dockName != null) res.addDefault("-Xdock:name=", dockName);
        if (dockIcon != null) res.addDefault("-Xdock:icon=", dockIcon);
        if (userHome != null) res.addDefault("-Duser.home=", userHome);
        boolean addProxyOptions = res.noneMatch(arg ->
                arg.startsWith("-Djava.net.useSystemProxies=")
                        || arg.startsWith("-Dhttp.proxy")
                        || arg.startsWith("-Dhttps.proxy")
                        || arg.startsWith("-DsocksProxy")
                        || arg.startsWith("-Djava.net.socks.")
        );

        if (addProxyOptions) {
            if (proxy == null || proxy == ProxyOption.Default.INSTANCE) {
                res.add("-Djava.net.useSystemProxies=true");
            } else if (proxy instanceof ProxyOption.Http httpProxy) {
                res.add("-Dhttp.proxyHost=" + httpProxy.host());
                res.add("-Dhttp.proxyPort=" + httpProxy.port());
                res.add("-Dhttps.proxyHost=" + httpProxy.host());
                res.add("-Dhttps.proxyPort=" + httpProxy.port());

                if (StringUtils.isNotBlank(httpProxy.username())) {
                    res.add("-Dhttp.proxyUser=" + httpProxy.username());
                    res.add("-Dhttp.proxyPassword=" + Objects.requireNonNullElse(httpProxy.password(), ""));
                    res.add("-Dhttps.proxyUser=" + httpProxy.username());
                    res.add("-Dhttps.proxyPassword=" + Objects.requireNonNullElse(httpProxy.password(), ""));
                }
            } else if (proxy instanceof ProxyOption.Socks socksProxy) {
                res.add("-DsocksProxyHost=" + socksProxy.host());
                res.add("-DsocksProxyPort=" + socksProxy.port());

                if (StringUtils.isNotBlank(socksProxy.username())) {
                    res.add("-Djava.net.socks.username=" + socksProxy.username());
                    res.add("-Djava.net.socks.password=" + Objects.requireNonNullElse(socksProxy.password(), ""));
                }
            }
        }

        appendJvmOptimizationDefaults(res, javaVersion, is64bit, memoryLarge, optimize);
    }

    /// Applies heap, metaspace and ordinary JVM defaults using the Java compatibility path.
    /// @param res current command builder
    /// @param javaVersion Java major version
    /// @param min minimum heap size in MiB, or null
    /// @param max maximum heap size in MiB, or null
    /// @param metaspace metaspace size in MiB, or null
    /// @param defaults user JVM defaults
    static void appendInitialJvmDefaults(CommandBuilder res, int javaVersion, @Nullable Integer min, @Nullable Integer max,
            @Nullable Integer metaspace, List<String> defaults) {
        if (max != null && max > 0) res.addDefault("-Xmx", max + "m");
        if (min != null && min > 0 && (max == null || min <= max)) res.addDefault("-Xms", min + "m");
        if (metaspace != null && metaspace > 0)
            res.addDefault(javaVersion < 8 ? "-XX:PermSize=" : "-XX:MetaspaceSize=", metaspace + "m");
        res.addAllDefaultWithoutParsing(defaults);
    }

    /// Applies the Java-compatible JVM optimization and version defaults.
    /// @param res current command builder
    /// @param javaVersion Java major version
    /// @param is64bit whether Java is 64-bit
    /// @param memoryLarge whether system memory exceeds four GiB
    /// @param optimize whether optimizing defaults are enabled
    static void appendJvmOptimizationDefaults(CommandBuilder res, int javaVersion, boolean is64bit, boolean memoryLarge, boolean optimize) {
        if (optimize) {
            res.addUnstableDefault("UnlockExperimentalVMOptions", true);
            res.addUnstableDefault("UnlockDiagnosticVMOptions", true);

            // Using G1GC with its settings by default
            if (javaVersion >= 8
                    && res.noneMatch(arg -> "-XX:-UseG1GC".equals(arg) || (arg.startsWith("-XX:+Use") && arg.endsWith("GC")))) {
                res.addUnstableDefault("UseG1GC", true);
                res.addUnstableDefault("G1MixedGCCountTarget", "5");
                res.addUnstableDefault("G1NewSizePercent", "20");
                res.addUnstableDefault("G1ReservePercent", "20");
                res.addUnstableDefault("MaxGCPauseMillis", "50");
                res.addUnstableDefault("G1HeapRegionSize", "32m");
            }

            res.addUnstableDefault("OmitStackTraceInFastThrow", false);

            // JIT Options
            if (javaVersion <= 8) {
                res.addUnstableDefault("MaxInlineLevel", "15");
            }
            if (is64bit && memoryLarge) {
                res.addUnstableDefault("DontCompileHugeMethods", false);
                res.addUnstableDefault("MaxNodeLimit", "240000");
                res.addUnstableDefault("NodeLimitFudgeFactor", "8000");
                res.addUnstableDefault("TieredCompileTaskTimeout", "10000");
                res.addUnstableDefault("ReservedCodeCacheSize", "400M");
                if (javaVersion >= 9) {
                    res.addUnstableDefault("NonNMethodCodeHeapSize", "12M");
                    res.addUnstableDefault("ProfiledCodeHeapSize", "194M");
                }

                if (javaVersion >= 8) {
                    res.addUnstableDefault("NmethodSweepActivity", "1");
                }
            }

            if (is64bit && (javaVersion >= 25 && javaVersion <= 26)) {
                res.addUnstableDefault("UseCompactObjectHeaders", true);
            }

            // As 32-bit JVM allocate 320KB for stack by default rather than 64-bit version allocating 1MB,
            // causing Minecraft 1.13 crashed accounting for java.lang.StackOverflowError.
            if (!is64bit) {
                res.addDefault("-Xss", "1m");
            }
        }

        if (javaVersion == 16)
            res.addDefault("--illegal-access=", "permit");

        if (javaVersion == 24 || javaVersion == 25)
            res.addDefault("--sun-misc-unsafe-memory-access=", "allow");

        res.addDefault("-Dfml.ignoreInvalidMinecraftCertificates=", "true");
        res.addDefault("-Dfml.ignorePatchDiscrepancies=", "true");
    }

    /// Selects applicable argument values and expands them in one native request when supported.
    ///
    /// Rule evaluation remains in Java for compatibility with existing platform and feature rules.
    /// Unknown argument implementations retain the original Java parser for all groups.
    ///
    /// @param jvm version JVM arguments
    /// @param authJvm authentication JVM arguments
    /// @param legacy legacy game arguments
    /// @param game version game arguments
    /// @param defaults default game arguments
    /// @param authGame authentication game arguments
    /// @param custom custom user game arguments
    /// @param configuration placeholder values, potentially containing credentials
    /// @param features active launch features
    /// @return expanded groups in the same order as the input groups
    /// @throws IOException if native expansion is interrupted
    static List<List<String>> expandLaunchArgumentGroups(List<Argument> jvm, List<Argument> authJvm,
            List<String> legacy, List<Argument> game, List<Argument> defaults, List<Argument> authGame,
            List<String> custom, Map<String, String> configuration, Map<String, Boolean> features) throws IOException {
        @Nullable String configured = System.getProperty("mfcl.native.path");
        if (configured != null && !configured.isBlank()
                && Boolean.parseBoolean(System.getProperty("mfcl.native.arguments.enabled", "true"))) {
            try {
                List<List<Argument>> nativeGroups = List.of(jvm, authJvm, wrapStringArguments(legacy), game,
                        defaults, authGame, wrapStringArguments(custom));
                List<Map<String, Boolean>> featureGroups = List.of(Collections.emptyMap(), Collections.emptyMap(),
                        Collections.emptyMap(), features, features, features, Collections.emptyMap());
                return new ArrayList<>(NativeBackend.expandRuledArgumentGroups(Path.of(configured), nativeGroups, featureGroups, configuration));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Native argument planning was interrupted");
            } catch (IOException | RuntimeException e) {
                LOG.warning("Native launch rule planning failed; using compatibility path");
            }
        }
        List<List<Argument>> ruledGroups = List.of(jvm, authJvm, game, defaults, authGame);
        List<List<String>> raw = new ArrayList<>(ruledGroups.size());
        for (int i = 0; i < ruledGroups.size(); i++) {
            Map<String, Boolean> active = i < 2 ? Collections.emptyMap() : features;
            Optional<List<String>> selected = Arguments.selectRawArguments(ruledGroups.get(i), active);
            if (selected.isEmpty()) {
                return List.of(Arguments.parseArguments(jvm, configuration), Arguments.parseArguments(authJvm, configuration),
                        Arguments.parseStringArguments(legacy, configuration), Arguments.parseArguments(game, configuration, features),
                        Arguments.parseArguments(defaults, configuration, features), Arguments.parseArguments(authGame, configuration, features),
                        Arguments.parseStringArguments(custom, configuration));
            }
            raw.add(selected.get());
        }
        return expandStringArgumentGroups(List.of(raw.get(0), raw.get(1), legacy, raw.get(2), raw.get(3), raw.get(4), custom), configuration);
    }

    /// Converts raw string arguments to the common argument model, omitting null entries.
    ///
    /// @param values raw argument strings
    /// @return equivalent argument objects
    private static List<Argument> wrapStringArguments(List<String> values) {
        List<Argument> result = new ArrayList<>();
        for (@Nullable String value : values) if (value != null) result.add(new StringArgument(value));
        return result;
    }

    /// Expands launch argument groups with the configured native backend when available.
    ///
    /// The configuration can contain credentials; request contents and native failures must not be
    /// logged. Unsupported requests or backend failures fall back to the existing Java parser.
    ///
    /// @param groups ordered groups of raw arguments
    /// @param configuration placeholder values
    /// @return expanded argument groups
    /// @throws IOException if the current thread is interrupted
    static List<List<String>> expandStringArgumentGroups(List<List<String>> groups, Map<String, String> configuration) throws IOException {
        @Nullable String configured = System.getProperty("mfcl.native.path");
        if (configured != null && !configured.isBlank()
                && Boolean.parseBoolean(System.getProperty("mfcl.native.arguments.enabled", "true"))) {
            try {
                return new ArrayList<>(NativeBackend.expandArgumentGroups(Path.of(configured), groups, configuration));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Native argument expansion was interrupted");
            } catch (IOException | RuntimeException e) {
                LOG.warning("Native launch argument expansion failed; using Java parser");
            }
        }
        List<List<String>> result = new ArrayList<>(groups.size());
        for (List<String> group : groups) result.add(Arguments.parseStringArguments(group, configuration));
        return result;
    }

    private final Map<String, Supplier<Boolean>> forbiddens = mapOf(
            pair("-Xincgc", () -> options.getJava().getParsedVersion() >= 9)
    );

    protected Map<String, Supplier<Boolean>> getForbiddens() {
        return forbiddens;
    }

    protected List<Argument> getDefaultJVMArguments() {
        return Arguments.DEFAULT_JVM_ARGUMENTS;
    }

    protected List<Argument> getDefaultGameArguments() {
        return Arguments.DEFAULT_GAME_ARGUMENTS;
    }

    /// Do something here.
    /// i.e.
    /// -Dminecraft.launcher.version=<Your launcher name>
    /// -Dminecraft.launcher.brand=<Your launcher version>
    /// -Dlog4j.configurationFile=<Your custom log4j configuration>
    protected void appendJvmArgs(CommandBuilder result) {
    }

    public void decompressNatives(Path destination) throws NotDecompressingNativesException {
        LOG.info("Decompress native libraries to " + destination);

        try {
            List<Library> libraries = manifest.getLibraries();
            @Nullable Map<Integer, Path> archives = planNativeArchives(instance.getLayout(), instance.getId(), libraries);
            boolean @Nullable [] nativeSelection = archives == null ? selectNativeLibraries(libraries) : null;
            FileUtils.cleanDirectoryQuietly(destination);
            for (int index = 0; index < libraries.size(); index++) {
                Library library = libraries.get(index);
                if (archives != null ? archives.containsKey(index) : (nativeSelection == null ? library.isNative() : nativeSelection[index]))
                    new Unzipper(archives == null ? instance.getLayout().getLibraryFile(instance.getId(), library) : archives.get(index), destination)
                            .setNativeExtraction()
                            .setFilter((zipEntry, destFile, relativePath) -> {
                                if (!zipEntry.isDirectory() && !zipEntry.isUnixSymlink()
                                        && Files.isRegularFile(destFile)
                                        && zipEntry.getSize() == Files.size(destFile)) {
                                    return false;
                                }
                                String ext = FileUtils.getExtension(destFile);
                                if (ext.equals("sha1") || ext.equals("git"))
                                    return false;

                                if (options.isUseNativeGLFWorSDL() && FileUtils.getName(destFile).toLowerCase(Locale.ROOT).contains("glfw")) {
                                    return false;
                                }

                                if (options.isUseNativeGLFWorSDL() && FileUtils.getName(destFile).toLowerCase(Locale.ROOT).contains("sdl")) {
                                    return false;
                                }

                                if (options.isUseNativeOpenAL() && FileUtils.getName(destFile).toLowerCase(Locale.ROOT).contains("openal")) {
                                    return false;
                                }

                                return library.getExtract().shouldExtract(relativePath);
                            })
                            .setReplaceExistentFile(false).unzip();
            }
        } catch (IOException e) {
            throw new NotDecompressingNativesException(e);
        }
    }

    /// Plans standard-layout native archive paths using Rust when supported.
    /// @param layout repository layout
    /// @param instanceId game instance identifier
    /// @param libraries resolved libraries in their original order
    /// @return archive paths, or null to use the existing path resolution
    /// @throws InterruptedIOException if interrupted
    static @Nullable Map<Integer, Path> planNativeArchives(GameRepositoryLayout layout, GameInstanceID instanceId,
            List<Library> libraries) throws InterruptedIOException {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.libraries.enabled", "true"))) return null;
        try {
            return NativeBackend.planNativeArchives(Path.of(executable), layout, instanceId, libraries);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Native archive planning was interrupted");
        } catch (IOException | RuntimeException e) {
            LOG.warning("Native archive planning failed; using Java paths");
            return null;
        }
    }

    /// Selects native archives using Rust when the configured backend supports it.
    /// @param libraries resolved libraries in their original order
    /// @return native selection, or null to use Java
    /// @throws InterruptedIOException if interrupted
    static boolean @Nullable [] selectNativeLibraries(List<Library> libraries) throws InterruptedIOException {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable == null || executable.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.libraries.enabled", "true"))) return null;
        try {
            return NativeBackend.selectNativeLibraries(Path.of(executable), libraries);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Native library selection was interrupted");
        } catch (IOException | RuntimeException e) {
            LOG.warning("Native library selection failed; using Java path");
            return null;
        }
    }

    /// Returns the detected Minecraft version string for this instance, if known.
    ///
    /// @return the version string, or empty when detection failed
    private Optional<String> findGameVersion() {
        GameVersionNumber version = instance.getVersion();
        if (version == GameVersionNumber.unknown()) {
            return Optional.empty();
        }
        return Optional.of(version.toString());
    }

    private boolean isUsingLog4j() {
        return GameVersionNumber.compare(findGameVersion().orElse("1.7"), "1.7") >= 0;
    }

    public Path getLog4jConfigurationFile() {
        return instance.getInstanceRoot().resolve("log4j2.xml");
    }

    public void extractLog4jConfigurationFile() throws IOException {
        Path targetFile = getLog4jConfigurationFile();

        String sourcePath;

        if (GameVersionNumber.asGameVersion(findGameVersion()).compareTo("1.12") < 0) {
            if (options.isEnableDebugLogOutput()) {
                sourcePath = "/assets/game/log4j2-1.7-debug.xml";
            } else {
                sourcePath = "/assets/game/log4j2-1.7.xml";
            }
        } else {
            if (options.isEnableDebugLogOutput()) {
                sourcePath = "/assets/game/log4j2-1.12-debug.xml";
            } else {
                sourcePath = "/assets/game/log4j2-1.12.xml";
            }
        }

        try (InputStream input = DefaultLauncher.class.getResourceAsStream(sourcePath)) {
            Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Rewrites `-DignoreList=` for old BootstrapLauncher when launching Forge / NeoForge.
    ///
    /// BootstrapLauncher older than 0.1.17 matches each ignore-list token as a substring against
    /// every classpath component. A game directory such as `/Users/asm` therefore causes every
    /// library whose path contains `asm` to be ignored. Using the library classpath already built
    /// for this launch, loose tokens are replaced with exact installed paths (or portable
    /// `${library_directory}` placeholders). `${primary_jar}` is always retained for Jigsaw.
    ///
    /// BootstrapLauncher 0.1.17+ only matches file names; those manifests are repaired for launch by
    /// [LaunchManifestNormalizer#repairForLaunch(GameInstanceManifest)].
    ///
    /// @param jvmArguments     JVM arguments from the launch manifest
    /// @param libraryClasspath absolute library classpath entries for this launch (without primary jar)
    /// @return a possibly rewritten argument list; the input list is not modified
    private List<Argument> rewriteUnsafeBootstrapLauncherIgnoreList(
            List<Argument> jvmArguments,
            Set<String> libraryClasspath) throws IOException {
        if (!GameComponentAnalyzer.BOOTSTRAP_LAUNCHER_MAIN.equals(manifest.mainClass())) {
            return jvmArguments;
        }
        if (!instance.hasComponent(GameComponentType.FORGE) && !instance.hasComponent(GameComponentType.NEO_FORGE)) {
            return jvmArguments;
        }
        @Nullable String bootstrapVersion = instance.getAnalyzer().getBootstrapVersion();
        if (bootstrapVersion == null || VersionNumber.compare(bootstrapVersion, "0.1.17") >= 0) {
            return jvmArguments;
        }

        Path libraryDirectory = instance.getLayout().getLibrariesDirectory().toAbsolutePath().normalize();
        List<String> rawLists = new ArrayList<>();
        for (Argument argument : jvmArguments)
            if (argument instanceof StringArgument value && value.argument().startsWith("-DignoreList="))
                rawLists.add(value.argument().substring("-DignoreList=".length()));
        @Nullable List<String> nativeLists = rawLists.isEmpty() ? null : NativeBootstrapIgnore.tryPlan(rawLists, libraryClasspath, libraryDirectory);
        List<Argument> rewritten = new ArrayList<>(jvmArguments.size());
        boolean changed = false; int listIndex = 0;
        for (Argument argument : jvmArguments) {
            if (argument instanceof StringArgument stringArgument) {
                String value = stringArgument.argument();
                if (value.startsWith("-DignoreList=")) {
                    String list = nativeLists != null ? nativeLists.get(listIndex)
                            : rewriteIgnoreList(value.substring("-DignoreList=".length()), libraryClasspath, libraryDirectory);
                    rewritten.add(new StringArgument("-DignoreList=" + list));
                    listIndex++;
                    changed = true;
                    continue;
                }
            }
            rewritten.add(argument);
        }
        return changed ? rewritten : jvmArguments;
    }

    /// Converts a substring-based BootstrapLauncher ignore list to exact classpath entries.
    ///
    /// For example, if `client-extra` is listed and a path component contains `client-extra`,
    /// every matching library would be ignored under substring matching. Matching is performed
    /// against library file names only; matched jars are rewritten to concrete paths.
    ///
    /// @param ignoreList       the original comma-separated substring list
    /// @param libraryClasspath absolute library classpath entries for this launch
    /// @param libraryDirectory absolute `.minecraft/libraries` directory
    /// @return the exact comma-separated ignore list
    static String rewriteIgnoreList(
            String ignoreList,
            Set<String> libraryClasspath,
            Path libraryDirectory) {
        String[] ignoredSubstrings = ignoreList.split(",");
        List<String> exactEntries = new ArrayList<>();
        exactEntries.add("${primary_jar}");

        for (String classpathName : libraryClasspath) {
            Path classpathFile = Paths.get(classpathName).toAbsolutePath();
            String fileName = classpathFile.getFileName().toString();
            if (Stream.of(ignoredSubstrings).anyMatch(fileName::contains)) {
                String absolutePath;
                if (classpathFile.startsWith(libraryDirectory)) {
                    absolutePath = "${library_directory}${file_separator}"
                            + libraryDirectory.relativize(classpathFile).toString()
                            .replace(File.separator, "${file_separator}");
                } else {
                    absolutePath = classpathFile.toString();
                }
                exactEntries.add(StringUtils.substringBefore(absolutePath, ","));
            }
        }
        return String.join(",", exactEntries);
    }

    protected Map<String, String> getConfigurations() {
        return mapOf(
                // defined by Minecraft official launcher
                pair("${auth_player_name}", authInfo.getUsername()),
                pair("${auth_session}", authInfo.getAccessToken()),
                pair("${auth_access_token}", authInfo.getAccessToken()),
                pair("${auth_uuid}", UUIDs.toCompactString(authInfo.getUUID())),
                pair("${version_name}", Optional.ofNullable(options.getVersionName()).orElse(manifest.id().toString())),
                pair("${profile_name}", Optional.ofNullable(options.getProfileName()).orElse("Minecraft")),
                pair("${version_type}", Optional.ofNullable(options.getVersionType()).orElse(manifest.type() != null ? manifest.type().getId() : ReleaseType.UNKNOWN.getId())),
                pair("${game_directory}", FileUtils.getAbsolutePath(instance.getRunDirectory())),
                pair("${user_type}", authInfo.getUserType()),
                pair("${assets_index_name}", manifest.getAssetIndex().getId()),
                pair("${user_properties}", authInfo.getUserProperties()),
                pair("${resolution_width}", options.getWidth().toString()),
                pair("${resolution_height}", options.getHeight().toString()),
                pair("${library_directory}", FileUtils.getAbsolutePath(instance.getLayout().getLibrariesDirectory())),
                pair("${classpath_separator}", File.pathSeparator),
                pair("${primary_jar}", FileUtils.getAbsolutePath(instance.getInstanceJarFile())),
                pair("${language}", Locale.getDefault().toLanguageTag()),

                // defined by HMCL
                // libraries_directory stands for historical reasons here. We don't know the official launcher
                // had already defined "library_directory" as the placeholder for path to ".minecraft/libraries"
                // when we propose this placeholder.
                pair("${libraries_directory}", FileUtils.getAbsolutePath(instance.getLayout().getLibrariesDirectory())),
                // file_separator is used in -DignoreList
                pair("${file_separator}", File.separator),
                pair("${primary_jar_name}", FileUtils.getName(instance.getInstanceJarFile()))
        );
    }

    /// Returns the native library directory selected by the launch options.
    private Path getNativeFolder() {
        if (StringUtils.isBlank(options.getNativesDir())) {
            return instance.getNativeDirectory(options.getJava().getPlatform());
        }

        return Path.of(options.getNativesDir());
    }

    @Override
    public ManagedProcess launch() throws IOException, InterruptedException {
        Path nativeFolder = getNativeFolder();

        final Command command = generateCommandLine(nativeFolder);

        // To guarantee that when failed to generate launch command line, we will not call pre-launch command
        List<String> rawCommandLine = command.commandLine.asList();

        if (command.tempNativeFolder != null) {
            Files.deleteIfExists(command.tempNativeFolder);
            Files.createSymbolicLink(command.tempNativeFolder, nativeFolder.toAbsolutePath());
        }

        if (rawCommandLine.stream().anyMatch(StringUtils::isBlank)) {
            throw new IllegalStateException("Illegal command line " + rawCommandLine);
        }

        if (!options.isUseCustomNatives()) {
            decompressNatives(command.javaNativeFolder);
        }

        if (isUsingLog4j())
            extractLog4jConfigurationFile();

        Path runDirectory = instance.getRunDirectory();

        if (StringUtils.isNotBlank(options.getPreLaunchCommand())) {
            ProcessBuilder builder = new ProcessBuilder(StringUtils.tokenize(options.getPreLaunchCommand(), getEnvVars(nativeFolder))).directory(runDirectory.toFile());
            builder.environment().putAll(getEnvVars(nativeFolder));
            SystemUtils.callExternalProcess(builder);
        }

        Process process;
        try {
            Map<String, String> environment = new LinkedHashMap<>();
            Path appdata = options.getGameDir().toAbsolutePath().getParent();
            if (appdata != null) environment.put("APPDATA", appdata.toString());
            environment.putAll(getEnvVars(nativeFolder));
            @Nullable NativeLaunchConfig nativeLaunch = listener != null && NativeLaunch.supportsCommand(rawCommandLine) ? nativeLaunchExecutable() : null;
            if (nativeLaunch != null) {
                process = NativeLaunch.start(nativeLaunch.executable(), rawCommandLine, runDirectory, environment, nativeLaunch.protocol());
            } else {
                ProcessBuilder builder = new ProcessBuilder(rawCommandLine).directory(runDirectory.toFile());
                if (listener == null) builder.inheritIO();
                builder.environment().putAll(environment);
                process = builder.start();
            }
        } catch (IOException e) {
            throw new ProcessCreationException(e);
        }

        ManagedProcess p = new ManagedProcess(process, rawCommandLine);
        if (listener != null)
            startMonitors(p, nativeFolder, listener, command.encoding, daemon);
        return p;
    }

    /// Resolves a configured native process-replacement backend when its capability is available.
    ///
    /// Unsupported systems, disabled native launch, or a failed capability probe retain the Java
    /// process-creation path. A native child is never retried after it may have started the game.
    ///
    /// @return native executable path, or `null` when Java should launch the process
    /// @throws InterruptedException if the capability check is interrupted
    private record NativeLaunchConfig(Path executable, int protocol) { }

    private @Nullable NativeLaunchConfig nativeLaunchExecutable() throws InterruptedException {
        @Nullable String configured = System.getProperty("mfcl.native.path");
        if (configured == null || configured.isBlank()
                || !Boolean.parseBoolean(System.getProperty("mfcl.native.launch.enabled", "true"))
                || !(OperatingSystem.CURRENT_OS.isLinuxOrBSD() || OperatingSystem.CURRENT_OS == OperatingSystem.MACOS)) {
            return null;
        }
        try {
            Path executable = Path.of(configured);
            int protocol = NativeBackend.launchExecProtocol(executable);
            return protocol == 0 ? null : new NativeLaunchConfig(executable, protocol);
        } catch (IOException | RuntimeException e) {
            LOG.warning("Native game launch is unavailable; using Java process creation");
            return null;
        }
    }

    private Map<String, String> getEnvVars(Path nativeFolder) {
        String versionName = Optional.ofNullable(options.getVersionName()).orElse(manifest.id().toString());

        Map<String, String> env = new LinkedHashMap<>();
        env.put("INST_NAME", versionName);
        env.put("INST_ID", versionName);
        env.put("INST_DIR", FileUtils.getAbsolutePath(instance.getInstanceRoot()));
        env.put("INST_MC_DIR", FileUtils.getAbsolutePath(instance.getRunDirectory()));
        env.put("INST_JAVA", options.getJava().getBinary().toString());

        if (options.getRenderer() instanceof Renderer.Driver driver) {
            if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                if (driver.mesaDriverName() != null) {
                    if (driver instanceof Renderer.OpenGL && driver != Renderer.OpenGL.LLVMPIPE)
                        env.put("GALLIUM_DRIVER", driver.mesaDriverName());
                    else if (driver instanceof Renderer.Vulkan vulkanDriver) {
                        String icdFile = FileUtils.getAbsolutePath(nativeFolder.resolve("mesa-loader/" + vulkanDriver.icdName() + "_icd.json"));

                        env.put("VK_ICD_FILENAMES", icdFile);
                        env.put("VK_DRIVER_FILES", icdFile);
                    }
                } else if (driver instanceof Renderer.Vulkan vulkanDriver
                        && vulkanDriver.icdFile() != null
                        && options.getJava().getArchitecture() == Architecture.SYSTEM_ARCH) {
                    String icdFile = FileUtils.getAbsolutePath(vulkanDriver.icdFile());

                    env.put("VK_ICD_FILENAMES", icdFile);
                    env.put("VK_DRIVER_FILES", icdFile);
                }
            } else if (OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
                if (driver instanceof Renderer.OpenGL oglDriver) {
                    if (oglDriver == Renderer.OpenGL.LLVMPIPE) {
                        env.put("__GLX_VENDOR_LIBRARY_NAME", "mesa");
                        env.put("LIBGL_ALWAYS_SOFTWARE", "1");
                    } else if (oglDriver == Renderer.OpenGL.ZINK) {
                        env.put("__GLX_VENDOR_LIBRARY_NAME", "mesa");
                        env.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
                        /*
                         * The amdgpu DDX is missing support for modifiers, causing Zink to fail.
                         * Disable DRI3 to workaround this issue.
                         *
                         * Link: https://gitlab.freedesktop.org/mesa/mesa/-/issues/10093
                         */
                        env.put("LIBGL_KOPPER_DRI2", "1");
                    }
                } else if (driver instanceof Renderer.Vulkan vulkanDriver
                        && options.getJava().getArchitecture() == Architecture.SYSTEM_ARCH) {
                    if (vulkanDriver.icdFile() != null) {
                        String absolutePath = FileUtils.getAbsolutePath(vulkanDriver.icdFile());
                        env.put("VK_ICD_FILENAMES", absolutePath);
                        env.put("VK_DRIVER_FILES", absolutePath);
                    }
                }
            } else if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS
                    && options.getJava().getArchitecture() == Architecture.SYSTEM_ARCH) {
                if (driver instanceof Renderer.Vulkan vulkanDriver
                        && vulkanDriver != Renderer.Vulkan.MOLTENVK
                        && vulkanDriver.icdFile() != null) {
                    String absolutePath = FileUtils.getAbsolutePath(vulkanDriver.icdFile());
                    env.put("VK_ICD_FILENAMES", absolutePath);
                    env.put("VK_DRIVER_FILES", absolutePath);
                }
            }
        }

        if (options.getRenderer() == Renderer.DEFAULT) {
            List<GraphicsCard> graphicsCards = SystemInfo.getGraphicsCards();
            if (graphicsCards != null && graphicsCards.size() == 2) {
                GraphicsCard card0 = graphicsCards.get(0);
                GraphicsCard card1 = graphicsCards.get(1);

                if (card0.getType() != null && card1.getType() != null
                        && card0.getType() != card1.getType()) {
                    GraphicsCard discreteGraphicsCard = card0.getType() == GraphicsCard.Type.Discrete ? card0 : card1;

                    if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX) {
                        if (HardwareVendor.NVIDIA.equals(discreteGraphicsCard.getVendor())) {
                            // https://askubuntu.com/a/1350825
                            env.put("__NV_PRIME_RENDER_OFFLOAD", "1");
                            env.put("__GLX_VENDOR_LIBRARY_NAME", "nvidia");
                            env.put("__VK_LAYER_NV_optimus", "NVIDIA_only");
                        }
                    }
                }
            }
        }

        for (ModLoaderType modLoader : instance.getModLoaders()) {
            if (modLoader.getEnvVarName() != null) {
                env.put(modLoader.getEnvVarName(), "1");
            }
        }

        if (instance.hasComponent(GameComponentType.OPTIFINE)) {
            env.put("INST_OPTIFINE", "1");
        }

        env.putAll(options.getEnvironmentVariables());

        return env;
    }

    /// Resolves the launch classpath using the configured native backend where supported.
    ///
    /// @return ordered library classpath entries
    /// @throws IOException if interrupted while contacting the native backend
    private Set<String> getClasspath() throws IOException {
        boolean processOptiFine = instance.hasComponent(OPTIFINE) && (instance.hasComponent(LITELOADER) || instance.hasComponent(FORGE));
        return resolveClasspath(instance.getId(), instance.getLayout(), manifest.getLibraries(), processOptiFine,
                GameComponentAnalyzer.MOD_LAUNCHER_MAIN.equals(manifest.mainClass()));
    }

    /// Builds the ordered library classpath with native support and Java compatibility fallback.
    ///
    /// @param instanceId selected instance identifier
    /// @param layout repository path layout
    /// @param libraries resolved library list
    /// @param processOptiFine whether OptiFine special handling applies
    /// @param modLauncher whether OptiFine is loaded by ModLauncher
    /// @return ordered deduplicated classpath
    /// @throws IOException if native planning is interrupted
    static Set<String> resolveClasspath(GameInstanceID instanceId, GameRepositoryLayout layout, List<Library> libraries,
                                        boolean processOptiFine, boolean modLauncher) throws IOException {
        @Nullable String executable = System.getProperty("mfcl.native.path");
        if (executable != null && !executable.isBlank()
                && Boolean.parseBoolean(System.getProperty("mfcl.native.classpath.enabled", "true"))) {
            try {
                return new LinkedHashSet<>(NativeBackend.planClasspath(Path.of(executable), layout, instanceId, libraries, processOptiFine, modLauncher));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Native classpath planning was interrupted");
            } catch (IOException | RuntimeException e) {
                LOG.warning("Native classpath planning failed; using Java path");
            }
        }
        @Nullable Path selectedOptiFineInstallerFile = null;
        Set<String> classpath = new LinkedHashSet<>();
        for (Library library : libraries) {
            if (library.appliesToCurrentEnvironment() && !library.isNative()) {
                if (processOptiFine) {
                    if (library.is("optifine", "OptiFine")) {
                        // Prefer the installer jar over the patch jar when both are present.
                        Library installer = new Library(
                                new Artifact("optifine", "OptiFine", library.version(), "installer"));
                        Path installerFile = layout.getLibraryFile(instanceId, installer);
                        if (Files.isRegularFile(installerFile)) {
                            selectedOptiFineInstallerFile = installerFile;
                            continue;
                        }
                    } else if (library.is("optifine", "launchwrapper-of")) {
                        // Drop OptiFine's private launchwrapper; Forge/LiteLoader supply their own.
                        continue;
                    }
                }

                Path libraryFile = layout.getLibraryFile(instanceId, library);
                if (Files.isRegularFile(libraryFile))
                    classpath.add(FileUtils.getAbsolutePath(libraryFile));
            }
        }

        // Re-append the installer last so OptiFine follows Forge when Forge has no patch entry.
        if (selectedOptiFineInstallerFile != null &&
                // With ModLauncher, OptiFine is discovered via HMCLTransformerDiscoveryService, not classpath.
                !modLauncher) {
            classpath.add(FileUtils.getAbsolutePath(selectedOptiFineInstallerFile));
        }
        return classpath;
    }

    @Override
    public void makeLaunchScript(Path scriptFile) throws IOException {
        boolean isWindows = OperatingSystem.WINDOWS == OperatingSystem.CURRENT_OS;

        Path nativeFolder = getNativeFolder();

        String scriptExtension = FileUtils.getExtension(scriptFile);
        boolean usePowerShell = "ps1".equals(scriptExtension);

        if (!usePowerShell) {
            if (isWindows && !scriptExtension.equalsIgnoreCase("bat"))
                throw new IllegalArgumentException("The extension of " + scriptFile + " is not 'bat' or 'ps1' in Windows");
            else if (!isWindows && !(scriptExtension.equalsIgnoreCase("sh") || scriptExtension.equalsIgnoreCase("command") || scriptExtension.equalsIgnoreCase("bash")))
                throw new IllegalArgumentException("The extension of " + scriptFile + " is not 'sh', 'bash', 'ps1' or 'command' in macOS/Linux");
        }

        final Command commandLine = generateCommandLine(nativeFolder);
        final String command = usePowerShell ? null : commandLine.commandLine.toString();
        Map<String, String> envVars = getEnvVars(nativeFolder);

        if (isWindows && !usePowerShell) {
            // https://stackoverflow.com/a/28452546
            // https://learn.microsoft.com/troubleshoot/windows-client/shell-experience/command-line-string-limitation
            if (command.length() > 32767) {
                throw new CommandTooLongException();
            }
        }

        if (isUsingLog4j())
            extractLog4jConfigurationFile();

        if (!options.isUseCustomNatives())
            decompressNatives(commandLine.javaNativeFolder);

        Files.createDirectories(scriptFile.getParent());

        try (OutputStream outputStream = Files.newOutputStream(scriptFile)) {
            Charset charset = StandardCharsets.UTF_8;

            if (isWindows) {
                if (usePowerShell) {
                    // Write UTF-8 BOM
                    try {
                        outputStream.write(0xEF);
                        outputStream.write(0xBB);
                        outputStream.write(0xBF);
                    } catch (IOException e) {
                        outputStream.close();
                        throw e;
                    }
                } else {
                    charset = OperatingSystem.NATIVE_CHARSET;
                }
            }

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, charset))) {
                if (usePowerShell) {
                    if (isWindows) {
                        Path appdata = options.getGameDir().toAbsolutePath().getParent();
                        if (appdata != null) {
                            writer.write("$Env:APPDATA=");
                            writer.write(CommandBuilder.pwshString(appdata.toString()));
                            writer.newLine();
                        }
                    }
                    for (Map.Entry<String, String> entry : envVars.entrySet()) {
                        writer.write("$Env:" + entry.getKey() + "=");
                        writer.write(CommandBuilder.pwshString(entry.getValue()));
                        writer.newLine();
                    }
                    writer.write("Set-Location -LiteralPath ");
                    writer.write(CommandBuilder.pwshString(FileUtils.getAbsolutePath(instance.getRunDirectory())));
                    writer.newLine();


                    if (StringUtils.isNotBlank(options.getPreLaunchCommand())) {
                        writer.write('&');
                        for (String rawCommand : StringUtils.tokenize(options.getPreLaunchCommand(), envVars)) {
                            writer.write(' ');
                            writer.write(CommandBuilder.pwshString(rawCommand));
                        }
                        writer.newLine();
                    }

                    writer.write('&');
                    for (String rawCommand : commandLine.commandLine.asList()) {
                        writer.write(' ');
                        writer.write(CommandBuilder.pwshString(rawCommand));
                    }
                    writer.newLine();

                    if (StringUtils.isNotBlank(options.getPostExitCommand())) {
                        writer.write('&');
                        for (String rawCommand : StringUtils.tokenize(options.getPostExitCommand(), envVars)) {
                            writer.write(' ');
                            writer.write(CommandBuilder.pwshString(rawCommand));
                        }
                        writer.newLine();
                    }
                } else {
                    if (isWindows) {
                        writer.write("@echo off");
                        writer.newLine();

                        Path appdata = options.getGameDir().toAbsolutePath().getParent();
                        if (appdata != null) {
                            writer.write("set APPDATA=" + appdata);
                            writer.newLine();
                        }

                        for (Map.Entry<String, String> entry : envVars.entrySet()) {
                            writer.write("set " + entry.getKey() + "=" + CommandBuilder.toBatchStringLiteral(entry.getValue()));
                            writer.newLine();
                        }
                        writer.newLine();
                        writer.write(new CommandBuilder().addAll("cd", "/D", FileUtils.getAbsolutePath(instance.getRunDirectory())).toString());
                    } else {
                        writer.write("#!/usr/bin/env bash");
                        writer.newLine();
                        for (Map.Entry<String, String> entry : envVars.entrySet()) {
                            writer.write("export " + entry.getKey() + "=" + CommandBuilder.toShellStringLiteral(entry.getValue()));
                            writer.newLine();
                        }
                        if (commandLine.tempNativeFolder != null) {
                            writer.write(new CommandBuilder().addAll("ln", "-s", FileUtils.getAbsolutePath(nativeFolder), commandLine.tempNativeFolder.toString()).toString());
                            writer.newLine();
                        }
                        writer.write(new CommandBuilder().addAll("cd", FileUtils.getAbsolutePath(instance.getRunDirectory())).toString());
                    }
                    writer.newLine();
                    if (StringUtils.isNotBlank(options.getPreLaunchCommand())) {
                        writer.write(new CommandBuilder().addAll(StringUtils.tokenize(options.getPreLaunchCommand(), envVars)).toString());
                        writer.newLine();
                    }
                    writer.write(command);
                    writer.newLine();

                    if (StringUtils.isNotBlank(options.getPostExitCommand())) {
                        writer.write(new CommandBuilder().addAll(StringUtils.tokenize(options.getPostExitCommand(), envVars)).toString());
                        writer.newLine();
                    }

                    if (isWindows) {
                        writer.write("pause");
                        writer.newLine();
                    }
                    if (commandLine.tempNativeFolder != null) {
                        writer.write(new CommandBuilder().addAll("rm", commandLine.tempNativeFolder.toString()).toString());
                        writer.newLine();
                    }
                }
            }
        }
        FileUtils.setExecutable(scriptFile);
        if (!Files.isExecutable(scriptFile))
            throw new PermissionException();

        if (usePowerShell && !CommandBuilder.hasExecutionPolicy())
            throw new ExecutionPolicyLimitException();
    }

    private void startMonitors(ManagedProcess managedProcess, Path nativeFolder, ProcessListener processListener, Charset encoding, boolean isDaemon) {
        processListener.setProcess(managedProcess);
        Thread stdout = Lang.thread(new StreamPump(managedProcess.getProcess().getInputStream(), it -> {
            processListener.onLog(it, false);
            managedProcess.addLine(it);
        }, encoding), "stdout-pump", isDaemon);
        managedProcess.addRelatedThread(stdout);
        Thread stderr = Lang.thread(new StreamPump(managedProcess.getProcess().getErrorStream(), it -> {
            processListener.onLog(it, true);
            managedProcess.addLine(it);
        }, encoding), "stderr-pump", isDaemon);
        managedProcess.addRelatedThread(stderr);
        managedProcess.addRelatedThread(Lang.thread(new ExitWaiter(managedProcess, Arrays.asList(stdout, stderr), (exitCode, exitType) -> {
            processListener.onExit(exitCode, exitType);

            if (StringUtils.isNotBlank(options.getPostExitCommand())) {
                try {
                    ProcessBuilder builder = new ProcessBuilder(StringUtils.tokenize(options.getPostExitCommand(), getEnvVars(nativeFolder))).directory(options.getGameDir().toFile());
                    builder.environment().putAll(getEnvVars(nativeFolder));
                    SystemUtils.callExternalProcess(builder);
                } catch (Throwable e) {
                    LOG.warning("An Exception happened while running exit command.", e);
                }
            }
        }), "exit-waiter", isDaemon));
    }

    private record Command(
            CommandBuilder commandLine,
            @Nullable Path tempNativeFolder,
            Path javaNativeFolder,
            Charset encoding) {
    }
}
