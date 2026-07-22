package com.izzy2lost.psx2;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.Data;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reliably downloads a bounded batch of covers. Downloads are validated in app
 * cache before the existing cover is touched, then committed through a staging
 * file/document so an interrupted request cannot be mistaken for a valid cover.
 */
public final class CoverDownloadWorker extends Worker {
    public static final String UNIQUE_WORK_NAME = "ps2-cover-download";
    public static final String RUN_TAG_PREFIX = "ps2-cover-download-run:";
    public static final String KEY_SERIALS = "serials";
    public static final String KEY_RUN_TOKEN = "run_token";
    public static final String KEY_READY = "ready";
    public static final String KEY_DOWNLOADED = "downloaded";
    public static final String KEY_FAILED = "failed";
    public static final String KEY_FAILED_SERIALS = "failed_serials";
    public static final String KEY_PROCESSED = "processed";
    public static final String KEY_TOTAL = "total";

    private static final String TAG = "CoverDownloadWorker";
    private static final String COVER_BASE_URL =
            "https://raw.githubusercontent.com/izzy2lost/ps2-covers/main/covers/3d/";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final long MAX_DOWNLOAD_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_IMAGE_DIMENSION = 4096;
    private static final int MAX_RETRY_ATTEMPTS = 2;
    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    public CoverDownloadWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        final String[] serials = getInputData().getStringArray(KEY_SERIALS);
        final String runToken = getInputData().getString(KEY_RUN_TOKEN);
        if (serials == null || serials.length == 0 || runToken == null || runToken.isEmpty()) {
            return Result.failure(resultData(runToken, 0, 0, 0, List.of()));
        }

        final Context context = getApplicationContext();
        int ready = 0;
        int downloaded = 0;
        boolean sawTransientFailure = false;
        final List<String> failedSerials = new ArrayList<>();

        for (int index = 0; index < serials.length; index++) {
            if (isStopped()) {
                return Result.failure(resultData(runToken, ready, downloaded,
                        serials.length - ready, failedSerials));
            }

            final String serial = sanitizeSerial(serials[index]);
            setProgressAsync(new Data.Builder()
                    .putString(KEY_RUN_TOKEN, runToken)
                    .putInt(KEY_PROCESSED, index)
                    .putInt(KEY_TOTAL, serials.length)
                    .putInt(KEY_READY, ready)
                    .build());

            if (serial.isEmpty()) {
                failedSerials.add("invalid-serial");
                continue;
            }

            try {
                if (hasValidExistingCover(context, serial)) {
                    ready++;
                    continue;
                }

                final DownloadStatus status = downloadAndCommit(context, serial);
                if (status == DownloadStatus.SUCCESS) {
                    ready++;
                    downloaded++;
                } else {
                    failedSerials.add(serial);
                    sawTransientFailure |= status == DownloadStatus.TRANSIENT_FAILURE;
                }
            } catch (Throwable error) {
                Log.e(TAG, "Cover download failed for " + serial, error);
                failedSerials.add(serial);
                sawTransientFailure = true;
            }
        }

        setProgressAsync(new Data.Builder()
                .putString(KEY_RUN_TOKEN, runToken)
                .putInt(KEY_PROCESSED, serials.length)
                .putInt(KEY_TOTAL, serials.length)
                .putInt(KEY_READY, ready)
                .build());

        if (sawTransientFailure && getRunAttemptCount() < MAX_RETRY_ATTEMPTS) {
            Log.w(TAG, "Retrying cover batch after transient failure; attempt="
                    + getRunAttemptCount());
            return Result.retry();
        }

