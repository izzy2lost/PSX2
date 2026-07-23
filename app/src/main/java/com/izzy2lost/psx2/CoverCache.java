package com.izzy2lost.psx2;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a validated index of downloaded covers. A single directory scan is much
 * cheaper than asking a DocumentProvider for every game individually, and checking
 * both storage locations preserves covers downloaded before a SAF root was selected.
 */
final class CoverCache {
    private static final String TAG = "CoverCache";
    private static final long MAX_CACHED_COVER_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_IMAGE_DIMENSION = 4096;
    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private CoverCache() {}

    /**
     * Returns one validated local path per requested serial. SAF covers take
     * precedence, followed by external app storage and then internal app storage.
     */
    static Map<String, String> findValidCoverPaths(
            Context context, Collection<String> requestedSerials) {
        final Set<String> requested = new LinkedHashSet<>();
        if (requestedSerials != null) {
            for (String serial : requestedSerials) {
                final String normalized = normalizeSerial(serial);
                if (!normalized.isEmpty()) requested.add(normalized);
            }
        }

        final Map<String, String> validPaths = new LinkedHashMap<>();
        if (requested.isEmpty()) return validPaths;

        final DocumentFile coversDirectory = SafManager.getOrCreateDir(context, "covers");
        if (coversDirectory != null) {
            try {
                for (DocumentFile candidate : coversDirectory.listFiles()) {
                    final String serial = serialFromCoverName(candidate.getName());
                    if (serial.isEmpty() || !requested.contains(serial)
                            || validPaths.containsKey(serial)) {
                        continue;
                    }
                    if (isValidPng(context, candidate)) {
                        validPaths.put(serial, candidate.getUri().toString());
                    }
                }
            } catch (SecurityException | IllegalStateException error) {
                Log.w(TAG, "Unable to index SAF covers", error);
            }
        }

        indexFileDirectory(context.getExternalFilesDir("covers"), requested, validPaths);
        indexFileDirectory(new File(context.getFilesDir(), "covers"), requested, validPaths);
        return validPaths;
    }

    private static void indexFileDirectory(File directory, Set<String> requested,
                                           Map<String, String> validPaths) {
        if (directory == null || !directory.isDirectory()
                || validPaths.size() == requested.size()) {
            return;
        }
        final File[] candidates = directory.listFiles();
        if (candidates == null) return;

        for (File candidate : candidates) {
            final String serial = serialFromCoverName(candidate.getName());
            if (serial.isEmpty() || !requested.contains(serial)
                    || validPaths.containsKey(serial)) {
                continue;
            }
            if (isValidPng(candidate)) {
                validPaths.put(serial, candidate.getAbsolutePath());
            }
        }
    }

    static boolean isValidPng(File file) {
        if (file == null || !file.isFile() || file.length() <= PNG_SIGNATURE.length
                || file.length() > MAX_CACHED_COVER_BYTES) {
            return false;
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            if (!hasPngSignature(input)) return false;
        } catch (IOException ignored) {
            return false;
        }

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return hasSafeDimensions(options);
    }

    static boolean isValidPng(Context context, Uri uri) {
        if (uri == null) return false;
        try (InputStream input = new BufferedInputStream(
                context.getContentResolver().openInputStream(uri))) {
            if (input == null || !hasPngSignature(input)) return false;
        } catch (IOException | SecurityException ignored) {
            return false;
        }

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) return false;
            BitmapFactory.decodeStream(input, null, options);
            return hasSafeDimensions(options);
        } catch (IOException | SecurityException ignored) {
            return false;
        }
    }

    private static boolean isValidPng(Context context, DocumentFile file) {
        try {
            final long length = file.length();
            return length > PNG_SIGNATURE.length && length <= MAX_CACHED_COVER_BYTES
                    && isValidPng(context, file.getUri());
        } catch (SecurityException | IllegalStateException ignored) {
            return false;
        }
    }

    private static boolean hasPngSignature(InputStream input) throws IOException {
        final byte[] signature = new byte[PNG_SIGNATURE.length];
        int offset = 0;
        while (offset < signature.length) {
            final int count = input.read(signature, offset, signature.length - offset);
            if (count == -1) return false;
            offset += count;
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (signature[index] != PNG_SIGNATURE[index]) return false;
        }
        return true;
    }

    private static boolean hasSafeDimensions(BitmapFactory.Options options) {
        return options.outWidth > 0 && options.outHeight > 0
                && options.outWidth <= MAX_IMAGE_DIMENSION
                && options.outHeight <= MAX_IMAGE_DIMENSION;
    }

    private static String serialFromCoverName(String name) {
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".png")) return "";
        final String base = name.substring(0, name.length() - 4);
        final String normalized = normalizeSerial(base);
        return normalized.equals(base.toUpperCase(Locale.ROOT)) ? normalized : "";
    }

    private static String normalizeSerial(String serial) {
        if (serial == null) return "";
        final String normalized = serial.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9-]", "");
        return normalized.length() <= 16 ? normalized : "";
    }
}
