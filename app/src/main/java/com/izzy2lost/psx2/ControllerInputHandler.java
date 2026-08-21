package com.izzy2lost.psx2;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

/**
 * Controller input handler based on AetherSX2's PAD implementation
 * Maps Android controller inputs to PS2 controller buttons
 */
public class ControllerInputHandler {
    private static final String TAG = "ControllerInput";
    
    // PS2 Controller button constants (using Android KeyEvent codes that MainActivity expects)
    public static final int PAD_L2 = KeyEvent.KEYCODE_BUTTON_L2;
    public static final int PAD_R2 = KeyEvent.KEYCODE_BUTTON_R2;
    public static final int PAD_L1 = KeyEvent.KEYCODE_BUTTON_L1;
    public static final int PAD_R1 = KeyEvent.KEYCODE_BUTTON_R1;
    public static final int PAD_TRIANGLE = KeyEvent.KEYCODE_BUTTON_Y;
    public static final int PAD_CIRCLE = KeyEvent.KEYCODE_BUTTON_B;
    public static final int PAD_CROSS = KeyEvent.KEYCODE_BUTTON_A;
    public static final int PAD_SQUARE = KeyEvent.KEYCODE_BUTTON_X;
    public static final int PAD_SELECT = KeyEvent.KEYCODE_BUTTON_SELECT;
    public static final int PAD_L3 = KeyEvent.KEYCODE_BUTTON_THUMBL;
    public static final int PAD_R3 = KeyEvent.KEYCODE_BUTTON_THUMBR;
    public static final int PAD_START = KeyEvent.KEYCODE_BUTTON_START;
    public static final int PAD_UP = KeyEvent.KEYCODE_DPAD_UP;
    public static final int PAD_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT;
    public static final int PAD_DOWN = KeyEvent.KEYCODE_DPAD_DOWN;
    public static final int PAD_LEFT = KeyEvent.KEYCODE_DPAD_LEFT;
    // Analog stick mapping (using MainActivity's custom codes)
    public static final int PAD_L_UP = 110;
    public static final int PAD_L_RIGHT = 111;
    public static final int PAD_L_DOWN = 112;
    public static final int PAD_L_LEFT = 113;
    public static final int PAD_R_UP = 120;
    public static final int PAD_R_RIGHT = 121;
    public static final int PAD_R_DOWN = 122;
    public static final int PAD_R_LEFT = 123;
    
    // Analog stick deadzone (matching AetherSX2's default)
    private static final float ANALOG_DEADZONE = 0.15f;
    
    // Button combo detection
    private boolean mSelectPressed = false;
    private boolean mStartPressed = false;
    private long mComboDetectionTime = 0;
    private static final long COMBO_TIMEOUT_MS = 500; // 500ms window for combo
    
    // Key mapping from Android KeyEvent to PS2 buttons
    private static final SparseIntArray sKeyMapping = new SparseIntArray();
    
    // Motion axis mapping from Android MotionEvent to PS2 analog inputs
    private static final SparseIntArray sAxisMapping = new SparseIntArray();
    
    static {
        // Initialize key mappings (Android KeyEvent -> PS2 button)
        // Since our constants now match Android keycodes, we can map directly
        sKeyMapping.put(KeyEvent.KEYCODE_BUTTON_A, PAD_CROSS);      // A -> Cross (X)
        sKeyMapping.put(KeyEvent.KEYCODE_BUTTON_B, PAD_CIRCLE);     // B -> Circle
        sKeyMapping.put(KeyEvent.KEYCODE_BUTTON_X, PAD_SQUARE);     // X -> Square
        sKeyMapping.put(KeyEvent.KEYCODE_BUTTON_Y, PAD_TRIANGLE);   // Y -> Triangle
        
        sKeyMapping.put(KeyEvent.KEYCODE_BUTTON_L1, PAD_L1);
        sKeyMapping.put(KeyEvent.KEYCODE_BUTTON_R1, PAD_R1);
        sKeyMapping.put(KeyEvent.KEYCODE_BUTTON_L2, PAD_L2);
        sKeyMapping.put(KeyEvent.KEYCODE_BUTTON_R2, PAD_R2);
        
        sKeyMapping.put(KeyEvent.KEYCODE_BUTTON_THUMBL, PAD_L3);
        sKeyMapping.put(KeyEvent.KEYCODE_BUTTON_THUMBR, PAD_R3);
        
        sKeyMapping.put(KeyEvent.KEYCODE_BUTTON_SELECT, PAD_SELECT);
        sKeyMapping.put(KeyEvent.KEYCODE_BUTTON_START, PAD_START);
        
        // D-Pad
        sKeyMapping.put(KeyEvent.KEYCODE_DPAD_UP, PAD_UP);
        sKeyMapping.put(KeyEvent.KEYCODE_DPAD_DOWN, PAD_DOWN);
        sKeyMapping.put(KeyEvent.KEYCODE_DPAD_LEFT, PAD_LEFT);
        sKeyMapping.put(KeyEvent.KEYCODE_DPAD_RIGHT, PAD_RIGHT);
        
        // Initialize axis mappings for analog sticks
        // Map to our custom analog codes that MainActivity expects
        sAxisMapping.put(MotionEvent.AXIS_X, PAD_L_LEFT);           // Left stick X (negative = left)
        sAxisMapping.put(MotionEvent.AXIS_Y, PAD_L_UP);             // Left stick Y (negative = up)
        sAxisMapping.put(MotionEvent.AXIS_Z, PAD_R_LEFT);           // Right stick X (negative = left)
        sAxisMapping.put(MotionEvent.AXIS_RZ, PAD_R_UP);            // Right stick Y (negative = up)
        sAxisMapping.put(MotionEvent.AXIS_LTRIGGER, PAD_L2);        // Left trigger
        sAxisMapping.put(MotionEvent.AXIS_RTRIGGER, PAD_R2);        // Right trigger
    }
    
