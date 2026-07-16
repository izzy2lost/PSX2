package com.izzy2lost.psx2;

import android.app.Dialog;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.widget.TextView;
import android.net.Uri;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.fragment.app.DialogFragment;

public class GameSettingsDialogFragment extends DialogFragment {

    private static final String ARG_GAME_TITLE = "game_title";
    private static final String ARG_GAME_URI = "game_uri";
    private static final String ARG_GAME_SERIAL = "game_serial";
    private static final String ARG_GAME_CRC = "game_crc";
    private static final int COVER_WIDTH = 567;
    private static final int COVER_HEIGHT = 878;

    // File picker state
    private ActivityResultLauncher<Intent> mPnachPicker;
    private ActivityResultLauncher<Intent> mCoverPicker;
    private boolean mImportAsCheats = true;

    public static GameSettingsDialogFragment newInstance(String gameTitle, String gameUri, String gameSerial, String gameCrc) {
        GameSettingsDialogFragment fragment = new GameSettingsDialogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_GAME_TITLE, gameTitle);
        args.putString(ARG_GAME_URI, gameUri);
        args.putString(ARG_GAME_SERIAL, gameSerial);
        args.putString(ARG_GAME_CRC, gameCrc);
        fragment.setArguments(args);
        return fragment;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        // Dialog is being destroyed - resume the game
        android.util.Log.d("GameSettingsDialog", "onDestroy called - resuming game");
        try {
            if (getActivity() != null && !getActivity().isFinishing() && getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onDialogClosed();
            }
        } catch (Throwable e) {
            android.util.Log.e("GameSettingsDialog", "Error in onDestroy: " + e.getMessage());
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        // Notify MainActivity that this dialog is opening
        try {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onDialogOpened();
            }
        } catch (Throwable ignored) {}
        
        Context ctx = requireContext();
        View view = getLayoutInflater().inflate(R.layout.dialog_game_settings, null, false);

