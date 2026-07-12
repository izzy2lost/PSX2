package com.izzy2lost.psx2;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class MemoryCardSettings {
    static final String USE_GLOBAL = "__global__";
    static final String EJECTED = "__ejected__";

    private static final String PREFS = "app_prefs";
    private static final String[] DEFAULT_CARDS = {"Mcd001.ps2", "Mcd002.ps2"};

    private MemoryCardSettings() {}

    static File getDirectory(Context context) {
        File root = context.getExternalFilesDir(null);
        if (root == null) root = context.getFilesDir();
        File directory = new File(root, "memcards");
        if (!directory.exists()) directory.mkdirs();
        return directory;
    }

    static List<String> listCards(Context context) {
        File[] files = getDirectory(context).listFiles(file ->
                file.isFile() && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".ps2"));
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
        ArrayList<String> names = new ArrayList<>(files.length);
        for (File file : files) names.add(file.getName());
        return names;
    }

    static String getGlobalCard(Context context, int slot) {
        int index = Math.max(0, Math.min(1, slot - 1));
        return prefs(context).getString("memcard_slot" + slot + "_filename", DEFAULT_CARDS[index]);
    }

    static boolean isGlobalSlotEnabled(Context context, int slot) {
        return prefs(context).getBoolean("memcard_slot" + slot + "_enabled", true);
    }

    static void setGlobalCard(Context context, int slot, String filename) {
        prefs(context).edit()
                .putString("memcard_slot" + slot + "_filename", filename)
                .putBoolean("memcard_slot" + slot + "_enabled", true)
                .apply();
    }

    static String getGameCard(Context context, String gameUri, int slot) {
        if (gameUri == null || gameUri.isEmpty()) return USE_GLOBAL;
        return prefs(context).getString(gameKey(gameUri, slot), USE_GLOBAL);
    }

    static void setGameCard(Context context, String gameUri, int slot, String selection) {
        if (gameUri == null || gameUri.isEmpty()) return;
        SharedPreferences.Editor editor = prefs(context).edit();
        if (USE_GLOBAL.equals(selection)) editor.remove(gameKey(gameUri, slot));
        else editor.putString(gameKey(gameUri, slot), selection);
        editor.apply();
    }

    static String describeGlobalSlot(Context context, int slot) {
        return isGlobalSlotEnabled(context, slot) ? getGlobalCard(context, slot) : "No card";
    }

    static void applyForGame(Context context, String gameUri) {
        applyResolved(context, gameUri, false);
    }

    static void applyForGameAsync(Context context, String gameUri) {
        applyResolved(context, gameUri, true);
    }

    private static void applyResolved(Context context, String gameUri, boolean async) {
        String selection1 = getGameCard(context, gameUri, 1);
        String selection2 = getGameCard(context, gameUri, 2);
        String filename1 = resolveFilename(context, 1, selection1);
        String filename2 = resolveFilename(context, 2, selection2);
        boolean enabled1 = resolveEnabled(context, 1, selection1);
        boolean enabled2 = resolveEnabled(context, 2, selection2);
        Runnable apply = () -> NativeApp.setMemoryCardSlots(filename1, enabled1, filename2, enabled2);
        if (async) NativeApp.runNativeSettingAsync("setMemoryCardSlots", apply);
        else apply.run();
    }

    private static String resolveFilename(Context context, int slot, String selection) {
        if (USE_GLOBAL.equals(selection)) return getGlobalCard(context, slot);
        if (EJECTED.equals(selection)) return "";
        return selection;
    }

    private static boolean resolveEnabled(Context context, int slot, String selection) {
        if (USE_GLOBAL.equals(selection)) return isGlobalSlotEnabled(context, slot);
        return !EJECTED.equals(selection);
    }

    private static String gameKey(String gameUri, int slot) {
        return "game_memcard_slot" + slot + ":" + gameUri;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