    /**
     * Re-read the user's button mapping. The static defaults above are the stock
     * layout; this replaces them with whatever the user configured in
     * {@link ControllerMapping}, and is called on startup and whenever a binding
     * changes so remaps take effect without restarting.
     */
    public static synchronized void reloadMapping(Context ctx) {
        if (ctx == null) return;
        SparseIntArray configured = ControllerMapping.buildKeyToTarget(ctx);
        sKeyMapping.clear();
        for (int i = 0; i < configured.size(); i++) {
            sKeyMapping.put(configured.keyAt(i), configured.valueAt(i));
        }
        Log.d(TAG, "Reloaded controller mapping: " + configured.size() + " bindings");
    }

    /**
     * PS2 target currently driven by a physical key, or {@link Integer#MIN_VALUE} when
     * that key is unbound. Used for inputs that arrive as axes rather than keycodes
     * (HAT D-pads, analog triggers) so they honour the same remapping as buttons.
     */
    private static synchronized int mappedTarget(int physicalKeyCode) {
        return sKeyMapping.get(physicalKeyCode, Integer.MIN_VALUE);
    }

    public interface ControllerInputListener {
        void onControllerButtonPressed(int controllerId, int button, boolean pressed);
        void onControllerAnalogInput(int controllerId, int axis, float value);
        void onControllerCombo(int controllerId, String comboName);
    }
    
    private ControllerInputListener mListener;

    public ControllerInputHandler(ControllerInputListener listener) {
        mListener = listener;
    }

    // Track D-pad state per controller (for HAT axes)
    private static class DpadState {
        boolean up;
        boolean down;
        boolean left;
        boolean right;
    }
    private final SparseArray<DpadState> mDpadStates = new SparseArray<>();
    private DpadState getDpadState(int controllerId) {
        DpadState s = mDpadStates.get(controllerId);
        if (s == null) {
            s = new DpadState();
            mDpadStates.put(controllerId, s);
        }
        return s;
    }
    
    /**
     * Handle key events from controllers
     */
    public boolean handleKeyEvent(KeyEvent event) {
        if (!isFromController(event)) {
            return false;
        }
        
        int keyCode = event.getKeyCode();
        int ps2Button = sKeyMapping.get(keyCode, Integer.MIN_VALUE);
        
        if (ps2Button != Integer.MIN_VALUE) {
            int controllerId = event.getDeviceId();
            boolean pressed = (event.getAction() == KeyEvent.ACTION_DOWN);
            
            // Check for Select+Start combo
            if (handleButtonCombo(controllerId, ps2Button, pressed)) {
                return true; // Combo detected, don't send individual button presses
            }
            
            Log.d(TAG, "Controller " + controllerId + " button " + ps2Button + " " + (pressed ? "pressed" : "released"));
            
            if (mListener != null) {
                mListener.onControllerButtonPressed(controllerId, ps2Button, pressed);
            }
            return true;
        }
        
        return false;
    }
    
