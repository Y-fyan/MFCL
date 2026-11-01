/*
 * MFCL
 * Copyright (C) 2026 MFCL contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.jackhuang.hmcl.download;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.game.LibrariesDownloadInfo;
import org.jackhuang.hmcl.game.Library;
import org.jackhuang.hmcl.game.LibraryDownloadInfo;
import org.jackhuang.hmcl.util.DigestUtils;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Reads Fabric-family library descriptors and retains published top-level SHA-1 metadata.
@NotNullByDefault
public final class LoaderLibraryParser {
    /// Prevents instantiation.
    private LoaderLibraryParser() { }

    /// Parses a library, using an official top-level SHA-1 only when no explicit download exists.
    /// @param element original library JSON
    /// @return parsed library, or null when the JSON value is null
    public static @Nullable Library parse(JsonElement element) {
        @Nullable Library library = JsonUtils.GSON.fromJson(element, Library.class);
        if (library == null || !element.isJsonObject() || library.downloads() != null) return library;
        JsonObject object = element.getAsJsonObject();
        @Nullable JsonElement hashValue = object.get("sha1");
        if (hashValue == null || !hashValue.isJsonPrimitive() || !hashValue.getAsJsonPrimitive().isString()) return library;
        String hash = hashValue.getAsString();
        if (!DigestUtils.isSha1Digest(hash)) return library;
        try {
            if (library.isNative()) return library;
            LibraryDownloadInfo existing = library.getDownload();
            int size = 0;
            @Nullable JsonElement sizeValue = object.get("size");
            if (sizeValue != null && sizeValue.isJsonPrimitive() && sizeValue.getAsJsonPrimitive().isNumber()) {
                try { size = sizeValue.getAsBigDecimal().intValueExact(); }
                catch (ArithmeticException ignored) { size = 0; }
                if (size < 0) size = 0;
            }
            var download = new LibraryDownloadInfo(existing.getPath(), existing.getUrl(), hash, size);
            return new Library(library.artifact(), library.url(), new LibrariesDownloadInfo(download),
                    library.checksums(), library.extract(), library.natives(), library.rules(), library.hint(), library.filename());
        } catch (RuntimeException ignored) {
            // Preserve the original parser's handling of unusual or malformed library descriptors.
            return library;
        }
    }
}
