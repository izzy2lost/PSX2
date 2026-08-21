package com.izzy2lost.psx2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.StatFs;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Downloads, verifies, and installs one release-backed texture pack. */
public final class TexturePackDownloadWorker extends Worker {
    public static final String TAG_ALL = "ps2-texture-pack-download";
    public static final String TAG_SERIAL_PREFIX = "ps2-texture-pack-serial:";
    public static final String TAG_PACK_PREFIX = "ps2-texture-pack-id:";

    public static final String KEY_PACK_ID = "pack_id";
    public static final String KEY_PACK_NAME = "pack_name";
    public static final String KEY_SERIAL = "serial";
    public static final String KEY_DOWNLOAD_URL = "download_url";
    public static final String KEY_SHA256 = "sha256";
    public static final String KEY_PART_URLS = "part_urls";
    public static final String KEY_PART_SIZES = "part_sizes";
    public static final String KEY_PART_HASHES = "part_hashes";
    public static final String KEY_SIZE_BYTES = "size_bytes";
    public static final String KEY_FILE_COUNT = "file_count";
    public static final String KEY_PHASE = "phase";
    public static final String KEY_BYTES_DONE = "bytes_done";
    public static final String KEY_BYTES_TOTAL = "bytes_total";
    public static final String KEY_FILES_DONE = "files_done";
    public static final String KEY_FILES_TOTAL = "files_total";
    public static final String KEY_ERROR = "error";

    public static final String PHASE_QUEUED = "queued";
    public static final String PHASE_DOWNLOADING = "downloading";
    public static final String PHASE_VERIFYING = "verifying";
    public static final String PHASE_INSTALLING = "installing";
    public static final String PHASE_COMPLETE = "complete";

    public static final String PREF_INSTALLED_ID_PREFIX = "texture_pack_installed_id:";
    public static final String PREF_INSTALLED_NAME_PREFIX = "texture_pack_installed_name:";
    public static final String PREF_INSTALLED_HASH_PREFIX = "texture_pack_installed_hash:";

