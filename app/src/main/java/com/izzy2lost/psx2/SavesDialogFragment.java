package com.izzy2lost.psx2;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
// Import MaterialAlertDialogBuilder for Material 3 dialogs
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.fragment.app.DialogFragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SavesDialogFragment extends DialogFragment {

    public static class SaveSlot {
        public int slot;
        public String title;
        public String timestamp;
        public byte[] screenshot;
        public boolean isEmpty;
        public String gamePath;

        public SaveSlot(int slot, String gamePath) {
            this.slot = slot;
            this.gamePath = gamePath;
            this.isEmpty = true;
            this.title = "Empty Slot " + slot;
            this.timestamp = "";
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

        // Create save slots (1-10)
        List<SaveSlot> saveSlots = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            SaveSlot slot = new SaveSlot(i, NativeApp.getGamePathSlot(i));
            
            // Check if slot has data and get screenshot
            byte[] screenshot = NativeApp.getImageSlot(i);
            if (screenshot != null && screenshot.length > 0) {
                slot.isEmpty = false;
                slot.screenshot = screenshot;
                slot.title = "Save Slot " + i;
                
                // Create a reasonable timestamp (you might want to get this from native code)
                slot.timestamp = "Saved " + SimpleDateFormat.getDateTimeInstance(
                    SimpleDateFormat.SHORT, SimpleDateFormat.SHORT, Locale.getDefault())
                    .format(new Date());
            }
            
            saveSlots.add(slot);
        }

        // Slot list is rendered with Jetpack Compose (SaveStatesScreen.kt)
        View view = SaveStatesComposeView.create(ctx, saveSlots,
                slot -> {
                    if (NativeApp.saveStateToSlot(slot)) {
                        // Success - close the dialog
                        dismiss();
                    }
                },
                slot -> {
                    if (NativeApp.loadStateFromSlot(slot)) {
                        // Success
                        dismiss();
                    }
                });

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog);
        builder.setTitle("Save States")
               .setView(view)
               .setNegativeButton("Cancel", (d, w) -> d.dismiss());

        return builder.create();
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        // Dialog is being destroyed - resume the game
        android.util.Log.d("SavesDialog", "onDestroy called - resuming game");
        try {
            if (getActivity() != null && !getActivity().isFinishing() && getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onDialogClosed();
            }
        } catch (Throwable e) {
            android.util.Log.e("SavesDialog", "Error in onDestroy: " + e.getMessage());
        }
    }
}
