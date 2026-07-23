package com.izzy2lost.psx2;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

public class SDLSurface extends SurfaceView implements SurfaceHolder.Callback {
    private int mAimPointerId = MotionEvent.INVALID_POINTER_ID;
    private float mLastAimX;
    private float mLastAimY;

    private float normalizedAimX(MotionEvent event, int pointerIndex) {
        return Math.max(0.0f, Math.min(1.0f, event.getX(pointerIndex) / Math.max(1, getWidth())));
    }

    private float normalizedAimY(MotionEvent event, int pointerIndex) {
        return Math.max(0.0f, Math.min(1.0f, event.getY(pointerIndex) / Math.max(1, getHeight())));
    }

    public SDLSurface(Context p_context) {
        super(p_context);
        myInit();
    }

    public SDLSurface(Context p_context, AttributeSet attrs) {
        super(p_context, attrs);
        myInit();
    }

    public SDLSurface(Context p_context, AttributeSet attrs, int defStyle) {
        super(p_context, attrs, defStyle);
        myInit();
    }

    private void myInit() {
        getHolder().addCallback(this);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (NativeApp.hasNoNativeBinary) return super.onTouchEvent(event);

        final int action = event.getActionMasked();
        final int actionIndex = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) {
            mAimPointerId = event.getPointerId(actionIndex);
            mLastAimX = normalizedAimX(event, actionIndex);
            mLastAimY = normalizedAimY(event, actionIndex);
            if (NativeApp.updateTouchscreenPointer(mLastAimX, mLastAimY, true)) return true;
            mAimPointerId = MotionEvent.INVALID_POINTER_ID;
            return super.onTouchEvent(event);
        }

        if (mAimPointerId == MotionEvent.INVALID_POINTER_ID) return super.onTouchEvent(event);

        if (action == MotionEvent.ACTION_MOVE) {
            final int pointerIndex = event.findPointerIndex(mAimPointerId);
            if (pointerIndex >= 0) {
                mLastAimX = normalizedAimX(event, pointerIndex);
                mLastAimY = normalizedAimY(event, pointerIndex);
                NativeApp.updateTouchscreenPointer(mLastAimX, mLastAimY, true);
            }
            return true;
        }

        if (action == MotionEvent.ACTION_POINTER_UP
                && event.getPointerId(actionIndex) == mAimPointerId) {
            mLastAimX = normalizedAimX(event, actionIndex);
            mLastAimY = normalizedAimY(event, actionIndex);
            NativeApp.updateTouchscreenPointer(mLastAimX, mLastAimY, false);
            mAimPointerId = MotionEvent.INVALID_POINTER_ID;
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            final int pointerIndex = event.findPointerIndex(mAimPointerId);
            if (pointerIndex >= 0) {
                mLastAimX = normalizedAimX(event, pointerIndex);
                mLastAimY = normalizedAimY(event, pointerIndex);
            }
            NativeApp.updateTouchscreenPointer(mLastAimX, mLastAimY, false);
            mAimPointerId = MotionEvent.INVALID_POINTER_ID;
            return true;
        }

        return true;
    }

    // Called when we have a valid drawing surface
    @Override
    public void surfaceCreated(@NonNull SurfaceHolder p_holder) {
    }

    // Called when the surface is resized
    @Override
    public void surfaceChanged(@NonNull SurfaceHolder p_holder, int p_format, int p_width, int p_height) {
        NativeApp.onNativeSurfaceChanged(p_holder.getSurface(), p_width, p_height);
        MainActivity _nativeActivity = (MainActivity) getContext();
        if(_nativeActivity != null) {
            _nativeActivity.startEmuThread();
        }
    }

    // Called when we lose the surface
    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder p_holder) {
        if (mAimPointerId != MotionEvent.INVALID_POINTER_ID) {
            NativeApp.updateTouchscreenPointer(mLastAimX, mLastAimY, false);
            mAimPointerId = MotionEvent.INVALID_POINTER_ID;
        }
        NativeApp.onNativeSurfaceChanged(null, 0, 0);
    }
}