    /**
     * Handle button combinations (like Select+Start)
     */
    private boolean handleButtonCombo(int controllerId, int button, boolean pressed) {
        long currentTime = System.currentTimeMillis();
        
        // Track Select and Start button states
        if (button == PAD_SELECT) {
            mSelectPressed = pressed;
            if (pressed) mComboDetectionTime = currentTime;
        } else if (button == PAD_START) {
            mStartPressed = pressed;
            if (pressed) mComboDetectionTime = currentTime;
        }
        
        // Check if both buttons are pressed within the timeout window
        if (mSelectPressed && mStartPressed && 
            (currentTime - mComboDetectionTime) < COMBO_TIMEOUT_MS) {
            
            Log.d(TAG, "Select+Start combo detected!");
            
            // Reset combo state
            mSelectPressed = false;
            mStartPressed = false;
            
            if (mListener != null) {
                mListener.onControllerCombo(controllerId, "select_start");
            }
            return true; // Combo handled, don't process individual buttons
        }
        
        // Reset combo state if timeout exceeded
        if ((currentTime - mComboDetectionTime) > COMBO_TIMEOUT_MS) {
            mSelectPressed = false;
            mStartPressed = false;
        }
        
        return false; // No combo, process button normally
    }
    
    /**
     * Handle motion events from controllers (analog sticks, triggers)
     */
    public boolean handleMotionEvent(MotionEvent event) {
        if (!isFromController(event)) {
            return false;
        }

        int controllerId = event.getDeviceId();
        
        // Handle left stick X axis
        float leftX = event.getAxisValue(MotionEvent.AXIS_X);
        leftX = applyDeadzone(leftX, ANALOG_DEADZONE);
        if (mListener != null) {
            // Only send the direction that's actually being pressed
            if (leftX < 0) {
                mListener.onControllerAnalogInput(controllerId, PAD_L_LEFT, -leftX);  // Left direction (positive value)
                mListener.onControllerAnalogInput(controllerId, PAD_L_RIGHT, 0);      // Clear right
            } else if (leftX > 0) {
                mListener.onControllerAnalogInput(controllerId, PAD_L_RIGHT, leftX);  // Right direction
                mListener.onControllerAnalogInput(controllerId, PAD_L_LEFT, 0);       // Clear left
            } else {
                mListener.onControllerAnalogInput(controllerId, PAD_L_LEFT, 0);       // Clear both
                mListener.onControllerAnalogInput(controllerId, PAD_L_RIGHT, 0);
            }
        }
        
        // Handle left stick Y axis
        float leftY = event.getAxisValue(MotionEvent.AXIS_Y);
        leftY = applyDeadzone(leftY, ANALOG_DEADZONE);
        if (mListener != null) {
            if (leftY < 0) {
                mListener.onControllerAnalogInput(controllerId, PAD_L_UP, -leftY);    // Up direction (positive value)
                mListener.onControllerAnalogInput(controllerId, PAD_L_DOWN, 0);       // Clear down
            } else if (leftY > 0) {
                mListener.onControllerAnalogInput(controllerId, PAD_L_DOWN, leftY);   // Down direction
                mListener.onControllerAnalogInput(controllerId, PAD_L_UP, 0);         // Clear up
            } else {
                mListener.onControllerAnalogInput(controllerId, PAD_L_UP, 0);         // Clear both
                mListener.onControllerAnalogInput(controllerId, PAD_L_DOWN, 0);
            }
        }
        
        // Handle right stick X axis
        float rightX = event.getAxisValue(MotionEvent.AXIS_Z);
        rightX = applyDeadzone(rightX, ANALOG_DEADZONE);
        if (mListener != null) {
            if (rightX < 0) {
                mListener.onControllerAnalogInput(controllerId, PAD_R_LEFT, -rightX); // Left direction (positive value)
                mListener.onControllerAnalogInput(controllerId, PAD_R_RIGHT, 0);      // Clear right
            } else if (rightX > 0) {
                mListener.onControllerAnalogInput(controllerId, PAD_R_RIGHT, rightX); // Right direction
                mListener.onControllerAnalogInput(controllerId, PAD_R_LEFT, 0);       // Clear left
            } else {
                mListener.onControllerAnalogInput(controllerId, PAD_R_LEFT, 0);       // Clear both
                mListener.onControllerAnalogInput(controllerId, PAD_R_RIGHT, 0);
            }
        }
        
        // Handle right stick Y axis
        float rightY = event.getAxisValue(MotionEvent.AXIS_RZ);
        rightY = applyDeadzone(rightY, ANALOG_DEADZONE);
        if (mListener != null) {
            if (rightY < 0) {
                mListener.onControllerAnalogInput(controllerId, PAD_R_UP, -rightY);   // Up direction (positive value)
                mListener.onControllerAnalogInput(controllerId, PAD_R_DOWN, 0);       // Clear down
            } else if (rightY > 0) {
                mListener.onControllerAnalogInput(controllerId, PAD_R_DOWN, rightY);  // Down direction
                mListener.onControllerAnalogInput(controllerId, PAD_R_UP, 0);         // Clear up
            } else {
                mListener.onControllerAnalogInput(controllerId, PAD_R_UP, 0);         // Clear both
                mListener.onControllerAnalogInput(controllerId, PAD_R_DOWN, 0);
            }
        }
        
        // Handle triggers (routed through the mapping, so a remapped L2/R2 still works)
        int leftTriggerTarget = mappedTarget(KeyEvent.KEYCODE_BUTTON_L2);
        if (mListener != null && leftTriggerTarget != Integer.MIN_VALUE) {
            mListener.onControllerAnalogInput(controllerId, leftTriggerTarget,
                    event.getAxisValue(MotionEvent.AXIS_LTRIGGER));
        }

        int rightTriggerTarget = mappedTarget(KeyEvent.KEYCODE_BUTTON_R2);
        if (mListener != null && rightTriggerTarget != Integer.MIN_VALUE) {
            mListener.onControllerAnalogInput(controllerId, rightTriggerTarget,
                    event.getAxisValue(MotionEvent.AXIS_RTRIGGER));
        }

        // Handle D-pad via HAT axes (some controllers report D-pad this way)
        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (mListener != null) {
            boolean left = hatX < -0.5f;
            boolean right = hatX > 0.5f;
            boolean up = hatY < -0.5f;
            boolean down = hatY > 0.5f;

            DpadState state = getDpadState(controllerId);

            // HAT D-pads are physically the D-pad, so send whatever the user bound the
            // matching D-pad key to. State is tracked even while unbound, so releasing an
            // unbound direction cannot leave a stale press behind after a rebind.
            int leftTarget = mappedTarget(KeyEvent.KEYCODE_DPAD_LEFT);
            int rightTarget = mappedTarget(KeyEvent.KEYCODE_DPAD_RIGHT);
            int upTarget = mappedTarget(KeyEvent.KEYCODE_DPAD_UP);
            int downTarget = mappedTarget(KeyEvent.KEYCODE_DPAD_DOWN);

            if (state.left != left) {
                if (leftTarget != Integer.MIN_VALUE)
                    mListener.onControllerButtonPressed(controllerId, leftTarget, left);
                state.left = left;
            }
            if (state.right != right) {
                if (rightTarget != Integer.MIN_VALUE)
                    mListener.onControllerButtonPressed(controllerId, rightTarget, right);
                state.right = right;
            }
            if (state.up != up) {
                if (upTarget != Integer.MIN_VALUE)
                    mListener.onControllerButtonPressed(controllerId, upTarget, up);
                state.up = up;
            }
            if (state.down != down) {
                if (downTarget != Integer.MIN_VALUE)
                    mListener.onControllerButtonPressed(controllerId, downTarget, down);
                state.down = down;
            }
        }

        return true;
    }
    
