package com.izzy2lost.psx2;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.content.res.ColorStateList;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.fragment.app.DialogFragment;
import androidx.core.widget.CompoundButtonCompat;
import androidx.core.content.ContextCompat;

public class SettingsDialogFragment extends DialogFragment {

    private static final String PREFS = "app_prefs";
    // Renderer constants (match native GSRendererType values used elsewhere)
    private static final int RENDERER_AUTO = -1;
    private static final int RENDERER_OPENGL = 12;
    private static final int RENDERER_SOFTWARE = 13;
    private static final int RENDERER_VULKAN = 14;

    // Static method to load and apply settings on app startup
    public static void loadAndApplySettings(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        
        // Default to Automatic if no renderer has been chosen yet
        int renderer = prefs.getInt("renderer", RENDERER_AUTO);
        float scale = prefs.getFloat("upscale_multiplier", 1.0f);
        int aspectRatio = prefs.getInt("aspect_ratio", 1);
        int blendingAccuracy = prefs.getInt("blending_accuracy", 1); // 0..5
        boolean widescreenPatches = prefs.getBoolean("widescreen_patches", true);
        boolean noInterlacingPatches = prefs.getBoolean("no_interlacing_patches", true);
        boolean loadTextures = prefs.getBoolean("load_textures", false);
        boolean asyncTextureLoading = prefs.getBoolean("async_texture_loading", true);
        boolean precacheTextures = prefs.getBoolean("precache_textures", false);
        boolean vsyncEnabled = prefs.getBoolean("vsync_enabled", false);
        boolean hudVisible = prefs.getBoolean("hud_visible", false);
        
        // Debug logging
        android.util.Log.d("SettingsDialog", "Loading renderer setting: " + renderer +
            " (-1=Auto, 12=OpenGL, 13=Software, 14=Vulkan)");
        
        // Apply all settings
        NativeApp.renderGpu(renderer);
        android.util.Log.d("SettingsDialog", "Applied renderer: " + renderer);
        NativeApp.renderUpscalemultiplier(scale);
        NativeApp.setAspectRatio(aspectRatio);
        NativeApp.setBlendingAccuracy(blendingAccuracy);
        NativeApp.setWidescreenPatches(widescreenPatches);
        NativeApp.setNoInterlacingPatches(noInterlacingPatches);
        NativeApp.setLoadTextures(loadTextures);
        NativeApp.setAsyncTextureLoading(asyncTextureLoading);
        NativeApp.setPrecacheTextureReplacements(precacheTextures);
        NativeApp.setVsyncEnabled(vsyncEnabled);
        NativeApp.setHudVisible(hudVisible);
        
        // Set brighter default brightness (60 instead of 50)
        NativeApp.setShadeBoost(true);
        NativeApp.setShadeBoostBrightness(60);
        NativeApp.setShadeBoostContrast(50);
        NativeApp.setShadeBoostSaturation(50);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        // Notify main activity that dialog is opening
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).onDialogOpened();
        }
        
        Context ctx = requireContext();
        View view = getLayoutInflater().inflate(R.layout.dialog_settings, null, false);

        RadioGroup rgRenderer = view.findViewById(R.id.rg_renderer);
        RadioButton rbAuto = view.findViewById(R.id.rb_renderer_auto);
        RadioButton rbGl = view.findViewById(R.id.rb_renderer_gl);
        RadioButton rbVk = view.findViewById(R.id.rb_renderer_vk);
        RadioButton rbSw = view.findViewById(R.id.rb_renderer_sw);
        Spinner spScale = view.findViewById(R.id.sp_scale);
        Spinner spBlending = view.findViewById(R.id.sp_blending_accuracy);
        Spinner spAspectRatio = view.findViewById(R.id.sp_aspect_ratio);
        MaterialSwitch swWidescreen = view.findViewById(R.id.sw_widescreen);
        MaterialSwitch swNoInterlacing = view.findViewById(R.id.sw_no_interlacing);
        MaterialSwitch swLoadTextures = view.findViewById(R.id.sw_load_textures);
        MaterialSwitch swAsyncTextureLoading = view.findViewById(R.id.sw_async_texture_loading);
        MaterialSwitch swPrecacheTextures = view.findViewById(R.id.sw_precache_textures);
        MaterialSwitch swVsync = view.findViewById(R.id.sw_vsync);
        MaterialSwitch swDevHud = view.findViewById(R.id.sw_dev_hud);
        View btnPower = view.findViewById(R.id.btn_power);
        View btnReboot = view.findViewById(R.id.btn_reboot);
        View btnTestController = view.findViewById(R.id.btn_test_controller);
        TextView tvBiosStatus = view.findViewById(R.id.tv_bios_status);
        View btnBios = view.findViewById(R.id.btn_bios);

        // Brand tints for checked/activated states to replace aqua
        int brand = ContextCompat.getColor(ctx, R.color.brand_primary);
        int outline = ContextCompat.getColor(ctx, R.color.brand_outline);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        int[] colors = new int[]{
                brand,
                outline
        };
        ColorStateList brandChecked = new ColorStateList(states, colors);

        // RadioButtons
        if (rbAuto != null) CompoundButtonCompat.setButtonTintList(rbAuto, brandChecked);
        if (rbGl != null) CompoundButtonCompat.setButtonTintList(rbGl, brandChecked);
        if (rbVk != null) CompoundButtonCompat.setButtonTintList(rbVk, brandChecked);
        if (rbSw != null) CompoundButtonCompat.setButtonTintList(rbSw, brandChecked);

        // MaterialSwitch: rely on default Material3 theme styling from XML style/overlay

        if (btnPower != null) {
            btnPower.setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(requireContext(),
                        com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                        .setTitle("Power Off")
                        .setMessage("Quit the app?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Quit", (d1, w1) -> {
                            // Stop emulator first
                            NativeApp.shutdown();
                            // Close dialog
                            dismissAllowingStateLoss();
                            // Quit the whole app/activity task
                            if (getActivity() != null) {
                                getActivity().finishAffinity();
                                getActivity().finishAndRemoveTask();
                            }
                            // As a fallback ensure process exit
                            System.exit(0);
                        })
                        .show();
            });
        }

        if (btnReboot != null) {
            btnReboot.setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(requireContext(),
                        com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                        .setCustomTitle(UiUtils.centeredDialogTitle(requireContext(), "Reboot"))
                        .setMessage("Restart the current game?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Reboot", (d1, w1) -> {
                            MainActivity mainActivity = UiUtils.getMainActivity(this);
                            if (mainActivity != null) {
                                mainActivity.rebootEmu();
                            } else {
                                NativeApp.shutdown();
                            }
                            dismissAllowingStateLoss();
                        })
                        .show();
            });
        }

        if (btnTestController != null) {
            btnTestController.setOnClickListener(v -> {
                ControllerTestDialogFragment controllerDialog = new ControllerTestDialogFragment();
                controllerDialog.show(getParentFragmentManager(), "controller_test");
            });
        }

        if (tvBiosStatus != null) {
            tvBiosStatus.setText(BiosVerifier.describeVerifiedRegions(ctx));
        }
        if (btnBios != null) {
            btnBios.setOnClickListener(v -> {
                android.app.Activity activity = getActivity();
                if (activity instanceof MainActivity) {
                    ((MainActivity) activity).showBiosPrompt();
                }
            });
        }
        
        // RetroAchievements button
        View btnAchievements = view.findViewById(R.id.btn_achievements);
        android.util.Log.d("SettingsDialog", "btnAchievements found: " + (btnAchievements != null));
        if (btnAchievements != null) {
            btnAchievements.setOnClickListener(v -> {
                android.util.Log.d("SettingsDialog", "Achievements button clicked!");
                AchievementsDialogFragment achievementsDialog = AchievementsDialogFragment.newInstance();
                achievementsDialog.show(getParentFragmentManager(), "achievements");
            });
        } else {
            android.util.Log.e("SettingsDialog", "btn_achievements NOT FOUND in layout!");
        }

        // Populate scale spinner (1x..8x)
        ArrayAdapter<CharSequence> scaleAdapter = ArrayAdapter.createFromResource(ctx,
                R.array.scale_entries, android.R.layout.simple_spinner_item);
        scaleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spScale.setAdapter(scaleAdapter);

        // Populate blending accuracy spinner (0..5)
        ArrayAdapter<CharSequence> blendAdapter = ArrayAdapter.createFromResource(ctx,
                R.array.blending_accuracy_entries, android.R.layout.simple_spinner_item);
        blendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBlending.setAdapter(blendAdapter);

        // Populate aspect ratio spinner
        ArrayAdapter<CharSequence> aspectAdapter = ArrayAdapter.createFromResource(ctx,
                R.array.aspect_ratio_entries, android.R.layout.simple_spinner_item);
        aspectAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spAspectRatio.setAdapter(aspectAdapter);

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int savedRenderer = prefs.getInt("renderer", RENDERER_AUTO);
        float savedScale = prefs.getFloat("upscale_multiplier", 1.0f);
        int savedAspectRatio = prefs.getInt("aspect_ratio", 1); // 1 = Auto 4:3/3:2 (recommended)
        boolean savedWidescreen = prefs.getBoolean("widescreen_patches", true);
        boolean savedNoInterlacing = prefs.getBoolean("no_interlacing_patches", true);
        boolean savedLoadTextures = prefs.getBoolean("load_textures", false);
        boolean savedAsyncTextureLoading = prefs.getBoolean("async_texture_loading", true);
        boolean savedPrecacheTextures = prefs.getBoolean("precache_textures", false);
        boolean savedVsync = prefs.getBoolean("vsync_enabled", false);
        boolean savedHud = prefs.getBoolean("hud_visible", false);
        boolean savedCheatsGlobal = prefs.getBoolean("enable_cheats", false);
        int savedBlending = prefs.getInt("blending_accuracy", 1);

        if (savedRenderer == RENDERER_VULKAN && rbVk != null) rbVk.setChecked(true);
        else if (savedRenderer == RENDERER_SOFTWARE && rbSw != null) rbSw.setChecked(true);
        else if (savedRenderer == RENDERER_OPENGL && rbGl != null) rbGl.setChecked(true);
        else if (rbAuto != null) rbAuto.setChecked(true);

        int scaleIndex = scaleToIndex(savedScale);
        if (scaleIndex < 0 || scaleIndex >= scaleAdapter.getCount()) scaleIndex = 0;
        spScale.setSelection(scaleIndex);

        if (savedBlending < 0 || savedBlending >= blendAdapter.getCount()) savedBlending = 1;
        spBlending.setSelection(savedBlending);

        if (savedAspectRatio >= 0 && savedAspectRatio < aspectAdapter.getCount()) {
            spAspectRatio.setSelection(savedAspectRatio);
        } else {
            spAspectRatio.setSelection(1); // Default to Auto 4:3/3:2
        }

        swWidescreen.setChecked(savedWidescreen);
        swNoInterlacing.setChecked(savedNoInterlacing);
        swLoadTextures.setChecked(savedLoadTextures);
        swAsyncTextureLoading.setChecked(savedAsyncTextureLoading);
        if (swPrecacheTextures != null) swPrecacheTextures.setChecked(savedPrecacheTextures);
        swVsync.setChecked(savedVsync);
        if (swDevHud != null) swDevHud.setChecked(savedHud);
        MaterialSwitch swCheatsGlobal = view.findViewById(R.id.sw_enable_cheats_global);
        if (swCheatsGlobal != null) swCheatsGlobal.setChecked(savedCheatsGlobal);

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(requireContext(),
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog);
        b.setCustomTitle(UiUtils.centeredDialogTitle(requireContext(), "Global Settings"))
         .setView(view)
         .setNegativeButton("Cancel", (d, w) -> d.dismiss())
         .setPositiveButton("Save", (d, w) -> {
             int renderer = RENDERER_AUTO;
             int checked = rgRenderer.getCheckedRadioButtonId();
             if (checked == R.id.rb_renderer_auto) renderer = RENDERER_AUTO;
             else if (checked == R.id.rb_renderer_vk) renderer = RENDERER_VULKAN;
             else if (checked == R.id.rb_renderer_gl) renderer = RENDERER_OPENGL;
             else if (checked == R.id.rb_renderer_sw) renderer = RENDERER_SOFTWARE;

             float scale = indexToScale(spScale.getSelectedItemPosition());
             int aspectRatio = spAspectRatio.getSelectedItemPosition();
             boolean widescreenPatches = swWidescreen.isChecked();
             boolean noInterlacingPatches = swNoInterlacing.isChecked();
             boolean loadTextures = swLoadTextures.isChecked();
            boolean asyncTextureLoading = swAsyncTextureLoading.isChecked();
            boolean precacheTextureReplacements = swPrecacheTextures != null && swPrecacheTextures.isChecked();
            boolean vsyncEnabled = swVsync.isChecked();
            boolean hudVisible = (swDevHud != null && swDevHud.isChecked());
            boolean enableCheatsGlobal = swCheatsGlobal != null && swCheatsGlobal.isChecked();

             // Persist settings to SharedPreferences
             int blendingLevel = spBlending.getSelectedItemPosition();
            prefs.edit()
                    .putInt("renderer", renderer)
                    .putFloat("upscale_multiplier", scale)
                    .putInt("aspect_ratio", aspectRatio)
                    .putInt("blending_accuracy", blendingLevel)
                    .putBoolean("widescreen_patches", widescreenPatches)
                    .putBoolean("no_interlacing_patches", noInterlacingPatches)
                    .putBoolean("load_textures", loadTextures)
                    .putBoolean("async_texture_loading", asyncTextureLoading)
                    .putBoolean("precache_textures", precacheTextureReplacements)
                    .putBoolean("vsync_enabled", vsyncEnabled)
                    .putBoolean("hud_visible", hudVisible)
                    .putBoolean("enable_cheats", enableCheatsGlobal)
                    .apply();

             // Apply in one batch to avoid repeated ApplySettings calls
            try {
                final int rendererToApply = renderer;
                final float scaleToApply = scale;
                final int aspectRatioToApply = aspectRatio;
                final int blendingLevelToApply = blendingLevel;
                final boolean widescreenPatchesToApply = widescreenPatches;
                final boolean noInterlacingPatchesToApply = noInterlacingPatches;
                final boolean loadTexturesToApply = loadTextures;
                final boolean asyncTextureLoadingToApply = asyncTextureLoading;
                final boolean vsyncEnabledToApply = vsyncEnabled;
                final boolean hudVisibleToApply = hudVisible;
                final boolean precacheTextureReplacementsToApply = precacheTextureReplacements;
                NativeApp.runNativeSettingAsync("applyGlobalSettings", () -> {
                    NativeApp.applyGlobalSettingsBatch(rendererToApply, scaleToApply, aspectRatioToApply, blendingLevelToApply,
                            widescreenPatchesToApply, noInterlacingPatchesToApply, loadTexturesToApply,
                            asyncTextureLoadingToApply, vsyncEnabledToApply, hudVisibleToApply);
                    // Apply precache separately (not part of the batch JNI)
                    NativeApp.setPrecacheTextureReplacements(precacheTextureReplacementsToApply);
                });
            } catch (Throwable t) {
                android.util.Log.e("SettingsDialog", "Apply failed: " + t.getMessage());
            }

             // Refresh quick UI (renderer label) if hosting activity is MainActivity
             try {
                 android.app.Activity a = getActivity();
                 if (a instanceof MainActivity) {
                     ((MainActivity) a).runOnUiThread(() -> ((MainActivity) a).refreshQuickUi());
                 }
             } catch (Throwable ignored) {}
         });

        Dialog dialog = b.create();
        
        // Resume game when dialog is dismissed
        dialog.setOnDismissListener(d -> {
            android.util.Log.d("SettingsDialog", "Settings dialog dismissed");
            // Use the global dialog tracking system
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onDialogClosed();
            }
        });
        
        return dialog;
    }

    private static int scaleToIndex(float scale) {
        if (scale <= 1.0f) return 0;
        if (scale <= 2.0f) return 1;
        if (scale <= 3.0f) return 2;
        if (scale <= 4.0f) return 3;
        if (scale <= 5.0f) return 4;
        if (scale <= 6.0f) return 5;
        if (scale <= 7.0f) return 6;
        return 7; // 8x or higher
    }

    private static float indexToScale(int index) {
        switch (index) {
            case 1: return 2.0f;
            case 2: return 3.0f;
            case 3: return 4.0f;
            case 4: return 5.0f;
            case 5: return 6.0f;
            case 6: return 7.0f;
            case 7: return 8.0f;
            case 0:
            default: return 1.0f;
        }
    }
}
