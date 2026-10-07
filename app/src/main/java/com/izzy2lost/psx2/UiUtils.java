package com.izzy2lost.psx2;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

class UiUtils {
    static TextView centeredDialogTitle(Context ctx, String title) {
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (ctx.getResources().getDisplayMetrics().density * 16);
        tv.setPadding(pad, pad, pad, pad / 2);
        tv.setTextAppearance(ctx, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        // Use brand primary (now mapped to brighter pink/purple) for dialog titles
        try { tv.setTextColor(ContextCompat.getColor(ctx, R.color.brand_primary)); } catch (Throwable ignored) {}
        return tv;
    }

    /**
     * Pads an edge-to-edge view so its content stays clear of display cutouts and of any
     * system bars / caption bar that are visible (e.g. ChromeOS freeform windows, or when
     * immersive mode is unavailable). Hidden immersive bars report zero insets.
     */
    static void applySafeAreaPadding(View view) {
        if (view == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            Insets safe = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return windowInsets;
        });
    }

    static MainActivity getMainActivity(Fragment fragment) {
        if (fragment == null) return null;
        Activity activity = fragment.getActivity();
        if (activity instanceof MainActivity && !activity.isFinishing()) {
            return (MainActivity) activity;
        }
        return null;
    }

    static void postIfFragmentAttached(Fragment fragment, Runnable action) {
        if (fragment == null || action == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            Activity activity = fragment.getActivity();
            if (!fragment.isAdded() || activity == null || activity.isFinishing()) return;
            action.run();
        });
    }
}
