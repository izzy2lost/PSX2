package com.izzy2lost.psx2;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BiosVerifier {
    private static final String DAT_ASSET =
            "resources/Sony - PlayStation 2 - BIOS Datfile (115) (2022-12-12).dat";

    private static final Pattern GAME_NAME_PATTERN =
            Pattern.compile("^\\s*name\\s+\"?([^\"\\s]+)\"?");
    private static final Pattern DESCRIPTION_PATTERN =
            Pattern.compile("^\\s*description\\s+\"([^\"]+)\"");
    private static final Pattern ROM_PATTERN = Pattern.compile(
            "\\bname\\s+([^\\s)]+).*\\bsize\\s+(\\d+).*\\bcrc\\s+([0-9a-fA-F]{8}).*" +
                    "\\bmd5\\s+([0-9a-fA-F]{32}).*\\bsha1\\s+([0-9a-fA-F]{40})");

    private static final Object LOCK = new Object();
    private static Map<String, HashEntry> sEntriesBySha1;
    private static final Map<String, BiosInfo> FILE_CACHE = new HashMap<>();

    public enum Region {
        USA("USA"),
        EUROPE("Europe"),
        JAPAN("Japan");

        public final String label;

        Region(String label) {
            this.label = label;
        }
    }

    private static final class HashEntry {
        final String name;
        final String description;
        final long size;
        final String crc;
        final String md5;
        final String sha1;
        final Region region;

        HashEntry(String name, String description, long size, String crc, String md5, String sha1, Region region) {
            this.name = name;
            this.description = description;
            this.size = size;
            this.crc = crc;
            this.md5 = md5;
            this.sha1 = sha1;
            this.region = region;
        }
    }

    public static final class BiosInfo {
        public final File file;
        public final String relativePath;
        public final Region region;
        public final String datName;
        public final String description;
        public final String sha1;

        private BiosInfo(File file, String relativePath, HashEntry entry) {
            this.file = file;
            this.relativePath = relativePath;
            this.region = entry.region;
            this.datName = entry.name;
            this.description = entry.description;
            this.sha1 = entry.sha1;
        }
    }

    public static final class ScanResult {
        public final EnumMap<Region, BiosInfo> byRegion = new EnumMap<>(Region.class);
        public final List<BiosInfo> all = new ArrayList<>();

        public boolean hasAny() {
            return !all.isEmpty();
        }

        public BiosInfo firstAvailable() {
            for (Region region : Region.values()) {
                BiosInfo info = byRegion.get(region);
                if (info != null) return info;
            }
            return all.isEmpty() ? null : all.get(0);
        }
    }

    private BiosVerifier() {
    }

    public static File getBiosDirectory(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        return new File(base, "bios");
    }

    public static boolean hasAnyVerifiedBios(Context context) {
        return scanVerifiedBioses(context).hasAny();
    }

    public static ScanResult scanVerifiedBioses(Context context) {
        File biosDir = getBiosDirectory(context);
        ScanResult result = new ScanResult();
        scanDirectory(context, biosDir, biosDir, result);
        return result;
    }

    public static BiosInfo verifyFile(Context context, File biosDir, File file) {
        if (file == null || biosDir == null || !file.isFile() || file.length() <= 0) return null;

        String cacheKey = file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
        synchronized (LOCK) {
            if (FILE_CACHE.containsKey(cacheKey)) return FILE_CACHE.get(cacheKey);
        }

        BiosInfo info = null;
        try {
            String sha1 = sha1(file);
            HashEntry entry = getEntriesBySha1(context).get(sha1);
            if (entry != null && entry.size == file.length()) {
                info = new BiosInfo(file, relativePath(biosDir, file), entry);
            }
        } catch (Throwable t) {
            android.util.Log.w("BiosVerifier", "Unable to verify BIOS " + file.getName(), t);
        }

        synchronized (LOCK) {
            FILE_CACHE.put(cacheKey, info);
        }
        return info;
    }

    public static String describeVerifiedRegions(Context context) {
        ScanResult scan = scanVerifiedBioses(context);
        if (!scan.hasAny()) return "Need verified BIOS";

        List<String> labels = new ArrayList<>();
        for (Region region : Region.values()) {
            if (scan.byRegion.containsKey(region)) labels.add(region.label);
        }
        if (labels.size() == 1) return labels.get(0) + " verified";
        if (labels.size() == 3) return "USA, Europe, Japan verified";
        return TextUtils.join(", ", labels) + " verified";
    }

    private static void scanDirectory(Context context, File biosDir, File dir, ScanResult result) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null) continue;
            if (file.isDirectory()) {
                scanDirectory(context, biosDir, file, result);
                continue;
            }

            BiosInfo info = verifyFile(context, biosDir, file);
            if (info != null) {
                result.all.add(info);
                BiosInfo current = result.byRegion.get(info.region);
                if (current == null || info.file.lastModified() > current.file.lastModified()) {
                    result.byRegion.put(info.region, info);
                }
            }
        }
    }

    private static Map<String, HashEntry> getEntriesBySha1(Context context) throws Exception {
        synchronized (LOCK) {
            if (sEntriesBySha1 != null) return sEntriesBySha1;
        }

        Map<String, HashEntry> parsed = new HashMap<>();
        try (InputStream in = context.getAssets().open(DAT_ASSET);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String gameName = "";
            String description = "";
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher gameMatcher = GAME_NAME_PATTERN.matcher(line);
                if (gameMatcher.find()) {
                    gameName = gameMatcher.group(1);
                    description = "";
                    continue;
                }

                Matcher descriptionMatcher = DESCRIPTION_PATTERN.matcher(line);
                if (descriptionMatcher.find()) {
                    description = descriptionMatcher.group(1);
                    continue;
                }

                Matcher romMatcher = ROM_PATTERN.matcher(line);
                if (romMatcher.find()) {
                    Region region = detectDatRegion(gameName, description);
                    if (region == null) continue;

                    String name = romMatcher.group(1);
                    long size = Long.parseLong(romMatcher.group(2));
                    String crc = romMatcher.group(3).toLowerCase(Locale.ROOT);
                    String md5 = romMatcher.group(4).toLowerCase(Locale.ROOT);
                    String sha1 = romMatcher.group(5).toLowerCase(Locale.ROOT);
                    parsed.put(sha1, new HashEntry(name, description, size, crc, md5, sha1, region));
                }
            }
        }

        synchronized (LOCK) {
            sEntriesBySha1 = parsed;
            return sEntriesBySha1;
        }
    }

    private static Region detectDatRegion(String gameName, String description) {
        if (!TextUtils.isEmpty(gameName)) {
            Matcher matcher = Pattern.compile("^ps2-\\d+([a-z]+)-").matcher(gameName.toLowerCase(Locale.ROOT));
            if (matcher.find()) {
                char code = matcher.group(1).charAt(0);
                if (code == 'a') return Region.USA;
                if (code == 'e') return Region.EUROPE;
                if (code == 'j') return Region.JAPAN;
            }
        }

        if (!TextUtils.isEmpty(description)) {
            Matcher matcher = Pattern.compile("\\(Version [^)]+ ([AEJ])\\)").matcher(description);
            if (matcher.find()) {
                return switch (matcher.group(1).charAt(0)) {
                    case 'A' -> Region.USA;
                    case 'E' -> Region.EUROPE;
                    case 'J' -> Region.JAPAN;
                    default -> null;
                };
            }
        }
        return null;
    }

    private static String sha1(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String relativePath(File root, File file) {
        String rootPath = root.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (filePath.startsWith(rootPath + File.separator)) {
            return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
        }
        return file.getName();
    }
}
