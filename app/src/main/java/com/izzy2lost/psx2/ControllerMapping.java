package com.izzy2lost.psx2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.SparseIntArray;
import android.view.KeyEvent;

/**
 * User-configurable mapping between physical controller buttons and PS2 pad buttons.
 *
 * The PS2 pad targets are identified by the same constants {@link ControllerInputHandler}
 * and {@link MainActivity#onControllerButtonPressed} already use, so a mapping entry is
 * "PS2 target <- physical Android keycode". Defaults are the identity mapping, which is
 * what the handler used to hardcode.
 */
public final class ControllerMapping {

    private static final String PREFS = "app_prefs";
    private static final String KEY_PREFIX = "ctrl_map_";

    /** Stored against a target that the user has deliberately left unbound. */
    public static final int UNASSIGNED = KeyEvent.KEYCODE_UNKNOWN; // 0

    /** PS2 pad targets, in the order they are presented in the UI. */
    public static final int[] TARGETS = {
            ControllerInputHandler.PAD_CROSS,
            ControllerInputHandler.PAD_CIRCLE,
            ControllerInputHandler.PAD_SQUARE,
            ControllerInputHandler.PAD_TRIANGLE,
            ControllerInputHandler.PAD_L1,
            ControllerInputHandler.PAD_R1,
            ControllerInputHandler.PAD_L2,
            ControllerInputHandler.PAD_R2,
            ControllerInputHandler.PAD_L3,
            ControllerInputHandler.PAD_R3,
            ControllerInputHandler.PAD_SELECT,
            ControllerInputHandler.PAD_START,
            ControllerInputHandler.PAD_UP,
            ControllerInputHandler.PAD_DOWN,
            ControllerInputHandler.PAD_LEFT,
            ControllerInputHandler.PAD_RIGHT,
    };

    private ControllerMapping() {}

    /** Human label for a PS2 pad target. */
    public static String getTargetLabel(int target) {
        switch (target) {
            case ControllerInputHandler.PAD_CROSS: return "Cross  ✕";
            case ControllerInputHandler.PAD_CIRCLE: return "Circle  ○";
            case ControllerInputHandler.PAD_SQUARE: return "Square  □";
            case ControllerInputHandler.PAD_TRIANGLE: return "Triangle  △";
            case ControllerInputHandler.PAD_L1: return "L1";
            case ControllerInputHandler.PAD_R1: return "R1";
            case ControllerInputHandler.PAD_L2: return "L2";
            case ControllerInputHandler.PAD_R2: return "R2";
            case ControllerInputHandler.PAD_L3: return "L3  (left stick click)";
            case ControllerInputHandler.PAD_R3: return "R3  (right stick click)";
            case ControllerInputHandler.PAD_SELECT: return "Select";
            case ControllerInputHandler.PAD_START: return "Start";
            case ControllerInputHandler.PAD_UP: return "D-Pad Up";
            case ControllerInputHandler.PAD_DOWN: return "D-Pad Down";
            case ControllerInputHandler.PAD_LEFT: return "D-Pad Left";
            case ControllerInputHandler.PAD_RIGHT: return "D-Pad Right";
            default: return "Button " + target;
        }
    }

    /** Friendly name for a physical controller keycode. */
    public static String describeKey(int keyCode) {
        if (keyCode == UNASSIGNED) return "Not set";
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "L1 / LB";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R1 / RB";
            case KeyEvent.KEYCODE_BUTTON_L2: return "L2 / LT";
            case KeyEvent.KEYCODE_BUTTON_R2: return "R2 / RT";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "Left stick click";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "Right stick click";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "Select / Back";
            case KeyEvent.KEYCODE_BUTTON_START: return "Start / Menu";
            case KeyEvent.KEYCODE_BUTTON_MODE: return "Mode / Home";
            case KeyEvent.KEYCODE_DPAD_UP: return "D-Pad Up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "D-Pad Down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "D-Pad Left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "D-Pad Right";
            case KeyEvent.KEYCODE_DPAD_CENTER: return "D-Pad Click";
            case KeyEvent.KEYCODE_BUTTON_C: return "C";
            case KeyEvent.KEYCODE_BUTTON_Z: return "Z";
            default: {
                String s = KeyEvent.keyCodeToString(keyCode);
                if (s != null && s.startsWith("KEYCODE_")) s = s.substring(8).replace('_', ' ');
                return s != null ? s : ("Key " + keyCode);
            }
        }
    }

    /** The stock mapping: every target driven by its like-named physical button. */
    public static int getDefaultKey(int target) {
        // The PAD_* constants are themselves Android keycodes, so the stock mapping
        // is the identity. Kept as a method so callers do not rely on that detail.
        return target;
    }

    /** Current mapping as target -> physical keycode. */
    public static SparseIntArray load(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SparseIntArray map = new SparseIntArray(TARGETS.length);
        for (int target : TARGETS) {
            map.put(target, prefs.getInt(KEY_PREFIX + target, getDefaultKey(target)));
        }
        return map;
    }

    /** Physical keycode currently bound to a target, or {@link #UNASSIGNED}. */
    public static int getKeyForTarget(Context ctx, int target) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PREFIX + target, getDefaultKey(target));
    }

    /**
     * Bind {@code keyCode} to {@code target}. A keycode can only drive one target, so any
     * other target already using it is unbound and returned (or -1 when there was no clash).
     */
    public static int assign(Context ctx, int target, int keyCode) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();

        int displaced = -1;
        if (keyCode != UNASSIGNED) {
            for (int other : TARGETS) {
                if (other == target) continue;
                if (prefs.getInt(KEY_PREFIX + other, getDefaultKey(other)) == keyCode) {
                    ed.putInt(KEY_PREFIX + other, UNASSIGNED);
                    displaced = other;
                }
            }
        }

        ed.putInt(KEY_PREFIX + target, keyCode).apply();
        ControllerInputHandler.reloadMapping(ctx);
        return displaced;
    }

    /** Clear a single binding. */
    public static void clear(Context ctx, int target) {
        assign(ctx, target, UNASSIGNED);
    }

    /** Restore every target to its stock binding. */
    public static void resetToDefaults(Context ctx) {
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (int target : TARGETS) {
            ed.remove(KEY_PREFIX + target);
        }
        ed.apply();
        ControllerInputHandler.reloadMapping(ctx);
    }

    /** True when nothing has been customised. */
    public static boolean isDefault(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (int target : TARGETS) {
            if (prefs.getInt(KEY_PREFIX + target, getDefaultKey(target)) != getDefaultKey(target)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The lookup the input handler needs: physical keycode -> PS2 target.
     * Unassigned targets contribute nothing.
     */
    public static SparseIntArray buildKeyToTarget(Context ctx) {
        SparseIntArray fwd = load(ctx);
        SparseIntArray out = new SparseIntArray(fwd.size());
        for (int i = 0; i < fwd.size(); i++) {
            int target = fwd.keyAt(i);
            int keyCode = fwd.valueAt(i);
            if (keyCode != UNASSIGNED) out.put(keyCode, target);
        }
        return out;
    }
}
