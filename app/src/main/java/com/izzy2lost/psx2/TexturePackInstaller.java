package com.izzy2lost.psx2;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validates normalized catalog ZIPs and installs them under the exact texture
 * directory consumed by the native core.
 *
 * Downloaded files live in a managed recursive subfolder:
 * textures/SERIAL/replacements/EmuCoreX/active/. This keeps manually installed
 * replacement files outside that folder intact.
 */
public final class TexturePackInstaller {
    private static final String TAG = "TexturePackInstaller";
    private static final Pattern SERIAL_PATTERN =
            Pattern.compile("^[A-Z]{4}-[0-9]{5}$");
    private static final int MAX_ENTRIES = 50_000;
    private static final long MAX_ENTRY_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final int BUFFER_BYTES = 1024 * 1024;
    private static final String MANAGED_DIRECTORY = "EmuCoreX";
    private static final String ACTIVE_DIRECTORY = "active";
    private static final String MARKER_FILE = "installed.json";

    private TexturePackInstaller() {}

    public interface ProgressListener {
        void onProgress(int installedFiles, int totalFiles, long installedBytes, long totalBytes);
        boolean isCancelled();
    }

    private static final class ValidatedEntry {
        final String zipName;
        final String[] relativeParts;
        final long size;
        final String extension;

        ValidatedEntry(String zipName, String[] relativeParts, long size, String extension) {
            this.zipName = zipName;
            this.relativeParts = relativeParts;
            this.size = size;
            this.extension = extension;
        }
    }

    private static final class Validation {
        final List<ValidatedEntry> entries;
        final long expandedBytes;

        Validation(List<ValidatedEntry> entries, long expandedBytes) {
            this.entries = entries;
            this.expandedBytes = expandedBytes;
        }
    }

    public static void install(
            Context context,
            File archive,
            String serial,
            String packId,
            String packName,
            String sha256,
            int expectedFileCount,
            ProgressListener progress) throws IOException {
        final String normalizedSerial = serial.trim().toUpperCase(Locale.ROOT);
        if (!SERIAL_PATTERN.matcher(normalizedSerial).matches()) {
            throw new IOException("Invalid game serial");
        }

        try (ZipFile zip = new ZipFile(archive)) {
            final Validation validation =
                    validateArchive(zip, normalizedSerial, expectedFileCount);
            if (SafManager.getDataRootUri(context) != null) {
                installToSaf(context, zip, validation, normalizedSerial,
                        packId, packName, sha256, progress);
            } else {
                installToFilesystem(context, zip, validation, normalizedSerial,
                        packId, packName, sha256, progress);
            }
        }
    }

