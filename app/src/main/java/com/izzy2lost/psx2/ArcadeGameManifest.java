package com.izzy2lost.psx2;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Lightweight reader for the fields the Android game library needs from an .acgame file. */
final class ArcadeGameManifest {
    static final class Metadata {
        String title;
        String subdir;
        String mediaSource;
    }

    private ArcadeGameManifest() {}

    static boolean isManifest(String nameOrUri) {
        return nameOrUri != null && nameOrUri.toLowerCase(Locale.ROOT).endsWith(".acgame");
    }

    static Metadata read(Context context, String manifestUri) {
        Metadata metadata = new Metadata();
        if (context == null || manifestUri == null) return metadata;

        try (InputStream input = context.getContentResolver().openInputStream(Uri.parse(manifestUri));
             BufferedReader reader = input == null ? null : new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
            if (reader == null) return metadata;

            String section = "";
            String line;
            int linesRead = 0;
            while ((line = reader.readLine()) != null && linesRead++ < 4096) {
                String trimmed = line.trim();
                if (trimmed.startsWith("\uFEFF")) trimmed = trimmed.substring(1).trim();
                if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("#")) continue;

                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    section = trimmed.substring(1, trimmed.length() - 1)
                            .trim().toLowerCase(Locale.ROOT);
                    continue;
                }

                int equals = trimmed.indexOf('=');
                if (equals <= 0) continue;
                String key = trimmed.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                String value = unquote(trimmed.substring(equals + 1).trim());

                if ("game".equals(section) && "name".equals(key)) {
                    metadata.title = value;
                } else if ("data".equals(section) && "subdir".equals(key)) {
                    metadata.subdir = value;
                } else if ("data".equals(section) && "mediasrc".equals(key)) {
                    metadata.mediaSource = value;
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("ArcadeGameManifest", "Unable to read " + manifestUri, t);
        }
        return metadata;
    }

    static String resolveMediaUriKey(String manifestUri, Metadata metadata) {
        if (metadata == null || isBlank(metadata.mediaSource)) return null;
        String relativePath = metadata.mediaSource;
        if (!isBlank(metadata.subdir)) {
            relativePath = metadata.subdir.replace('\\', '/') + "/" + relativePath;
        }

        // Mirror NativeApp.resolveArcadeAssetUri(), but only construct the document key.
        // The scanner already has the list of existing files, so opening every referenced
        // image here would add needless I/O and warnings for incomplete arcade sets.
        try {
            Uri manifest = Uri.parse(manifestUri);
            String targetId = DocumentsContract.getDocumentId(manifest);
            int slash = targetId.lastIndexOf('/');
            if (slash >= 0) targetId = targetId.substring(0, slash);

            for (String part : relativePath.replace('\\', '/').split("/")) {
                if (part.isEmpty() || ".".equals(part)) continue;
                if ("..".equals(part)) {
                    int parentSlash = targetId.lastIndexOf('/');
                    if (parentSlash >= 0) targetId = targetId.substring(0, parentSlash);
                } else {
                    targetId += "/" + part;
                }
            }
            return manifest.getAuthority() + "\n" + targetId;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String uriKey(String uriString) {
        if (uriString == null) return "";
        try {
            Uri uri = Uri.parse(uriString);
            String documentId = DocumentsContract.getDocumentId(uri);
            if (documentId != null) return uri.getAuthority() + "\n" + documentId;
        } catch (Throwable ignored) {}
        return uriString;
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }
}
