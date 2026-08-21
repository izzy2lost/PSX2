package com.izzy2lost.psx2;

import android.app.Dialog;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Controller setup: shows which controllers are connected, lets the user rebind every
 * PS2 button, and echoes live input so a binding can be verified on the spot.
 */
public class ControllerTestDialogFragment extends DialogFragment
        implements InputManager.InputDeviceListener {

    /** No rebind in progress. */
    private static final int NOT_LISTENING = -1;

    private TextView mControllerListText;
    private TextView mInputLogText;
    private LinearLayout mMappingRows;

    private final Deque<String> mInputLog = new ArrayDeque<>();
    private static final int MAX_LOG_LINES = 8;

    /** PS2 target awaiting a button press, or {@link #NOT_LISTENING}. */
    private int mListeningTarget = NOT_LISTENING;

    private InputManager mInputManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public static ControllerTestDialogFragment newInstance() {
        return new ControllerTestDialogFragment();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        try {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onDialogOpened();
            }
        } catch (Throwable ignored) {}

        View view = getLayoutInflater().inflate(R.layout.dialog_controller_test, null);

        mControllerListText = view.findViewById(R.id.tv_controller_list);
        mInputLogText = view.findViewById(R.id.tv_input_log);
        mMappingRows = view.findViewById(R.id.ll_mapping_rows);

        buildMappingRows();
        updateControllerList();

        Button reset = view.findViewById(R.id.btn_reset_mapping);
        if (reset != null) {
            reset.setOnClickListener(v -> {
                ControllerMapping.resetToDefaults(requireContext());
                mListeningTarget = NOT_LISTENING;
                refreshAllRows();
                toast("Mapping reset to defaults");
            });
        }

        Dialog dialog = new MaterialAlertDialogBuilder(requireContext(),
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle("Controller Setup")
                .setView(view)
                .setPositiveButton("Close", null)
                .create();

        // Controller keys reach the dialog because MainActivity.dispatchKeyEvent skips
        // gameplay handling while a dialog is showing.
        dialog.setOnKeyListener((d, keyCode, event) -> onControllerKey(keyCode, event));

        dialog.setOnDismissListener(d -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onDialogClosed();
            }
        });

        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        // Live controller detection.
        try {
            mInputManager = (InputManager) requireContext().getSystemService(Context.INPUT_SERVICE);
            if (mInputManager != null) mInputManager.registerInputDeviceListener(this, mHandler);
        } catch (Throwable ignored) {}

        // Analog sticks and triggers arrive as motion events on the dialog's window.
        try {
            Dialog d = getDialog();
            if (d != null && d.getWindow() != null) {
                View decor = d.getWindow().getDecorView();
                decor.setOnGenericMotionListener((v, event) -> onControllerMotion(event));
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onStop() {
        try {
            if (mInputManager != null) mInputManager.unregisterInputDeviceListener(this);
        } catch (Throwable ignored) {}
        super.onStop();
    }

    // ---- controller detection -------------------------------------------------

    @Override public void onInputDeviceAdded(int deviceId) { updateControllerList(); }
    @Override public void onInputDeviceRemoved(int deviceId) { updateControllerList(); }
    @Override public void onInputDeviceChanged(int deviceId) { updateControllerList(); }

    private void updateControllerList() {
        if (mControllerListText == null) return;
        List<ControllerConfig.ControllerInfo> controllers = ControllerConfig.getConnectedControllers();

        if (controllers.isEmpty()) {
            mControllerListText.setText(
                    "No controllers detected.\n\nConnect a controller over Bluetooth or USB, "
                            + "then press one of its buttons. This list updates automatically.");
        } else {
            StringBuilder sb = new StringBuilder();
            for (ControllerConfig.ControllerInfo c : controllers) {
                sb.append("• ").append(c.name).append("\n    ")
                  .append(c.type).append("  (device ").append(c.deviceId).append(")\n");
            }
            mControllerListText.setText(sb.toString().trim());
        }
    }

    // ---- mapping rows ---------------------------------------------------------

    private void buildMappingRows() {
        if (mMappingRows == null) return;
        mMappingRows.removeAllViews();

        for (int target : ControllerMapping.TARGETS) {
            View row = getLayoutInflater().inflate(
                    R.layout.item_controller_mapping, mMappingRows, false);
            TextView label = row.findViewById(R.id.tv_map_target);
            label.setText(ControllerMapping.getTargetLabel(target));
            row.setTag(target);

            row.setOnClickListener(v -> startListening((Integer) v.getTag()));
            row.setOnLongClickListener(v -> {
                int t = (Integer) v.getTag();
                ControllerMapping.clear(requireContext(), t);
                if (mListeningTarget == t) mListeningTarget = NOT_LISTENING;
                refreshAllRows();
                toast(ControllerMapping.getTargetLabel(t) + " cleared");
                return true;
            });

            mMappingRows.addView(row);
            refreshRow(row, target);
        }
    }

    private void startListening(int target) {
        mListeningTarget = target;
        refreshAllRows();
    }

    private void refreshAllRows() {
        if (mMappingRows == null) return;
        for (int i = 0; i < mMappingRows.getChildCount(); i++) {
            View row = mMappingRows.getChildAt(i);
            Object tag = row.getTag();
            if (tag instanceof Integer) refreshRow(row, (Integer) tag);
        }
    }

    private void refreshRow(View row, int target) {
        TextView binding = row.findViewById(R.id.tv_map_binding);
        if (binding == null) return;

        if (mListeningTarget == target) {
            binding.setText("Press a button…");
            binding.setAlpha(1.0f);
        } else {
            int key = ControllerMapping.getKeyForTarget(requireContext(), target);
            binding.setText(ControllerMapping.describeKey(key));
            binding.setAlpha(key == ControllerMapping.UNASSIGNED ? 0.5f : 1.0f);
        }
    }

    // ---- input handling -------------------------------------------------------

    private boolean isFromController(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (device == null) return false;
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private boolean onControllerKey(int keyCode, KeyEvent event) {
        // Leave system keys (and anything not from a pad) alone so Back still closes.
        if (!isFromController(event)) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (mListeningTarget != NOT_LISTENING) {
                assignBinding(mListeningTarget, keyCode);
            } else {
                logInput(describeIncoming(keyCode));
            }
        }
        // Consume both down and up: gamepad B would otherwise dismiss the dialog
        // mid-rebind, and released keys must not leak through to the game.
        return true;
    }

    private void assignBinding(int target, int keyCode) {
        int displaced = ControllerMapping.assign(requireContext(), target, keyCode);
        mListeningTarget = NOT_LISTENING;
        refreshAllRows();

        String msg = ControllerMapping.getTargetLabel(target) + "  ←  "
                + ControllerMapping.describeKey(keyCode);
        if (displaced != -1) {
            msg += "\n(cleared from " + ControllerMapping.getTargetLabel(displaced) + ")";
        }
        toast(msg);
    }

    /** For the live test: what this physical key currently drives. */
    private String describeIncoming(int keyCode) {
        int target = ControllerMapping.buildKeyToTarget(requireContext())
                .get(keyCode, Integer.MIN_VALUE);
        String physical = ControllerMapping.describeKey(keyCode);
        return target == Integer.MIN_VALUE
                ? physical + "  →  (unbound)"
                : physical + "  →  " + ControllerMapping.getTargetLabel(target);
    }

    private boolean onControllerMotion(MotionEvent event) {
        if (event.getDevice() == null) return false;
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) {
            return false;
        }
        if (mListeningTarget != NOT_LISTENING) return false; // sticks are not bindable

        reportAxis("Left stick", event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y));
        reportAxis("Right stick", event.getAxisValue(MotionEvent.AXIS_Z),
                event.getAxisValue(MotionEvent.AXIS_RZ));

        float lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
        if (lt > 0.2f) logInput(String.format(Locale.ROOT, "L2 trigger  %.2f", lt));
        float rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
        if (rt > 0.2f) logInput(String.format(Locale.ROOT, "R2 trigger  %.2f", rt));

        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(hatX) > 0.5f || Math.abs(hatY) > 0.5f) {
            logInput("D-Pad (hat)  " + (hatX < -0.5f ? "left" : hatX > 0.5f ? "right" : "")
                    + (hatY < -0.5f ? "up" : hatY > 0.5f ? "down" : ""));
        }
        return true;
    }

    private void reportAxis(String name, float x, float y) {
        if (Math.abs(x) > 0.3f || Math.abs(y) > 0.3f) {
            logInput(String.format(Locale.ROOT, "%s  x=%+.2f  y=%+.2f", name, x, y));
        }
    }

    private void logInput(String line) {
        if (mInputLogText == null) return;
        if (!mInputLog.isEmpty() && line.equals(mInputLog.peekLast())) return; // collapse repeats
        mInputLog.addLast(line);
        while (mInputLog.size() > MAX_LOG_LINES) mInputLog.removeFirst();

        StringBuilder sb = new StringBuilder();
        for (String s : mInputLog) sb.append(s).append('\n');
        mInputLogText.setText(sb.toString());
    }

    private void toast(String msg) {
        try {
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }
}