    private static Validation validateArchive(
            ZipFile zip, String serial, int expectedFileCount) throws IOException {
        final ArrayList<ValidatedEntry> entries = new ArrayList<>(expectedFileCount);
        final Set<String> seenPaths = new HashSet<>();
        long expandedBytes = 0;
        final Enumeration<? extends ZipEntry> enumeration = zip.entries();
        while (enumeration.hasMoreElements()) {
            final ZipEntry entry = enumeration.nextElement();
            if (entry.isDirectory()) continue;
            if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) {
                throw new IOException("Unsupported ZIP compression: " + entry.getName());
            }
            final String[] relative = validatedRelativePath(entry.getName(), serial);
            final String extension = extensionOf(relative[relative.length - 1]);
            if (!".png".equals(extension) && !".dds".equals(extension)) {
                throw new IOException("Unsupported file in texture pack: " + entry.getName());
            }
            final long size = entry.getSize();
            if (size <= 0 || size > MAX_ENTRY_BYTES) {
                throw new IOException("Invalid texture size: " + entry.getName());
            }
            expandedBytes += size;
            if (expandedBytes > MAX_EXPANDED_BYTES) {
                throw new IOException("Texture pack expands beyond 8 GiB");
            }
            final String key = String.join("/", relative).toLowerCase(Locale.ROOT);
            if (!seenPaths.add(key)) {
                throw new IOException("Duplicate texture path: " + entry.getName());
            }
            entries.add(new ValidatedEntry(entry.getName(), relative, size, extension));
            if (entries.size() > MAX_ENTRIES) {
                throw new IOException("Texture pack contains more than 50,000 files");
            }
        }
        if (entries.size() != expectedFileCount) {
            throw new IOException("Texture count mismatch (expected " + expectedFileCount
                    + ", found " + entries.size() + ")");
        }
        return new Validation(entries, expandedBytes);
    }

    private static String[] validatedRelativePath(String rawName, String selectedSerial)
            throws IOException {
        if (rawName == null || rawName.isEmpty() || rawName.startsWith("/")
                || rawName.startsWith("\\") || rawName.indexOf('\\') >= 0
                || rawName.indexOf('\0') >= 0) {
            throw new IOException("Unsafe ZIP path");
        }
        final String[] rawParts = rawName.split("/");
        final ArrayList<String> parts = new ArrayList<>(rawParts.length);
        for (String part : rawParts) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part) || part.indexOf(':') >= 0) {
                throw new IOException("Unsafe ZIP path: " + rawName);
            }
            parts.add(part);
        }

        int start;
        if (parts.size() >= 2 && "replacements".equalsIgnoreCase(parts.get(0))) {
            start = 1;
        } else if (parts.size() >= 3
                && SERIAL_PATTERN.matcher(parts.get(0).toUpperCase(Locale.ROOT)).matches()
                && "replacements".equalsIgnoreCase(parts.get(1))) {
            final String archiveSerial = parts.get(0).toUpperCase(Locale.ROOT);
            if (!archiveSerial.equals(selectedSerial)) {
                throw new IOException("Pack content is for " + archiveSerial
                        + ", not " + selectedSerial);
            }
            start = 2;
        } else {
            throw new IOException("Texture entry is not rooted at replacements/: " + rawName);
        }
        if (start >= parts.size()) {
            throw new IOException("Texture entry has no filename: " + rawName);
        }
        return parts.subList(start, parts.size()).toArray(new String[0]);
    }

    private static void installToFilesystem(
            Context context,
            ZipFile zip,
            Validation validation,
            String serial,
            String packId,
            String packName,
            String sha256,
            ProgressListener progress) throws IOException {
        File dataRoot = context.getExternalFilesDir(null);
        if (dataRoot == null) dataRoot = context.getDataDir();
        final File managed = new File(
                new File(new File(new File(dataRoot, "textures"), serial), "replacements"),
                MANAGED_DIRECTORY);
        if (!managed.isDirectory() && !managed.mkdirs()) {
            throw new IOException("Unable to create the texture replacement directory");
        }
        if (managed.getUsableSpace() < validation.expandedBytes + 64L * 1024L * 1024L) {
            throw new IOException("Not enough free space to install this texture pack");
        }
        recoverFilesystemInstall(managed);

        final String token = UUID.randomUUID().toString();
        final File stage = new File(managed, "install-" + token);
        final File active = new File(managed, ACTIVE_DIRECTORY);
        final File backup = new File(managed, "backup-" + token);
        if (!stage.mkdirs()) {
            throw new IOException("Unable to create texture installation staging");
        }

        boolean committed = false;
        try {
            extractToFilesystem(zip, validation, stage, progress);
            if (active.exists()) moveDirectory(active, backup);
            try {
                moveDirectory(stage, active);
            } catch (IOException error) {
                if (backup.exists() && !active.exists()) moveDirectory(backup, active);
                throw error;
            }
            committed = true;
            if (backup.exists()) deleteRecursively(backup);
            try {
                writeFilesystemMarker(managed, serial, packId, packName, sha256,
                        validation.entries.size());
            } catch (IOException error) {
                Log.w(TAG, "Texture pack installed, but its status marker was not written",
                        error);
            }
        } finally {
            if (!committed && stage.exists()) deleteRecursively(stage);
        }
    }

    private static void installToSaf(
            Context context,
            ZipFile zip,
            Validation validation,
            String serial,
            String packId,
            String packName,
            String sha256,
            ProgressListener progress) throws IOException {
        final DocumentFile replacements =
                SafManager.getOrCreateDir(context, "textures", serial, "replacements");
        if (replacements == null || !replacements.isDirectory() || !replacements.canWrite()) {
            throw new IOException("The selected data folder is not writable");
        }
        DocumentFile managed = replacements.findFile(MANAGED_DIRECTORY);
        if (managed == null) managed = replacements.createDirectory(MANAGED_DIRECTORY);
        if (managed == null || !managed.isDirectory()) {
            throw new IOException("Unable to create the managed texture directory");
        }
        recoverSafInstall(managed);

        final String token = UUID.randomUUID().toString();
        final String stageName = "install-" + token;
        final String backupName = "backup-" + token;
        final DocumentFile stage = managed.createDirectory(stageName);
        if (stage == null) {
            throw new IOException("Unable to create texture installation staging");
        }

        boolean committed = false;
        try {
            extractToSaf(context, zip, validation, stage, progress);
            DocumentFile active = managed.findFile(ACTIVE_DIRECTORY);
            DocumentFile backup = null;
            if (active != null) {
                if (!active.renameTo(backupName)) {
                    throw new IOException("The data provider cannot replace the active texture pack");
                }
                backup = managed.findFile(backupName);
            }
            if (!stage.renameTo(ACTIVE_DIRECTORY)) {
                if (backup != null) backup.renameTo(ACTIVE_DIRECTORY);
                throw new IOException("The data provider cannot finalize the texture pack");
            }
            committed = true;
            if (backup != null) backup.delete();
            try {
                writeSafMarker(context, managed, serial, packId, packName, sha256,
                        validation.entries.size());
            } catch (IOException error) {
                Log.w(TAG, "Texture pack installed, but its SAF status marker was not written",
                        error);
            }
        } finally {
            if (!committed) {
                final DocumentFile leftover = managed.findFile(stageName);
                if (leftover != null) leftover.delete();
            }
        }
    }

    private static void extractToFilesystem(
            ZipFile zip, Validation validation, File stage, ProgressListener progress)
            throws IOException {
        int installedFiles = 0;
        long installedBytes = 0;
        for (ValidatedEntry item : validation.entries) {
            checkCancelled(progress);
            final File target = safeChild(stage, item.relativeParts);
            final File parent = target.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                throw new IOException("Unable to create a texture subdirectory");
            }
            try (InputStream input = new BufferedInputStream(
                    zip.getInputStream(zip.getEntry(item.zipName)));
                 FileOutputStream fileOutput = new FileOutputStream(target);
                 OutputStream output = new BufferedOutputStream(fileOutput, BUFFER_BYTES)) {
                copyValidatedTexture(input, output, item, progress);
                output.flush();
                fileOutput.getFD().sync();
            }
            installedFiles++;
            installedBytes += item.size;
            progress.onProgress(installedFiles, validation.entries.size(),
                    installedBytes, validation.expandedBytes);
        }
    }

    private static void extractToSaf(
            Context context,
            ZipFile zip,
            Validation validation,
            DocumentFile stage,
            ProgressListener progress) throws IOException {
        final Map<String, DocumentFile> directories = new HashMap<>();
        directories.put("", stage);
        int installedFiles = 0;
        long installedBytes = 0;
        for (ValidatedEntry item : validation.entries) {
            checkCancelled(progress);
            DocumentFile parent = stage;
            final StringBuilder key = new StringBuilder();
            for (int index = 0; index < item.relativeParts.length - 1; index++) {
                if (key.length() > 0) key.append('/');
                key.append(item.relativeParts[index]);
                final String pathKey = key.toString();
                DocumentFile next = directories.get(pathKey);
                if (next == null) {
                    next = parent.findFile(item.relativeParts[index]);
                    if (next == null) next = parent.createDirectory(item.relativeParts[index]);
                    if (next == null || !next.isDirectory()) {
                        throw new IOException("Unable to create a texture subdirectory");
                    }
                    directories.put(pathKey, next);
                }
                parent = next;
            }

            final String filename = item.relativeParts[item.relativeParts.length - 1];
            final String mime = ".png".equals(item.extension)
                    ? "image/png" : "image/vnd-ms.dds";
            DocumentFile target = parent.createFile(mime, filename);
            if (target == null) {
                throw new IOException("Unable to create texture file: " + filename);
            }
            try (InputStream input = new BufferedInputStream(
                    zip.getInputStream(zip.getEntry(item.zipName)));
                 OutputStream output = new BufferedOutputStream(
                         openSafOutput(context, target.getUri()), BUFFER_BYTES)) {
                copyValidatedTexture(input, output, item, progress);
                output.flush();
            }
            installedFiles++;
            installedBytes += item.size;
            progress.onProgress(installedFiles, validation.entries.size(),
                    installedBytes, validation.expandedBytes);
        }
    }

    private static OutputStream openSafOutput(Context context, Uri uri) throws IOException {
        final OutputStream output = context.getContentResolver().openOutputStream(uri, "w");
        if (output == null) throw new IOException("Unable to open a texture document");
        return output;
    }

    private static void copyValidatedTexture(
            InputStream input,
            OutputStream output,
            ValidatedEntry item,
            ProgressListener progress) throws IOException {
        final byte[] header = new byte[32];
        int headerLength = 0;
        while (headerLength < header.length) {
            final int count = input.read(header, headerLength, header.length - headerLength);
            if (count == -1) break;
            headerLength += count;
        }
        validateTextureHeader(header, headerLength, item.extension, item.zipName);
        output.write(header, 0, headerLength);

        long copied = headerLength;
        final byte[] buffer = new byte[BUFFER_BYTES];
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkCancelled(progress);
            copied += count;
            if (copied > item.size || copied > MAX_ENTRY_BYTES) {
                throw new IOException("Texture exceeds its declared size: " + item.zipName);
            }
            output.write(buffer, 0, count);
        }
        if (copied != item.size) {
            throw new IOException("Texture size mismatch: " + item.zipName);
        }
    }

    private static void validateTextureHeader(
            byte[] header, int length, String extension, String name) throws IOException {
        final int width;
        final int height;
        if (".png".equals(extension)) {
            final byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
            if (length < 24) throw new IOException("Truncated PNG: " + name);
            for (int index = 0; index < png.length; index++) {
                if (header[index] != png[index]) throw new IOException("Invalid PNG: " + name);
            }
            if (header[12] != 'I' || header[13] != 'H'
                    || header[14] != 'D' || header[15] != 'R') {
                throw new IOException("PNG has no IHDR header: " + name);
            }
            width = readBigEndianInt(header, 16);
            height = readBigEndianInt(header, 20);
        } else {
            if (length < 20 || header[0] != 'D' || header[1] != 'D'
                    || header[2] != 'S' || header[3] != ' '
                    || readLittleEndianInt(header, 4) != 124) {
                throw new IOException("Invalid DDS: " + name);
            }
            height = readLittleEndianInt(header, 12);
            width = readLittleEndianInt(header, 16);
        }
        if (width <= 0 || height <= 0) {
            throw new IOException("Texture has invalid dimensions: " + name);
        }
    }

    private static int readBigEndianInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static int readLittleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static File safeChild(File root, String[] relativeParts) throws IOException {
        File target = root;
        for (String part : relativeParts) target = new File(target, part);
        final String rootPath = root.getCanonicalPath() + File.separator;
        final String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(rootPath)) throw new IOException("Unsafe texture path");
        return target;
    }

    private static void recoverFilesystemInstall(File managed) throws IOException {
        final File active = new File(managed, ACTIVE_DIRECTORY);
        final File[] children = managed.listFiles();
        if (children == null) return;
        File recoverableBackup = null;
        for (File child : children) {
            if (child.getName().startsWith("backup-") && child.isDirectory()) {
                if (recoverableBackup == null) recoverableBackup = child;
            }
        }
        if (!active.exists() && recoverableBackup != null) {
            moveDirectory(recoverableBackup, active);
        }
        for (File child : children) {
            if ((child.getName().startsWith("install-")
                    || child.getName().startsWith("backup-")) && child.exists()) {
                deleteRecursively(child);
            }
        }
    }

    private static void recoverSafInstall(DocumentFile managed) {
        DocumentFile active = managed.findFile(ACTIVE_DIRECTORY);
        DocumentFile recoverableBackup = null;
        for (DocumentFile child : managed.listFiles()) {
            final String name = child.getName();
            if (name != null && name.startsWith("backup-") && child.isDirectory()
                    && recoverableBackup == null) {
                recoverableBackup = child;
            }
        }
        if (active == null && recoverableBackup != null) {
            recoverableBackup.renameTo(ACTIVE_DIRECTORY);
        }
        for (DocumentFile child : managed.listFiles()) {
            final String name = child.getName();
            if (name != null && (name.startsWith("install-") || name.startsWith("backup-"))) {
                child.delete();
            }
        }
    }

    private static void moveDirectory(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath());
        }
    }

    private static void writeFilesystemMarker(
            File managed,
            String serial,
            String packId,
            String packName,
            String sha256,
            int fileCount) throws IOException {
        final File staging = new File(managed, MARKER_FILE + ".tmp");
        final File target = new File(managed, MARKER_FILE);
        try (FileOutputStream output = new FileOutputStream(staging)) {
            output.write(markerJson(serial, packId, packName, sha256, fileCount));
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
    }

    private static void writeSafMarker(
            Context context,
            DocumentFile managed,
            String serial,
            String packId,
            String packName,
            String sha256,
            int fileCount) throws IOException {
        DocumentFile marker = managed.findFile(MARKER_FILE);
        if (marker == null) marker = managed.createFile("application/json", MARKER_FILE);
        if (marker == null || !SafManager.writeBytes(context, marker.getUri(),
                markerJson(serial, packId, packName, sha256, fileCount))) {
            throw new IOException("Texture pack installed, but its status marker could not be written");
        }
    }

    private static byte[] markerJson(
            String serial, String packId, String packName, String sha256, int fileCount) {
        final JSONObject marker = new JSONObject();
        try {
            marker.put("schemaVersion", 1);
            marker.put("serial", serial);
            marker.put("id", packId);
            marker.put("name", packName);
            marker.put("sha256", sha256.toUpperCase(Locale.ROOT));
            marker.put("fileCount", fileCount);
            marker.put("installedAt", System.currentTimeMillis());
        } catch (Exception ignored) {
            // All values above are primitive and cannot fail in practice.
        }
        return marker.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void checkCancelled(ProgressListener progress) throws IOException {
        if (progress.isCancelled()) throw new IOException("Texture installation was cancelled");
    }

    private static String extensionOf(String filename) {
        final int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    private static void deleteRecursively(File file) throws IOException {
        final File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("Unable to clean texture staging: " + file.getName());
        }
    }
}
