/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.nativebridge;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/// Narrow, read-only content-store RPC surface, separate from the legacy backend facade.
@NotNullByDefault
public final class NativeCatalogRpc {
    /// Only these non-mutating operations may enter the shared pure worker pool.
    private static final @Unmodifiable Set<String> METHODS = Set.of("plan_modrinth_query", "plan_curse_query",
            "project_modrinth_versions", "select_modrinth_categories", "select_addon_versions", "curse_fingerprint");

    /// Prevents instantiation.
    private NativeCatalogRpc() { }

    /// Uses capability-checked workers or the existing cancellable one-shot transport.
    /// Unknown operations are rejected locally; callers own metadata compatibility fallback.
    public static JsonObject request(Path executable, String method, JsonObject params) throws IOException, InterruptedException {
        if (!METHODS.contains(method)) throw new IOException("Unsupported catalog operation");
        @Nullable JsonObject pooled = NativePureRpc.tryAddonCatalog(executable, method, params, () -> Thread.currentThread().isInterrupted());
        return pooled != null ? pooled : NativeBackend.request(executable, method, params,
                "curse_fingerprint".equals(method) ? 180 : 30, () -> Thread.currentThread().isInterrupted());
    }
}
