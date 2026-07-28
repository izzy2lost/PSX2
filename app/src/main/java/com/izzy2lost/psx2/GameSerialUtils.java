package com.izzy2lost.psx2;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonicalizes real game IDs without mistaking a filename or title for one. */
final class GameSerialUtils {
    private static final Pattern PS2_SERIAL_PATTERN = Pattern.compile(
            "(?<![A-Z0-9])([A-Z]{4})[\\s._-]?([0-9]{3})[\\s._-]?([0-9]{2})(?![0-9])");
    private static final Pattern ARCADE_SERIAL_PATTERN = Pattern.compile(
            "(?<![A-Z0-9])(NM[0-9]{5})(?![0-9])");

    private GameSerialUtils() {}

    static String normalizePs2Serial(String candidate) {
        if (candidate == null || candidate.isBlank()) return "";
        final Matcher matcher = PS2_SERIAL_PATTERN.matcher(
                candidate.trim().toUpperCase(Locale.ROOT));
        if (!matcher.find()) return "";
        return matcher.group(1) + "-" + matcher.group(2) + matcher.group(3);
    }

    static String normalizeLibrarySerial(String candidate) {
        final String ps2Serial = normalizePs2Serial(candidate);
        if (!ps2Serial.isEmpty()) return ps2Serial;
        if (candidate == null || candidate.isBlank()) return "";

        final Matcher matcher = ARCADE_SERIAL_PATTERN.matcher(
                candidate.trim().toUpperCase(Locale.ROOT));
        return matcher.find() ? matcher.group(1) : "";
    }

    static boolean isPs2Serial(String candidate) {
        return !normalizePs2Serial(candidate).isEmpty();
    }
}
