package com.izzy2lost.psx2;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Loads and validates the public EmuCoreX texture catalog. */
public final class TexturePackCatalog {
    public static final String CATALOG_URL =
            "https://raw.githubusercontent.com/sashkinbro/EmuCoreX-Textures/main/textures.json";
    public static final String RELEASES_URL =
            "https://github.com/sashkinbro/EmuCoreX-Textures/releases";

    private static final String RELEASE_DOWNLOAD_PREFIX =
            "/sashkinbro/EmuCoreX-Textures/releases/download/";
    private static final Pattern SERIAL_PATTERN =
            Pattern.compile("^[A-Z]{4}-[0-9]{5}$");
    private static final Pattern HASH_PATTERN =
            Pattern.compile("^[0-9A-Fa-f]{64}$");
    private static final Pattern SAFE_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9._-]{1,180}$");
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;
    private static final long MAX_ARCHIVE_BYTES = 2L * 1024L * 1024L * 1024L;

    private TexturePackCatalog() {}

    public static final class Entry {
        public final String id;
        public final String name;
        public final String gameTitle;
        public final List<String> serials;
        public final String version;
        public final List<String> authors;
        public final String credits;
        public final String description;
        public final String downloadUrl;
        public final String sourceUrl;
        public final String license;
        public final long sizeBytes;
        public final String sha256;
        public final int fileCount;

        private Entry(
                String id,
                String name,
                String gameTitle,
                List<String> serials,
                String version,
                List<String> authors,
                String credits,
                String description,
                String downloadUrl,
                String sourceUrl,
                String license,
                long sizeBytes,
                String sha256,
                int fileCount) {
            this.id = id;
            this.name = name;
            this.gameTitle = gameTitle;
            this.serials = Collections.unmodifiableList(serials);
            this.version = version;
            this.authors = Collections.unmodifiableList(authors);
            this.credits = credits;
            this.description = description;
            this.downloadUrl = downloadUrl;
            this.sourceUrl = sourceUrl;
            this.license = license;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256.toUpperCase(Locale.ROOT);
            this.fileCount = fileCount;
        }
    }

    public static final class Result {
        public final List<Entry> entries;
        public final String generatedAt;
        public final boolean fromCache;

        private Result(List<Entry> entries, String generatedAt, boolean fromCache) {
            this.entries = Collections.unmodifiableList(entries);
            this.generatedAt = generatedAt;
            this.fromCache = fromCache;
        }
    }

    /**
     * Refreshes from GitHub and falls back to the last fully validated catalog
     * when offline. An invalid network response never replaces a good cache.
     */
    public static Result load(Context context) throws IOException {
        final File cacheFile = getCacheFile(context);
        IOException networkError;
        try {
            final byte[] response = downloadCatalog();
            final Result parsed = parse(response, false);
            try {
                commitCache(cacheFile, response);
            } catch (IOException ignored) {
                // A valid live catalog remains usable even if local cache storage
                // is temporarily unavailable.
            }
            return parsed;
        } catch (IOException error) {
            networkError = error;
        }

        if (cacheFile.isFile() && cacheFile.length() > 0
                && cacheFile.length() <= MAX_CATALOG_BYTES) {
            try {
                return parse(readBounded(cacheFile), true);
            } catch (IOException ignored) {
                // Return the more useful network failure below.
            }
        }
        throw networkError;
    }

    private static File getCacheFile(Context context) {
        return new File(new File(context.getFilesDir(), "texture-catalog"), "textures.json");
    }

    private static byte[] downloadCatalog() throws IOException {
        final HttpURLConnection connection =
                (HttpURLConnection) new URL(CATALOG_URL).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "PSX2-Android-TextureCatalog/1");

            final int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Texture catalog returned HTTP " + code);
            }
            final long declared = connection.getContentLengthLong();
            if (declared > MAX_CATALOG_BYTES) {
                throw new IOException("Texture catalog exceeds the size limit");
            }

            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream(
                         declared > 0 ? (int) declared : 64 * 1024)) {
                final byte[] buffer = new byte[16 * 1024];
                int count;
                int total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_CATALOG_BYTES) {
                        throw new IOException("Texture catalog exceeds the size limit");
                    }
                    output.write(buffer, 0, count);
                }
                if (total == 0 || (declared >= 0 && declared != total)) {
                    throw new IOException("Texture catalog download was incomplete");
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static Result parse(byte[] data, boolean fromCache) throws IOException {
        try {
            final JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            if (root.getInt("schemaVersion") != 1) {
                throw new IOException("Unsupported texture catalog schema");
            }
            final String generatedAt = requiredString(root, "generatedAt");
            final JSONArray items = root.getJSONArray("entries");
            final ArrayList<Entry> entries = new ArrayList<>(items.length());
            final Set<String> ids = new LinkedHashSet<>();

            for (int index = 0; index < items.length(); index++) {
                final JSONObject item = items.getJSONObject(index);
                final String id = requiredString(item, "id");
                if (!SAFE_ID_PATTERN.matcher(id).matches() || !ids.add(id)) {
                    throw new IOException("Invalid or duplicate texture pack id: " + id);
                }

                final ArrayList<String> serials =
                        requiredStringArray(item, "serials", SERIAL_PATTERN, true);
                final ArrayList<String> authors =
                        requiredStringArray(item, "authors", null, false);
                final String downloadUrl = requiredString(item, "downloadUrl");
                // The upstream catalog can retain links to original-author assets,
                // but this app intentionally offers only the mirrored downloads
                // published on the repository Releases page requested by PSX2.
                if (!isApprovedDownloadUrl(downloadUrl)) continue;
                final String sourceUrl = requiredHttpsUrl(item, "sourceUrl");
                final long sizeBytes = item.getLong("sizeBytes");
                final int fileCount = item.getInt("fileCount");
                final String hash = requiredString(item, "sha256");
                if (sizeBytes <= 0 || sizeBytes >= MAX_ARCHIVE_BYTES) {
                    throw new IOException("Invalid archive size for " + id);
                }
                if (fileCount <= 0 || fileCount > 50_000) {
                    throw new IOException("Invalid texture count for " + id);
                }
                if (!HASH_PATTERN.matcher(hash).matches()) {
                    throw new IOException("Invalid SHA-256 for " + id);
                }

                entries.add(new Entry(
                        id,
                        requiredString(item, "name"),
                        requiredString(item, "gameTitle"),
                        serials,
                        requiredString(item, "version"),
                        authors,
                        item.optString("credits", ""),
                        item.optString("description", ""),
                        downloadUrl,
                        sourceUrl,
                        item.optString("license", ""),
                        sizeBytes,
                        hash,
                        fileCount));
            }
            if (entries.isEmpty()) {
                throw new IOException("Texture catalog is empty");
            }
            return new Result(entries, generatedAt, fromCache);
        } catch (JSONException error) {
            throw new IOException("Texture catalog JSON is invalid", error);
        }
    }

    private static String requiredString(JSONObject object, String key)
            throws JSONException, IOException {
        final String value = object.getString(key).trim();
        if (value.isEmpty()) {
            throw new IOException("Missing catalog field: " + key);
        }
        return value;
    }

    private static String requiredHttpsUrl(JSONObject object, String key)
            throws JSONException, IOException {
        final String value = requiredString(object, key);
        try {
            final URL url = new URL(value);
            if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty()) {
                throw new IOException("Catalog URL must use HTTPS: " + key);
            }
            return value;
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid catalog URL: " + key, error);
        }
    }

    private static ArrayList<String> requiredStringArray(
            JSONObject object, String key, Pattern pattern, boolean uppercase)
            throws JSONException, IOException {
        final JSONArray array = object.getJSONArray(key);
        if (array.length() == 0) {
            throw new IOException("Catalog array is empty: " + key);
        }
        final LinkedHashSet<String> values = new LinkedHashSet<>();
        for (int index = 0; index < array.length(); index++) {
            String value = array.getString(index).trim();
            if (uppercase) value = value.toUpperCase(Locale.ROOT);
            if (value.isEmpty() || (pattern != null && !pattern.matcher(value).matches())) {
                throw new IOException("Invalid catalog value in " + key);
            }
            values.add(value);
        }
        return new ArrayList<>(values);
    }

    private static boolean isApprovedDownloadUrl(String value) throws IOException {
        try {
            final URL url = new URL(value);
            return "https".equalsIgnoreCase(url.getProtocol())
                    && "github.com".equalsIgnoreCase(url.getHost())
                    && url.getPath().startsWith(RELEASE_DOWNLOAD_PREFIX)
                    && url.getPath().toLowerCase(Locale.ROOT).endsWith(".zip");
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid texture download URL", error);
        }
    }

    private static byte[] readBounded(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())) {
            final byte[] buffer = new byte[16 * 1024];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_CATALOG_BYTES) {
                    throw new IOException("Cached texture catalog exceeds the size limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void commitCache(File target, byte[] data) throws IOException {
        final File directory = target.getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("Unable to create texture catalog cache");
        }
        final File staging = File.createTempFile("textures-", ".json", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(staging)) {
                output.write(data);
                output.flush();
                output.getFD().sync();
            }
            try {
                Files.move(staging.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(staging.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (staging.exists()) staging.delete();
        }
    }
}
