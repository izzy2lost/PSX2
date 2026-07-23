package com.izzy2lost.psx2;

import android.content.Context;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
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
    private static final Pattern COH_ROMVER_PATTERN = Pattern.compile("[0-9]{4}TZ[0-9]{8}");

    private static final long MIN_ARCADE_BIOS_SIZE = 2L * 1024 * 1024;
    private static final long MAX_ARCADE_BIOS_SIZE = 8L * 1024 * 1024;
    private static final int ROMDIR_ENTRY_SIZE = 16;

    // PCSX2x6 identifies COH-H boards from the 15-byte EXTINFO build serial.
    // System 256 and Super System 256 use the same BIOS and cannot be distinguished.
    private static final String SYSTEM_256_EXTINFO = "20040519-145634";
    private static final String SYSTEM_246_EXTINFO = "20021119-163841";
    private static final String COH_H_A000010_EXTINFO = "20000901-114731";

    // Exact hashes remain useful for known 2 MiB chip dumps, but structurally valid
    // COH-H images with other hashes are accepted below just as they are by PCSX2x6.
    private static final String SYSTEM_246_BIOS_SHA1 =
            "f0a74bbcaf801f3fd0b7002ebd0118564aae3528";
    private static final String SYSTEM_256_BIOS_SHA1 =
            "bc4fb4e1e53adbd92385f1726bd69663ff870f1e";

    private static final Object LOCK = new Object();
    private static Map<String, HashEntry> sEntriesBySha1;
    private static final Map<String, BiosInfo> FILE_CACHE = new HashMap<>();

    public enum Region {
        USA("USA"),
        EUROPE("Europe"),
        JAPAN("Japan"),
        ARCADE("COH-H Arcade");

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
        final String arcadeSerial;

        HashEntry(String name, String description, long size, String crc, String md5, String sha1, Region region) {
            this(name, description, size, crc, md5, sha1, region, "");
        }

        HashEntry(String name, String description, long size, String crc, String md5, String sha1,
                  Region region, String arcadeSerial) {
            this.name = name;
            this.description = description;
            this.size = size;
            this.crc = crc;
            this.md5 = md5;
            this.sha1 = sha1;
            this.region = region;
            this.arcadeSerial = arcadeSerial;
        }
    }

    private static final class ArcadeBiosIdentity {
        final String description;
        final String extInfoSerial;

        ArcadeBiosIdentity(String description, String extInfoSerial) {
            this.description = description;
            this.extInfoSerial = extInfoSerial;
        }
    }

    public static final class BiosInfo {
        public final File file;
        public final String relativePath;
        public final Region region;
        public final String datName;
        public final String description;
        public final String sha1;
        public final String arcadeSerial;

        private BiosInfo(File file, String relativePath, HashEntry entry) {
            this.file = file;
            this.relativePath = relativePath;
            this.region = entry.region;
            this.datName = entry.name;
            this.description = entry.description;
            this.sha1 = entry.sha1;
            this.arcadeSerial = entry.arcadeSerial;
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
            if (entry == null) {
                ArcadeBiosIdentity arcadeIdentity = inspectCohArcadeBios(file);
                if (arcadeIdentity != null) {
                    entry = new HashEntry(file.getName(), arcadeIdentity.description,
                            file.length(), "", "", sha1, Region.ARCADE,
                            arcadeIdentity.extInfoSerial);
                }
            }
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
        if (labels.size() == Region.values().length) return "USA, Europe, Japan, COH-H Arcade verified";
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
                int priority = selectionPriority(info);
                int currentPriority = selectionPriority(current);
                if (current == null || priority > currentPriority ||
                        (priority == currentPriority &&
                                info.file.lastModified() > current.file.lastModified())) {
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

        parsed.put(SYSTEM_246_BIOS_SHA1, new HashEntry(
                "r27v1602f.7d", "Namco System 246 Rack C COH-H arcade BIOS", 2L * 1024 * 1024,
                "2b2e41a2", "52cca0058626569c7a9699838baab2d8",
                SYSTEM_246_BIOS_SHA1, Region.ARCADE, SYSTEM_246_EXTINFO));
        parsed.put(SYSTEM_256_BIOS_SHA1, new HashEntry(
                "r27v1602f.8g", "Namco System 256 / Super System 256 COH-H arcade BIOS",
                2L * 1024 * 1024,
                "b2a8eeb6", "a58676c6bd79229bda967d07b4ec2e16",
                SYSTEM_256_BIOS_SHA1, Region.ARCADE, SYSTEM_256_EXTINFO));

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

    /**
     * Mirrors PCSX2x6's BIOS probe: locate the ROMDIR, resolve the ROMVER and
     * EXTINFO payloads, and require the ROMVER region/type bytes to be "TZ".
     * A loose text search is intentionally avoided so unrelated ROMs cannot pass
     * merely by containing a date-shaped string.
     */
    private static ArcadeBiosIdentity inspectCohArcadeBios(File file) throws Exception {
        long length = file.length();
        if (length < MIN_ARCADE_BIOS_SIZE || length > MAX_ARCADE_BIOS_SIZE)
            return null;

        try (RandomAccessFile rom = new RandomAccessFile(file, "r")) {
            byte[] entry = new byte[ROMDIR_ENTRY_SIZE];
            boolean foundRomDir = false;

            rom.seek(0);
            for (long offset = 0; offset + ROMDIR_ENTRY_SIZE <= length;
                 offset += ROMDIR_ENTRY_SIZE) {
                rom.readFully(entry);
                if ("RESET".equals(readRomDirName(entry))) {
                    foundRomDir = true;
                    break;
                }
            }
            if (!foundRomDir)
                return null;

            long fileOffset = 0;
            String romVersion = "";
            String extInfoSerial = "";
            long maxEntries = length / ROMDIR_ENTRY_SIZE;

            for (long i = 0; i < maxEntries; i++) {
                String name = readRomDirName(entry);
                if (name.isEmpty())
                    break;

                long directoryPosition = rom.getFilePointer();
                if ("ROMVER".equals(name) && fileOffset + 14 <= length) {
                    rom.seek(fileOffset);
                    romVersion = readFixedAscii(rom, 14);
                    rom.seek(directoryPosition);
                } else if ("EXTINFO".equals(name) && fileOffset + 0x10 + 15 <= length) {
                    rom.seek(fileOffset + 0x10);
                    extInfoSerial = readFixedAscii(rom, 15);
                    rom.seek(directoryPosition);
                }

                long entryFileSize = readLittleEndianUnsignedInt(entry, 12);
                fileOffset += (entryFileSize + 15L) & ~15L;

                if (directoryPosition + ROMDIR_ENTRY_SIZE > length)
                    break;
                rom.seek(directoryPosition);
                rom.readFully(entry);
            }

            if (!COH_ROMVER_PATTERN.matcher(romVersion).matches())
                return null;

            return new ArcadeBiosIdentity(
                    describeArcadeBios(extInfoSerial), extInfoSerial);
        }
    }

    private static String readRomDirName(byte[] entry) {
        int end = 0;
        while (end < 10 && entry[end] != 0)
            end++;
        if (end == 0 || end == 10)
            return "";
        return new String(entry, 0, end, StandardCharsets.US_ASCII);
    }

    private static long readLittleEndianUnsignedInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) |
                ((bytes[offset + 1] & 0xffL) << 8) |
                ((bytes[offset + 2] & 0xffL) << 16) |
                ((bytes[offset + 3] & 0xffL) << 24);
    }

    private static String readFixedAscii(RandomAccessFile file, int length) throws Exception {
        byte[] bytes = new byte[length];
        file.readFully(bytes);
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            int value = bytes[end] & 0xff;
            if (value < 0x20 || value > 0x7e)
                return "";
            end++;
        }
        return new String(bytes, 0, end, StandardCharsets.US_ASCII).trim();
    }

    private static String describeArcadeBios(String extInfoSerial) {
        return switch (extInfoSerial) {
            case SYSTEM_256_EXTINFO ->
                    "Namco System 256 / Super System 256 COH-H arcade BIOS";
            case SYSTEM_246_EXTINFO ->
                    "Namco System 246 Rack C COH-H arcade BIOS";
            case COH_H_A000010_EXTINFO ->
                    "Sony COH-H Board (A-000-010) arcade BIOS";
            case "" -> "Sony COH-H arcade BIOS (unknown board)";
            default -> "Sony COH-H arcade BIOS (unknown board, EXTINFO " +
                    extInfoSerial + ")";
        };
    }

    private static int selectionPriority(BiosInfo info) {
        if (info == null || info.region != Region.ARCADE) return 0;
        if (SYSTEM_256_EXTINFO.equals(info.arcadeSerial)) return 3;
        if (SYSTEM_246_EXTINFO.equals(info.arcadeSerial)) return 2;
        if (COH_H_A000010_EXTINFO.equals(info.arcadeSerial)) return 1;
        return 0;
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