        // Register picker ahead of time to avoid lifecycle crashes
        if (mPnachPicker == null) {
            mPnachPicker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                try {
                    if (result.getResultCode() != Activity.RESULT_OK) return;
                    Intent data = result.getData(); if (data == null) return;
                    Uri uri = data.getData(); if (uri == null) return;
                    Bundle args = getArguments();
                    String gameSerial = args != null ? args.getString(ARG_GAME_SERIAL, "") : "";
                    if (gameSerial == null || gameSerial.isEmpty()) {
                        try { gameSerial = NativeApp.getCurrentGameSerial(); } catch (Throwable ignored) {}
                    }
                    if (gameSerial == null || gameSerial.isEmpty()) {
                        android.widget.Toast.makeText(ctx, "Serial unknown; cannot import", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    java.io.File baseDir = ctx.getExternalFilesDir(null);
                    if (baseDir == null) baseDir = ctx.getFilesDir();
                    java.io.File targetDir = new java.io.File(baseDir, mImportAsCheats ? "cheats" : "patches");
                    if (!targetDir.exists()) targetDir.mkdirs();
                    java.io.File outFile = new java.io.File(targetDir, gameSerial + ".pnach");
                    android.content.ContentResolver cr = ctx.getContentResolver();
                    java.io.InputStream in = cr.openInputStream(uri);
                    if (in == null) { android.widget.Toast.makeText(ctx, "Failed to open file", android.widget.Toast.LENGTH_SHORT).show(); return; }
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                    byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.flush(); fos.close(); in.close();

                    // Mirror to SAF data root if set
                    android.net.Uri dataRoot = SafManager.getDataRootUri(ctx);
                    if (dataRoot != null) {
                        String subdir = mImportAsCheats ? "cheats" : "patches";
                        androidx.documentfile.provider.DocumentFile target = SafManager.createChild(ctx, new String[]{subdir}, gameSerial + ".pnach", "application/octet-stream");
                        if (target != null) {
                            try (java.io.InputStream in2 = cr.openInputStream(android.net.Uri.fromFile(outFile))) {
                                SafManager.copyFromStream(ctx, in2, target.getUri());
                            } catch (Exception ignored) {}
                        }
                    }
                    android.widget.Toast.makeText(ctx, (mImportAsCheats ? "Cheats" : "Patch Codes") + " imported for " + gameSerial, android.widget.Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    android.widget.Toast.makeText(ctx, "Import failed: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }
        
        // Register cover picker
        if (mCoverPicker == null) {
            mCoverPicker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                try {
                    if (result.getResultCode() != Activity.RESULT_OK) return;
                    Intent data = result.getData(); if (data == null) return;
                    Uri uri = data.getData(); if (uri == null) return;
                    Bundle args = getArguments();
                    String gameSerial = args != null ? args.getString(ARG_GAME_SERIAL, "") : "";
                    if (gameSerial == null || gameSerial.isEmpty()) {
                        try { gameSerial = NativeApp.getCurrentGameSerial(); } catch (Throwable ignored) {}
                    }
                    if (gameSerial == null || gameSerial.isEmpty()) {
                        android.widget.Toast.makeText(ctx, "Serial unknown; cannot set cover", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    final String serial = gameSerial;
                    android.widget.Toast.makeText(ctx, "Processing cover...", android.widget.Toast.LENGTH_SHORT).show();
                    
                    // Process in background to avoid blocking UI
                    new Thread(() -> {
                        com.bumptech.glide.request.FutureTarget<android.graphics.Bitmap> coverTarget = null;
                        try {
                            // Let Glide decode directly at cover size instead of allocating the
                            // source image at its full resolution and scaling it afterward.
                            com.bumptech.glide.RequestManager requestManager =
                                com.bumptech.glide.Glide.with(ctx.getApplicationContext());
                            coverTarget = requestManager
                                .asBitmap()
                                .load(uri)
                                .override(COVER_WIDTH, COVER_HEIGHT)
                                .centerCrop()
                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                                .skipMemoryCache(true)
                                .submit();
                            android.graphics.Bitmap cover = coverTarget.get();
                            
                            // Save to SAF location only
                            androidx.documentfile.provider.DocumentFile existing = SafManager.getChild(ctx, new String[]{"covers"}, serial + ".png");
                            if (existing != null && existing.exists()) {
                                existing.delete();
                            }
                            androidx.documentfile.provider.DocumentFile newFile = SafManager.createChild(ctx, new String[]{"covers"}, serial + ".png", "image/png");
                            if (newFile == null) {
                                throw new java.io.IOException("Cannot create cover file");
                            }
                            try (java.io.OutputStream out = ctx.getContentResolver().openOutputStream(newFile.getUri(), "w")) {
                                if (out == null) throw new java.io.IOException("Cannot open cover file");
                                if (!cover.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                                    throw new java.io.IOException("Cannot encode cover image");
                                }
                            }
                            
                            // Mark as custom cover
                            ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("custom_cover:" + serial, true)
                                .apply();
                            
                            // Clear Glide cache on background thread
                            try {
                                com.bumptech.glide.Glide.get(ctx).clearDiskCache();
                            } catch (Throwable ignored) {}
                            
                            // Show success message and clear memory cache on UI thread
                            if (getActivity() != null && !getActivity().isFinishing()) {
                                getActivity().runOnUiThread(() -> {
                                    try {
                                        com.bumptech.glide.Glide.get(ctx).clearMemory();
                                    } catch (Throwable ignored) {}
                                    android.widget.Toast.makeText(ctx, "Cover saved for " + serial, android.widget.Toast.LENGTH_SHORT).show();
                                });
                            }
                        } catch (Exception e) {
                            android.util.Log.e("GameSettings", "Error saving cover: " + e.getMessage(), e);
                            if (getActivity() != null && !getActivity().isFinishing()) {
                                getActivity().runOnUiThread(() -> {
                                    android.widget.Toast.makeText(ctx, "Failed to save cover: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                                });
                            }
                        } finally {
                            if (coverTarget != null) {
                                // Return the bitmap to Glide's managed pool when encoding is done.
                                com.bumptech.glide.Glide.with(ctx.getApplicationContext()).clear(coverTarget);
                            }
                        }
                    }).start();
                } catch (Exception e) {
                    android.widget.Toast.makeText(ctx, "Cover import failed: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }

        Bundle args = getArguments();
        String gameTitle = args != null ? args.getString(ARG_GAME_TITLE, "Unknown Game") : "Unknown Game";
        String gameUri = args != null ? args.getString(ARG_GAME_URI, "") : "";
        String gameSerial = args != null ? args.getString(ARG_GAME_SERIAL, "") : "";
        String gameCrc = args != null ? args.getString(ARG_GAME_CRC, "") : "";

        // Set title
        TextView titleView = view.findViewById(R.id.tv_game_title);
        titleView.setText(gameTitle);

        TextView serialView = view.findViewById(R.id.tv_game_serial);
        if (!gameSerial.isEmpty() || !gameCrc.isEmpty()) {
            serialView.setText(String.format("Serial: %s | CRC: %s", 
                gameSerial.isEmpty() ? "Unknown" : gameSerial,
                gameCrc.isEmpty() ? "Unknown" : gameCrc));
            serialView.setVisibility(View.VISIBLE);
        } else {
            serialView.setVisibility(View.GONE);
        }

        // Aspect Ratio Spinner
        Spinner spAspectRatio = view.findViewById(R.id.sp_aspect_ratio);
        ArrayAdapter<CharSequence> aspectAdapter = ArrayAdapter.createFromResource(ctx,
                R.array.aspect_ratio_entries, android.R.layout.simple_spinner_item);
        aspectAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spAspectRatio.setAdapter(aspectAdapter);

        // Blending Accuracy Spinner
        Spinner spBlendingAccuracy = view.findViewById(R.id.sp_blending_accuracy);
        ArrayAdapter<CharSequence> blendingAdapter = ArrayAdapter.createFromResource(ctx,
                R.array.blending_accuracy_entries, android.R.layout.simple_spinner_item);
        blendingAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBlendingAccuracy.setAdapter(blendingAdapter);

        // Renderer Spinner (now includes Auto)
        Spinner spRenderer = view.findViewById(R.id.sp_renderer);
        ArrayAdapter<CharSequence> rendererAdapter = ArrayAdapter.createFromResource(ctx,
                R.array.renderer_entries, android.R.layout.simple_spinner_item);
        rendererAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spRenderer.setAdapter(rendererAdapter);

        // Resolution Multiplier Spinner
        Spinner spResolution = view.findViewById(R.id.sp_resolution);
        ArrayAdapter<CharSequence> resolutionAdapter = ArrayAdapter.createFromResource(ctx,
                R.array.scale_entries, android.R.layout.simple_spinner_item);
        resolutionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spResolution.setAdapter(resolutionAdapter);

        Spinner spMemoryCard1 = view.findViewById(R.id.sp_memcard_slot1);
        Spinner spMemoryCard2 = view.findViewById(R.id.sp_memcard_slot2);
        java.util.ArrayList<String> memoryCardValues1 = configureMemoryCardSpinner(
                ctx, spMemoryCard1, gameUri, 1);
        java.util.ArrayList<String> memoryCardValues2 = configureMemoryCardSpinner(
                ctx, spMemoryCard2, gameUri, 2);

        // Switches
        MaterialSwitch swWidescreenPatches = view.findViewById(R.id.sw_widescreen_patches);
        MaterialSwitch swNoInterlacingPatches = view.findViewById(R.id.sw_no_interlacing_patches);
        MaterialSwitch swEnablePatchCodes = view.findViewById(R.id.sw_enable_patch_codes);
        MaterialSwitch swEnableCheats = view.findViewById(R.id.sw_enable_cheats);
        MaterialSwitch swLoadTextures = view.findViewById(R.id.sw_load_textures_per_game);
        MaterialSwitch swAsyncTextures = view.findViewById(R.id.sw_async_texture_loading_per_game);

        // Prefill with global defaults
        android.content.SharedPreferences gp = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        int gRenderer = gp.getInt("renderer", -1);
        float gScale = gp.getFloat("upscale_multiplier", 1.0f);
        int gAspect = gp.getInt("aspect_ratio", 1);
        int gBlend = gp.getInt("blending_accuracy", 1);
        boolean gWide = gp.getBoolean("widescreen_patches", true);
        boolean gNoInt = gp.getBoolean("no_interlacing_patches", true);
        boolean gLoadTex = gp.getBoolean("load_textures", false);
        boolean gAsyncTex = gp.getBoolean("async_texture_loading", true);
        boolean gCheats = gp.getBoolean("enable_cheats", false);

        // Map to indices
        int defaultRendererIdx = (gRenderer == -1 ? 0 : (gRenderer == 14 ? 1 : (gRenderer == 12 ? 2 : 3)));
        int defaultScaleIdx = Math.max(0, Math.min(7, Math.round(gScale) - 1));
        spAspectRatio.setSelection(Math.max(0, Math.min(4, gAspect)));
        spRenderer.setSelection(defaultRendererIdx);
        spResolution.setSelection(defaultScaleIdx);
        spBlendingAccuracy.setSelection(Math.max(0, Math.min(5, gBlend)));
        swWidescreenPatches.setChecked(gWide);
        swNoInterlacingPatches.setChecked(gNoInt);
        if (swLoadTextures != null) swLoadTextures.setChecked(gLoadTex);
        if (swAsyncTextures != null) swAsyncTextures.setChecked(gAsyncTex);
        swEnableCheats.setChecked(gCheats);

        // Load existing per-game settings from INI and prefill widgets; if present overrides globals
        try {
            String serial = gameSerial;
            if (serial == null || serial.isEmpty()) {
                serial = NativeApp.getCurrentGameSerial();
            }
            if (serial != null && !serial.isEmpty()) {
                // Build INI path
                String dataRoot = getContext().getExternalFilesDir(null).getAbsolutePath();
                java.io.File ini = new java.io.File(new java.io.File(dataRoot, "gamesettings"), serial + ".ini");
                boolean appliedRenderer = false;
                boolean appliedBlend = false;
                if (ini.exists()) {
                    String content = "";
                    try {
                        java.io.FileInputStream fis = new java.io.FileInputStream(ini);
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
                        fis.close();
                        content = baos.toString("UTF-8");
                    } catch (Exception ignored) {}
                    // Very light parsing
                    java.util.regex.Matcher m;
                    m = java.util.regex.Pattern.compile("(?m)^Renderer=\\s*(.+)$").matcher(content);
                    if (m.find()) {
                        String rv = m.group(1).trim();
                        int idx = 0; // 0=Auto,1=Vulkan,2=OpenGL,3=Software
                        if ("Auto".equalsIgnoreCase(rv) || "-1".equals(rv)) idx = 0;
                        else if ("Vulkan".equalsIgnoreCase(rv) || "14".equals(rv)) idx = 1;
                        else if ("OpenGL".equalsIgnoreCase(rv) || "12".equals(rv)) idx = 2;
                        else if ("Software".equalsIgnoreCase(rv) || "13".equals(rv)) idx = 3;
                        spRenderer.setSelection(idx);
                        appliedRenderer = true;
                    }
                    m = java.util.regex.Pattern.compile("(?m)^upscale_multiplier=\\s*([0-9]+(?:\\.[0-9]+)?)$").matcher(content);
                    if (m.find()) {
                        try { float mult = Float.parseFloat(m.group(1)); int sel = Math.max(0, Math.min(7, Math.round(mult - 1))); spResolution.setSelection(sel); } catch (Exception ignored) {}
                    }
                    m = java.util.regex.Pattern.compile("(?m)^AspectRatio=\\s*(.+)$").matcher(content);
                    if (m.find()) {
                        String av = m.group(1).trim();
                        int idx = 1; // default Auto 4:3/3:2
                        try {
                            int num = Integer.parseInt(av);
                            if (num >= 0 && num <= 4) idx = num;
                        } catch (Exception e) {
                            if ("Stretch".equalsIgnoreCase(av)) idx = 0;
                            else if ("Auto".equalsIgnoreCase(av)) idx = 1;
                            else if ("4:3".equalsIgnoreCase(av)) idx = 2;
                            else if ("16:9".equalsIgnoreCase(av)) idx = 3;
                            else if ("10:7".equalsIgnoreCase(av)) idx = 4;
                        }
                        spAspectRatio.setSelection(idx);
                    }
                    m = java.util.regex.Pattern.compile("(?m)^accurate_blending_unit=\\s*(.+)$").matcher(content);
                    if (m.find()) {
                        String bv = m.group(1).trim();
                        int idx = 1; // default Basic
                        try {
                            int num = Integer.parseInt(bv);
                            if (num >= 0 && num <= 5) idx = num;
                        } catch (Exception e) {
                            if ("Minimum".equalsIgnoreCase(bv)) idx = 0;
                            else if ("Basic".equalsIgnoreCase(bv)) idx = 1;
                            else if ("Medium".equalsIgnoreCase(bv)) idx = 2;
                            else if ("High".equalsIgnoreCase(bv)) idx = 3;
                            else if ("Full".equalsIgnoreCase(bv)) idx = 4;
                            else if ("Maximum".equalsIgnoreCase(bv)) idx = 5;
                        }
                        spBlendingAccuracy.setSelection(idx);
                        appliedBlend = true;
                    }
                    m = java.util.regex.Pattern.compile("(?m)^EnableWideScreenPatches=\\s*(true|false)$").matcher(content);
                    if (m.find()) swWidescreenPatches.setChecked(Boolean.parseBoolean(m.group(1)));
                    m = java.util.regex.Pattern.compile("(?m)^EnableNoInterlacingPatches=\\s*(true|false)$").matcher(content);
                    if (m.find()) swNoInterlacingPatches.setChecked(Boolean.parseBoolean(m.group(1)));
                    m = java.util.regex.Pattern.compile("(?m)^EnableCheats=\\s*(true|false)$").matcher(content);
                    if (m.find()) swEnableCheats.setChecked(Boolean.parseBoolean(m.group(1)));
                    m = java.util.regex.Pattern.compile("(?m)^EnablePatches=\\s*(true|false)$").matcher(content);
                    if (m.find()) swEnablePatchCodes.setChecked(Boolean.parseBoolean(m.group(1)));
                }

                // If no per-game renderer specified, mirror the global renderer choice
                if (!appliedRenderer) {
                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
                    int globalRenderer = prefs.getInt("renderer", -1); // default Auto
                    int idx;
                    if (globalRenderer == -1) idx = 0; // Auto
                    else if (globalRenderer == 14) idx = 1; // Vulkan
                    else if (globalRenderer == 12) idx = 2; // OpenGL
                    else if (globalRenderer == 13) idx = 3; // Software
                    else idx = 0;
                    spRenderer.setSelection(idx);
                }
                // If no per-game blending specified, mirror the global blending
                if (!appliedBlend) {
                    android.content.SharedPreferences prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
                    int globalBlend = prefs.getInt("blending_accuracy", 1);
                    spBlendingAccuracy.setSelection(Math.max(0, Math.min(5, globalBlend)));
                }
            }
        } catch (Throwable ignored) {
            // Fallback to defaults if loading fails
            spAspectRatio.setSelection(1);
            spBlendingAccuracy.setSelection(1);
            // Mirror global default when error
            android.content.SharedPreferences prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
            int globalRenderer = prefs.getInt("renderer", -1);
            int idx;
            if (globalRenderer == -1) idx = 0;
            else if (globalRenderer == 14) idx = 1;
            else if (globalRenderer == 12) idx = 2;
            else if (globalRenderer == 13) idx = 3;
            else idx = 0;
            spRenderer.setSelection(idx);
            spResolution.setSelection(0);
        }

        // Use MaterialAlertDialogBuilder with Material 3 overlay for the main dialog
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog);
        builder.setCustomTitle(UiUtils.centeredDialogTitle(ctx, "Per-Game Settings"))
                .setView(view)
                .setNegativeButton("Cancel", (d, w) -> d.dismiss())
                .setPositiveButton("Save", (d, w) -> {
                   final int aspectIdx = spAspectRatio.getSelectedItemPosition();
                   final int blendLevel = spBlendingAccuracy.getSelectedItemPosition();
                   final int rendererIdx = spRenderer.getSelectedItemPosition();
                   final int resIdx = spResolution.getSelectedItemPosition();
                   final boolean wide = swWidescreenPatches.isChecked();
                   final boolean noInt = swNoInterlacingPatches.isChecked();
                   final boolean enablePatches = true; // always on
                   final boolean enableCheats = swEnableCheats.isChecked();
                   final boolean loadTex = (swLoadTextures != null && swLoadTextures.isChecked());
                   final boolean asyncTex = (swAsyncTextures != null && swAsyncTextures.isChecked());
                   final String memoryCard1 = memoryCardValues1.get(spMemoryCard1.getSelectedItemPosition());
                   final String memoryCard2 = memoryCardValues2.get(spMemoryCard2.getSelectedItemPosition());

                   MemoryCardSettings.setGameCard(ctx, gameUri, 1, memoryCard1);
                   MemoryCardSettings.setGameCard(ctx, gameUri, 2, memoryCard2);

                   // Persist per-game INI explicitly (supports Auto as well)
                   writeGameSettingsIni(ctx, gameSerial, gameCrc,
                           aspectIdx, blendLevel, rendererIdx, resIdx, wide, noInt,
                           /*enablePatches*/ enablePatches, enableCheats);

                   // Live-apply per-game settings in one batch
                   try {
                       int renderer;
                       // rendererIdx: 0=Auto,1=Vulkan,2=OpenGL,3=Software
                       if (rendererIdx == 0) renderer = -1;
                       else if (rendererIdx == 1) renderer = 14;
                       else if (rendererIdx == 2) renderer = 12;
                       else renderer = 13;

                       float scale = Math.max(1, Math.min(8, resIdx + 1));
                       final int rendererToApply = renderer;
                       final float scaleToApply = scale;
                       NativeApp.runNativeSettingAsync("applyPerGameSettings", () -> {
                           NativeApp.setAspectRatio(aspectIdx);
                           NativeApp.setLoadTextures(loadTex);
                           NativeApp.setAsyncTextureLoading(asyncTex);
                           NativeApp.applyPerGameSettingsBatch(rendererToApply, scaleToApply, blendLevel, wide, noInt, enablePatches, enableCheats);
                       });
                   } catch (Throwable t) {
                       android.util.Log.e("GameSettings", "Per-game batch apply failed: " + t.getMessage());
                   }

                   // Refresh quick UI (renderer label) to reflect runtime renderer
                   try {
                       android.app.Activity a = getActivity();
                       if (a instanceof MainActivity) {
                           ((MainActivity) a).runOnUiThread(() -> ((MainActivity) a).refreshQuickUi());
                       }
                   } catch (Throwable ignored) {}

                   d.dismiss();
               })
               .setNeutralButton("Reset to Global", (d, w) -> {
                   // Delete game-specific settings file so globals apply
                   deleteGameSettingsIni(ctx, gameSerial, gameCrc);
                   MemoryCardSettings.setGameCard(ctx, gameUri, 1, MemoryCardSettings.USE_GLOBAL);
                   MemoryCardSettings.setGameCard(ctx, gameUri, 2, MemoryCardSettings.USE_GLOBAL);
                   d.dismiss();
               });

        // Import PNACH button wiring
        com.google.android.material.button.MaterialButton btnImport = view.findViewById(R.id.btn_import_pnach);
        if (btnImport != null) {
            btnImport.setOnClickListener(v -> {
                final String[] choices = new String[]{"Import as Cheats", "Import as Patch Codes"};
                new MaterialAlertDialogBuilder(ctx,
                        com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                        .setCustomTitle(UiUtils.centeredDialogTitle(ctx, "Import PNACH"))
                        .setItems(choices, (dlg, which) -> {
                            mImportAsCheats = (which == 0);
                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.setType("*/*");
                            mPnachPicker.launch(intent);
                        })
                        .show();
            });
        }
        
        // Custom Cover button wiring
        com.google.android.material.button.MaterialButton btnCustomCover = view.findViewById(R.id.btn_set_custom_cover);
        if (btnCustomCover != null) {
            btnCustomCover.setOnClickListener(v -> {
                // Check if this game has a custom cover
                android.content.SharedPreferences prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
                boolean hasCustomCover = prefs.getBoolean("custom_cover:" + gameSerial, false);
                
                if (hasCustomCover) {
                    // Show options: Set New or Delete Custom
                    final String[] choices = new String[]{"Set New Custom Cover", "Delete Custom Cover"};
                    new MaterialAlertDialogBuilder(ctx,
                            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                            .setCustomTitle(UiUtils.centeredDialogTitle(ctx, "Custom Cover"))
                            .setItems(choices, (dlg, which) -> {
                                if (which == 0) {
                                    // Set new custom cover
                                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                                    intent.setType("image/*");
                                    mCoverPicker.launch(intent);
                                } else {
                                    // Delete custom cover
                                    deleteCustomCover(ctx, gameSerial);
                                }
                            })
                            .show();
                } else {
                    // No custom cover exists, directly open picker
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");
                    mCoverPicker.launch(intent);
                }
            });
        }

        return builder.create();
    }

    private static java.util.ArrayList<String> configureMemoryCardSpinner(
            Context context, Spinner spinner, String gameUri, int slot) {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        labels.add("Use global (" + MemoryCardSettings.describeGlobalSlot(context, slot) + ")");
        values.add(MemoryCardSettings.USE_GLOBAL);
        labels.add("No card (ejected)");
        values.add(MemoryCardSettings.EJECTED);
        for (String card : MemoryCardSettings.listCards(context)) {
            labels.add(card);
            values.add(card);
        }

        String selected = MemoryCardSettings.getGameCard(context, gameUri, slot);
        int selectedIndex = values.indexOf(selected);
        if (selectedIndex < 0) {
            labels.add(selected + " (missing)");
            values.add(selected);
            selectedIndex = values.size() - 1;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                context, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selectedIndex);
        return values;
    }

    private void saveGameSettings(String gameSerial, String gameCrc,
                                 int blendingAccuracy, int renderer, int resolution,
                                 boolean widescreenPatches, boolean noInterlacingPatches,
                                 boolean enablePatches, boolean enableCheats) {

        // Create the settings filename based on serial (like SLUS-12345.ini)
        if (gameSerial == null || gameSerial.isEmpty()) {
            android.util.Log.w("GameSettings", "No game serial available, cannot save settings");
            return;
        }
        String filename = gameSerial + ".ini";

        // Save to PCSX2's DataRoot/gamesettings via native helper.
        NativeApp.saveGameSettings(filename, blendingAccuracy, renderer, resolution,
                widescreenPatches, noInterlacingPatches, enablePatches, enableCheats);

        android.widget.Toast.makeText(requireContext(), "Game settings saved: " + filename, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void deleteGameSettings(String gameSerial, String gameCrc) {
        String filename = "";
        if (!gameSerial.isEmpty()) {
            filename = gameSerial + ".ini";
        } else {
            return;
        }

        NativeApp.deleteGameSettings(filename);
    }

    private static String pickSettingsFileName(String gameSerial, String gameCrc) {
        if (gameSerial != null && !gameSerial.isEmpty()) return gameSerial + ".ini";
        if (gameCrc != null && !gameCrc.isEmpty()) return gameCrc + ".ini";
        return null;
    }

    private static void writeGameSettingsIni(Context ctx,
                                             String gameSerial,
                                             String gameCrc,
                                             int aspectRatioIdx,
                                             int blendingAccuracyIdx,
                                             int rendererIdx,
                                             int resolutionIdx,
                                             boolean widescreenPatches,
                                             boolean noInterlacingPatches,
                                             boolean enablePatches,
                                             boolean enableCheats) {
        try {
            String fileName = pickSettingsFileName(gameSerial, gameCrc);
            if (fileName == null) return;
            java.io.File baseDir = new java.io.File(ctx.getExternalFilesDir(null), "gamesettings");
            if (!baseDir.exists()) baseDir.mkdirs();
            java.io.File ini = new java.io.File(baseDir, fileName);

            // Map indices
            String rendererName = (rendererIdx == 0) ? "Auto" : (rendererIdx == 1 ? "Vulkan" : (rendererIdx == 2 ? "OpenGL" : "Software"));
            float upscale = Math.max(1, Math.min(8, resolutionIdx + 1));
            int abl = Math.max(0, Math.min(5, blendingAccuracyIdx));
            int aspectRatio = Math.max(0, Math.min(4, aspectRatioIdx));

            StringBuilder sb = new StringBuilder();
            sb.append("[EmuCore/GS]\n");
            sb.append("Renderer=").append(rendererName).append('\n');
            sb.append("upscale_multiplier=").append((int) upscale).append('\n');
            sb.append("AspectRatio=").append(aspectRatio).append('\n');
            sb.append("accurate_blending_unit=").append(abl).append('\n');
            sb.append('\n');
            sb.append("[EmuCore]\n");
            sb.append("EnableWideScreenPatches=").append(widescreenPatches).append('\n');
            sb.append("EnableNoInterlacingPatches=").append(noInterlacingPatches).append('\n');
            sb.append("EnablePatches=").append(enablePatches).append('\n');
            sb.append("EnableCheats=").append(enableCheats).append('\n');

            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(ini, false);
                byte[] data = sb.toString().getBytes("UTF-8");
                fos.write(data);
                fos.flush();
                fos.close();
            } catch (Exception ignored) {}

            // Mirror to SAF data root if set
            android.net.Uri dataRoot = SafManager.getDataRootUri(ctx);
            if (dataRoot != null) {
                try {
                    androidx.documentfile.provider.DocumentFile target = SafManager.createChild(ctx, new String[]{"gamesettings"}, fileName, "application/octet-stream");
                    if (target != null) {
                        byte[] data = sb.toString().getBytes("UTF-8");
                        SafManager.writeBytes(ctx, target.getUri(), data);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteGameSettingsIni(Context ctx, String gameSerial, String gameCrc) {
        try {
            String fileName = pickSettingsFileName(gameSerial, gameCrc);
            if (fileName == null) return;
            java.io.File ini = new java.io.File(new java.io.File(ctx.getExternalFilesDir(null), "gamesettings"), fileName);
            if (ini.exists()) ini.delete();
        } catch (Throwable ignored) {
        }
    }
    
    private void deleteCustomCover(Context ctx, String gameSerial) {
        if (gameSerial == null || gameSerial.isEmpty()) {
            android.widget.Toast.makeText(ctx, "Cannot delete cover: serial unknown", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        
        new Thread(() -> {
            try {
                String fileName = gameSerial + ".png";
                boolean deleted = false;
                
                // Delete from SAF location
                androidx.documentfile.provider.DocumentFile existing = SafManager.getChild(ctx, new String[]{"covers"}, fileName);
                if (existing != null && existing.exists()) {
                    deleted = existing.delete();
                }
                
                // Clear custom cover flag
                ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("custom_cover:" + gameSerial, false)
                    .apply();
                
                // Clear Glide cache
                try {
                    com.bumptech.glide.Glide.get(ctx).clearDiskCache();
                } catch (Throwable ignored) {}
                
                // Show result on UI thread
                final boolean success = deleted;
                if (getActivity() != null && !getActivity().isFinishing()) {
                    getActivity().runOnUiThread(() -> {
                        try {
                            com.bumptech.glide.Glide.get(ctx).clearMemory();
                        } catch (Throwable ignored) {}
                        
                        if (success) {
                            android.widget.Toast.makeText(ctx, "Custom cover deleted for " + gameSerial, android.widget.Toast.LENGTH_SHORT).show();
                        } else {
                            android.widget.Toast.makeText(ctx, "No custom cover found to delete", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            } catch (Exception e) {
                android.util.Log.e("GameSettings", "Error deleting custom cover: " + e.getMessage(), e);
                if (getActivity() != null && !getActivity().isFinishing()) {
                    getActivity().runOnUiThread(() -> {
                        android.widget.Toast.makeText(ctx, "Failed to delete cover: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                    });
                }
            }
        }).start();
    }
}