        return Result.success(resultData(runToken, ready, downloaded,
                failedSerials.size(), failedSerials));
    }

    private DownloadStatus downloadAndCommit(Context context, String serial) {
        final File tempDir = new File(context.getCacheDir(), "cover-downloads");
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            Log.e(TAG, "Unable to create cover download cache directory");
            return DownloadStatus.TRANSIENT_FAILURE;
        }

        File tempFile = null;
        HttpURLConnection connection = null;
        try {
            tempFile = File.createTempFile("cover-" + serial + "-", ".part", tempDir);
            final URL url = new URL(COVER_BASE_URL + serial + ".png");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(true);
            connection.setRequestProperty("Accept", "image/png");
            connection.setRequestProperty("User-Agent", "PSX2-Android-CoverDownloader/1");

            final int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + responseCode + " for " + serial);
                return isTransientHttpCode(responseCode)
                        ? DownloadStatus.TRANSIENT_FAILURE
                        : DownloadStatus.PERMANENT_FAILURE;
            }

            final String contentType = connection.getContentType();
            if (contentType == null
                    || !contentType.toLowerCase(Locale.ROOT).startsWith("image/png")) {
                Log.w(TAG, "Unexpected content type for " + serial + ": " + contentType);
                return DownloadStatus.PERMANENT_FAILURE;
            }

            final long declaredLength = connection.getContentLengthLong();
            if (declaredLength > MAX_DOWNLOAD_BYTES) {
                Log.w(TAG, "Cover exceeds size limit for " + serial + ": " + declaredLength);
                return DownloadStatus.PERMANENT_FAILURE;
            }

            long copied = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream fileOutput = new FileOutputStream(tempFile);
                 OutputStream output = new BufferedOutputStream(fileOutput)) {
                final byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    copied += count;
                    if (copied > MAX_DOWNLOAD_BYTES) {
                        Log.w(TAG, "Cover stream exceeds size limit for " + serial);
                        return DownloadStatus.PERMANENT_FAILURE;
                    }
                    output.write(buffer, 0, count);
                }
                output.flush();
                fileOutput.getFD().sync();
            }

            if (copied == 0 || (declaredLength >= 0 && copied != declaredLength)) {
                Log.w(TAG, "Incomplete cover response for " + serial + ": "
                        + copied + "/" + declaredLength);
                return DownloadStatus.TRANSIENT_FAILURE;
            }
            if (!isValidPng(tempFile)) {
                Log.w(TAG, "Downloaded cover failed PNG validation for " + serial);
                return DownloadStatus.PERMANENT_FAILURE;
            }

            return commitValidatedCover(context, serial, tempFile)
                    ? DownloadStatus.SUCCESS
                    : DownloadStatus.TRANSIENT_FAILURE;
        } catch (IOException error) {
            Log.w(TAG, "Network/storage failure for " + serial + ": " + error.getMessage());
            return DownloadStatus.TRANSIENT_FAILURE;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (tempFile != null && tempFile.exists() && !tempFile.delete()) {
                tempFile.deleteOnExit();
            }
        }
    }

    private boolean commitValidatedCover(Context context, String serial, File source) {
        final DocumentFile coversDirectory = SafManager.getOrCreateDir(context, "covers");
        if (coversDirectory == null) {
            return commitToAppStorage(context, serial, source);
        }

        final String finalName = serial + ".png";
        final String stagingName = serial + "." + getId() + ".tmp.png";
        for (DocumentFile child : coversDirectory.listFiles()) {
            final String name = child.getName();
            if (name != null && name.startsWith(serial + ".") && name.endsWith(".tmp.png")) {
                child.delete();
            }
        }
        DocumentFile staging = coversDirectory.findFile(stagingName);
        if (staging != null) {
            staging.delete();
        }
        staging = coversDirectory.createFile("image/png", stagingName);
        if (staging == null) {
            Log.e(TAG, "Unable to create SAF staging cover for " + serial);
            return false;
        }

        try {
            if (!copyFileToUri(context, source, staging.getUri())
                    || !isValidPng(context, staging.getUri())) {
                Log.e(TAG, "SAF staging cover failed validation for " + serial);
                staging.delete();
                return false;
            }

            final DocumentFile existing = coversDirectory.findFile(finalName);
            if (existing != null && !existing.delete()) {
                Log.e(TAG, "Unable to remove invalid SAF cover for " + serial);
                staging.delete();
                return false;
            }

            if (!staging.renameTo(finalName)) {
                // Some providers do not support rename. Copy from the already validated
                // cache file and keep the staging document until the final copy validates.
                DocumentFile target = coversDirectory.findFile(finalName);
                if (target == null) {
                    target = coversDirectory.createFile("image/png", finalName);
                }
                if (target == null || !copyFileToUri(context, source, target.getUri())
                        || !isValidPng(context, target.getUri())) {
                    Log.e(TAG, "Unable to finalize SAF cover for " + serial);
                    return false;
                }
                staging.delete();
            }

            final DocumentFile committed = coversDirectory.findFile(finalName);
            return committed != null && isValidPng(context, committed.getUri());
        } catch (Throwable error) {
            Log.e(TAG, "Unable to commit SAF cover for " + serial, error);
            return false;
        }
    }

    private boolean commitToAppStorage(Context context, String serial, File source) {
        File directory = context.getExternalFilesDir("covers");
        if (directory == null) {
            directory = new File(context.getFilesDir(), "covers");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            return false;
        }

        final File staging = new File(directory, serial + ".tmp.png");
        final File target = new File(directory, serial + ".png");
        try {
            copyFile(source, staging);
            if (!isValidPng(staging)) {
                staging.delete();
                return false;
            }
            try {
                Files.move(staging.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(staging.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return isValidPng(target);
        } catch (IOException error) {
            Log.e(TAG, "Unable to commit app-storage cover for " + serial, error);
            staging.delete();
            return false;
        }
    }

    private static boolean hasValidExistingCover(Context context, String serial) {
        final DocumentFile coversDirectory = SafManager.getOrCreateDir(context, "covers");
        if (coversDirectory != null) {
            final DocumentFile existing = coversDirectory.findFile(serial + ".png");
            return existing != null && isValidPng(context, existing.getUri());
        }

        File directory = context.getExternalFilesDir("covers");
        if (directory == null) {
            directory = new File(context.getFilesDir(), "covers");
        }
        return isValidPng(new File(directory, serial + ".png"));
    }

    private static boolean copyFileToUri(Context context, File source, Uri target) {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = context.getContentResolver().openOutputStream(target, "w")) {
            if (output == null) {
                return false;
            }
            final byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
            return true;
        } catch (IOException error) {
            Log.e(TAG, "Unable to copy cover to SAF", error);
            return false;
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             FileOutputStream fileOutput = new FileOutputStream(target);
             OutputStream output = new BufferedOutputStream(fileOutput)) {
            final byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.flush();
            fileOutput.getFD().sync();
        }
    }

    private static boolean isValidPng(File file) {
        if (file == null || !file.isFile() || file.length() <= PNG_SIGNATURE.length
                || file.length() > MAX_DOWNLOAD_BYTES) {
            return false;
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            if (!hasPngSignature(input)) {
                return false;
            }
        } catch (IOException ignored) {
            return false;
        }

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return hasSafeDimensions(options);
    }

    private static boolean isValidPng(Context context, Uri uri) {
        if (uri == null) {
            return false;
        }
        try (InputStream input = new BufferedInputStream(
                context.getContentResolver().openInputStream(uri))) {
            if (input == null || !hasPngSignature(input)) {
                return false;
            }
        } catch (IOException | SecurityException ignored) {
            return false;
        }

        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return false;
            }
            BitmapFactory.decodeStream(input, null, options);
            return hasSafeDimensions(options);
        } catch (IOException | SecurityException ignored) {
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
            if (signature[index] != PNG_SIGNATURE[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasSafeDimensions(BitmapFactory.Options options) {
        return options.outWidth > 0 && options.outHeight > 0
                && options.outWidth <= MAX_IMAGE_DIMENSION
                && options.outHeight <= MAX_IMAGE_DIMENSION;
    }

    private static boolean isTransientHttpCode(int code) {
        return code == HttpURLConnection.HTTP_CLIENT_TIMEOUT
                || code == 425
                || code == 429
                || code >= 500;
    }

    private static String sanitizeSerial(String serial) {
        if (serial == null) {
            return "";
        }
        final String normalized = serial.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9-]", "");
        return normalized.length() <= 16 ? normalized : "";
    }

    private static Data resultData(String runToken, int ready, int downloaded,
                                   int failed, List<String> failedSerials) {
        String joined = String.join(", ", failedSerials);
        if (joined.length() > 2_000) {
            joined = joined.substring(0, 2_000);
        }
        return new Data.Builder()
                .putString(KEY_RUN_TOKEN, runToken == null ? "" : runToken)
                .putInt(KEY_READY, ready)
                .putInt(KEY_DOWNLOADED, downloaded)
                .putInt(KEY_FAILED, failed)
                .putString(KEY_FAILED_SERIALS, joined)
                .build();
    }

    private enum DownloadStatus {
        SUCCESS,
        TRANSIENT_FAILURE,
        PERMANENT_FAILURE
    }
}
