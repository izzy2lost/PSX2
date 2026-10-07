package com.izzy2lost.psx2;

import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;
import android.os.Build;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.app.Dialog;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkContinuation;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.core.view.GravityCompat;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.io.File;

public class GamesCoverDialogFragment extends DialogFragment {
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NO_FRAME, R.style.AppTheme);
    }
    private String[] titles;
    private String[] uris;
    private String[] coverUrls;
    private String[] localPaths;
    String[] origTitles; // Package-private for GameSettingsDialogFragment access
    String[] origUris;   // Package-private for GameSettingsDialogFragment access
    private String[] origCoverUrls;
    private String[] origLocalPaths;
    private final GameLibraryState libraryState = new GameLibraryState();
    @Nullable private SettingsDrawerController settingsDrawer;
    private boolean coverWorkWasRunning = false;
    private boolean texturePackManagerLoading = false;

    // Keep WorkManager input well below its 10 KiB Data limit, even for long serials.
    private static final int COVER_DOWNLOAD_BATCH_SIZE = 100;
    private static final String PREF_ACTIVE_COVER_RUN = "active_cover_download_run";
    private static final String PREF_LIBRARY_VIEW_MODE = "library_view_mode";
    private static final java.util.concurrent.ExecutorService COVER_PREPARATION_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private static final int SORT_ALPHA = 0;
    private static final int SORT_RECENT = 1;
    private int sortMode = SORT_ALPHA;
    private String query = null;
    // Simpler grouping/sort helpers (revert)


    public interface OnGameSelectedListener {
        void onGameSelected(String gameUri);
    }

    private static final String ARG_TITLES = "titles";
    private static final String ARG_URIS = "uris";

    public static GamesCoverDialogFragment newInstance(String[] titles, String[] uris) {
        GamesCoverDialogFragment f = new GamesCoverDialogFragment();
        Bundle b = new Bundle();
        b.putStringArray(ARG_TITLES, titles);
        b.putStringArray(ARG_URIS, uris);
        f.setArguments(b);
        return f;
    }

    private OnGameSelectedListener listener;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnGameSelectedListener) {
            listener = (OnGameSelectedListener) context;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Keep dialog immersive like the rest of the app
        forceDialogImmersive();
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        // Dialog is being destroyed - resume the game
        android.util.Log.d("GamesCoverDialog", "onDestroy called - resuming game");
        try {
            if (getActivity() != null && !getActivity().isFinishing() && getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onDialogClosed();
            }
        } catch (Throwable e) {
            android.util.Log.e("GamesCoverDialog", "Error in onDestroy: " + e.getMessage());
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
        
        // Return a styled dialog; content is provided by onCreateView
        Dialog d = new Dialog(requireContext(), R.style.PSX2_FullScreenDialog);
        // Ensure immersive as soon as window exists
        try { applyImmersiveToWindow(d.getWindow()); } catch (Throwable ignored) {}
        
        return d;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.dialog_covers_grid, container, false);
        // Post to re-assert immersive after layout
        try { root.post(this::forceDialogImmersive); } catch (Throwable ignored) {}

        titles = getArguments() != null ? getArguments().getStringArray(ARG_TITLES) : new String[0];
        uris = getArguments() != null ? getArguments().getStringArray(ARG_URIS) : new String[0];
        coverUrls = new String[uris.length];
        localPaths = new String[uris.length];
        SharedPreferences prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        boolean isFirstBoot = !prefs.getBoolean("has_resolved_titles_once", false);
        final File localCoverDirectory = getCoversDir();

        // Bind the library immediately from cached serials. Native disc inspection and
        // DocumentProvider lookups are completed on the library executor below.
        for (int i = 0; i < uris.length; i++) {
            String saved = prefs.getString("serial:" + uris[i], null);
            String serial = normalizeUsableSerial(saved);
            if (serial.isEmpty()) serial = buildSerialFromUri(uris[i]);
            coverUrls[i] = buildCoverUrlFromSerial(serial);
            localPaths[i] = new File(localCoverDirectory, serial + ".png").getAbsolutePath();
        }

        // cache originals for sorting/filtering
        origTitles = Arrays.copyOf(titles, titles.length);
        origUris = Arrays.copyOf(uris, uris.length);
        origCoverUrls = Arrays.copyOf(coverUrls, coverUrls.length);
        origLocalPaths = Arrays.copyOf(localPaths, localPaths.length);
        // restore sort pref if any
        sortMode = prefs.getInt("covers_sort_mode", SORT_ALPHA);
        libraryState.setViewMode(viewModeFromPref(prefs.getString(PREF_LIBRARY_VIEW_MODE, null)));
        updateSortLabel();

        // The library body (toolbar, coverflow/grid, footer) is Compose: GameLibraryScreen.kt
        ViewGroup host = root.findViewById(R.id.library_content);
        host.addView(GameLibraryComposeView.create(requireContext(), libraryState, new LibraryActions(root)));

        // Always apply the saved sort: the incoming list is in folder order, not A–Z.
        applyFilterAndSort();
        resolveCoverMetadataAsync(!isFirstBoot);

        // Resolve proper game titles using local YAML index if available (GameIndex/Redump).
        // Falls back to native URI API, then filename if needed.
        // Capture context early to avoid requireContext() crashes if fragment detaches
        final Context ctx = requireContext().getApplicationContext();
        
        COVER_PREPARATION_EXECUTOR.execute(() -> {
            try {
                // On first boot, add a delay to let native library fully initialize
                if (isFirstBoot) {
                    Thread.sleep(2000); // 2 second delay on first boot
                }
                
                // Check if fragment is still attached before proceeding
                if (!isAdded()) return;
                
                boolean changed = false;
                for (int i = 0; i < uris.length; i++) {
                    // Check if fragment is still attached on each iteration
                    if (!isAdded()) break;
                    
                    try {
                        String t = TitleResolver.resolveTitleForUri(ctx, uris[i], titles[i]);
                        if (t != null && !t.isEmpty() && i < titles.length && !t.equals(titles[i])) {
                            titles[i] = t;
                            if (origTitles != null && i < origTitles.length) origTitles[i] = t;
                            changed = true;
                        }
                    } catch (Throwable e) {
                        android.util.Log.w("GamesCoverDialog", "Error resolving title for " + uris[i] + ": " + e.getMessage());
                    }
                }
                
                // Mark that we've resolved titles at least once
                if (isFirstBoot && isAdded()) {
                    try {
                        ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("has_resolved_titles_once", true).apply();
                    } catch (Throwable ignored) {}
                }
                
                // Only update UI if fragment is still attached
                if (changed && isAdded()) {
                    try {
                        UiUtils.postIfFragmentAttached(this, () -> {
                            try {
                                if (sortMode != SORT_ALPHA || (query != null && !query.isEmpty())) {
                                    applyFilterAndSort();
                                } else {
                                    publishGames(false);
                                }
                            } catch (Throwable e) {
                                android.util.Log.w("GamesCoverDialog", "Error updating UI after title resolution: " + e.getMessage());
                            }
                        });
                    } catch (Throwable e) {
                        android.util.Log.w("GamesCoverDialog", "Error posting to UI thread: " + e.getMessage());
                    }
                }
            } catch (Throwable e) {
                android.util.Log.e("GamesCoverDialog", "Error in title resolution thread: " + e.getMessage());
            }
        });

        // Setup drawer listener for pause/resume tracking
        try {
            androidx.drawerlayout.widget.DrawerLayout dialogDrawer = root.findViewById(R.id.dlg_drawer_layout);
            if (dialogDrawer != null) {
                dialogDrawer.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.DrawerListener() {
                    @Override
                    public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {}

                    @Override
                    public void onDrawerOpened(@NonNull View drawerView) {
                        // Notify MainActivity that drawer opened (will pause game)
                        if (getActivity() instanceof MainActivity) {
                            ((MainActivity) getActivity()).onDrawerOpened();
                        }
                    }

                    @Override
                    public void onDrawerClosed(@NonNull View drawerView) {
                        // Notify MainActivity that drawer closed (will resume if no other dialogs open)
                        if (getActivity() instanceof MainActivity) {
                            ((MainActivity) getActivity()).onDrawerClosed();
                        }
                    }

                    @Override
                    public void onDrawerStateChanged(int newState) {}
                });
            }
        } catch (Throwable ignored) {}

        // Settings drawer (Compose, shared with MainActivity): SettingsDrawer.kt
        try {
            MainActivity activity = UiUtils.getMainActivity(this);
            ViewGroup drawerHost = root.findViewById(R.id.dialog_settings_drawer);
            if (activity != null && drawerHost != null) {
                androidx.drawerlayout.widget.DrawerLayout drawer = root.findViewById(R.id.dlg_drawer_layout);
                Runnable closeDrawer = () -> {
                    if (drawer != null) drawer.closeDrawer(GravityCompat.START);
                };
                settingsDrawer = new SettingsDrawerController(activity, new SettingsDrawerConfig(
                        "Game Library",
                        closeDrawer,
                        // Already in the library: just close the drawer.
                        closeDrawer,
                        () -> {
                            closeDrawer.run();
                            showTexturePackManager();
                        }));
                drawerHost.addView(SettingsDrawerController.createView(requireContext(), settingsDrawer));
            }
        } catch (Throwable error) {
            android.util.Log.e("GamesCoverDialog", "Unable to set up settings drawer", error);
        }
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        observeCoverDownloadWork();
    }
    
    @Override
    public void onStart() {
        super.onStart();
        // Re-assert immersive when dialog shows
        forceDialogImmersive();
    }

    private void forceDialogImmersive() {
        try {
            Dialog dlg = getDialog();
            if (dlg != null) applyImmersiveToWindow(dlg.getWindow());
        } catch (Throwable ignored) {}
    }

    private void applyImmersiveToWindow(@Nullable Window w) {
        if (w == null) return;
        // Match activity behavior: full-screen and transient bars by swipe
        w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        final int legacyFlags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = w.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        View decor = w.getDecorView();
        if (decor != null) {
            decor.setSystemUiVisibility(legacyFlags);
            decor.setOnSystemUiVisibilityChangeListener(vis -> {
                if ((vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    decor.setSystemUiVisibility(legacyFlags);
                }
            });
        }
    }

    // Public API to open the in-dialog navigation drawer from other components
    public void openDialogDrawer() {
        try {
            View root = getView();
            if (root == null) return;
            // Refresh drawer settings before opening
            refreshDialogDrawerSettings();
            androidx.drawerlayout.widget.DrawerLayout drawer = root.findViewById(R.id.dlg_drawer_layout);
            if (drawer != null) drawer.openDrawer(androidx.core.view.GravityCompat.START);
        } catch (Throwable ignored) {}
    }

    // --- Helper methods reintroduced after letters-row removal ---
    private static String normalizeSerial(String s) {
        if (s == null) return "";
        String t = s.trim().toUpperCase(java.util.Locale.ROOT);
        // Keep letters, digits and dash
        return t.replaceAll("[^A-Z0-9-]", "");
    }

    private static String normalizeUsableSerial(String serial) {
        return GameSerialUtils.normalizeLibrarySerial(serial);
    }

    private static String buildSerialFromUri(String uriStr) {
        try {
            Uri u = Uri.parse(uriStr);
            String last = u.getLastPathSegment();
            if (last != null) last = Uri.decode(last);
            if (last == null) last = uriStr;
            int slash = Math.max(last.lastIndexOf('/'), last.lastIndexOf('\\'));
            if (slash >= 0 && slash + 1 < last.length()) last = last.substring(slash + 1);
            int colon = last.lastIndexOf(':');
            if (colon >= 0 && colon + 1 < last.length()) last = last.substring(colon + 1);
            int dot = last.lastIndexOf('.');
            if (dot > 0) last = last.substring(0, dot);
            return normalizeUsableSerial(last);
        } catch (Throwable ignored) {
            return String.format(java.util.Locale.ROOT, "%08X", Math.abs(uriStr != null ? uriStr.hashCode() : 0));
        }
    }

    private void resolveCoverMetadataAsync(boolean allowNativeSerialLookup) {
        final Context context = getContext();
        if (context == null || origUris == null) return;
        final Context appContext = context.getApplicationContext();
        final String[] uriSnapshot = Arrays.copyOf(origUris, origUris.length);

        COVER_PREPARATION_EXECUTOR.execute(() -> {
            final SharedPreferences prefs = appContext.getSharedPreferences(
                    "app_prefs", Context.MODE_PRIVATE);
            final SharedPreferences.Editor editor = prefs.edit();
            final String[] resolvedSerials = new String[uriSnapshot.length];
            final String[] resolvedUrls = new String[uriSnapshot.length];
            final String[] resolvedPaths = new String[uriSnapshot.length];
            final java.util.HashMap<String, String> pathByUri = new java.util.HashMap<>();
            final java.util.HashMap<String, String> urlByUri = new java.util.HashMap<>();
            final java.util.LinkedHashSet<String> resolvedSerialSet =
                    new java.util.LinkedHashSet<>();

            for (int index = 0; index < uriSnapshot.length; index++) {
                final String gameUri = uriSnapshot[index];
                final String saved = prefs.getString("serial:" + gameUri, null);
                final String legacySerial = normalizeSerial(saved);
                String serial = normalizeUsableSerial(saved);
                if (serial.isEmpty() && allowNativeSerialLookup) {
                    try {
                        serial = normalizeUsableSerial(NativeApp.getGameSerialSafe(gameUri));
                    } catch (Throwable error) {
                        android.util.Log.w("GamesCoverDialog",
                                "Unable to resolve serial for " + gameUri, error);
                    }
                }
                if (serial.isEmpty()) serial = buildSerialFromUri(gameUri);

                if (!serial.isEmpty() && !legacySerial.isEmpty()
                        && !legacySerial.equals(serial)) {
                    migrateLegacyCoverSerial(appContext, prefs, legacySerial, serial);
                }
                if (!serial.isEmpty() && !serial.equals(saved)) {
                    editor.putString("serial:" + gameUri, serial);
                }

                resolvedSerials[index] = serial;
                resolvedSerialSet.add(serial);
                resolvedUrls[index] = buildCoverUrlFromSerial(serial);
                urlByUri.put(gameUri, resolvedUrls[index]);
            }
            editor.apply();

            final java.util.Map<String, String> cachedCoverPaths =
                    CoverCache.findValidCoverPaths(appContext, resolvedSerialSet);
            final File fallbackCoverDirectory = getCoversDir(appContext);
            for (int index = 0; index < uriSnapshot.length; index++) {
                final String serial = resolvedSerials[index];
                final String cachedPath = cachedCoverPaths.get(serial);
                resolvedPaths[index] = cachedPath != null
                        ? cachedPath
                        : new File(fallbackCoverDirectory, serial + ".png").getAbsolutePath();
                pathByUri.put(uriSnapshot[index], resolvedPaths[index]);
            }

            UiUtils.postIfFragmentAttached(this, () -> {
                if (origUris == null || !Arrays.equals(uriSnapshot, origUris)) return;
                origCoverUrls = resolvedUrls;
                origLocalPaths = resolvedPaths;
                for (int index = 0; index < uris.length; index++) {
                    String path = pathByUri.get(uris[index]);
                    String url = urlByUri.get(uris[index]);
                    if (path != null) localPaths[index] = path;
                    if (url != null) coverUrls[index] = url;
                }
                publishGames(false);
            });
        });
    }

    private static void migrateLegacyCoverSerial(Context context, SharedPreferences prefs,
                                                 String legacySerial, String correctedSerial) {
        if (legacySerial.isEmpty() || correctedSerial.isEmpty()
                || legacySerial.equals(correctedSerial)) return;
        boolean moved = false;
        try {
            final androidx.documentfile.provider.DocumentFile coversDirectory =
                    SafManager.getOrCreateDir(context, "covers");
            if (coversDirectory != null) {
                final androidx.documentfile.provider.DocumentFile oldCover =
                        coversDirectory.findFile(legacySerial + ".png");
                final androidx.documentfile.provider.DocumentFile newCover =
                        coversDirectory.findFile(correctedSerial + ".png");
                if (oldCover != null && newCover == null) {
                    if (oldCover.length() > 0) {
                        moved = oldCover.renameTo(correctedSerial + ".png");
                    } else {
                        oldCover.delete();
                    }
                }
            } else {
                final File directory = getCoversDir(context);
                final File oldCover = new File(directory, legacySerial + ".png");
                final File newCover = new File(directory, correctedSerial + ".png");
                if (oldCover.isFile() && !newCover.exists()) moved = oldCover.renameTo(newCover);
            }
        } catch (Throwable error) {
            android.util.Log.w("GamesCoverDialog", "Unable to migrate legacy cover serial", error);
        }

        final String oldCustomKey = "custom_cover:" + legacySerial;
        if (prefs.getBoolean(oldCustomKey, false) && moved) {
            prefs.edit()
                    .remove(oldCustomKey)
                    .putBoolean("custom_cover:" + correctedSerial, true)
                    .apply();
        }
    }

    private String extractSerialFromUri(String gameUri) {
        Context context = getContext();
        return context == null ? null
                : extractSerialFromUri(context.getApplicationContext(), gameUri);
    }

    private static String extractSerialFromUri(Context context, String gameUri) {
        try {
            try (java.io.InputStream in = context.getContentResolver()
                    .openInputStream(Uri.parse(gameUri))) {
                if (in == null) return null;
                final int maxBytes = 8 * 1024 * 1024;
                final byte[] buffer = new byte[64 * 1024];
                int read;
                int total = 0;
                StringBuilder text = new StringBuilder();
                while (total < maxBytes
                        && (read = in.read(buffer, 0, Math.min(buffer.length, maxBytes - total))) != -1) {
                    total += read;
                    text.append(new String(buffer, 0, read,
                            java.nio.charset.StandardCharsets.ISO_8859_1));
                    String found = findSerialInString(text);
                    if (found != null) return found;
                    if (text.length() > 512 * 1024) {
                        text.delete(0, text.length() - 128 * 1024);
                    }
                }
            }
        } catch (Exception error) {
            android.util.Log.w("GamesCoverDialog", "Unable to scan game serial", error);
        }
        return null;
    }

    private static String findSerialInString(CharSequence cs) {
        // Match common forms: SLUS_203.12, SLPM_650.51, SCES_123.45 etc.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([A-Z]{4,5})[_-]([0-9]{3})\\.([0-9]{2})")
                .matcher(cs);
        if (m.find()) {
            String prefix = m.group(1);
            String part1 = m.group(2);
            String part2 = m.group(3);
            return prefix + "-" + part1 + part2; // SLUS-20312
        }
        return null;
    }

    private String buildCoverUrlFromSerial(String serial) {
        // Use repo-backed PS2 cover set (3D covers)
        return "https://raw.githubusercontent.com/izzy2lost/ps2-covers/main/covers/3d/" + serial + ".png";
    }

    private File getCoversDir() {
        return getCoversDir(requireContext().getApplicationContext());
    }

    private void showTexturePackManager() {
        if (!isAdded()) return;
        final androidx.fragment.app.FragmentManager manager = getParentFragmentManager();
        final androidx.fragment.app.Fragment existing =
                manager.findFragmentByTag("texture_pack_manager");
        if (existing != null && existing.isAdded()) return;
        if (texturePackManagerLoading) return;

        final String[] sourceUris = origUris != null ? origUris : uris;
        final String[] sourceTitles = origTitles != null ? origTitles : titles;
        if (sourceUris == null || sourceTitles == null) {
            Toast.makeText(requireContext(), "Game library metadata is not ready",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final Context appContext = requireContext().getApplicationContext();
        final String[] uriSnapshot = Arrays.copyOf(sourceUris, sourceUris.length);
        final String[] titleSnapshot = Arrays.copyOf(sourceTitles, sourceTitles.length);
        texturePackManagerLoading = true;
        Toast.makeText(requireContext(), "Checking library game IDs…",
                Toast.LENGTH_SHORT).show();

        COVER_PREPARATION_EXECUTOR.execute(() -> {
            final SharedPreferences prefs = appContext.getSharedPreferences(
                    "app_prefs", Context.MODE_PRIVATE);
            final SharedPreferences.Editor editor = prefs.edit();
            final java.util.LinkedHashMap<String, String> games =
                    new java.util.LinkedHashMap<>();
            final int count = Math.min(uriSnapshot.length, titleSnapshot.length);

            for (int index = 0; index < count; index++) {
                final String gameUri = uriSnapshot[index];
                final String saved = prefs.getString("serial:" + gameUri, null);
                String serial = normalizeUsableSerial(saved);
                if (serial.isEmpty()) {
                    try {
                        serial = normalizeUsableSerial(
                                NativeApp.getGameSerialSafe(gameUri));
                    } catch (Throwable error) {
                        android.util.Log.w("GamesCoverDialog",
                                "Unable to identify game for texture packs: " + gameUri,
                                error);
                    }
                }
                if (serial.isEmpty()) {
                    serial = normalizeUsableSerial(
                            extractSerialFromUri(appContext, gameUri));
                }
                if (serial.isEmpty()) serial = buildSerialFromUri(gameUri);
                if (!GameSerialUtils.isPs2Serial(serial)) continue;

                if (!serial.equals(saved)) {
                    editor.putString("serial:" + gameUri, serial);
                }
                String title = titleSnapshot[index];
                if (title == null || title.isBlank()) title = serial;
                games.putIfAbsent(serial, title);
            }
            editor.apply();

            UiUtils.postIfFragmentAttached(this, () -> {
                texturePackManagerLoading = false;
                final androidx.fragment.app.FragmentManager currentManager =
                        getParentFragmentManager();
                final androidx.fragment.app.Fragment current =
                        currentManager.findFragmentByTag("texture_pack_manager");
                if (current != null && current.isAdded()) return;
                if (games.isEmpty()) {
                    Toast.makeText(requireContext(),
                            "No valid PS2 game IDs were found in the library.",
                            Toast.LENGTH_LONG).show();
                    return;
                }

                TexturePackManagerDialogFragment.newInstance(
                                games.values().toArray(new String[0]),
                                games.keySet().toArray(new String[0]))
                        .show(currentManager, "texture_pack_manager");
            });
        });
    }

    private static File getCoversDir(Context context) {
        File base = context.getExternalFilesDir("covers");
        if (base == null) base = new File(context.getFilesDir(), "covers");
        if (!base.exists()) base.mkdirs();
        return base;
    }

    private void showGameSettings(String gameTitle, String gameUri) {
        try {
            // Prefer native extraction so CHDs work
            String gameSerial = null;
            try { gameSerial = NativeApp.getGameSerialSafe(gameUri); } catch (Throwable ignored) {}
            if (gameSerial == null || gameSerial.isEmpty()) {
                gameSerial = extractSerialFromUri(gameUri);
            }
            if (gameSerial == null || gameSerial.isEmpty()) {
                gameSerial = buildSerialFromUri(gameUri);
            }
            gameSerial = normalizeSerial(gameSerial);

            // CRC (native if available)
            String gameCrc = null;
            try { gameCrc = NativeApp.getGameCrc(gameUri); } catch (Throwable ignored) {}
            if (gameCrc == null || gameCrc.isEmpty()) {
                gameCrc = String.format(java.util.Locale.ROOT, "%08X", Math.abs(gameUri != null ? gameUri.hashCode() : 0));
            }

            GameSettingsDialogFragment dialog = GameSettingsDialogFragment.newInstance(
                    gameTitle, gameUri, gameSerial, gameCrc);
            dialog.show(getParentFragmentManager(), "game_settings");
        } catch (Throwable ignored) {}
    }

    /** Pushes the current (sorted/filtered) arrays to the Compose library. */
    private void publishGames(boolean resetPosition) {
        libraryState.setGames(titles, uris, localPaths, resetPosition);
    }

    @Nullable
    private static LibraryViewMode viewModeFromPref(@Nullable String value) {
        if (value == null) return null; // follow the window width
        try {
            return LibraryViewMode.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** Callbacks from the Compose library (GameLibraryScreen.kt). */
    private final class LibraryActions implements GameLibraryActions {
        private final View root;

        LibraryActions(View root) {
            this.root = root;
        }

        @Override
        public void onGameClick(int index) {
            if (listener != null && index >= 0 && index < uris.length) {
                listener.onGameSelected(uris[index]);
                dismissAllowingStateLoss();
            }
        }

        @Override
        public void onGameLongClick(int index) {
            if (index >= 0 && index < uris.length) {
                showGameSettings(titles[index], uris[index]);
            }
        }

        @Override
        public void onMenu() {
            try {
                // Refresh drawer settings before opening
                refreshDialogDrawerSettings();
                androidx.drawerlayout.widget.DrawerLayout drawer = root.findViewById(R.id.dlg_drawer_layout);
                if (drawer != null) drawer.openDrawer(GravityCompat.START);
            } catch (Throwable ignored) {}
        }

        @Override
        public void onAddFolder() {
            MainActivity activity = UiUtils.getMainActivity(GamesCoverDialogFragment.this);
            if (activity != null) activity.addGamesFolder();
        }

        @Override
        public void onSearch() {
            showSearchDialog();
        }

        @Override
        public void onToggleSort() {
            sortMode = (sortMode == SORT_ALPHA) ? SORT_RECENT : SORT_ALPHA;
            requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("covers_sort_mode", sortMode).apply();
            applyFilterAndSort();
            updateSortLabel();
        }

        @Override
        public void onViewModeChanged(@NonNull LibraryViewMode mode) {
            requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit().putString(PREF_LIBRARY_VIEW_MODE, mode.name()).apply();
        }

        @Override
        public void onDownloadCovers() {
            startDownloadCovers();
        }

        @Override
        public void onTexturePacks() {
            showTexturePackManager();
        }
    }

    private void updateSortLabel() {
        libraryState.setSortLabel(sortMode == SORT_ALPHA ? "A–Z" : "RECENT");
    }

    private void applyFilterAndSort() {
        int n = origTitles != null ? origTitles.length : 0;
        java.util.ArrayList<Integer> idxs = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (query == null || query.isEmpty()) {
                idxs.add(i);
            } else {
                String t = origTitles[i] != null ? origTitles[i] : "";
                if (t.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))) idxs.add(i);
            }
        }
        SharedPreferences prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        if (sortMode == SORT_RECENT) {
            idxs.sort((a, b) -> {
                long ta = prefs.getLong("last_played:" + origUris[a], 0L);
                long tb = prefs.getLong("last_played:" + origUris[b], 0L);
                if (ta == tb) return (origTitles[a] != null ? origTitles[a] : "").compareToIgnoreCase(origTitles[b] != null ? origTitles[b] : "");
                return Long.compare(tb, ta);
            });
        } else {
            java.text.Normalizer.Form form = java.text.Normalizer.Form.NFD;
            java.util.function.Function<Integer, String> keyFn = (Integer i) -> {
                String t = origTitles[i];
                if (t == null) return "";
                String s = t.trim().toLowerCase(java.util.Locale.ROOT);
                // Drop leading articles commonly used in titles
                if (s.startsWith("the ")) s = s.substring(4);
                else if (s.startsWith("an ")) s = s.substring(3);
                else if (s.startsWith("a ")) s = s.substring(2);
                // Remove diacritics
                s = java.text.Normalizer.normalize(s, form).replaceAll("\\p{M}+", "");
                // Strip non-alphanumeric at start
                s = s.replaceFirst("^[^a-z0-9]+", "");
                return s;
            };
            idxs.sort((a,b) -> keyFn.apply(a).compareTo(keyFn.apply(b)));
        }
        titles = new String[idxs.size()];
        uris = new String[idxs.size()];
        coverUrls = new String[idxs.size()];
        localPaths = new String[idxs.size()];
        for (int k = 0; k < idxs.size(); k++) {
            int i = idxs.get(k);
            titles[k] = origTitles[i];
            uris[k] = origUris[i];
            coverUrls[k] = origCoverUrls[i];
            localPaths[k] = origLocalPaths[i];
        }
        publishGames(true);
    }

    private void showSearchDialog() {
        final EditText input = new EditText(requireContext());
        input.setHint("Search games");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);
        new MaterialAlertDialogBuilder(requireContext(),
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle("Search")
                .setView(input)
                .setNegativeButton("Clear", (d, w) -> {
                    query = null;
                    applyFilterAndSort();
                })
                .setPositiveButton("Apply", (d, w) -> {
                    query = input.getText() != null ? input.getText().toString().trim() : null;
                    if (query != null && query.isEmpty()) query = null;
                    applyFilterAndSort();
                })
                .show();
    }

    private void startDownloadCovers() {
        // Check if any games have custom covers
        SharedPreferences prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        java.util.ArrayList<String> customCoverGames = new java.util.ArrayList<>();

        final String[] uriSnapshot = origUris != null ? origUris : uris;
        final String[] titleSnapshot = origTitles != null ? origTitles : titles;
        for (int i = 0; i < uriSnapshot.length; i++) {
            try {
                String serial = normalizeUsableSerial(
                        prefs.getString("serial:" + uriSnapshot[i], null));
                if (serial == null || serial.isEmpty()) {
                    try { serial = NativeApp.getGameSerialSafe(uriSnapshot[i]); } catch (Throwable ignored) {}
                }
                serial = normalizeUsableSerial(serial);
                if (serial == null || serial.isEmpty()) {
                    serial = buildSerialFromUri(uriSnapshot[i]);
                }
                serial = normalizeUsableSerial(serial);

                if (prefs.getBoolean("custom_cover:" + serial, false)) {
                    customCoverGames.add(i < titleSnapshot.length ? titleSnapshot[i] : serial);
                }
            } catch (Exception ignored) {}
        }
        
        // If custom covers exist, ask user what to do
        if (!customCoverGames.isEmpty()) {
            String message = "Found " + customCoverGames.size() + " game(s) with custom covers.\n\nWhat would you like to do?";
            new MaterialAlertDialogBuilder(requireContext(),
                    com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                    .setTitle("Custom Covers Detected")
                    .setMessage(message)
                    .setNegativeButton("Cancel", null)
                    .setNeutralButton("Skip Custom", (d, w) -> downloadCoversInternal(true))
                    .setPositiveButton("Delete All Custom", (d, w) -> deleteCustomCovers())
                    .show();
        } else {
            downloadCoversInternal(false);
        }
    }
    
    private void downloadCoversInternal(boolean skipCustomCovers) {
        final Context appContext = requireContext().getApplicationContext();
        final String[] uriSnapshot = Arrays.copyOf(
                origUris != null ? origUris : uris,
                origUris != null ? origUris.length : uris.length);

        setDownloadButtonEnabled(false, "Checking for missing covers");
        Toast.makeText(requireContext(), "Checking for missing covers",
                Toast.LENGTH_SHORT).show();

        COVER_PREPARATION_EXECUTOR.execute(() -> {
            final WorkManager workManager = WorkManager.getInstance(appContext);
            try {
                final java.util.List<WorkInfo> existing = workManager
                        .getWorkInfosForUniqueWork(CoverDownloadWorker.UNIQUE_WORK_NAME).get();
                for (WorkInfo info : existing) {
                    if (!info.getState().isFinished()) {
                        UiUtils.postIfFragmentAttached(this, () -> {
                            Toast.makeText(requireContext(), "Cover download is already running",
                                    Toast.LENGTH_SHORT).show();
                            setDownloadButtonEnabled(false, "Cover download in progress");
                        });
                        return;
                    }
                }
            } catch (Exception error) {
                android.util.Log.w("GamesCoverDialog", "Unable to query cover work", error);
            }

            final SharedPreferences prefs = appContext.getSharedPreferences(
                    "app_prefs", Context.MODE_PRIVATE);
            final SharedPreferences.Editor serialEditor = prefs.edit();
            final java.util.LinkedHashSet<String> uniqueSerials = new java.util.LinkedHashSet<>();
            int skipped = 0;

            for (String gameUri : uriSnapshot) {
                String serial = normalizeUsableSerial(
                        prefs.getString("serial:" + gameUri, null));
                if (serial == null || serial.isEmpty()) {
                    try { serial = NativeApp.getGameSerialSafe(gameUri); } catch (Throwable ignored) {}
                }
                serial = normalizeUsableSerial(serial);
                if (serial == null || serial.isEmpty()) {
                    serial = extractSerialFromUri(appContext, gameUri);
                }
                serial = normalizeUsableSerial(serial);
                if (serial == null || serial.isEmpty()) {
                    serial = buildSerialFromUri(gameUri);
                }
                serial = normalizeUsableSerial(serial);
                if (serial.isEmpty()) {
                    android.util.Log.w("GamesCoverDialog", "No serial for " + gameUri);
                    continue;
                }

                serialEditor.putString("serial:" + gameUri, serial);
                if (skipCustomCovers && prefs.getBoolean("custom_cover:" + serial, false)) {
                    skipped++;
                    continue;
                }
                uniqueSerials.add(serial);
            }
            serialEditor.apply();

            // Index both SAF and app storage once, validate the files already on disk,
            // and send only genuinely missing serials to WorkManager. The worker still
            // performs its own check to make retries and process restarts safe.
            final java.util.Map<String, String> cachedCoverPaths =
                    CoverCache.findValidCoverPaths(appContext, uniqueSerials);
            uniqueSerials.removeAll(cachedCoverPaths.keySet());
            final int alreadyCachedCount = cachedCoverPaths.size();
            final int skippedCount = skipped;
            android.util.Log.i("GamesCoverDialog", "Cover inventory: cached="
                    + alreadyCachedCount + ", missing=" + uniqueSerials.size()
                    + ", customSkipped=" + skippedCount);

            if (uniqueSerials.isEmpty()) {
                UiUtils.postIfFragmentAttached(this, () -> {
                    coverWorkWasRunning = false;
                    setDownloadButtonEnabled(true, "Download missing covers");
                    String message;
                    if (alreadyCachedCount > 0) {
                        message = "All " + alreadyCachedCount + " covers are already cached";
                    } else {
                        message = "No missing covers to download";
                    }
                    if (skippedCount > 0) {
                        message += " (" + skippedCount + " custom skipped)";
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
                });
                return;
            }

            final String runToken = java.util.UUID.randomUUID().toString();
            prefs.edit().putString(PREF_ACTIVE_COVER_RUN, runToken).apply();
            final java.util.ArrayList<String> serialList = new java.util.ArrayList<>(uniqueSerials);
            final java.util.ArrayList<OneTimeWorkRequest> requests = new java.util.ArrayList<>();
            final Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            for (int start = 0; start < serialList.size(); start += COVER_DOWNLOAD_BATCH_SIZE) {
                final int end = Math.min(start + COVER_DOWNLOAD_BATCH_SIZE, serialList.size());
                final String[] batch = serialList.subList(start, end).toArray(new String[0]);
                final androidx.work.Data input = new androidx.work.Data.Builder()
                        .putStringArray(CoverDownloadWorker.KEY_SERIALS, batch)
                        .putString(CoverDownloadWorker.KEY_RUN_TOKEN, runToken)
                        .build();
                requests.add(new OneTimeWorkRequest.Builder(CoverDownloadWorker.class)
                        .setInputData(input)
                        .setConstraints(constraints)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10,
                                java.util.concurrent.TimeUnit.SECONDS)
                        .addTag(CoverDownloadWorker.RUN_TAG_PREFIX + runToken)
                        .build());
            }

            WorkContinuation continuation = workManager.beginUniqueWork(
                    CoverDownloadWorker.UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    requests.get(0));
            for (int index = 1; index < requests.size(); index++) {
                continuation = continuation.then(requests.get(index));
            }
            continuation.enqueue();

            UiUtils.postIfFragmentAttached(this, () -> {
                coverWorkWasRunning = true;
                String message = "Downloading " + serialList.size() + " missing "
                        + (serialList.size() == 1 ? "cover" : "covers") + " in background";
                if (alreadyCachedCount > 0) {
                    message += " (" + alreadyCachedCount + " already cached)";
                }
                if (skippedCount > 0) {
                    message += " (" + skippedCount + " custom skipped)";
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void observeCoverDownloadWork() {
        final Context appContext = requireContext().getApplicationContext();
        final SharedPreferences prefs = appContext.getSharedPreferences(
                "app_prefs", Context.MODE_PRIVATE);
        WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkLiveData(CoverDownloadWorker.UNIQUE_WORK_NAME)
                .observe(getViewLifecycleOwner(), workInfos -> {
                    if (workInfos == null) return;

                    String runToken = prefs.getString(PREF_ACTIVE_COVER_RUN, null);
                    if (runToken == null || runToken.isEmpty()) {
                        for (WorkInfo info : workInfos) {
                            if (info.getState().isFinished()) continue;
                            for (String tag : info.getTags()) {
                                if (tag.startsWith(CoverDownloadWorker.RUN_TAG_PREFIX)) {
                                    runToken = tag.substring(
                                            CoverDownloadWorker.RUN_TAG_PREFIX.length());
                                    prefs.edit().putString(PREF_ACTIVE_COVER_RUN, runToken).apply();
                                    break;
                                }
                            }
                            if (runToken != null) break;
                        }
                    }
                    if (runToken == null || runToken.isEmpty()) {
                        setDownloadButtonEnabled(true, "Download missing covers");
                        return;
                    }

                    final String runTag = CoverDownloadWorker.RUN_TAG_PREFIX + runToken;
                    final java.util.ArrayList<WorkInfo> currentRun = new java.util.ArrayList<>();
                    for (WorkInfo info : workInfos) {
                        if (info.getTags().contains(runTag)) currentRun.add(info);
                    }
                    if (currentRun.isEmpty()) return;

                    boolean active = false;
                    int processed = 0;
                    int total = 0;
                    for (WorkInfo info : currentRun) {
                        active |= !info.getState().isFinished();
                        processed += info.getProgress().getInt(
                                CoverDownloadWorker.KEY_PROCESSED, 0);
                        total += info.getProgress().getInt(CoverDownloadWorker.KEY_TOTAL, 0);
                    }

                    if (active) {
                        coverWorkWasRunning = true;
                        String description = total > 0
                                ? "Downloading missing covers " + processed + " of " + total
                                : "Missing-cover download in progress";
                        setDownloadButtonEnabled(false, description);
                        return;
                    }

                    setDownloadButtonEnabled(true, "Download missing covers");
                    if (!coverWorkWasRunning) return;
                    coverWorkWasRunning = false;
                    prefs.edit().remove(PREF_ACTIVE_COVER_RUN).apply();

                    int ready = 0;
                    int downloaded = 0;
                    int failed = 0;
                    boolean workFailed = false;
                    final java.util.ArrayList<String> failedSerials = new java.util.ArrayList<>();
                    for (WorkInfo info : currentRun) {
                        if (info.getState() != WorkInfo.State.SUCCEEDED) {
                            workFailed = true;
                            continue;
                        }
                        ready += info.getOutputData().getInt(CoverDownloadWorker.KEY_READY, 0);
                        downloaded += info.getOutputData().getInt(
                                CoverDownloadWorker.KEY_DOWNLOADED, 0);
                        failed += info.getOutputData().getInt(CoverDownloadWorker.KEY_FAILED, 0);
                        String names = info.getOutputData().getString(
                                CoverDownloadWorker.KEY_FAILED_SERIALS);
                        if (names != null && !names.isBlank()) failedSerials.add(names);
                    }

                    refreshDownloadedCoverPaths();
                    String message;
                    if (workFailed) {
                        message = "Cover download stopped before it completed";
                    } else {
                        final int foundInCache = Math.max(0, ready - downloaded);
                        message = "Downloaded " + downloaded + " new "
                                + (downloaded == 1 ? "cover" : "covers");
                        if (foundInCache > 0) {
                            message += " (" + foundInCache + " already cached)";
                        }
                        if (failed > 0) message += " (" + failed + " failed)";
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                    if (!failedSerials.isEmpty()) {
                        android.util.Log.w("GamesCoverDialog", "Cover failures: "
                                + String.join(", ", failedSerials));
                    }
                });
    }

    private void setDownloadButtonEnabled(boolean enabled, String description) {
        libraryState.setDownloadEnabled(enabled);
        libraryState.setDownloadDescription(description);
    }

    private void refreshDownloadedCoverPaths() {
        final Context context = getContext();
        if (context == null || origUris == null) return;
        final Context appContext = context.getApplicationContext();
        final String[] uriSnapshot = Arrays.copyOf(origUris, origUris.length);

        COVER_PREPARATION_EXECUTOR.execute(() -> {
            final SharedPreferences prefs = appContext.getSharedPreferences(
                    "app_prefs", Context.MODE_PRIVATE);
            final String[] refreshedSerials = new String[uriSnapshot.length];
            final String[] refreshedUrls = new String[uriSnapshot.length];
            final String[] refreshedPaths = new String[uriSnapshot.length];
            final java.util.HashMap<String, String> pathByUri = new java.util.HashMap<>();
            final java.util.HashMap<String, String> urlByUri = new java.util.HashMap<>();
            final java.util.LinkedHashSet<String> refreshedSerialSet =
                    new java.util.LinkedHashSet<>();

            for (int index = 0; index < uriSnapshot.length; index++) {
                String serial = normalizeUsableSerial(
                        prefs.getString("serial:" + uriSnapshot[index], null));
                if (serial.isEmpty()) serial = buildSerialFromUri(uriSnapshot[index]);
                refreshedSerials[index] = serial;
                refreshedSerialSet.add(serial);
                refreshedUrls[index] = buildCoverUrlFromSerial(serial);
                urlByUri.put(uriSnapshot[index], refreshedUrls[index]);
            }

            final java.util.Map<String, String> cachedCoverPaths =
                    CoverCache.findValidCoverPaths(appContext, refreshedSerialSet);
            final File fallbackCoverDirectory = getCoversDir(appContext);
            for (int index = 0; index < uriSnapshot.length; index++) {
                final String serial = refreshedSerials[index];
                final String cachedPath = cachedCoverPaths.get(serial);
                refreshedPaths[index] = cachedPath != null
                        ? cachedPath
                        : new File(fallbackCoverDirectory, serial + ".png").getAbsolutePath();
                pathByUri.put(uriSnapshot[index], refreshedPaths[index]);
            }

            UiUtils.postIfFragmentAttached(this, () -> {
                if (origUris == null || !Arrays.equals(uriSnapshot, origUris)) return;
                origCoverUrls = refreshedUrls;
                origLocalPaths = refreshedPaths;
                for (int index = 0; index < uris.length; index++) {
                    String refreshedPath = pathByUri.get(uris[index]);
                    String refreshedUrl = urlByUri.get(uris[index]);
                    if (refreshedPath != null) localPaths[index] = refreshedPath;
                    if (refreshedUrl != null) coverUrls[index] = refreshedUrl;
                }
                publishGames(false);
            });
        });
    }

    private void deleteCustomCovers() {
        final Context appContext = requireContext().getApplicationContext();
        final String[] uriSnapshot = Arrays.copyOf(
                origUris != null ? origUris : uris,
                origUris != null ? origUris.length : uris.length);
        Toast.makeText(requireContext(), "Deleting custom covers...", Toast.LENGTH_SHORT).show();
        COVER_PREPARATION_EXECUTOR.execute(() -> {
            SharedPreferences prefs = appContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            int deletedCount = 0;

            for (int i = 0; i < uriSnapshot.length; i++) {
                try {
                    String serial = normalizeUsableSerial(
                            prefs.getString("serial:" + uriSnapshot[i], null));
                    if (serial == null || serial.isEmpty()) {
                        try { serial = NativeApp.getGameSerialSafe(uriSnapshot[i]); } catch (Throwable ignored) {}
                    }
                    serial = normalizeUsableSerial(serial);
                    if (serial == null || serial.isEmpty()) {
                        serial = buildSerialFromUri(uriSnapshot[i]);
                    }
                    serial = normalizeUsableSerial(serial);

                    // Check if this game has a custom cover
                    if (prefs.getBoolean("custom_cover:" + serial, false)) {
                        // Delete the custom cover file
                        try {
                            androidx.documentfile.provider.DocumentFile existing = SafManager.getChild(
                                    appContext, new String[]{"covers"}, serial + ".png");
                            if (existing != null && existing.exists()) {
                                existing.delete();
                                deletedCount++;
                            }
                        } catch (Exception e) {
                            android.util.Log.w("GamesCoverDialog", "Error deleting custom cover for " + serial + ": " + e.getMessage());
                        }
                        
                        // Clear the custom cover flag
                        editor.putBoolean("custom_cover:" + serial, false);
                    }
                } catch (Exception e) {
                    android.util.Log.w("GamesCoverDialog", "Error processing game " + i + ": " + e.getMessage());
                }
            }
            editor.apply();
            
            final int deleted = deletedCount;
            UiUtils.postIfFragmentAttached(this, () -> {
                Toast.makeText(requireContext(), "Deleted " + deleted + " custom cover(s)", Toast.LENGTH_SHORT).show();
                refreshDownloadedCoverPaths();
            });
        });
    }
    


    private void refreshDialogDrawerSettings() {
        if (settingsDrawer != null) settingsDrawer.refresh();
    }
}