    /**
     * Check if the input event is from a controller
     */
    private boolean isFromController(android.view.InputEvent event) {
        InputDevice device = event.getDevice();
        if (device == null) {
            return false;
        }
        
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
               (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
               (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }
    
    /**
     * Apply deadzone to analog input
     */
    private float applyDeadzone(float value, float deadzone) {
        if (Math.abs(value) < deadzone) {
            return 0.0f;
        }
        
        // Scale the remaining range
        float sign = Math.signum(value);
        float scaledValue = (Math.abs(value) - deadzone) / (1.0f - deadzone);
        return sign * scaledValue;
    }
    
    /**
     * Check if the PS2 input is an analog stick axis
     */
    private boolean isAnalogStickAxis(int ps2Input) {
        return ps2Input >= PAD_L_UP && ps2Input <= PAD_R_LEFT;
    }
    
    /**
     * Convert Android input value to PS2 value range
     */
    private int convertToPS2Value(int ps2Input, float value) {
        if (ps2Input == PAD_L2 || ps2Input == PAD_R2) {
            // Triggers: 0-255 range
            return Math.round(value * 255.0f);
        } else if (isAnalogStickAxis(ps2Input)) {
            // Analog sticks: -32768 to 32767 range
            return Math.round(value * 32767.0f);
        } else {
            // Digital buttons: 0 or 255
            return value > 0.5f ? 255 : 0;
        }
    }
    
    /**
     * Get controller name for debugging
     */
    public static String getControllerName(int deviceId) {
        InputDevice device = InputDevice.getDevice(deviceId);
        return device != null ? device.getName() : "Unknown Controller";
    }
}
