package com.izzy2lost.psx2;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
// Import MaterialAlertDialogBuilder for Material 3 dialogs
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.fragment.app.DialogFragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SavesDialogFragment extends DialogFragment {

    private static final ExecutorService SLOT_ACTION_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "SaveStateAction");
                thread.setDaemon(true);
                return thread;
            });

    private boolean mSlotActionRunning = false;

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
                slot -> runSlotAction(true, slot),
                slot -> runSlotAction(false, slot));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(ctx,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog);
        builder.setTitle("Save States")
               .setView(view)
               .setNegativeButton("Cancel", (d, w) -> d.dismiss());

        return builder.create();
    }

    // Saving or loading waits for the emulation thread to settle and then writes or
    // reads the whole state, which can take seconds. Doing that on the UI thread
    // was an ANR, so it runs on a worker while the dialog stays open and locked:
    // closing it would resume the game in the middle of the save.
    private void runSlotAction(boolean save, int slot) {
        if (mSlotActionRunning) return;
        mSlotActionRunning = true;
        setDialogLocked(true);
        Toast.makeText(requireContext(), save ? "Saving…" : "Loading…", Toast.LENGTH_SHORT).show();

        SLOT_ACTION_EXECUTOR.execute(() -> {
            boolean success;
            try {
                success = save ? NativeApp.saveStateToSlot(slot) : NativeApp.loadStateFromSlot(slot);
            } catch (Throwable error) {
                android.util.Log.e("SavesDialog", "Save state action failed", error);
                success = false;
            }
            final boolean result = success;
            UiUtils.postIfFragmentAttached(this, () -> {
                mSlotActionRunning = false;
                setDialogLocked(false);
                if (result) {
                    dismissAllowingStateLoss();
                } else {
                    Toast.makeText(requireContext(),
                            save ? "Couldn't save state" : "Couldn't load state",
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void setDialogLocked(boolean locked) {
        setCancelable(!locked);
        Dialog dialog = getDialog();
        if (dialog instanceof AlertDialog) {
            Button cancel = ((AlertDialog) dialog).getButton(DialogInterface.BUTTON_NEGATIVE);
            if (cancel != null) cancel.setEnabled(!locked);
        }
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
