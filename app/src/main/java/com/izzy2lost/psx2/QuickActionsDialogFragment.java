package com.izzy2lost.psx2;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import android.widget.Spinner;
import android.widget.ArrayAdapter;

public class QuickActionsDialogFragment extends DialogFragment {

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        // Notify MainActivity that this dialog is opening
        try {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onDialogOpened();
            }
        } catch (Throwable ignored) {}
        
        View view = getLayoutInflater().inflate(R.layout.dialog_quick_actions, null, false);

        FloatingActionButton btnPower = view.findViewById(R.id.btn_quick_power);
        FloatingActionButton btnReboot = view.findViewById(R.id.btn_quick_reboot);
        com.google.android.material.button.MaterialButtonToggleGroup tgRenderer = view.findViewById(R.id.qa_tg_renderer);
        View tbAt = view.findViewById(R.id.qa_tb_at);
        View tbVk = view.findViewById(R.id.qa_tb_vk);
        View tbGl = view.findViewById(R.id.qa_tb_gl);
        View tbSw = view.findViewById(R.id.qa_tb_sw);
        FloatingActionButton btnPausePlay = view.findViewById(R.id.btn_quick_pause_play);
        MaterialButton btnGames = view.findViewById(R.id.btn_quick_games);
        MaterialButton btnSaves = view.findViewById(R.id.btn_quick_saves);
        MaterialButton btnExitGame = view.findViewById(R.id.btn_quick_exit_game);
        MaterialButton btnCancel = view.findViewById(R.id.btn_cancel);
        Spinner spAspect = view.findViewById(R.id.qa_sp_aspect_ratio);
        Spinner spResolution = view.findViewById(R.id.qa_sp_resolution_scale);
        Spinner spBlending = view.findViewById(R.id.qa_sp_blending_accuracy);
        MaterialSwitch swWide = view.findViewById(R.id.qa_sw_widescreen);
        MaterialSwitch swNoInt = view.findViewById(R.id.qa_sw_no_interlacing);
        MaterialSwitch swLoadTex = view.findViewById(R.id.qa_sw_load_textures);
        MaterialSwitch swAsyncTex = view.findViewById(R.id.qa_sw_async_textures);
        MaterialSwitch swPrecache = view.findViewById(R.id.qa_sw_precache_textures);

        // Power (Quit App)
        if (btnPower != null) {
            btnPower.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext(),
                    com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                    .setCustomTitle(UiUtils.centeredDialogTitle(requireContext(), "Power Off"))
                    .setMessage("Quit PSX2?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Quit", (d, w) -> { quitApp(); dismissAllowingStateLoss(); })
                    .show());
        }

        // Pause/Play toggle
        if (btnPausePlay != null) {
            try {
                boolean isPaused = NativeApp.isPaused();
                btnPausePlay.setImageResource(R.drawable.play_pause_24px);
            } catch (Throwable ignored) {}
            btnPausePlay.setOnClickListener(v -> {
                try {
                    MainActivity mainActivity = UiUtils.getMainActivity(this);
                    if (mainActivity != null) {
                        mainActivity.togglePauseState();
                    } else {
                        boolean paused = NativeApp.isPaused();
                        if (paused) NativeApp.resume(); else NativeApp.pause();
                    }
                    boolean isPausedNow = NativeApp.isPaused();
                    btnPausePlay.setImageResource(R.drawable.play_pause_24px);
                } catch (Throwable ignored) {}
            });
        }

        // Reboot game
        if (btnReboot != null) {
            btnReboot.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext(),
                    com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                    .setCustomTitle(UiUtils.centeredDialogTitle(requireContext(), "Reboot"))
                    .setMessage("Restart the current game?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Reboot", (d, w) -> {
                        MainActivity mainActivity = UiUtils.getMainActivity(this);
                        if (mainActivity != null) {
                            mainActivity.rebootEmu();
                        } else { NativeApp.shutdown(); }
                        dismissAllowingStateLoss();
                    })
                    .show());
        }

        // Renderer toggle: set current and handle changes
        if (tgRenderer != null) {
            int current = -1;
            try { current = NativeApp.getCurrentRenderer(); } catch (Throwable ignored) {}
            if (current == 14 && tbVk != null) tgRenderer.check(tbVk.getId());
            else if (current == 12 && tbGl != null) tgRenderer.check(tbGl.getId());
            else if (current == 13 && tbSw != null) tgRenderer.check(tbSw.getId());
            else if (tbAt != null) tgRenderer.check(tbAt.getId());

            tgRenderer.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked) return;
                int r = -1;
                if (checkedId == (tbVk != null ? tbVk.getId() : -2)) r = 14;
                else if (checkedId == (tbGl != null ? tbGl.getId() : -2)) r = 12;
                else if (checkedId == (tbSw != null ? tbSw.getId() : -2)) r = 13;
                else r = -1;
                try {
                    int currentRenderer = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .getInt("renderer", -1);
                    if (currentRenderer == r) return;
                    requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putInt("renderer", r).apply();
                    NativeApp.renderGpuAsync(r);
                } catch (Throwable ignored) {}
            });
        }

        // Aspect Ratio spinner
        if (spAspect != null) {
            ArrayAdapter<CharSequence> aspectAdapter = ArrayAdapter.createFromResource(requireContext(),
                    R.array.aspect_ratio_entries, android.R.layout.simple_spinner_item);
            aspectAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spAspect.setAdapter(aspectAdapter);
            int savedAspect = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    .getInt("aspect_ratio", 1);
            if (savedAspect < 0 || savedAspect >= aspectAdapter.getCount()) savedAspect = 1;
            spAspect.setSelection(savedAspect);
            spAspect.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view1, int position, long id) {
                    int current = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .getInt("aspect_ratio", 1);
                    if (current == position) return;
                    requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putInt("aspect_ratio", position).apply();
                    NativeApp.setAspectRatioAsync(position);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }

        // Resolution Scale spinner
        if (spResolution != null) {
            ArrayAdapter<CharSequence> scaleAdapter = ArrayAdapter.createFromResource(requireContext(),
                    R.array.scale_entries, android.R.layout.simple_spinner_item);
            scaleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spResolution.setAdapter(scaleAdapter);
            float savedScale = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    .getFloat("upscale_multiplier", 1.0f);
            int scaleIndex = Math.max(0, Math.min(scaleAdapter.getCount() - 1, Math.round(savedScale) - 1));
            spResolution.setSelection(scaleIndex);
            spResolution.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view1, int position, long id) {
                    float scale = Math.max(1, Math.min(8, position + 1));
                    float current = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .getFloat("upscale_multiplier", 1.0f);
                    if (Math.abs(current - scale) < 0.001f) return;
                    requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putFloat("upscale_multiplier", scale).apply();
                    NativeApp.renderUpscalemultiplierAsync(scale);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }

        // Blending Accuracy spinner
        if (spBlending != null) {
            ArrayAdapter<CharSequence> blendAdapter = ArrayAdapter.createFromResource(requireContext(),
                    R.array.blending_accuracy_entries, android.R.layout.simple_spinner_item);
            blendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spBlending.setAdapter(blendAdapter);
            int savedBlend = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    .getInt("blending_accuracy", 1);
            if (savedBlend < 0 || savedBlend >= blendAdapter.getCount()) savedBlend = 1;
            spBlending.setSelection(savedBlend);
            spBlending.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view12, int position, long id) {
                    int current = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .getInt("blending_accuracy", 1);
                    if (current == position) return;
                    requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                            .edit().putInt("blending_accuracy", position).apply();
                    NativeApp.setBlendingAccuracyAsync(position);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }

        // Switches: widescreen, no interlacing, textures
        final android.content.SharedPreferences prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE);
        if (swWide != null) {
            swWide.setChecked(prefs.getBoolean("widescreen_patches", true));
            swWide.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked == prefs.getBoolean("widescreen_patches", true)) return;
                prefs.edit().putBoolean("widescreen_patches", isChecked).apply();
                NativeApp.setWidescreenPatchesAsync(isChecked);
            });
        }
        if (swNoInt != null) {
            swNoInt.setChecked(prefs.getBoolean("no_interlacing_patches", true));
            swNoInt.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked == prefs.getBoolean("no_interlacing_patches", true)) return;
                prefs.edit().putBoolean("no_interlacing_patches", isChecked).apply();
                NativeApp.setNoInterlacingPatchesAsync(isChecked);
            });
        }
        if (swLoadTex != null) {
            swLoadTex.setChecked(prefs.getBoolean("load_textures", false));
            swLoadTex.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked == prefs.getBoolean("load_textures", false)) return;
                prefs.edit().putBoolean("load_textures", isChecked).apply();
                NativeApp.setLoadTexturesAsync(isChecked);
            });
        }
        if (swAsyncTex != null) {
            swAsyncTex.setChecked(prefs.getBoolean("async_texture_loading", true));
            swAsyncTex.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked == prefs.getBoolean("async_texture_loading", true)) return;
                prefs.edit().putBoolean("async_texture_loading", isChecked).apply();
                NativeApp.setAsyncTextureLoadingAsync(isChecked);
            });
        }
        if (swPrecache != null) {
            swPrecache.setChecked(prefs.getBoolean("precache_textures", false));
            swPrecache.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked == prefs.getBoolean("precache_textures", false)) return;
                prefs.edit().putBoolean("precache_textures", isChecked).apply();
                NativeApp.setPrecacheTextureReplacementsAsync(isChecked);
            });
        }

        // Games: open covers dialog
        if (btnGames != null) {
            btnGames.setOnClickListener(v -> {
                MainActivity mainActivity = UiUtils.getMainActivity(this);
                if (mainActivity != null) {
                    mainActivity.openGamesDialog();
                }
                dismissAllowingStateLoss();
            });
        }

        // Saves: open save states dialog
        if (btnSaves != null) {
            btnSaves.setOnClickListener(v -> {
                try { new SavesDialogFragment().show(getParentFragmentManager(), "saves_dialog"); } catch (Throwable ignored) {}
                dismissAllowingStateLoss();
            });
        }

        // Memory Cards: open memory card manager dialog
        MaterialButton btnMemcards = view.findViewById(R.id.btn_quick_memcards);
        if (btnMemcards != null) {
            btnMemcards.setOnClickListener(v -> {
                try { new MemoryCardManagerDialogFragment().show(getParentFragmentManager(), "memcard_manager_dialog"); } catch (Throwable ignored) {}
                dismissAllowingStateLoss();
            });
        }

        // Achievements: open achievements dialog
        MaterialButton btnAchievements = view.findViewById(R.id.btn_quick_achievements);
        if (btnAchievements != null) {
            btnAchievements.setOnClickListener(v -> {
                try { AchievementsDialogFragment.newInstance().show(getParentFragmentManager(), "achievements_dialog"); } catch (Throwable ignored) {}
                dismissAllowingStateLoss();
            });
        }

        // Exit Game: open games dialog
        if (btnExitGame != null) {
            btnExitGame.setOnClickListener(v -> {
                try {
                    // Capture a stable Activity reference before dismissing the dialog
                    final android.app.Activity activity = getActivity();

                    // Close this dialog
                    dismissAllowingStateLoss();

                    // Open games dialog after a short delay, using the captured Activity
                    if (activity instanceof MainActivity) {
                        android.view.View root = activity.findViewById(android.R.id.content);
                        if (root != null) {
                            root.postDelayed(() -> {
                                try {
                                    if (!activity.isFinishing()) {
                                        MainActivity mainActivity = (MainActivity) activity;
                                        mainActivity.openGamesDialog();
                                    }
                                } catch (Throwable ignored) {}
                            }, 300);
                        }
                    }
                } catch (Throwable ignored) {}
            });
        }

        // Cancel button
        if (btnCancel != null) btnCancel.setOnClickListener(v -> dismissAllowingStateLoss());

        return new MaterialAlertDialogBuilder(requireContext(),
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setView(view)
                .create();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Dialog is being destroyed - resume the game
        android.util.Log.d("QuickActionsDialog", "onDestroy called - resuming game");
        try {
            if (getActivity() != null && !getActivity().isFinishing() && getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onDialogClosed();
            }
        } catch (Throwable e) {
            android.util.Log.e("QuickActionsDialog", "Error in onDestroy: " + e.getMessage());
        }
    }
    
    private void quitApp() {
        // Stop emulator first
        NativeApp.shutdown();
        // Quit the whole app/activity task
        if (getActivity() != null) {
            getActivity().finishAffinity();
            getActivity().finishAndRemoveTask();
        }
        // As a fallback ensure process exit
        System.exit(0);
    }
}