    private static final String LOG_TAG = "TexturePackWorker";
    private static final String NOTIFICATION_CHANNEL = "texture_pack_downloads";
    private static final Pattern SERIAL_PATTERN =
            Pattern.compile("^[A-Z]{4}-[0-9]{5}$");
    private static final Pattern ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9._-]{1,256}$");
    private static final Pattern HASH_PATTERN =
            Pattern.compile("^[0-9A-Fa-f]{64}$");
    private static final Pattern PART_PATH_PATTERN =
            Pattern.compile("^.+\\.zip\\.part[0-9]{3,6}$");
    private static final int MAX_PARTIAL_ID_CHARS = 120;
    private static final long MAX_ASSET_BYTES = TexturePackCatalog.MAX_ASSET_BYTES;
    private static final long MAX_ARCHIVE_BYTES = TexturePackCatalog.MAX_ARCHIVE_BYTES;
    private static final long DOWNLOAD_SPACE_MARGIN = 64L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int BUFFER_BYTES = 1024 * 1024;
    private static final int MAX_REDIRECTS = 8;
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private long lastProgressAt;
    private int lastProgressPercent = -1;
    private String packName;
    private long expectedBytes;

    public TexturePackDownloadWorker(
            @NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    public static String uniqueWorkName(String serial) {
        return TAG_ALL + ":" + serial;
    }

    @NonNull
    @Override
    public Result doWork() {
        final Data input = getInputData();
        final String packId = trimmed(input.getString(KEY_PACK_ID));
        packName = trimmed(input.getString(KEY_PACK_NAME));
        final String serial = trimmed(input.getString(KEY_SERIAL)).toUpperCase(Locale.ROOT);
        final String downloadUrl = trimmed(input.getString(KEY_DOWNLOAD_URL));
        final String expectedHash =
                trimmed(input.getString(KEY_SHA256)).toUpperCase(Locale.ROOT);
        expectedBytes = input.getLong(KEY_SIZE_BYTES, -1);
        final int expectedFiles = input.getInt(KEY_FILE_COUNT, -1);

        try {
            final List<PartSpec> parts = partsFrom(input, downloadUrl, expectedHash, expectedBytes);
            validateInput(packId, packName, serial, parts,
                    expectedHash, expectedBytes, expectedFiles);
            setForegroundAsync(foregroundInfo(
                    "Preparing " + packName, 0, false)).get();

            final File archive = download(packId, parts, expectedHash);
            try {
                publishProgress(PHASE_VERIFYING, expectedBytes, expectedBytes, 0, expectedFiles,
                        "Verified download; checking texture archive", true);
                try {
                    TexturePackInstaller.install(
                            getApplicationContext(),
                            archive,
                            serial,
                            packId,
                            packName,
                            expectedHash,
                            expectedFiles,
                            new TexturePackInstaller.ProgressListener() {
                                @Override
                                public void onProgress(
                                        int installedFiles,
                                        int totalFiles,
                                        long installedBytes,
                                        long totalBytes) {
                                    publishProgress(PHASE_INSTALLING,
                                            installedBytes, totalBytes,
                                            installedFiles, totalFiles,
                                            "Installing textures " + installedFiles
                                                    + " of " + totalFiles,
                                            false);
                                }

                                @Override
                                public boolean isCancelled() {
                                    return isStopped();
                                }
                            });
                } catch (IOException error) {
                    // A verified archive that the provider cannot install will
                    // not improve through automatic network retries.
                    throw new PermanentFailure(error.getMessage(), error);
                }
            } finally {
                if (archive.exists() && !archive.delete()) archive.deleteOnExit();
            }

            final SharedPreferences prefs = getApplicationContext()
                    .getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(PREF_INSTALLED_ID_PREFIX + serial, packId)
                    .putString(PREF_INSTALLED_NAME_PREFIX + serial, packName)
                    .putString(PREF_INSTALLED_HASH_PREFIX + serial, expectedHash)
                    .putBoolean("load_textures", true)
                    .putBoolean("async_texture_loading", true)
                    .apply();
            if (NativeApp.getContext() != null && !NativeApp.hasNoNativeBinary) {
                NativeApp.setLoadTexturesAsync(true);
                NativeApp.setAsyncTextureLoadingAsync(true);
                NativeApp.reloadTextureReplacementsAsync();
            }

            publishProgress(PHASE_COMPLETE, expectedBytes, expectedBytes,
                    expectedFiles, expectedFiles, "Texture pack installed", true);
            return Result.success(new Data.Builder()
                    .putString(KEY_PACK_ID, packId)
                    .putString(KEY_PACK_NAME, packName)
                    .putString(KEY_SERIAL, serial)
                    .putString(KEY_PHASE, PHASE_COMPLETE)
                    .build());
        } catch (PermanentFailure error) {
            Log.e(LOG_TAG, "Texture pack rejected: " + error.getMessage(), error);
            deletePartial(packId, expectedHash);
            return Result.failure(errorData(error));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Result.failure(errorData(new IOException("Texture download was interrupted")));
        } catch (IOException error) {
            Log.w(LOG_TAG, "Texture pack download/install failed: " + error.getMessage(), error);
            if (!isStopped() && getRunAttemptCount() < MAX_RETRY_ATTEMPTS) {
                return Result.retry();
            }
            return Result.failure(errorData(error));
        } catch (Throwable error) {
            Log.e(LOG_TAG, "Unexpected texture pack failure", error);
            return Result.failure(errorData(error));
        }
    }

    /** One release asset: a whole archive, or one ordered slice of a split one. */
    private static final class PartSpec {
        final String downloadUrl;
        final long sizeBytes;
        final String sha256;

        PartSpec(String downloadUrl, long sizeBytes, String sha256) {
            this.downloadUrl = downloadUrl;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }
    }

    /** A live response for one part, plus the offset within the part it starts at. */
    private static final class PartStream {
        final HttpURLConnection connection;
        final long start;

        PartStream(HttpURLConnection connection, long start) {
            this.connection = connection;
            this.start = start;
        }
    }

    private List<PartSpec> partsFrom(
            Data input, String downloadUrl, String expectedHash, long size)
            throws PermanentFailure {
        final String[] urls = input.getStringArray(KEY_PART_URLS);
        final long[] sizes = input.getLongArray(KEY_PART_SIZES);
        final String[] hashes = input.getStringArray(KEY_PART_HASHES);
        if (urls == null || sizes == null || hashes == null || urls.length == 0) {
            return Collections.singletonList(new PartSpec(downloadUrl, size, expectedHash));
        }
        if (urls.length < 2 || urls.length > TexturePackCatalog.MAX_PARTS
                || sizes.length != urls.length || hashes.length != urls.length) {
            throw new PermanentFailure("Invalid split texture pack request");
        }
        final ArrayList<PartSpec> parts = new ArrayList<>(urls.length);
        long total = 0;
        for (int index = 0; index < urls.length; index++) {
            final String url = trimmed(urls[index]);
            final String hash = trimmed(hashes[index]).toUpperCase(Locale.ROOT);
            if (url.isEmpty() || sizes[index] <= 0 || sizes[index] >= MAX_ASSET_BYTES
                    || !HASH_PATTERN.matcher(hash).matches()) {
                throw new PermanentFailure("Invalid split texture pack request");
            }
            total += sizes[index];
            parts.add(new PartSpec(url, sizes[index], hash));
        }
        if (total != size || !downloadUrl.equals(parts.get(0).downloadUrl)) {
            throw new PermanentFailure("Split texture pack parts do not match the catalog");
        }
        return parts;
    }

    /**
     * Downloads every part in order into one archive file, verifying each part
     * against its own SHA-256 and the reassembled archive against the catalog
     * hash. Interrupted downloads resume at the exact byte they stopped on.
     */
    private File download(String packId, List<PartSpec> parts, String expectedHash)
            throws IOException {
        final File directory = downloadDirectory();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Unable to create texture download cache");
        }
        final File partial = partialFile(directory, packId, expectedHash);
        long completed = partial.isFile() ? partial.length() : 0;
        if (completed < 0 || completed > expectedBytes) {
            if (partial.exists() && !partial.delete()) {
                throw new IOException("Unable to restart the texture download");
            }
            completed = 0;
        }
        if (completed == expectedBytes && completed > 0) {
            // A previous run already fetched everything; re-verify instead of refetching.
            final MessageDigest digest = sha256Digest();
            primeDigests(partial, completed, completed, digest, null);
            if (toHex(digest.digest()).equalsIgnoreCase(expectedHash)) return partial;
            if (!partial.delete()) {
                throw new IOException("Unable to restart the texture download");
            }
            completed = 0;
        }
        ensureDownloadSpace(directory, expectedBytes - completed);

        // Locate the part that owns the resume offset.
        int startIndex = 0;
        long partStart = 0;
        while (startIndex < parts.size()
                && partStart + parts.get(startIndex).sizeBytes <= completed) {
            partStart += parts.get(startIndex).sizeBytes;
            startIndex++;
        }

        MessageDigest whole = sha256Digest();
        long downloaded = completed;
        publishProgress(PHASE_DOWNLOADING, downloaded, expectedBytes, 0, 0,
                progressText(downloaded, expectedBytes, startIndex, parts.size()), true);

        for (int index = startIndex; index < parts.size(); index++) {
            final PartSpec spec = parts.get(index);
            long already = index == startIndex ? completed - partStart : 0;
            HttpURLConnection connection = null;
            try {
                final PartStream stream = openPart(spec, already);
                connection = stream.connection;
                if (stream.start != already) {
                    // The server ignored Range, so this part restarts from zero.
                    already = stream.start;
                    truncateTo(partial, partStart + already);
                    downloaded = partStart + already;
                }

                final MessageDigest part = sha256Digest();
                if (index == startIndex) {
                    // Rebuild the digests over everything already on disk. Later
                    // parts continue the running archive digest instead.
                    whole = sha256Digest();
                    primeDigests(partial, partStart + already, partStart, whole, part);
                }

                long doneInPart = already;
                try (InputStream input = new BufferedInputStream(
                             connection.getInputStream(), BUFFER_BYTES);
                     FileOutputStream fileOutput = new FileOutputStream(partial, true);
                     OutputStream output = new BufferedOutputStream(fileOutput, BUFFER_BYTES)) {
                    final byte[] buffer = new byte[BUFFER_BYTES];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (isStopped()) throw new IOException("Texture download was cancelled");
                        doneInPart += count;
                        downloaded += count;
                        if (doneInPart > spec.sizeBytes || downloaded > expectedBytes) {
                            throw new PermanentFailure("Release asset exceeds the catalog size");
                        }
                        whole.update(buffer, 0, count);
                        part.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                        publishProgress(PHASE_DOWNLOADING, downloaded, expectedBytes, 0, 0,
                                progressText(downloaded, expectedBytes, index, parts.size()),
                                false);
                    }
                    output.flush();
                    fileOutput.getFD().sync();
                }

                if (doneInPart != spec.sizeBytes) {
                    throw new IOException("Texture download was incomplete");
                }
                if (!toHex(part.digest()).equalsIgnoreCase(spec.sha256)) {
                    throw new PermanentFailure(parts.size() > 1
                            ? "Texture pack part " + (index + 1)
                                    + " failed SHA-256 verification"
                            : "Texture download failed SHA-256 verification");
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
            partStart += spec.sizeBytes;
        }

        if (downloaded != expectedBytes) {
            throw new IOException("Texture download was incomplete");
        }
        if (!toHex(whole.digest()).equalsIgnoreCase(expectedHash)) {
            throw new PermanentFailure("Texture download failed SHA-256 verification");
        }
        return partial;
    }

    /**
     * Opens one part, following GitHub's redirects to its asset host. The
     * returned offset is where the response body starts inside the part: it is
     * zero when the server declined to resume.
     */
    private PartStream openPart(PartSpec spec, long resumeAt) throws IOException {
        HttpURLConnection connection = null;
        boolean release = true;
        try {
            URL current = new URL(spec.downloadUrl);
            long start = resumeAt;
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                validateDownloadLocation(current, redirects == 0);
                connection = (HttpURLConnection) current.openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/octet-stream");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", "PSX2-Android-TextureDownloader/1");
                if (start > 0) {
                    connection.setRequestProperty("Range", "bytes=" + start + "-");
                }

                final int code = connection.getResponseCode();
                if (isRedirect(code)) {
                    final String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new PermanentFailure("GitHub returned an invalid redirect");
                    }
                    current = new URL(current, location);
                    connection.disconnect();
                    connection = null;
                    continue;
                }

                if (start > 0 && code == HttpURLConnection.HTTP_OK) {
                    // The server ignored Range. Restart this part rather than appending.
                    start = 0;
                } else if (start > 0 && code == HttpURLConnection.HTTP_PARTIAL) {
                    final String range = connection.getHeaderField("Content-Range");
                    if (range == null || !range.startsWith("bytes " + start + "-")) {
                        throw new PermanentFailure("GitHub returned an invalid partial response");
                    }
                } else if (code != HttpURLConnection.HTTP_OK) {
                    if (code == 408 || code == 429 || code >= 500) {
                        throw new IOException("GitHub returned HTTP " + code);
                    }
                    throw new PermanentFailure("GitHub returned HTTP " + code);
                }

                final long declared = connection.getContentLengthLong();
                final long expectedResponse = spec.sizeBytes - start;
                if (declared > expectedResponse
                        || (declared >= 0 && declared != expectedResponse)) {
                    throw new PermanentFailure("Release asset size does not match the catalog");
                }
                release = false;
                return new PartStream(connection, start);
            }
            throw new PermanentFailure("Too many GitHub download redirects");
        } finally {
            if (release && connection != null) connection.disconnect();
        }
    }

    private void publishProgress(
            String phase,
            long bytesDone,
            long bytesTotal,
            int filesDone,
            int filesTotal,
            String notificationText,
            boolean force) {
        final long now = System.currentTimeMillis();
        final int percent = bytesTotal > 0
                ? (int) Math.min(100, (bytesDone * 100L) / bytesTotal) : 0;
        if (!force && percent == lastProgressPercent && now - lastProgressAt < 1000) return;
        lastProgressPercent = percent;
        lastProgressAt = now;

        setProgressAsync(new Data.Builder()
                .putString(KEY_PHASE, phase)
                .putLong(KEY_BYTES_DONE, bytesDone)
                .putLong(KEY_BYTES_TOTAL, bytesTotal)
                .putInt(KEY_FILES_DONE, filesDone)
                .putInt(KEY_FILES_TOTAL, filesTotal)
                .build());
        setForegroundAsync(foregroundInfo(notificationText, percent, bytesTotal <= 0));
    }

    private ForegroundInfo foregroundInfo(String text, int progress, boolean indeterminate) {
        createNotificationChannel();
        final Intent launch = new Intent(getApplicationContext(), MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        final PendingIntent pendingIntent = PendingIntent.getActivity(
                getApplicationContext(),
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        final Notification notification = new NotificationCompat.Builder(
                getApplicationContext(), NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.download_24px)
                .setContentTitle(packName == null || packName.isBlank()
                        ? "Texture pack" : packName)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, Math.max(0, Math.min(100, progress)), indeterminate)
                .build();
        final int notificationId = 0x54580000 | (getId().hashCode() & 0xffff);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(notificationId, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(notificationId, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        final NotificationManager manager =
                getApplicationContext().getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(NOTIFICATION_CHANNEL) != null) {
            return;
        }
        final NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Texture pack downloads",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progress for texture packs downloaded from GitHub Releases");
        manager.createNotificationChannel(channel);
    }

    private void validateInput(
            String packId,
            String name,
            String serial,
            List<PartSpec> parts,
            String hash,
            long size,
            int files) throws PermanentFailure {
        final long sizeLimit = parts.size() > 1 ? MAX_ARCHIVE_BYTES : MAX_ASSET_BYTES;
        if (!ID_PATTERN.matcher(packId).matches() || name.isEmpty()
                || !SERIAL_PATTERN.matcher(serial).matches()
                || !HASH_PATTERN.matcher(hash).matches()
                || size <= 0 || size >= sizeLimit
                || files <= 0 || files > 50_000) {
            throw new PermanentFailure("Invalid texture pack request");
        }
        for (PartSpec part : parts) {
            try {
                validateDownloadLocation(new URL(part.downloadUrl), true);
            } catch (IOException error) {
                throw new PermanentFailure("Invalid texture release URL", error);
            }
        }
    }

    private static void validateDownloadLocation(URL url, boolean initial)
            throws PermanentFailure {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new PermanentFailure("Texture downloads must use HTTPS");
        }
        final String host = url.getHost().toLowerCase(Locale.ROOT);
        if (initial) {
            final String path = url.getPath().toLowerCase(Locale.ROOT);
            if (!"github.com".equals(host)
                    || !isApprovedReleasePath(url.getPath())
                    || !(path.endsWith(".zip") || PART_PATH_PATTERN.matcher(path).matches())) {
                throw new PermanentFailure(
                        "Texture downloads must use EmuCoreX-Textures GitHub Releases");
            }
        } else if (!"github.com".equals(host)
                && !"objects.githubusercontent.com".equals(host)
                && !host.endsWith(".githubusercontent.com")) {
            throw new PermanentFailure("GitHub redirected to an unexpected host");
        }
    }

    private static boolean isApprovedReleasePath(String path) {
        for (String prefix : TexturePackCatalog.RELEASE_DOWNLOAD_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    private File downloadDirectory() {
        File root = getApplicationContext().getExternalCacheDir();
        if (root == null) root = getApplicationContext().getCacheDir();
        return new File(root, "texture-pack-downloads");
    }

    private File partialFile(File directory, String packId, String hash) {
        // Catalog ids can be long enough to overrun the filesystem name limit, so
        // the archive hash keeps the shortened name unique.
        final String shortId = packId.length() > MAX_PARTIAL_ID_CHARS
                ? packId.substring(packId.length() - MAX_PARTIAL_ID_CHARS) : packId;
        return new File(directory, shortId + "-" + hash.substring(0, 12) + ".part");
    }

    private void deletePartial(String packId, String hash) {
        if (!ID_PATTERN.matcher(packId).matches() || !HASH_PATTERN.matcher(hash).matches()) return;
        final File partial = partialFile(downloadDirectory(), packId, hash);
        if (partial.exists() && !partial.delete()) partial.deleteOnExit();
    }

    private static void ensureDownloadSpace(File directory, long remaining) throws IOException {
        if (remaining <= 0) return;
        final StatFs stat = new StatFs(directory.getAbsolutePath());
        if (stat.getAvailableBytes() < remaining + DOWNLOAD_SPACE_MARGIN) {
            throw new PermanentFailure("Not enough free space for this texture pack download");
        }
    }

    private static MessageDigest sha256Digest() throws PermanentFailure {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new PermanentFailure("SHA-256 is unavailable", error);
        }
    }

    /**
     * Feeds the first {@code limit} bytes already on disk into the archive
     * digest, and the bytes from {@code partStart} onwards into the current
     * part digest, so a resumed download verifies exactly like a fresh one.
     */
    private static void primeDigests(
            File file, long limit, long partStart, MessageDigest whole, MessageDigest part)
            throws IOException {
        if (limit <= 0) return;
        try (InputStream input = new BufferedInputStream(
                new FileInputStream(file), BUFFER_BYTES)) {
            final byte[] buffer = new byte[BUFFER_BYTES];
            long position = 0;
            while (position < limit) {
                final int count = input.read(
                        buffer, 0, (int) Math.min(buffer.length, limit - position));
                if (count == -1) {
                    throw new IOException("Texture download resume state is invalid");
                }
                whole.update(buffer, 0, count);
                if (part != null && position + count > partStart) {
                    final int from = (int) Math.max(0, partStart - position);
                    part.update(buffer, from, count - from);
                }
                position += count;
            }
        }
    }

    private static void truncateTo(File file, long length) throws IOException {
        if (!file.isFile() || file.length() <= length) return;
        try (RandomAccessFile handle = new RandomAccessFile(file, "rw")) {
            handle.setLength(length);
        }
    }

    private Data errorData(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = "Texture pack installation failed";
        if (message.length() > 500) message = message.substring(0, 500);
        return new Data.Builder()
                .putString(KEY_PACK_NAME, packName)
                .putString(KEY_ERROR, message)
                .build();
    }

    private static String progressText(long done, long total, int partIndex, int partCount) {
        final String text = "Downloading " + formatBytes(done) + " of " + formatBytes(total);
        return partCount > 1
                ? text + " (part " + Math.min(partIndex + 1, partCount) + " of " + partCount + ")"
                : text;
    }

    public static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.2f GiB",
                    bytes / (1024d * 1024d * 1024d));
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024d * 1024d));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024d);
        }
        return bytes + " B";
    }

    private static String toHex(byte[] bytes) {
        final StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(Locale.ROOT, "%02X", value));
        return output.toString();
    }

    private static boolean isRedirect(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307
                || code == 308;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class PermanentFailure extends IOException {
        PermanentFailure(String message) {
            super(message);
        }

        PermanentFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
