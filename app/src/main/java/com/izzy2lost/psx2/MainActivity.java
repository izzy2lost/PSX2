package com.izzy2lost.psx2;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.os.Bundle;
import android.text.TextUtils;
import android.net.Uri;
import android.view.InputDevice;
import android.hardware.input.InputManager;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.view.ViewGroup;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.content.ClipData;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.util.TypedValue;
import android.widget.TextView;
import android.widget.Toast;
import android.app.PictureInPictureParams;
import android.util.Rational;
import android.app.RemoteAction;
import android.graphics.drawable.Icon;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.core.view.GravityCompat;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.EdgeToEdge;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.WindowCompat;
import androidx.core.graphics.Insets;

import com.google.android.material.button.MaterialButton;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONException;
import androidx.fragment.app.FragmentManager;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.button.MaterialButtonToggleGroup;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements GamesCoverDialogFragment.OnGameSelectedListener, ControllerInputHandler.ControllerInputListener {
    public static final int ORIENTATION_AUTO = 0;
    public static final int ORIENTATION_LANDSCAPE = 1;
    public static final int ORIENTATION_PORTRAIT = 2;

    private static final String PREF_TOUCH_RIGHT_STICK = "touch_right_stick";
    private static final String PREF_GAMES_FOLDER_URI = "games_folder_uri";
    private static final String PREF_GAMES_FOLDER_URIS = "games_folder_uris_json";
    private static final java.util.concurrent.ExecutorService GAME_LIBRARY_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private String m_szGamefile = "";
    private boolean mRaLoginPromptScheduled = false;
    private boolean mAddingGamesFolder = false;

    private HIDDeviceManager mHIDDeviceManager;
    private ControllerInputHandler mControllerInputHandler;
    private final Object mEmulationThreadLock = new Object();
    private final java.util.concurrent.ExecutorService mEmulationControlExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger mEmulationRestartGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean mGameLibraryScanPending =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Thread mEmulationThread = null;
    private boolean mSetupWizardActive = false;
    private boolean mHudVisible = false;
    private InputManager mInputManager;
    
    // Track joystick directional pressed state to avoid duplicate down events
    private boolean joyUpPressed = false;
    private boolean joyDownPressed = false;
    private boolean joyLeftPressed = false;
    private boolean joyRightPressed = false;
    private boolean joyRUpPressed = false;
    private boolean joyRDownPressed = false;
    private boolean joyRLeftPressed = false;
    private boolean joyRRightPressed = false;
    private boolean controllerUiApplied = false;
    private AlertDialog mBiosPromptDialog = null;
    private boolean mControllerHintShowing = false;
    private boolean mPreviousControllerState = false;
    
    // Pause requests have separate owners so closing a dialog or returning to the
    // activity cannot accidentally clear a pause explicitly requested by the user.
    private int mOpenDialogCount = 0;
    private int mOpenDrawerCount = 0;
    private boolean mActivityPauseRequested = false;
    private boolean mUserPauseRequested = false;

    public boolean isThread() {
        Thread emulationThread = mEmulationThread;
        return (emulationThread != null && emulationThread.isAlive()) || isNativeVMActive();
    }

    private boolean isNativeVMActive() {
        if (NativeApp.hasNoNativeBinary) {
            return false;
        }

        try {
            return NativeApp.isVMActive();
        } catch (Throwable t) {
            android.util.Log.w("MainActivity", "Unable to query native VM state", t);
            return false;
        }
    }

    // Public method to check if emulation thread is running
    public boolean isEmulationThreadRunning() {
        return isThread();
    }

    // Expose whether a game has been chosen (non-empty path)
    public boolean hasSelectedGame() {
        return !TextUtils.isEmpty(m_szGamefile);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Keep fullscreen on rotate and reflow constraints without recreating
        hideStatusBar();
        applyConstraintsForOrientation(newConfig.orientation);
    }

    private void applyConstraintsForOrientation(int orientation) {
        // Views present in both layouts
        View quick = findViewById(R.id.ll_quick_actions);
        View btnSettings = findViewById(R.id.btn_settings);
        // Visibility toggle removed; no dependency on it for constraints
        View llJoy = findViewById(R.id.ll_pad_joy);
        View llRJoy = findViewById(R.id.ll_pad_rjoy);
        View llDpad = findViewById(R.id.ll_pad_dpad);
        View llRight = findViewById(R.id.ll_pad_right_buttons);
        View llSelectStart = findViewById(R.id.ll_pad_select_start);

        // Use helper dp()

        if (quick != null && btnSettings != null) {
            ConstraintLayout.LayoutParams lp = safeCLP(quick);
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                // Top-aligned row stretching from settings to end
                lp.width = 0; // chain between start/end
                lp.topToTop = btnSettings.getId();
                lp.topToBottom = ConstraintLayout.LayoutParams.UNSET;
                lp.startToEnd = btnSettings.getId();
                lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                lp.startToStart = ConstraintLayout.LayoutParams.UNSET;
                lp.topMargin = dp(0);
                lp.horizontalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED;
            } else {
                // Centered under settings
                lp.width = ConstraintLayout.LayoutParams.WRAP_CONTENT;
                lp.topToTop = ConstraintLayout.LayoutParams.UNSET;
                lp.topToBottom = btnSettings.getId();
                lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
                lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                lp.startToEnd = ConstraintLayout.LayoutParams.UNSET;
                lp.endToStart = ConstraintLayout.LayoutParams.UNSET;
                lp.topMargin = dp(16);
                lp.horizontalChainStyle = ConstraintLayout.LayoutParams.CHAIN_SPREAD;
            }
            quick.setLayoutParams(lp);
        }

        if (llJoy != null) {
            ConstraintLayout.LayoutParams lp = safeCLP(llJoy);
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.endToEnd = ConstraintLayout.LayoutParams.UNSET;
            lp.topToTop = ConstraintLayout.LayoutParams.UNSET;
            lp.topToBottom = ConstraintLayout.LayoutParams.UNSET;
            int m = (orientation == Configuration.ORIENTATION_LANDSCAPE) ? 6 : 6;
            lp.setMargins(dp(m), dp(m), dp(m), dp(m));
            llJoy.setLayoutParams(lp);
            // Nudge joystick further left in both orientations to avoid Select overlap
            llJoy.setTranslationX(-dp(28));
        }

        if (llRJoy != null) {
            ConstraintLayout.LayoutParams lp = safeCLP(llRJoy);
            lp.startToStart = ConstraintLayout.LayoutParams.UNSET;
            lp.endToEnd = ConstraintLayout.LayoutParams.UNSET;
            lp.endToStart = ConstraintLayout.LayoutParams.UNSET;
            lp.topToTop = ConstraintLayout.LayoutParams.UNSET;
            lp.topToBottom = ConstraintLayout.LayoutParams.UNSET;
            lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
            lp.bottomToTop = ConstraintLayout.LayoutParams.UNSET;

            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                if (llRight != null) lp.endToStart = llRight.getId();
                else lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            } else {
                lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                if (llRight != null) lp.bottomToTop = llRight.getId();
                else if (llSelectStart != null) lp.bottomToTop = llSelectStart.getId();
                else lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                lp.setMargins(dp(0), dp(0), dp(12), dp(6));
            }
            llRJoy.setLayoutParams(lp);
        }

        if (llDpad != null && llJoy != null) {
            ConstraintLayout.LayoutParams lp = safeCLP(llDpad);
            // Above-left of joystick for both, with slight spacing
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.endToEnd = ConstraintLayout.LayoutParams.UNSET;
            lp.bottomToTop = llJoy.getId();
            lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
            lp.topToTop = ConstraintLayout.LayoutParams.UNSET;
            lp.topToBottom = ConstraintLayout.LayoutParams.UNSET;
            // Shift the whole D-pad slightly to the right with a small start margin
            lp.setMargins(dp(48), dp(0), dp(0), dp(orientation == Configuration.ORIENTATION_LANDSCAPE ? 2 : 0));
            llDpad.setLayoutParams(lp);
            // Nudge D-pad downward a little
            llDpad.setTranslationY(dp(14));
        }

        if (llRight != null) {
            ConstraintLayout.LayoutParams lp = safeCLP(llRight);
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                lp.bottomToTop = ConstraintLayout.LayoutParams.UNSET;
                lp.setMargins(dp(12), dp(12), dp(12), dp(12));
            } else {
                // Above Select/Start, aligned to end
                if (llSelectStart != null) {
                    lp.bottomToTop = llSelectStart.getId();
                } else {
                    lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                }
                lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                lp.bottomToBottom = (llSelectStart == null) ? ConstraintLayout.LayoutParams.PARENT_ID : ConstraintLayout.LayoutParams.UNSET;
                lp.setMargins(0,0,0,dp(8));
            }
            llRight.setLayoutParams(lp);
        }

        if (llSelectStart != null) {
            ConstraintLayout.LayoutParams lp = safeCLP(llSelectStart);
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.setMargins(0,0,0,dp(orientation == Configuration.ORIENTATION_LANDSCAPE ? 8 : 0));
            llSelectStart.setLayoutParams(lp);
        }
    }

    private ConstraintLayout.LayoutParams safeCLP(View v) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p instanceof ConstraintLayout.LayoutParams) return (ConstraintLayout.LayoutParams) p;
        ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(p);
        v.setLayoutParams(lp);
        return lp;
    }

    private int dp(int d) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, d, getResources().getDisplayMetrics());
    }

    private void hideStatusBar() {
        // Make immersive (hide status + navigation) and allow swipe to reveal temporarily
        Window w = getWindow();
        if (w == null) return;

        // Allow drawing into display cutouts (notches) on API 28+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern API for Android 11+ (API 30+)
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = w.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // Legacy API for Android 10 and below
            final int legacyFlags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            
            View decor = w.getDecorView();
            decor.setSystemUiVisibility(legacyFlags);
            decor.setOnSystemUiVisibilityChangeListener(vis -> {
                if ((vis & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    decor.setSystemUiVisibility(legacyFlags);
                }
            });
        }
    }

    private void applyEdgeToEdge() {
        Window w = getWindow();
        if (w == null) return;

        // API 35+ recommends avoiding setStatusBarColor/setNavigationBarColor for edge-to-edge.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            WindowCompat.setDecorFitsSystemWindows(w, false);
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(w, w.getDecorView());
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                // App is forced dark mode; keep bars non-light to avoid contrast issues.
                controller.setAppearanceLightStatusBars(false);
                controller.setAppearanceLightNavigationBars(false);
            }
        } else {
            // Fallback to the androidx helper on older platforms
            EdgeToEdge.enable(
                this,
                androidx.activity.SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT
                ),
                androidx.activity.SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT
                )
            );
        }
    }
    
    private void setupWindowInsets() {
        View mainView = findViewById(R.id.drawer_layout); // Use the actual root layout ID
        if (mainView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, windowInsets) -> {
                Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
                
                // For a gaming app, we typically want to draw behind the system bars
                // but ensure important UI elements are not obscured
                v.setPadding(0, 0, 0, 0); // Draw edge-to-edge
                
                return windowInsets;
            });
        }
    }

    // Enable immersive mode to hide navigation and status bars
    private void enableImmersiveMode() {
        hideStatusBar();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideStatusBar();
    }

    public void pickGamesFolder() {
        launchGamesFolderPicker(false);
    }

    public void addGamesFolder() {
        launchGamesFolderPicker(true);
    }

    private void launchGamesFolderPicker(boolean addToLibrary) {
        mAddingGamesFolder = addToLibrary;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityResultGamesFolderPick.launch(intent);
    }

    // Let the user select a data root (SAF) where app folders/files live (covers, resources, saves, etc.)
    public void pickDataRootFolder() {
        startActivityResultDataRootPick.launch(SafManager.buildOpenTreeIntent());
    }

    private void showGamesListOrReselect() {
        showGamesListOrReselect(false);
    }

    private void showGamesListOrReselect(boolean requireFreshScan) {
        FragmentManager fm = getSupportFragmentManager();
        if (isFinishing() || isDestroyed() || fm.isStateSaved()) return;

        // A recursive DocumentProvider scan can take seconds on large folders. Open
        // the last known library immediately, then refresh that cache off the UI thread.
        if (!requireFreshScan) {
            GameList cached = loadCachedGameList();
            if (cached.names.length > 0) {
                showGamesList(cached);
                refreshGameLibraryCacheAsync();
                return;
            }
        }

        if (!mGameLibraryScanPending.compareAndSet(false, true)) {
            Toast.makeText(this, "Game library scan is already running", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Loading game library…", Toast.LENGTH_SHORT).show();
        GAME_LIBRARY_EXECUTOR.execute(() -> {
            GameList scanned;
            try {
                scanned = scanConfiguredGameFolders();
            } catch (Throwable error) {
                android.util.Log.e("GameLibrary", "Unable to scan game folders", error);
                scanned = new GameList(new String[0], new String[0]);
            }
            final GameList result = scanned;
            runOnUiThread(() -> {
                mGameLibraryScanPending.set(false);
                if (isFinishing() || isDestroyed()
                        || getSupportFragmentManager().isStateSaved()) return;
                showGamesList(result);
            });
        });
    }

    private void showGamesList(GameList list) {
        FragmentManager fm = getSupportFragmentManager();
        if (isFinishing() || isDestroyed() || fm.isStateSaved()) return;
        androidx.fragment.app.Fragment existing = fm.findFragmentByTag("covers_dialog");
        if (existing != null && existing.isAdded()) return;

        if (list.names.length == 0) {
            new MaterialAlertDialogBuilder(this,
                    com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                    .setCustomTitle(UiUtils.centeredDialogTitle(this, "GAMES"))
                    .setMessage("No games found. Add another game folder?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Add Folder", (d,w) -> addGamesFolder())
                    .show();
            return;
        }

        GamesCoverDialogFragment frag = GamesCoverDialogFragment.newInstance(
                list.names, list.uris);
        frag.show(fm, "covers_dialog");
    }

    private void refreshGameLibraryCacheAsync() {
        if (!mGameLibraryScanPending.compareAndSet(false, true)) return;
        GAME_LIBRARY_EXECUTOR.execute(() -> {
            try {
                scanConfiguredGameFolders();
            } catch (Throwable error) {
                android.util.Log.w("GameLibrary", "Background library refresh failed", error);
            } finally {
                mGameLibraryScanPending.set(false);
            }
        });
    }

    private GameList loadCachedGameList() {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        java.util.ArrayList<String> uris = new java.util.ArrayList<>();
        String json = getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getString("games_list_json", null);
        if (TextUtils.isEmpty(json)) return new GameList(new String[0], new String[0]);
        try {
            JSONArray array = new JSONArray(json);
            for (int index = 0; index < array.length(); index++) {
                JSONArray pair = array.optJSONArray(index);
                if (pair == null) continue;
                String name = pair.optString(0, "");
                String uri = pair.optString(1, "");
                if (TextUtils.isEmpty(name) || TextUtils.isEmpty(uri)) continue;
                names.add(name);
                uris.add(uri);
            }
        } catch (JSONException error) {
            android.util.Log.w("GameLibrary", "Ignoring invalid cached library", error);
        }
        return new GameList(names.toArray(new String[0]), uris.toArray(new String[0]));
    }

    @Override
    public void onGameSelected(String gameUri) {
        if (!TextUtils.isEmpty(gameUri)) {
            // Avoid any pre-VM native calls here; just set the game and launch.
            m_szGamefile = gameUri;
            // Record last played timestamp for sorting
            try {
                SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
                prefs.edit().putLong("last_played:" + gameUri, System.currentTimeMillis()).apply();
            } catch (Throwable ignored) {}
            restartEmuThread();
        }
    }

    private static final String[] GAME_EXTS = new String[]{
            ".iso", ".bin", ".img", ".mdf", ".nrg", ".chd", ".cso", ".zso", ".gz", ".acgame"
    };

    private static boolean hasGameExt(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : GAME_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static class GameList {
        final String[] names;
        final String[] uris;
        GameList(String[] n, String[] u) { names = n; uris = u; }
    }

    private static class ScannedGame {
        String name;
        final String uri;
        final boolean arcadeManifest;

        ScannedGame(String name, String uri) {
            this.name = name;
            this.uri = uri;
            this.arcadeManifest = ArcadeGameManifest.isManifest(name);
        }
    }

    private GameList scanConfiguredGameFolders() {
        java.util.ArrayList<ScannedGame> scannedGames = new java.util.ArrayList<>();
        java.util.HashSet<String> seenUris = new java.util.HashSet<>();
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        for (String folderUri : getGameFolderUris(prefs)) {
            try {
                DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(folderUri));
                if (dir != null && dir.isDirectory()) {
                    scanGamesRecursive(dir, scannedGames, seenUris);
                }
            } catch (Throwable ignored) {}
        }

        // An .acgame manifest is the launchable arcade entry. Hide only the exact media file
        // referenced by that manifest; unrelated CHDs and other disc images remain visible.
        java.util.HashSet<String> manifestMediaUris = new java.util.HashSet<>();
        for (ScannedGame game : scannedGames) {
            if (!game.arcadeManifest) continue;
            ArcadeGameManifest.Metadata metadata = ArcadeGameManifest.read(this, game.uri);
            if (!ArcadeGameManifest.isBlank(metadata.title)) game.name = metadata.title.trim();
            String mediaUriKey = ArcadeGameManifest.resolveMediaUriKey(game.uri, metadata);
            if (!TextUtils.isEmpty(mediaUriKey)) {
                manifestMediaUris.add(mediaUriKey);
            }
        }

        java.util.ArrayList<String> nameList = new java.util.ArrayList<>();
        java.util.ArrayList<String> uriList = new java.util.ArrayList<>();
        for (ScannedGame game : scannedGames) {
            if (!game.arcadeManifest &&
                    manifestMediaUris.contains(ArcadeGameManifest.uriKey(game.uri))) {
                continue;
            }
            nameList.add(game.name);
            uriList.add(game.uri);
        }

        // Persist the latest merged list for components which need a cached snapshot.
        JSONArray arr = new JSONArray();
        for (int i = 0; i < nameList.size(); i++) {
            JSONArray pair = new JSONArray();
            try {
                pair.put(nameList.get(i));
                pair.put(uriList.get(i));
            } catch (Exception ignored) {}
            arr.put(pair);
        }
        prefs.edit().putString("games_list_json", arr.toString()).apply();

        return new GameList(nameList.toArray(new String[0]), uriList.toArray(new String[0]));
    }

    private java.util.ArrayList<String> getGameFolderUris(SharedPreferences prefs) {
        java.util.LinkedHashSet<String> folders = new java.util.LinkedHashSet<>();
        String json = prefs.getString(PREF_GAMES_FOLDER_URIS, null);
        if (!TextUtils.isEmpty(json)) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    String uri = arr.optString(i, "");
                    if (!TextUtils.isEmpty(uri)) folders.add(uri);
                }
            } catch (JSONException ignored) {}
        }

        // Migrate installations which stored only one game folder.
        String legacyFolder = prefs.getString(PREF_GAMES_FOLDER_URI, null);
        if (!TextUtils.isEmpty(legacyFolder)) folders.add(legacyFolder);
        return new java.util.ArrayList<>(folders);
    }

    private void saveGameFolderUris(SharedPreferences prefs, java.util.List<String> folders) {
        JSONArray arr = new JSONArray();
        for (String folder : folders) arr.put(folder);
        SharedPreferences.Editor editor = prefs.edit().putString(PREF_GAMES_FOLDER_URIS, arr.toString());
        if (!folders.isEmpty()) editor.putString(PREF_GAMES_FOLDER_URI, folders.get(0));
        editor.apply();
    }

    private void scanGamesRecursive(DocumentFile dir, java.util.List<ScannedGame> games,
                                    java.util.Set<String> seenUris) {
        DocumentFile[] children = dir.listFiles();
        if (children == null) return;
        for (DocumentFile child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                scanGamesRecursive(child, games, seenUris);
            } else if (child.isFile()) {
                String name = child.getName();
                String uri = child.getUri().toString();
                if (hasGameExt(name) && seenUris.add(uri)) {
                    games.add(new ScannedGame(name, uri));
                }
            }
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // FORCE dark mode always - ignore system setting
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES);
        
        super.onCreate(savedInstanceState);

        // Edge-to-edge without deprecated status/nav color APIs (API 35+)
        applyEdgeToEdge();
        
        // Hide action bar to improve immersive appearance
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        
        setContentView(R.layout.activity_main);

        // Track every DialogFragment centrally. Several dialogs historically used
        // different dismissal callbacks, which could leave the manual counter stale
        // and keep emulation paused forever after the UI had already closed.
        setupDialogPauseTracking();
        
        // Handle window insets for edge-to-edge display
        setupWindowInsets();
        
        enableImmersiveMode();

        // Setup back button handler
        setupBackPressedHandler();

        // Default resources
        copyAssetAll(getApplicationContext(), "resources");
        // If a SAF data root is set, mirror resources to it (first time only)
        copyAssetsToSafDataRoot();

        Initialize();

        // Initialize RetroAchievements
        RetroAchievementsManager.initialize(this);
        
        // Initialize controller input handler
        mControllerInputHandler = new ControllerInputHandler(this);
        
        // Log connected controllers for debugging
        ControllerConfig.logControllerInfo(this);

        makeButtonTouch();
        updateRendererButtonLabel();

        setSurfaceView(new SDLSurface(this));

        // Ensure consistent ripple across all MaterialButtons
        tintAllMaterialButtonOutlines();

        // Apply orientation-specific constraints once at startup
        int currentOrientation = getResources().getConfiguration().orientation;
        applyConstraintsForOrientation(currentOrientation);
      
        // Prompt for BIOS if missing, but only after first-run setup
        if (getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean("first_run_done", false)) {
            maybePromptForBios();
        }

        // Listen for controller attach/detach and update UI accordingly
        mInputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (mInputManager != null) {
            mInputManager.registerInputDeviceListener(mInputDeviceListener, null);
        }
        updateUiForControllerPresence();

        // Show first-run setup wizard if needed
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        boolean firstRunDone = prefs.getBoolean("first_run_done", false);
        
        if (!firstRunDone) {
            SetupWizardDialogFragment f = SetupWizardDialogFragment.newInstance();
            f.setCancelable(false);
            f.show(getSupportFragmentManager(), "setup_wizard");
        } else {
            // Only auto-open games dialog if this is NOT the first boot after setup
            // (Setup wizard handles opening the games dialog on first completion)
            boolean hasOpenedGamesAfterSetup = prefs.getBoolean("has_opened_games_after_setup", false);
            if (hasOpenedGamesAfterSetup) {
                try {
                    final View decor = (getWindow() != null) ? getWindow().getDecorView() : null;
                    if (decor != null) {
                        decor.postDelayed(() -> {
                            if (!isFinishing() && !isDestroyed() && !mSetupWizardActive
                                    && !getSupportFragmentManager().isStateSaved()) {
                                openGamesDialog();
                            }
                        }, 1600); // small delay to let BIOS boot briefly
                    }
                } catch (Throwable ignored) {}
            }
        }

        // Setup right drawer quick actions
        setupRightDrawerActions();
        
        // Setup drawer listeners for pause/resume
        setupDrawerListeners();
        
        // Setup picture-in-picture support
        addPictureInPictureSupport();

        // Offer RetroAchievements re-login after a short delay (if previously enabled)
        scheduleRetroAchievementsReLoginPrompt();
    }
    
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Automatically enter PiP mode when user presses home button during gameplay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (hasSelectedGame() && isThread() && !isInPictureInPictureMode()) {
                enterPictureInPictureMode();
            }
        }
    }

    public void setSetupWizardActive(boolean active) {
        mSetupWizardActive = active;
    }

    // Public method to open the games covers dialog via controller quick actions
    public void openGamesDialog() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        
        // Mark that we've opened games dialog after setup (prevents double-open on first boot)
        if (!prefs.getBoolean("has_opened_games_after_setup", false)) {
            prefs.edit().putBoolean("has_opened_games_after_setup", true).apply();
        }
        
        if (getGameFolderUris(prefs).isEmpty()) {
            pickGamesFolder();
        } else {
            showGamesListOrReselect();
        }
    }

    private void setupBackPressedHandler() {
        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                showExitDialog();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, callback);
    }

    private void makeButtonTouch() {
        // SAVES button removed from main UI; access Save States via drawer or Quick Actions


        // Menu button opens left drawer
        MaterialButton btn_settings = findViewById(R.id.btn_settings);
        if (btn_settings != null) {
            btn_settings.setOnClickListener(v -> {
                try {
                    // Just open the drawer - let the drawer listener handle pausing
                    DrawerLayout drawer = findViewById(R.id.drawer_layout);
                    if (drawer != null) {
                        drawer.openDrawer(androidx.core.view.GravityCompat.START);
                    }
                } catch (Throwable t) {
                    android.util.Log.e("MainActivity", "Error opening settings drawer: " + t.getMessage());
                }
            });
        }

        // Pause/Play button toggles emulation pause state
        MaterialButton btn_pause_play = findViewById(R.id.btn_pause_play);
        if (btn_pause_play != null) {
            btn_pause_play.setOnClickListener(v -> {
                try {
                    togglePauseState();
                } catch (Throwable ignored) {}
            });
        }
        // Wire left drawer header controls (title/power/reboot/renderer)
        try {
            NavigationView nav = findViewById(R.id.nav_view);
            if (nav != null) {
                View header = (nav.getHeaderCount() > 0) ? nav.getHeaderView(0) : nav.inflateHeaderView(R.layout.drawer_header_settings);
                if (header != null) {
                    View btnPower = header.findViewById(R.id.drawer_btn_power);
                    if (btnPower != null) {
                        btnPower.setOnClickListener(v -> {
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("Power Off")
                                    .setMessage("Quit the app?")
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton("Quit", (d,w) -> { try { NativeApp.shutdown(); } catch (Throwable ignored) {} finishAffinity(); finishAndRemoveTask(); System.exit(0); })
                                    .show();
                        });
                    }
                    View btnReboot = header.findViewById(R.id.drawer_btn_reboot);
                    if (btnReboot != null) {
                        btnReboot.setOnClickListener(v -> {
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("Reboot")
                                    .setMessage("Restart the current game?")
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton("Reboot", (d,w) -> rebootEmu())
                                    .show();
                        });
                    }
                    MaterialButtonToggleGroup tg = header.findViewById(R.id.drawer_tg_renderer);
                    View btnGameState = header.findViewById(R.id.drawer_btn_game_state);
                    View tbAt = header.findViewById(R.id.drawer_tb_at);
                    View tbVk = header.findViewById(R.id.drawer_tb_vk);
                    View tbGl = header.findViewById(R.id.drawer_tb_gl);
                    View tbSw = header.findViewById(R.id.drawer_tb_sw);
                    if (tg != null) {
                        int current = -1;
                        try { current = NativeApp.getCurrentRenderer(); } catch (Throwable ignored) {}
                        if (current == 14 && tbVk != null) tg.check(tbVk.getId());
                        else if (current == 12 && tbGl != null) tg.check(tbGl.getId());
                        else if (current == 13 && tbSw != null) tg.check(tbSw.getId());
                        else if (tbAt != null) tg.check(tbAt.getId());
                        tg.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                            if (!isChecked) return;
                            int r = -1;
                            if (checkedId == (tbVk != null ? tbVk.getId() : -2)) r = 14;
                            else if (checkedId == (tbGl != null ? tbGl.getId() : -2)) r = 12;
                            else if (checkedId == (tbSw != null ? tbSw.getId() : -2)) r = 13;
                            else r = -1;
                            try {
                                int currentRenderer = getSharedPreferences("app_prefs", MODE_PRIVATE).getInt("renderer", -1);
                                if (currentRenderer == r) return;
                                getSharedPreferences("app_prefs", MODE_PRIVATE).edit().putInt("renderer", r).apply();
                                NativeApp.renderGpuAsync(r);
                            } catch (Throwable ignored) {}
                        });
                    }
                    View btnGames = header.findViewById(R.id.drawer_btn_games);
                    if (btnGames != null) {
                        btnGames.setOnClickListener(v -> {
                            try {
                                // Close drawer first
                                DrawerLayout drawer = findViewById(R.id.drawer_layout);
                                if (drawer != null) drawer.closeDrawer(androidx.core.view.GravityCompat.START);
                                // Open games dialog
                                openGamesDialog();
                            } catch (Throwable ignored) {}
                        });
                    }
                    if (btnGameState != null) {
                        btnGameState.setOnClickListener(v -> {
                            try {
                                SavesDialogFragment dialog = new SavesDialogFragment();
                                dialog.show(getSupportFragmentManager(), "saves_dialog");
                            } catch (Throwable ignored) {}
                        });
                    }
                    View btnMemcardManager = header.findViewById(R.id.drawer_btn_memcard_manager);
                    if (btnMemcardManager != null) {
                        btnMemcardManager.setOnClickListener(v -> {
                            try {
                                MemoryCardManagerDialogFragment dialog = new MemoryCardManagerDialogFragment();
                                dialog.show(getSupportFragmentManager(), "memcard_manager_dialog");
                            } catch (Throwable ignored) {}
                        });
                    }
                    setupDrawerBiosControls(header);
                    View btnAchievements = header.findViewById(R.id.drawer_btn_achievements);
                    if (btnAchievements != null) {
                        btnAchievements.setOnClickListener(v -> {
                            try {
                                AchievementsDialogFragment.newInstance()
                                        .show(getSupportFragmentManager(), "achievements_dialog");
                            } catch (Throwable t) {
                                android.util.Log.e("MainActivity", "Failed to open achievements dialog: " + t.getMessage());
                            }
                        });
                    }
                    View btnAbout = header.findViewById(R.id.drawer_btn_about);
                    if (btnAbout != null) {
                        btnAbout.setOnClickListener(v -> {
                            try {
                                showAboutDialog();
                            } catch (Throwable ignored) {}
                        });
                    }

                    // Setup drawer settings controls to mirror quick actions
                    setupDrawerSettings(header);
                }
            }
        } catch (Throwable ignored) {}
        // Visibility toggle removed

        // PAD
        MaterialButton btn_pad_select = findViewById(R.id.btn_pad_select);
        if(btn_pad_select != null) {
            btn_pad_select.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_BUTTON_SELECT);
                return true;
            });
        }
        MaterialButton btn_pad_start = findViewById(R.id.btn_pad_start);
        if(btn_pad_start != null) {
            btn_pad_start.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_BUTTON_START);
                return true;
            });
        }
         MaterialButton btn_pad_a = findViewById(R.id.btn_pad_a);
        if(btn_pad_a != null) {
            btn_pad_a.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_BUTTON_A);
                return true;
            });
        }
        MaterialButton btn_pad_b = findViewById(R.id.btn_pad_b);
        if(btn_pad_b != null) {
            btn_pad_b.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_BUTTON_B);
                return true;
            });
        }
        MaterialButton btn_pad_x = findViewById(R.id.btn_pad_x);
        if(btn_pad_x != null) {
            btn_pad_x.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_BUTTON_X);
                return true;
            });
        }
        MaterialButton btn_pad_y = findViewById(R.id.btn_pad_y);
        if(btn_pad_y != null) {
            btn_pad_y.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_BUTTON_Y);
                return true;
            });
        }

        //// Shoulder buttons
        MaterialButton btn_pad_l1 = findViewById(R.id.btn_pad_l1);
        if(btn_pad_l1 != null) {
            btn_pad_l1.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_BUTTON_L1);
                return true;
            });
        }
        MaterialButton btn_pad_r1 = findViewById(R.id.btn_pad_r1);
        if(btn_pad_r1 != null) {
            btn_pad_r1.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_BUTTON_R1);
                return true;
            });
        }

        MaterialButton btn_pad_l2 = findViewById(R.id.btn_pad_l2);
        if(btn_pad_l2 != null) {
            btn_pad_l2.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_BUTTON_L2);
                return true;
            });
        }
        MaterialButton btn_pad_r2 = findViewById(R.id.btn_pad_r2);
        if(btn_pad_r2 != null) {
            btn_pad_r2.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_BUTTON_R2);
                return true;
            });
        }

        MaterialButton btn_pad_l3 = findViewById(R.id.btn_pad_l3);
        if(btn_pad_l3 != null) {
            btn_pad_l3.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_BUTTON_THUMBL);
                return true;
            });
        }
        MaterialButton btn_pad_r3 = findViewById(R.id.btn_pad_r3);
        if(btn_pad_r3 != null) {
            btn_pad_r3.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_BUTTON_THUMBR);
                return true;
            });
        }

        //// D-Pad
        final int PAD_L_UP = 110;
        final int PAD_L_RIGHT = 111;
        final int PAD_L_DOWN = 112;
        final int PAD_L_LEFT = 113;

        final int PAD_R_UP = 120;
        final int PAD_R_RIGHT = 121;
        final int PAD_R_DOWN = 122;
        final int PAD_R_LEFT = 123;

        // Draggable JoystickView (portrait/landscape layouts)
        View joystick = findViewById(R.id.joystick_view);
        if (joystick instanceof JoystickView) {
            JoystickView jv = (JoystickView) joystick;
            jv.setOnMoveListener((nx, ny, action) -> {
                // Thresholds
                final float T = 0.3f;
                boolean up = ny < -T;
                boolean down = ny > T;
                boolean left = nx < -T;
                boolean right = nx > T;

                // Issue key down/up transitions only when state changes
                if (up != joyUpPressed) {
                    sendKeyAction(jv, up ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP, PAD_L_UP);
                    joyUpPressed = up;
                }
                if (down != joyDownPressed) {
                    sendKeyAction(jv, down ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP, PAD_L_DOWN);
                    joyDownPressed = down;
                }
                if (left != joyLeftPressed) {
                    sendKeyAction(jv, left ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP, PAD_L_LEFT);
                    joyLeftPressed = left;
                }
                if (right != joyRightPressed) {
                    sendKeyAction(jv, right ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP, PAD_L_RIGHT);
                    joyRightPressed = right;
                }

                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    // Ensure all released
                    if (joyUpPressed)  sendKeyAction(jv, MotionEvent.ACTION_UP, PAD_L_UP);
                    if (joyDownPressed) sendKeyAction(jv, MotionEvent.ACTION_UP, PAD_L_DOWN);
                    if (joyLeftPressed) sendKeyAction(jv, MotionEvent.ACTION_UP, PAD_L_LEFT);
                    if (joyRightPressed) sendKeyAction(jv, MotionEvent.ACTION_UP, PAD_L_RIGHT);
                    joyUpPressed = joyDownPressed = joyLeftPressed = joyRightPressed = false;
                }
            });
        }

        // Optional Right JoystickView (right analog stick)
        View joystickRight = findViewById(R.id.joystick_view_right);
        if (joystickRight instanceof JoystickView) {
            JoystickView jv = (JoystickView) joystickRight;
            jv.setOnMoveListener((nx, ny, action) -> {
                final float T = 0.3f;
                boolean up = ny < -T;
                boolean down = ny > T;
                boolean left = nx < -T;
                boolean right = nx > T;

                if (up != joyRUpPressed) {
                    sendKeyAction(jv, up ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP, PAD_R_UP);
                    joyRUpPressed = up;
                }
                if (down != joyRDownPressed) {
                    sendKeyAction(jv, down ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP, PAD_R_DOWN);
                    joyRDownPressed = down;
                }
                if (left != joyRLeftPressed) {
                    sendKeyAction(jv, left ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP, PAD_R_LEFT);
                    joyRLeftPressed = left;
                }
                if (right != joyRRightPressed) {
                    sendKeyAction(jv, right ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP, PAD_R_RIGHT);
                    joyRRightPressed = right;
                }

                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (joyRUpPressed)  sendKeyAction(jv, MotionEvent.ACTION_UP, PAD_R_UP);
                    if (joyRDownPressed) sendKeyAction(jv, MotionEvent.ACTION_UP, PAD_R_DOWN);
                    if (joyRLeftPressed) sendKeyAction(jv, MotionEvent.ACTION_UP, PAD_R_LEFT);
                    if (joyRRightPressed) sendKeyAction(jv, MotionEvent.ACTION_UP, PAD_R_RIGHT);
                    joyRUpPressed = joyRDownPressed = joyRLeftPressed = joyRRightPressed = false;
                }
            });
        }

        //// D-Pad buttons
        MaterialButton btn_pad_dir_top = findViewById(R.id.btn_pad_dir_top);
        if(btn_pad_dir_top != null) {
            btn_pad_dir_top.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_DPAD_UP);
                return true;
            });
        }
        MaterialButton btn_pad_dir_bottom = findViewById(R.id.btn_pad_dir_bottom);
        if(btn_pad_dir_bottom != null) {
            btn_pad_dir_bottom.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_DPAD_DOWN);
                return true;
            });
        }
        MaterialButton btn_pad_dir_left = findViewById(R.id.btn_pad_dir_left);
        if(btn_pad_dir_left != null) {
            btn_pad_dir_left.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_DPAD_LEFT);
                return true;
            });
        }
        MaterialButton btn_pad_dir_right = findViewById(R.id.btn_pad_dir_right);
        if(btn_pad_dir_right != null) {
            btn_pad_dir_right.setOnTouchListener((v, event) -> {
                sendKeyAction(v, event.getAction(), KeyEvent.KEYCODE_DPAD_RIGHT);
                return true;
            });
        }
    }

    // Visibility toggle removed; no global hidden state flag

    private void setControlsVisible(boolean visible) {
        if (!visible) {
            releaseVirtualStickInputs();
        }
        int vis = visible ? View.VISIBLE : View.GONE;
        View llDpad = findViewById(R.id.ll_pad_dpad);
        View llRight = findViewById(R.id.ll_pad_right_buttons);
        View llSelectStart = findViewById(R.id.ll_pad_select_start);
        View llJoy = findViewById(R.id.ll_pad_joy);
        View llRJoy = findViewById(R.id.ll_pad_rjoy);

        if (llDpad != null) llDpad.setVisibility(vis);
        if (llRight != null) llRight.setVisibility(vis);
        if (llSelectStart != null) llSelectStart.setVisibility(vis);
        if (llJoy != null) llJoy.setVisibility(vis);
        if (llRJoy != null) {
            boolean show = visible && getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean(PREF_TOUCH_RIGHT_STICK, false);
            llRJoy.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private void releaseVirtualStickInputs() {
        // Left stick
        if (joyUpPressed || joyDownPressed || joyLeftPressed || joyRightPressed) {
            try { NativeApp.setPadButton(ControllerInputHandler.PAD_L_UP, 0, false); } catch (Throwable ignored) {}
            try { NativeApp.setPadButton(ControllerInputHandler.PAD_L_DOWN, 0, false); } catch (Throwable ignored) {}
            try { NativeApp.setPadButton(ControllerInputHandler.PAD_L_LEFT, 0, false); } catch (Throwable ignored) {}
            try { NativeApp.setPadButton(ControllerInputHandler.PAD_L_RIGHT, 0, false); } catch (Throwable ignored) {}
            joyUpPressed = joyDownPressed = joyLeftPressed = joyRightPressed = false;
        }

        // Right stick
        releaseVirtualRightStickInputs();
    }

    private void releaseVirtualRightStickInputs() {
        if (joyRUpPressed || joyRDownPressed || joyRLeftPressed || joyRRightPressed) {
            try { NativeApp.setPadButton(ControllerInputHandler.PAD_R_UP, 0, false); } catch (Throwable ignored) {}
            try { NativeApp.setPadButton(ControllerInputHandler.PAD_R_DOWN, 0, false); } catch (Throwable ignored) {}
            try { NativeApp.setPadButton(ControllerInputHandler.PAD_R_LEFT, 0, false); } catch (Throwable ignored) {}
            try { NativeApp.setPadButton(ControllerInputHandler.PAD_R_RIGHT, 0, false); } catch (Throwable ignored) {}
            joyRUpPressed = joyRDownPressed = joyRLeftPressed = joyRRightPressed = false;
        }
    }

    // Removed visibility toggle function

    // --- Controller presence handling ---
    private final InputManager.InputDeviceListener mInputDeviceListener = new InputManager.InputDeviceListener() {
        @Override public void onInputDeviceAdded(int deviceId) { updateUiForControllerPresence(); }
        @Override public void onInputDeviceRemoved(int deviceId) { updateUiForControllerPresence(); }
        @Override public void onInputDeviceChanged(int deviceId) { updateUiForControllerPresence(); }
    };

    private boolean isControllerDevice(InputDevice device) {
        if (device == null) return false;

        // Filter out virtual/built-in devices which can falsely report DPAD sources
        try {
            if (device.isVirtual()) return false;
        } catch (Throwable ignored) {}

        // Heuristic: devices with both vendor and product = 0 are often virtual
        try {
            if (device.getVendorId() == 0 && device.getProductId() == 0) return false;
        } catch (Throwable ignored) {}

        // Filter by name for common virtual/built-in inputs
        String name = device.getName();
        if (name != null) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("virtual") || lower.contains("uinput") || lower.contains("touch")
                    || lower.contains("keyboard") || lower.contains("keypad") || lower.contains("gpio")) {
                return false;
            }
        }

        int sources = device.getSources();
        boolean gamepad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD;
        boolean joystick = (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        boolean dpad = (sources & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;

        // Consider DPAD-only devices as controllers only if they are external (heuristic above)
        return gamepad || joystick || dpad;
    }

    private boolean isAnyControllerConnected() {
        try {
            int[] ids = InputDevice.getDeviceIds();
            for (int id : ids) {
                InputDevice dev = InputDevice.getDevice(id);
                if (isControllerDevice(dev)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void updateUiForControllerPresence() {
        boolean hasController = isAnyControllerConnected();
        // Show all UI when no controller; hide UI when a controller is connected.
        int vis = hasController ? View.GONE : View.VISIBLE;

        View btnSettings = findViewById(R.id.btn_settings);
        View llQuickActions = findViewById(R.id.ll_quick_actions);

        // Keep menu (settings) button visible even when a controller is connected
        if (btnSettings != null) btnSettings.setVisibility(View.VISIBLE);
        if (llQuickActions != null) llQuickActions.setVisibility(vis);

        // Hide on-screen touch controls when a physical controller is connected
        setControlsVisible(!hasController);

        // Show controller hint when a controller is newly connected
        if (hasController && !mPreviousControllerState) {
            showControllerHintIfNeeded();
        }
        
        mPreviousControllerState = hasController;
    }

    private int getCurrentRendererPref() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        // Default to -1 (Automatic) if not set
        return prefs.getInt("renderer", -1);
    }

    private String rendererShortLabel(int r) {
        if (r == -1) return "AUTO";
        if (r == 12) return "OGL";
        if (r == 13) return "SW";
        return "VK"; // 14
    }


    private void updateRendererButtonLabel() {
    // No-op: renderer label now handled in drawer
}

// Public minimal UI refresh hook for dialogs
    public void refreshQuickUi() {
        updateRendererButtonLabel();
    }

    private void setRendererAndSave(int renderer) {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        if (prefs.getInt("renderer", -1) == renderer) {
            updateRendererButtonLabel();
            return;
        }
        prefs.edit().putInt("renderer", renderer).apply();
        // Apply live if possible; this path has proven stable via top bar buttons
        NativeApp.renderGpuAsync(renderer);
        updateRendererButtonLabel();
    }

    private void tintAllMaterialButtonOutlines() {
        // Ripple + specific color tweaks, no strokes (transparent container style)
        View root = findViewById(android.R.id.content);
        if (root instanceof ViewGroup) {
            traverseAndTintButtons((ViewGroup) root);
        }
    }

    private void traverseAndTintButtons(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                traverseAndTintButtons((ViewGroup) child);
            }
            if (child instanceof MaterialButton) {
                MaterialButton mb = (MaterialButton) child;
                // Ensure ripple matches brand globally
                mb.setRippleColor(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.brand_ripple)));
                int id = mb.getId();
                if (id == R.id.btn_pad_y) { // Triangle = green
                    final int base = ContextCompat.getColor(this, R.color.ps2_triangle_green);
                    ColorStateList stateful = pressedColorStateList(base);
                    mb.setTextColor(stateful);
                } else if (id == R.id.btn_pad_b) { // Circle = red
                    final int base = ContextCompat.getColor(this, R.color.ps2_circle_red);
                    ColorStateList stateful = pressedColorStateList(base);
                    mb.setTextColor(stateful);
                } else if (id == R.id.btn_pad_a) { // Cross = blue
                    final int base = ContextCompat.getColor(this, R.color.ps2_cross_blue);
                    ColorStateList stateful = pressedColorStateList(base);
                    mb.setTextColor(stateful);
                } else if (id == R.id.btn_pad_x) { // Square = pink
                    final int base = ContextCompat.getColor(this, R.color.ps2_square_pink);
                    ColorStateList stateful = pressedColorStateList(base);
                    mb.setTextColor(stateful);
                } else if (id == R.id.btn_pad_dir_top || id == R.id.btn_pad_dir_left || id == R.id.btn_pad_dir_right || id == R.id.btn_pad_dir_bottom) {
                    // D-pad arrow icon tint to brand accent
                    ColorStateList iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.brand_accent));
                    mb.setIconTint(iconTint);
                }
                // No stroke; background is transparent per style
            }
        }
    }

    // --- BIOS presence check and prompt ---
    private boolean hasAnyBiosFiles(File dir) {
        return BiosVerifier.hasAnyVerifiedBios(this);
    }

    private void maybePromptForBios() {
        // Temporarily disable automatic BIOS prompt
        if (!getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean("bios_auto_prompt_enabled", false))
            return;
        File biosDir = BiosVerifier.getBiosDirectory(this);
        if (hasAnyBiosFiles(biosDir)) return;
        showBiosPrompt();
    }

    private boolean ensureBiosOrPrompt() {
        BiosVerifier.ScanResult scan = BiosVerifier.scanVerifiedBioses(this);
        boolean arcadeGame = m_szGamefile != null &&
                m_szGamefile.toLowerCase(Locale.ROOT).endsWith(".acgame");
        boolean hasRequiredBios = arcadeGame
                ? scan.byRegion.containsKey(BiosVerifier.Region.ARCADE)
                : scan.byRegion.containsKey(BiosVerifier.Region.USA) ||
                  scan.byRegion.containsKey(BiosVerifier.Region.EUROPE) ||
                  scan.byRegion.containsKey(BiosVerifier.Region.JAPAN);
        if (hasRequiredBios) {
            configureVerifiedBiosFiles();
            return true;
        }

        showBiosPrompt();
        Toast.makeText(this, arcadeGame
                        ? "This game requires a COH-H arcade BIOS; a retail PS2 BIOS will not work."
                        : "This game requires a verified retail PS2 BIOS.",
                Toast.LENGTH_LONG).show();
        return false;
    }

    public void showBiosPrompt() {
        if (mBiosPromptDialog != null && mBiosPromptDialog.isShowing()) return;
        boolean hasVerifiedBios = BiosVerifier.hasAnyVerifiedBios(this);
        String title = hasVerifiedBios ? "BIOS Files" : "BIOS Required";
        String message = (hasVerifiedBios ? BiosVerifier.describeVerifiedRegions(this) + ".\n\n" : "") +
                "Import a USA, Europe, or Japan PS2 BIOS matching the bundled Redump DAT, or a COH-H arcade BIOS for System 246/256 games.\n\nHint: Press Select+Start for Quick Actions.";
        mBiosPromptDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setCustomTitle(UiUtils.centeredDialogTitle(this, title))
                .setMessage(message)
                .setNegativeButton("Later", (d, w) -> { /* leave dialog dismiss */ })
                .setPositiveButton("Pick Files", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    intent.setType("*/*");
                    startActivityResultBiosPick.launch(intent);
                })
                .setNeutralButton("Pick Folder", (d, w) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    startActivityResultBiosFolderPick.launch(intent);
                })
                .create();
        mBiosPromptDialog.setOnDismissListener(dialog -> mBiosPromptDialog = null);
        mBiosPromptDialog.show();
    }

    private void dismissBiosPromptIfResolved() {
        File biosDir = BiosVerifier.getBiosDirectory(this);
        if (hasAnyBiosFiles(biosDir)) {
            if (mBiosPromptDialog != null && mBiosPromptDialog.isShowing()) {
                try { mBiosPromptDialog.dismiss(); } catch (Exception ignored) {}
            }
        }
    }

    private boolean configureVerifiedBiosFiles() {
        BiosVerifier.ScanResult scan = BiosVerifier.scanVerifiedBioses(this);
        if (!scan.hasAny()) return false;

        String usa = "";
        String europe = "";
        String japan = "";
        String arcade = "";
        BiosVerifier.BiosInfo usaInfo = scan.byRegion.get(BiosVerifier.Region.USA);
        BiosVerifier.BiosInfo europeInfo = scan.byRegion.get(BiosVerifier.Region.EUROPE);
        BiosVerifier.BiosInfo japanInfo = scan.byRegion.get(BiosVerifier.Region.JAPAN);
        BiosVerifier.BiosInfo arcadeInfo = scan.byRegion.get(BiosVerifier.Region.ARCADE);
        if (usaInfo != null) usa = usaInfo.relativePath;
        if (europeInfo != null) europe = europeInfo.relativePath;
        if (japanInfo != null) japan = japanInfo.relativePath;
        if (arcadeInfo != null) arcade = arcadeInfo.relativePath;

        try {
            NativeApp.setVerifiedBiosFiles(usa, europe, japan, arcade);
        } catch (Throwable t) {
            android.util.Log.w("MainActivity", "Unable to pass verified BIOS list to native core", t);
        }
        return true;
    }

    private ColorStateList pressedColorStateList(int base) {
        int pressed = darkenColor(base, 0.85f); // 15% darker when pressed
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_pressed},
                new int[]{}
        };
        int[] colors = new int[]{
                pressed,
                base
        };
        return new ColorStateList(states, colors);
    }

    private int darkenColor(int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = Math.max(0, Math.min(255, (int)(r * factor)));
        g = Math.max(0, Math.min(255, (int)(g * factor)));
        b = Math.max(0, Math.min(255, (int)(b * factor)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void applySavedSettings() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        // Renderer constants (match SettingsDialogFragment)
        final int RENDERER_OPENGL = 12;
        final int RENDERER_SOFTWARE = 13;
        final int RENDERER_VULKAN = 14;
        
        // Default to Automatic (-1) so the core can select a compatible renderer on older devices
        int renderer = prefs.getInt("renderer", -1);
        NativeApp.renderGpu(renderer);

        // Resolution scale multiplier (float), default 1.0
        float scale = prefs.getFloat("upscale_multiplier", 1.0f);
        NativeApp.renderUpscalemultiplier(scale);

        // Aspect ratio: 0=Stretch, 1=Auto 4:3/3:2, 2=4:3, 3=16:9, 4=10:7
        int aspectRatio = prefs.getInt("aspect_ratio", 1); // Default to Auto 4:3/3:2
        NativeApp.setAspectRatio(aspectRatio);

        // Widescreen patches
        boolean widescreenPatches = prefs.getBoolean("widescreen_patches", true);
        NativeApp.setWidescreenPatches(widescreenPatches);

        // No interlacing patches
        boolean noInterlacingPatches = prefs.getBoolean("no_interlacing_patches", true);
        NativeApp.setNoInterlacingPatches(noInterlacingPatches);

        // Texture loading options
        boolean loadTextures = prefs.getBoolean("load_textures", false);
        NativeApp.setLoadTextures(loadTextures);
        
        boolean asyncTextureLoading = prefs.getBoolean("async_texture_loading", true);
        NativeApp.setAsyncTextureLoading(asyncTextureLoading);

        // HUD visibility
        boolean hudVisible = prefs.getBoolean("hud_visible", false);
        NativeApp.setHudVisible(hudVisible);
        
        // Set brighter default brightness (60 instead of 50)
        NativeApp.setShadeBoost(true);
        NativeApp.setShadeBoostBrightness(60);
        NativeApp.setShadeBoostContrast(50);
        NativeApp.setShadeBoostSaturation(50);
    }

    public final ActivityResultLauncher<Intent> startActivityResultLocalFilePlay = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if(result.getResultCode() == Activity.RESULT_OK) {
                    try {
                        Intent _intent = result.getData();
                        if(_intent != null) {
                            m_szGamefile = _intent.getDataString();
                            if(!TextUtils.isEmpty(m_szGamefile)) {
                                try {
                                    SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
                                    prefs.edit().putLong("last_played:" + m_szGamefile, System.currentTimeMillis()).apply();
                                } catch (Throwable ignored) {}
                                restartEmuThread();
                            }
                        }
                    }
                    catch (Exception ignored) {}
                }
            }
        );

    public final ActivityResultLauncher<Intent> startActivityResultBiosPick = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    try {
                        Intent data = result.getData();
                        if (data != null) {
                            File biosDir = BiosVerifier.getBiosDirectory(this);
                            if (!biosDir.exists()) biosDir.mkdirs();

                            BiosImportStats stats = new BiosImportStats();
                            ClipData clipData = data.getClipData();
                            if (clipData != null && clipData.getItemCount() > 0) {
                                for (int i = 0; i < clipData.getItemCount(); i++) {
                                    Uri uri = clipData.getItemAt(i).getUri();
                                    stats.add(importSingleBiosUri(uri, biosDir));
                                }
                            } else {
                                Uri uri = data.getData();
                                if (uri != null) {
                                    stats.add(importSingleBiosUri(uri, biosDir));
                                }
                            }
                            showBiosImportSummary(stats);
                            dismissBiosPromptIfResolved();
                        }
                    } catch (Exception ignored) {}
                }
            });

    public final ActivityResultLauncher<Intent> startActivityResultBiosFolderPick = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    try {
                        Intent data = result.getData();
                        if (data != null) {
                            Uri treeUri = data.getData();
                            if (treeUri != null) {
                                // Persist read permission for future imports, optional
                                final int takeFlags = (data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                                if ((takeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                                    getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                }
                                if ((takeFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                                    getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                                }

                                DocumentFile pickedDir = DocumentFile.fromTreeUri(this, treeUri);
                                if (pickedDir != null && pickedDir.isDirectory()) {
                                    File biosDir = BiosVerifier.getBiosDirectory(this);
                                    if (!biosDir.exists()) biosDir.mkdirs();
                                    BiosImportStats stats = copyDocumentTreeToDirectory(pickedDir, biosDir);
                                    showBiosImportSummary(stats);
                                    dismissBiosPromptIfResolved();
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            });

    public final ActivityResultLauncher<Intent> startActivityResultGamesFolderPick = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    try {
                        Intent data = result.getData();
                        if (data != null) {
                            Uri treeUri = data.getData();
                            if (treeUri != null) {
                                final int takeFlags = (data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                                if (takeFlags != 0) {
                                    try {
                                        getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                                    } catch (SecurityException ignored) {}
                                }
                                SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
                                java.util.ArrayList<String> folders = mAddingGamesFolder
                                        ? getGameFolderUris(prefs) : new java.util.ArrayList<>();
                                if (!folders.contains(treeUri.toString())) folders.add(treeUri.toString());
                                saveGameFolderUris(prefs, folders);
                                if (!mSetupWizardActive) {
                                    androidx.fragment.app.Fragment current = getSupportFragmentManager()
                                            .findFragmentByTag("covers_dialog");
                                    if (current instanceof androidx.fragment.app.DialogFragment) {
                                        ((androidx.fragment.app.DialogFragment) current).dismissAllowingStateLoss();
                                    }
                                    showGamesListOrReselect(true);
                                } else {
                                    Toast.makeText(this, "Games folder set", Toast.LENGTH_SHORT).show();
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
                mAddingGamesFolder = false;
            });

    // SAF data-root picker result
    public final ActivityResultLauncher<Intent> startActivityResultDataRootPick = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    try {
                        Intent data = result.getData();
                        if (data != null) {
                            Uri treeUri = data.getData();
                            if (treeUri != null) {
                                final int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                                try { getContentResolver().takePersistableUriPermission(treeUri, takeFlags); } catch (SecurityException ignored) {}
                                SafManager.setDataRootUri(this, treeUri);
                                // Seed default resources to SAF data root
                                copyAssetsToSafDataRoot();
                                Toast.makeText(this, "Data folder set", Toast.LENGTH_SHORT).show();
                            }
                        }
                    } catch (Exception ignored) {}
                }
            });

    // Guards against running the (slow) SAF seed on more than one thread at once.
    private final java.util.concurrent.atomic.AtomicBoolean mSeedingSafResources =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // Copies assets/resources under the selected SAF data root (resources/..).
    // The copy walks hundreds of files through the Storage Access Framework, and
    // every SAF write is an IPC round-trip - on the UI thread this froze the app
    // for ~60s (ANR) during the setup wizard's data-folder step. Run it on a
    // background thread instead.
    private void copyAssetsToSafDataRoot() {
        Uri root = SafManager.getDataRootUri(this);
        if (root == null) return;
        // Only seed once
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        if (prefs.getBoolean("saf_resources_seeded", false)) return;
        // Don't start a second seed if one is already running.
        if (!mSeedingSafResources.compareAndSet(false, true)) return;

        final Context appContext = getApplicationContext();
        new Thread(() -> {
            try {
                copyAssetAllToSaf(appContext, "resources");
                prefs.edit().putBoolean("saf_resources_seeded", true).apply();
            } catch (Throwable ignored) {
            } finally {
                mSeedingSafResources.set(false);
            }
        }, "SafResourceSeed").start();
    }

    private static class BiosImportStats {
        int verified;
        int rejected;

        void add(BiosVerifier.BiosInfo info) {
            if (info != null) verified++;
            else rejected++;
        }

        void add(BiosImportStats other) {
            if (other == null) return;
            verified += other.verified;
            rejected += other.rejected;
        }
    }

    private BiosVerifier.BiosInfo importSingleBiosUri(Uri uri, File biosDir) {
        if (uri == null) return null;
        
        try {
            String displayName = getDisplayNameFromUri(this, uri);
            if (TextUtils.isEmpty(displayName)) displayName = "bios.bin";
            File outFile = uniqueBiosFile(biosDir, displayName);
            
            // Ensure bios directory exists
            if (!biosDir.exists()) {
                if (!biosDir.mkdirs()) {
                    android.util.Log.e("MainActivity", "Failed to create BIOS directory: " + biosDir.getAbsolutePath());
                    return null;
                }
            }
            
            // Copy the file with improved error handling
            boolean success = copyUriToFile(this, uri, outFile);
            if (!success) {
                android.util.Log.e("MainActivity", "Failed to copy BIOS file: " + displayName);
                try { if (outFile.exists()) outFile.delete(); } catch (Exception ignored) {}
                return null;
            }

            BiosVerifier.BiosInfo verifiedBios = BiosVerifier.verifyFile(this, biosDir, outFile);
            if (verifiedBios == null) {
                android.util.Log.w("MainActivity", "Rejected file without a recognized BIOS hash: " + displayName);
                try { if (outFile.exists()) outFile.delete(); } catch (Exception ignored) {}
                return null;
            }
            
            // Also mirror to SAF data root if set (with error handling)
            android.net.Uri dataRoot = SafManager.getDataRootUri(this);
            if (dataRoot != null) {
                try {
                    androidx.documentfile.provider.DocumentFile target = SafManager.createChild(this, new String[]{"bios"}, outFile.getName(), "application/octet-stream");
                    if (target != null) {
                        try (java.io.InputStream in = new java.io.FileInputStream(outFile)) {
                            if (in != null) {
                                SafManager.copyFromStream(this, in, target.getUri());
                                android.util.Log.d("MainActivity", "BIOS file mirrored to SAF: " + outFile.getName());
                            }
                        } catch (Exception e) {
                            android.util.Log.w("MainActivity", "Failed to mirror BIOS to SAF: " + e.getMessage());
                            // Don't fail the import if SAF mirroring fails
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w("MainActivity", "Failed to create SAF target for BIOS: " + e.getMessage());
                    // Don't fail the import if SAF mirroring fails
                }
            }
            
            android.util.Log.d("MainActivity", "BIOS import verified successfully: " + outFile.getName());
            configureVerifiedBiosFiles();
            return verifiedBios;
            
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Error during BIOS import: " + e.getMessage());
        }
        return null;
    }

    private BiosImportStats copyDocumentTreeToDirectory(DocumentFile dir, File outDir) {
        BiosImportStats stats = new BiosImportStats();
        if (dir == null || !dir.isDirectory()) return stats;
        DocumentFile[] children = dir.listFiles();
        if (children == null) return stats;
        for (DocumentFile child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                stats.add(copyDocumentTreeToDirectory(child, outDir));
            } else if (child.isFile()) {
                stats.add(importSingleBiosUri(child.getUri(), outDir));
            }
        }
        return stats;
    }

    private void showBiosImportSummary(BiosImportStats stats) {
        if (stats == null) return;
        if (stats.verified > 0) {
            String message = stats.verified == 1 ? "Verified BIOS imported" : stats.verified + " verified BIOS files imported";
            if (stats.rejected > 0) message += "; " + stats.rejected + " rejected";
            Toast.makeText(this, message + ". " + BiosVerifier.describeVerifiedRegions(this), Toast.LENGTH_LONG).show();
            refreshDrawerSettings();
        } else if (stats.rejected > 0) {
            Toast.makeText(this, "No selected BIOS matched the Redump DAT hash.", Toast.LENGTH_LONG).show();
        }
    }

    private static File uniqueBiosFile(File biosDir, String displayName) {
        String safeName = sanitizeBiosFileName(displayName);
        File candidate = new File(biosDir, safeName);
        if (!candidate.exists()) return candidate;

        String base = safeName;
        String ext = "";
        int dot = safeName.lastIndexOf('.');
        if (dot > 0) {
            base = safeName.substring(0, dot);
            ext = safeName.substring(dot);
        }

        for (int i = 2; i < 1000; i++) {
            candidate = new File(biosDir, base + "-" + i + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(biosDir, base + "-" + System.currentTimeMillis() + ext);
    }

    private static String sanitizeBiosFileName(String name) {
        if (TextUtils.isEmpty(name)) return "bios.bin";
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return TextUtils.isEmpty(safe) ? "bios.bin" : safe;
    }

    private static String getDisplayNameFromUri(Context context, Uri uri) {
        String name = null;
        Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex);
                }
            } finally {
                cursor.close();
            }
        }
        if (TextUtils.isEmpty(name)) {
            name = uri.getLastPathSegment();
        }
        return name;
    }

    private static boolean copyUriToFile(Context context, Uri uri, File destFile) {
        InputStream is = null;
        FileOutputStream os = null;
        try {
            is = context.getContentResolver().openInputStream(uri);
            if (is == null) return false;
            os = new FileOutputStream(destFile);
            
            // Use smaller buffer size for lower-end devices to reduce memory pressure
            byte[] buffer = new byte[4096]; // Reduced from 8192
            int read;
            long totalBytes = 0;
            long maxFileSize = 16 * 1024 * 1024; // 16MB max BIOS file size limit
            
            while ((read = is.read(buffer)) != -1) {
                totalBytes += read;
                
                // Check file size limit to prevent memory issues on lower-end devices
                if (totalBytes > maxFileSize) {
                    android.util.Log.w("MainActivity", "BIOS file too large: " + totalBytes + " bytes");
                    throw new IOException("BIOS file too large: " + totalBytes + " bytes");
                }
                
                os.write(buffer, 0, read);
                
                // Force periodic flush to reduce memory pressure
                if (totalBytes % (64 * 1024) == 0) { // Flush every 64KB
                    os.flush();
                }
            }
            os.flush();
            
            // Verify the file was copied successfully
            if (destFile.length() == 0) {
                android.util.Log.e("MainActivity", "BIOS file copy failed: empty file");
                return false;
            }
            
            android.util.Log.d("MainActivity", "BIOS file copied successfully: " + destFile.getName() +
                              " (" + destFile.length() + " bytes)");
            return true;
        } catch (OutOfMemoryError e) {
            android.util.Log.e("MainActivity", "Out of memory during BIOS file copy: " + e.getMessage());
            // Try to clean up partial file
            try { if (destFile.exists()) destFile.delete(); } catch (Exception ignored) {}
            return false;
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Error copying BIOS file: " + e.getMessage());
            // Try to clean up partial file
            try { if (destFile.exists()) destFile.delete(); } catch (Exception ignored) {}
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            try { if (os != null) os.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onPause() {
        mActivityPauseRequested = true;
        applyRequestedPauseState("activity paused");
        super.onPause();
        ////
        if (mHIDDeviceManager != null) {
            mHIDDeviceManager.setFrozen(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mActivityPauseRequested = false;
        reconcileAutomaticPauseOwners();
        applyRequestedPauseState("activity resumed");
        applyRequestedPauseStateDelayed("activity resumed (settled)");
        ////
        if (mHIDDeviceManager != null) {
            mHIDDeviceManager.setFrozen(false);
        }
        // Re-assert full screen when returning to the activity
        enableImmersiveMode();
        updateUiForControllerPresence();
    }

    @Override
    protected void onDestroy() {
        try {
            NativeApp.achievementsLogout();
            NativeApp.achievementsShutdown();
        } catch (Throwable t) {
            android.util.Log.w("MainActivity", "Error shutting down achievements on exit: " + t.getMessage());
        }
        NativeApp.shutdown();
        super.onDestroy();
        ////
        if (mHIDDeviceManager != null) {
            HIDDeviceManager.release(mHIDDeviceManager);
            mHIDDeviceManager = null;
        }
        if (mInputManager != null) {
            try { mInputManager.unregisterInputDeviceListener(mInputDeviceListener); } catch (Exception ignored) {}
            mInputManager = null;
        }
        ////
        Thread threadToJoin;
        synchronized (mEmulationThreadLock) {
            threadToJoin = mEmulationThread;
        }
        if (threadToJoin != null) {
            try {
                threadToJoin.join();
                synchronized (mEmulationThreadLock) {
                    if (mEmulationThread == threadToJoin) {
                        mEmulationThread = null;
                    }
                }
            }
            catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        int appPid = android.os.Process.myPid();
        android.os.Process.killProcess(appPid);
    }

    public void Initialize() {
        NativeApp.initializeOnce(getApplicationContext());

        // Set up JNI
        SDLControllerManager.nativeSetupJNI();

        // Initialize state
        SDLControllerManager.initialize();

        // Load and apply saved settings
        loadAndApplyStoredSettings();

        mHIDDeviceManager = HIDDeviceManager.acquire(this);
        // Initialize HID device manager for USB and Bluetooth controllers
        mHIDDeviceManager.initialize(true, true);
        
    }

    private void setSurfaceView(Object p_value) {
        FrameLayout fl_board = findViewById(R.id.fl_board);
        if(fl_board != null) {
            if(fl_board.getChildCount() > 0) {
                fl_board.removeAllViews();
            }
            ////
            if(p_value instanceof SDLSurface) {
                fl_board.addView((SDLSurface)p_value);
            }
        }
    }

    public void startEmuThread() {
        // Ensure BIOS present before starting emulation
        if (!ensureBiosOrPrompt()) return;

        synchronized (mEmulationThreadLock) {
            if ((mEmulationThread != null && mEmulationThread.isAlive()) || isNativeVMActive()) {
                return;
            }

            final String gameFile = m_szGamefile;
            // Restore global cards, then apply this exact file URI's overrides before boot.
            MemoryCardSettings.applyForGame(this, gameFile);
            NativeApp.prepareVMStart();

            Thread emulationThread = new Thread(() -> {
                try {
                    final boolean started = NativeApp.runVMThread(gameFile);
                    if (!started && !TextUtils.isEmpty(gameFile)) {
                        final String startupError = NativeApp.getLastVMError();
                        if (!TextUtils.isEmpty(startupError)) {
                            runOnUiThread(() -> {
                                if (isFinishing() || isDestroyed()) return;
                                new MaterialAlertDialogBuilder(MainActivity.this)
                                        .setTitle("Game failed to start")
                                        .setMessage(startupError)
                                        .setPositiveButton(android.R.string.ok, null)
                                        .show();
                            });
                        }
                    }
                } finally {
                    synchronized (mEmulationThreadLock) {
                        if (mEmulationThread == Thread.currentThread()) {
                            mEmulationThread = null;
                        }
                    }
                }
            }, "EmulationThread");
            mEmulationThread = emulationThread;
            emulationThread.start();
        }

        // Show pause button when game starts (game is always running initially)
        runOnUiThread(() -> {
            MaterialButton btn_pause_play = findViewById(R.id.btn_pause_play);
            if (btn_pause_play != null) {
                btn_pause_play.setVisibility(View.VISIBLE);
                btn_pause_play.setIcon(ContextCompat.getDrawable(this, R.drawable.play_pause_24px));
            }
        });
    }

    public void applyCurrentMemoryCardSettings() {
        MemoryCardSettings.applyForGameAsync(this, m_szGamefile);
    }

    private void restartEmuThread() {
        // Ensure BIOS present before starting/restarting emulation
        if (!ensureBiosOrPrompt()) return;
        mUserPauseRequested = false;
        final int generation = mEmulationRestartGeneration.incrementAndGet();
        final String requestedGame = m_szGamefile;
        final int renderer = getSharedPreferences("app_prefs", MODE_PRIVATE)
                .getInt("renderer", -1);

        // Shutdown and Thread.join() can take several seconds for some arcade games.
        // Never wait for the emulation thread from Android's UI thread.
        mEmulationControlExecutor.execute(() -> {
            try {
                NativeApp.shutdown();
                Thread threadToJoin;
                synchronized (mEmulationThreadLock) {
                    threadToJoin = mEmulationThread;
                }
                if (threadToJoin != null && threadToJoin != Thread.currentThread()) {
                    threadToJoin.join();
                    synchronized (mEmulationThreadLock) {
                        if (mEmulationThread == threadToJoin) {
                            mEmulationThread = null;
                        }
                    }
                }
                if (generation != mEmulationRestartGeneration.get()) return;
                android.util.Log.d("MainActivity",
                        "Applying global renderer before game restart: " + renderer);
                NativeApp.renderGpu(renderer);
                runOnUiThread(() -> {
                    if (generation != mEmulationRestartGeneration.get()
                            || isFinishing() || isDestroyed()
                            || !requestedGame.equals(m_szGamefile)) return;
                    startEmuThread();
                    applyRequestedPauseState("game started");
                });
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable error) {
                android.util.Log.e("MainActivity", "Unable to restart emulation", error);
            }
        });
    }

    // (Renderer toast temporarily removed per user request — AUTO behavior retained.)

    // Public API for UI components to reboot the emulator
    public void rebootEmu() {
        if (!TextUtils.isEmpty(m_szGamefile)) {
            restartEmuThread();
        } else {
            // No game loaded; just shutdown for safety
            NativeApp.shutdown();
            updatePausePlayButton();
        }
    }

    

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        // Use only our controller handler - disable SDL fallback to avoid conflicts
        if (mControllerInputHandler != null && mControllerInputHandler.handleMotionEvent(event)) {
            return true;
        }
        
        // Skip SDL controller manager to avoid mapping conflicts
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Allow dialogs to receive DPAD navigation when visible
        if (!isAnyAppDialogShowing()) {
            // Intercept controller DPAD/gamepad keys before focused views consume them
            if (mControllerInputHandler != null && mControllerInputHandler.handleKeyEvent(event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isAnyAppDialogShowing() {
        try {
            java.util.List<androidx.fragment.app.Fragment> frags = getSupportFragmentManager().getFragments();
            for (androidx.fragment.app.Fragment f : frags) {
                if (f instanceof androidx.fragment.app.DialogFragment) {
                    android.app.Dialog d = ((androidx.fragment.app.DialogFragment) f).getDialog();
                    if (d != null && d.isShowing()) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    public boolean onKeyDown(int p_keyCode, KeyEvent p_event) {
        // Use only our controller handler - disable SDL fallback to avoid conflicts
        if (mControllerInputHandler != null && mControllerInputHandler.handleKeyEvent(p_event)) {
            return true;
        }
        
        // Skip SDL controller manager to avoid mapping conflicts
        return super.onKeyDown(p_keyCode, p_event);
    }

    @Override
    public boolean onKeyUp(int p_keyCode, KeyEvent p_event) {
        // Use only our controller handler - disable SDL fallback to avoid conflicts
        if (mControllerInputHandler != null && mControllerInputHandler.handleKeyEvent(p_event)) {
            return true;
        }
        
        // Skip SDL controller manager to avoid mapping conflicts
        return super.onKeyUp(p_keyCode, p_event);
    }

    public static void sendKeyAction(View p_view, int p_action, int p_keycode) {
        if(p_action == MotionEvent.ACTION_DOWN) {
            p_view.setPressed(true);
            int pad_force = 0;
            if(p_keycode >= 110) {
                float _abs = 90; // Joystic test value
                _abs = Math.min(_abs, 100);
                pad_force = (int) (_abs * 32766.0f / 100);
            }
            NativeApp.setPadButton(p_keycode, pad_force, true);
        } else if(p_action == MotionEvent.ACTION_UP || p_action == MotionEvent.ACTION_CANCEL) {
            p_view.setPressed(false);
            NativeApp.setPadButton(p_keycode, 0, false);
        }
    }

    // Asset copy helpers used on first launch to seed default resources
    private void copyAssetAll(Context context, String srcPath) {
        AssetManager assetMgr = context.getAssets();
        try {
            String[] assets = assetMgr.list(srcPath);
            String destPath = context.getExternalFilesDir(null) + File.separator + srcPath;
            if (assets != null) {
                if (assets.length == 0) {
                    copyFile(context, srcPath, destPath);
                } else {
                    File dir = new File(destPath);
                    if (!dir.exists()) dir.mkdirs();
                    for (String element : assets) {
                        copyAssetAll(context, srcPath + File.separator + element);
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    // Mirror asset folder into SAF data root (if set)
    private void copyAssetAllToSaf(Context context, String srcPath) {
        Uri dataRoot = SafManager.getDataRootUri(context);
        if (dataRoot == null) return;
        AssetManager assetMgr = context.getAssets();
        try {
            String[] assets = assetMgr.list(srcPath);
            if (assets != null) {
                if (assets.length == 0) {
                    // It's a file under srcPath; create it in SAF
                    String[] parts = srcPath.split("/");
                    String filename = parts.length > 0 ? parts[parts.length - 1] : srcPath;
                    String[] dirSegs = parts.length > 1 ? java.util.Arrays.copyOf(parts, parts.length - 1) : new String[0];
                    DocumentFile existing = SafManager.getChild(context, dirSegs, filename);
                    if (existing != null && existing.length() > 0) return;
                    DocumentFile target = SafManager.createChild(context, dirSegs, filename, guessMime(filename));
                    if (target != null) {
                        try (InputStream is = assetMgr.open(srcPath)) {
                            SafManager.copyFromStream(context, is, target.getUri());
                        } catch (Exception ignored) {}
                    }
                } else {
                    for (String element : assets) {
                        copyAssetAllToSaf(context, srcPath + File.separator + element);
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    private static String guessMime(String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/x-yaml";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private static void copyFile(Context context, String srcFile, String destFile) {
        AssetManager assetMgr = context.getAssets();
        InputStream is = null;
        FileOutputStream os = null;
        try {
            is = assetMgr.open(srcFile);
            boolean exists = new File(destFile).exists();
            if (srcFile.contains("shaders")) {
                exists = false; // always refresh shaders
            }
            if (!exists) {
                File parent = new File(destFile).getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                os = new FileOutputStream(destFile);
                byte[] buffer = new byte[4096];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try { if (is != null) is.close(); } catch (IOException ignored) {}
            try { if (os != null) os.close(); } catch (IOException ignored) {}
        }
    }

    // Removed deprecated onBackPressed() - using OnBackPressedCallback instead

    private void showExitDialog() {
        new MaterialAlertDialogBuilder(this,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setCustomTitle(UiUtils.centeredDialogTitle(this, "Exit App"))
                .setMessage("Do you want to exit PSX2?")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("Exit", (dialog, which) -> {
                    // Stop emulator first
                    NativeApp.shutdown();
                    // Quit the app
                    finishAffinity();
                    finishAndRemoveTask();
                    // As a fallback ensure process exit
                    System.exit(0);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ControllerInputHandler.ControllerInputListener implementation
    @Override
    public void onControllerButtonPressed(int controllerId, int button, boolean pressed) {
        android.util.Log.d("Controller", "Controller " + controllerId + " button " + button + " (" + getButtonName(button) + ") " + (pressed ? "pressed" : "released"));

        // Send controller input to native code using existing setPadButton method
        // Range 255 for pressed, 0 for released (matching PS2 button range)
        int range = pressed ? 255 : 0;
        NativeApp.setPadButton(button, range, pressed);

        // Hide touch controls as soon as controller activity is detected
        maybeActivateControllerUi();
    }
    
    private String getButtonName(int button) {
        switch (button) {
            case KeyEvent.KEYCODE_BUTTON_A: return "Cross";
            case KeyEvent.KEYCODE_BUTTON_B: return "Circle";
            case KeyEvent.KEYCODE_BUTTON_X: return "Square";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Triangle";
            case KeyEvent.KEYCODE_BUTTON_L1: return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "R2";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "Select";
            case KeyEvent.KEYCODE_BUTTON_START: return "Start";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "R3";
            case KeyEvent.KEYCODE_DPAD_UP: return "D-Up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "D-Down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "D-Left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "D-Right";
            default: return "Unknown(" + button + ")";
        }
    }

    @Override
    public void onControllerAnalogInput(int controllerId, int axis, float value) {
        android.util.Log.d("Controller", "Controller " + controllerId + " axis " + axis + " (" + getAxisName(axis) + ") value " + value);

        // Send analog input to native code using setPadButton
        handleAnalogInput(axis, value);

        // Hide touch controls on analog movement as well
        if (Math.abs(value) > 0.1f) {
            maybeActivateControllerUi();
        }
    }

    private void maybeActivateControllerUi() {
        if (!controllerUiApplied) {
            controllerUiApplied = true;
            runOnUiThread(() -> setControlsVisible(false));
        }
    }

    @Override
    public void onControllerCombo(int controllerId, String comboName) {
        android.util.Log.d("Controller", "Controller " + controllerId + " combo: " + comboName);
        
        if ("select_start".equals(comboName)) {
            // Dialog tracking owns the temporary pause. Do not turn it into a user
            // pause, otherwise closing Quick Actions leaves the game stuck paused.
            runOnUiThread(() -> {
                QuickActionsDialogFragment dialog = new QuickActionsDialogFragment();
                dialog.show(getSupportFragmentManager(), "quick_actions");
            });
        }
    }
    
    private String getAxisName(int axis) {
        switch (axis) {
            case 110: return "L-Up";
            case 111: return "L-Right";
            case 112: return "L-Down";
            case 113: return "L-Left";
            case 120: return "R-Up";
            case 121: return "R-Right";
            case 122: return "R-Down";
            case 123: return "R-Left";
            case KeyEvent.KEYCODE_BUTTON_L2: return "L2-Trigger";
            case KeyEvent.KEYCODE_BUTTON_R2: return "R2-Trigger";
            default: return "Unknown(" + axis + ")";
        }
    }
    
    private void handleAnalogInput(int axis, float value) {
        // Convert analog input to button presses for the native interface    
        // For analog sticks, only send positive values (negative values are handled by opposite direction)
        int intensity = Math.max(0, Math.round(Math.abs(value) * 255));
        boolean pressed = Math.abs(value) > 0.1f;
        
        switch (axis) {
            case ControllerInputHandler.PAD_L_UP:
            case ControllerInputHandler.PAD_L_DOWN:
            case ControllerInputHandler.PAD_L_LEFT:
            case ControllerInputHandler.PAD_L_RIGHT:
            case ControllerInputHandler.PAD_R_UP:
            case ControllerInputHandler.PAD_R_DOWN:
            case ControllerInputHandler.PAD_R_LEFT:
            case ControllerInputHandler.PAD_R_RIGHT:
                // Analog stick directions
                NativeApp.setPadButton(axis, intensity, pressed);
                break;
            case ControllerInputHandler.PAD_L2:
            case ControllerInputHandler.PAD_R2:
                // Triggers: 0-255 range
                NativeApp.setPadButton(axis, Math.round(value * 255), value > 0.1f);
                break;
        }
    }

    private int normalizeOrientationPref(int value) {
        if (value == ORIENTATION_LANDSCAPE || value == ORIENTATION_PORTRAIT) return value;
        return ORIENTATION_AUTO;
    }

    public void setOrientationPreference(int mode) {
        int normalized = normalizeOrientationPref(mode);
        try {
            SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            prefs.edit().putInt("orientation_lock", normalized).apply();
        } catch (Throwable ignored) {}
        applyOrientationPreference(normalized);
    }

    public void applyOrientationPreference() {
        int mode = ORIENTATION_AUTO;
        try {
            SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            mode = normalizeOrientationPref(prefs.getInt("orientation_lock", ORIENTATION_AUTO));
        } catch (Throwable ignored) {}
        applyOrientationPreference(mode);
    }

    private void applyOrientationPreference(int mode) {
        int requested = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        if (mode == ORIENTATION_LANDSCAPE) {
            requested = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        } else if (mode == ORIENTATION_PORTRAIT) {
            requested = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        }

        try {
            setRequestedOrientation(requested);
        } catch (Throwable e) {
            android.util.Log.e("MainActivity", "Failed to apply orientation preference: " + e.getMessage());
        }
    }

    private void setupDrawerSettings(View header) {
        if (header == null) return;

        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        setupDrawerBiosControls(header);

        MaterialButtonToggleGroup tgOrientation = header.findViewById(R.id.drawer_tg_orientation);
        View tbOrientAuto = header.findViewById(R.id.drawer_tb_orientation_auto);
        View tbOrientLand = header.findViewById(R.id.drawer_tb_orientation_landscape);
        View tbOrientPort = header.findViewById(R.id.drawer_tb_orientation_portrait);
        if (tgOrientation != null && tbOrientAuto != null && tbOrientLand != null && tbOrientPort != null) {
            int savedOrientation = normalizeOrientationPref(prefs.getInt("orientation_lock", ORIENTATION_AUTO));
            int checkId = savedOrientation == ORIENTATION_LANDSCAPE ? tbOrientLand.getId()
                    : (savedOrientation == ORIENTATION_PORTRAIT ? tbOrientPort.getId() : tbOrientAuto.getId());
            tgOrientation.check(checkId);
            tgOrientation.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked) return;
                int mode = ORIENTATION_AUTO;
                if (checkedId == tbOrientLand.getId()) mode = ORIENTATION_LANDSCAPE;
                else if (checkedId == tbOrientPort.getId()) mode = ORIENTATION_PORTRAIT;
                setOrientationPreference(mode);
            });
        }

        // Aspect Ratio spinner - setup like QuickActions
        Spinner spAspect = header.findViewById(R.id.drawer_sp_aspect_ratio);
        if (spAspect != null) {
            spAspect.setOnItemSelectedListener(null);
            if (spAspect.getAdapter() == null) {
                ArrayAdapter<CharSequence> aspectAdapter = ArrayAdapter.createFromResource(this,
                        R.array.aspect_ratio_entries, android.R.layout.simple_spinner_item);
                aspectAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spAspect.setAdapter(aspectAdapter);
            }
            ArrayAdapter<?> adapter = (ArrayAdapter<?>) spAspect.getAdapter();
            if (adapter != null) {
                int savedAspect = prefs.getInt("aspect_ratio", 1);
                if (savedAspect < 0 || savedAspect >= adapter.getCount()) savedAspect = 1;
                spAspect.setSelection(savedAspect, false);
            }
            spAspect.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    if (position == prefs.getInt("aspect_ratio", 1)) return;
                    prefs.edit().putInt("aspect_ratio", position).apply();
                    NativeApp.setAspectRatioAsync(position);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }

        // Resolution Scale spinner
        Spinner spScale = header.findViewById(R.id.drawer_sp_scale);
        if (spScale != null) {
            spScale.setOnItemSelectedListener(null);
            if (spScale.getAdapter() == null) {
                ArrayAdapter<CharSequence> scaleAdapter = ArrayAdapter.createFromResource(this,
                        R.array.scale_entries, android.R.layout.simple_spinner_item);
                scaleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spScale.setAdapter(scaleAdapter);
            }
            ArrayAdapter<?> adapter = (ArrayAdapter<?>) spScale.getAdapter();
            if (adapter != null) {
                float savedScale = prefs.getFloat("upscale_multiplier", 1.0f);
                int scaleIndex = Math.max(0, Math.min(adapter.getCount() - 1, Math.round(savedScale) - 1));
                spScale.setSelection(scaleIndex, false);
            }
            spScale.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    float scale = Math.max(1, Math.min(8, position + 1));
                    if (Math.abs(prefs.getFloat("upscale_multiplier", 1.0f) - scale) < 0.001f) return;
                    prefs.edit().putFloat("upscale_multiplier", scale).apply();
                    NativeApp.renderUpscalemultiplierAsync(scale);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }

        // Blending Accuracy spinner - setup like QuickActions
        Spinner spBlending = header.findViewById(R.id.drawer_sp_blending_accuracy);
        if (spBlending != null) {
            spBlending.setOnItemSelectedListener(null);
            if (spBlending.getAdapter() == null) {
                ArrayAdapter<CharSequence> blendAdapter = ArrayAdapter.createFromResource(this,
                        R.array.blending_accuracy_entries, android.R.layout.simple_spinner_item);
                blendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spBlending.setAdapter(blendAdapter);
            }
            ArrayAdapter<?> adapter = (ArrayAdapter<?>) spBlending.getAdapter();
            if (adapter != null) {
                int savedBlend = prefs.getInt("blending_accuracy", 1);
                if (savedBlend < 0 || savedBlend >= adapter.getCount()) savedBlend = 1;
                spBlending.setSelection(savedBlend, false);
            }
            spBlending.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    if (position == prefs.getInt("blending_accuracy", 1)) return;
                    prefs.edit().putInt("blending_accuracy", position).apply();
                    NativeApp.setBlendingAccuracyAsync(position);
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }

        // Setup switch listeners only once
        setupDrawerSwitchListeners(header, prefs);
    }

    private void setupDrawerSwitchListeners(View header, SharedPreferences prefs) {
        // Vertical Sync switch
        com.google.android.material.materialswitch.MaterialSwitch swVsync = header.findViewById(R.id.drawer_sw_vsync);
        if (swVsync != null) {
            swVsync.setTag("setup");
            swVsync.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked == prefs.getBoolean("vsync_enabled", false)) return;
                prefs.edit().putBoolean("vsync_enabled", isChecked).apply();
                NativeApp.setVsyncEnabledAsync(isChecked);
            });
        }

        // Widescreen Patches switch
        com.google.android.material.materialswitch.MaterialSwitch swWide = header.findViewById(R.id.drawer_sw_widescreen);
        if (swWide != null) {
            swWide.setTag("setup");
            swWide.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked == prefs.getBoolean("widescreen_patches", true)) return;
                prefs.edit().putBoolean("widescreen_patches", isChecked).apply();
                NativeApp.setWidescreenPatchesAsync(isChecked);
            });
        }

        // No Interlacing switch
        com.google.android.material.materialswitch.MaterialSwitch swNoInt = header.findViewById(R.id.drawer_sw_no_interlacing);
        if (swNoInt != null) {
            swNoInt.setTag("setup");
            swNoInt.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked == prefs.getBoolean("no_interlacing_patches", true)) return;
                prefs.edit().putBoolean("no_interlacing_patches", isChecked).apply();
                NativeApp.setNoInterlacingPatchesAsync(isChecked);
            });
        }

        // Load Textures switch
        com.google.android.material.materialswitch.MaterialSwitch swLoadTex = header.findViewById(R.id.drawer_sw_load_textures);
        if (swLoadTex != null) {
            swLoadTex.setTag("setup");
            swLoadTex.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked == prefs.getBoolean("load_textures", false)) return;
                prefs.edit().putBoolean("load_textures", isChecked).apply();
                NativeApp.setLoadTexturesAsync(isChecked);
            });
        }

        // Async Textures switch
        com.google.android.material.materialswitch.MaterialSwitch swAsyncTex = header.findViewById(R.id.drawer_sw_async_textures);
        if (swAsyncTex != null) {
            swAsyncTex.setTag("setup");
            swAsyncTex.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked == prefs.getBoolean("async_texture_loading", true)) return;
                prefs.edit().putBoolean("async_texture_loading", isChecked).apply();
                NativeApp.setAsyncTextureLoadingAsync(isChecked);
            });
        }

        // Precache Textures switch
        com.google.android.material.materialswitch.MaterialSwitch swPrecache = header.findViewById(R.id.drawer_sw_precache_textures);
        if (swPrecache != null) {
            swPrecache.setTag("setup");
            swPrecache.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked == prefs.getBoolean("precache_textures", false)) return;
                prefs.edit().putBoolean("precache_textures", isChecked).apply();
                NativeApp.setPrecacheTextureReplacementsAsync(isChecked);
            });
        }

        // HUD Developer switch
        com.google.android.material.materialswitch.MaterialSwitch swDevHud = header.findViewById(R.id.drawer_sw_dev_hud);
        if (swDevHud != null) {
            swDevHud.setTag("setup");
            swDevHud.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked == prefs.getBoolean("hud_visible", false)) return;
                prefs.edit().putBoolean("hud_visible", isChecked).apply();
                NativeApp.setHudVisibleAsync(isChecked);
            });
        }

        // Touch right stick joystick (optional on-screen control)
        com.google.android.material.materialswitch.MaterialSwitch swTouchRightStick = header.findViewById(R.id.drawer_sw_touch_right_stick);
        if (swTouchRightStick != null) {
            swTouchRightStick.setTag("setup");
            swTouchRightStick.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean(PREF_TOUCH_RIGHT_STICK, isChecked).apply();
                if (!isChecked) releaseVirtualRightStickInputs();
                try { updateUiForControllerPresence(); } catch (Throwable ignored) {}
            });
        }
    }

    private void clearDrawerSwitchListeners(View header) {
        int[] switchIds = {
                R.id.drawer_sw_vsync,
                R.id.drawer_sw_widescreen,
                R.id.drawer_sw_no_interlacing,
                R.id.drawer_sw_load_textures,
                R.id.drawer_sw_async_textures,
                R.id.drawer_sw_precache_textures,
                R.id.drawer_sw_dev_hud,
                R.id.drawer_sw_touch_right_stick
        };

        for (int switchId : switchIds) {
            com.google.android.material.materialswitch.MaterialSwitch switchView = header.findViewById(switchId);
            if (switchView != null) {
                switchView.setOnCheckedChangeListener(null);
                switchView.setTag(null);
            }
        }
    }

    private void refreshDrawerSettings() {
        try {
            NavigationView nav = findViewById(R.id.nav_view);
            if (nav == null) {
                android.util.Log.w("MainActivity", "NavigationView not found during refreshDrawerSettings");
                return;
            }
            
            // Ensure header exists before trying to access it
            if (nav.getHeaderCount() == 0) {
                android.util.Log.w("MainActivity", "NavigationView has no header during refreshDrawerSettings");
                return;
            }
            
            View header = nav.getHeaderView(0);
            if (header == null) {
                android.util.Log.w("MainActivity", "NavigationView header is null during refreshDrawerSettings");
                return;
            }
            
            SharedPreferences prefs = null;
            try {
                prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Error getting SharedPreferences: " + e.getMessage());
                return;
            }
            
            if (prefs == null) {
                android.util.Log.e("MainActivity", "SharedPreferences is null during refreshDrawerSettings");
                return;
            }

            refreshDrawerBiosStatus(header);

            try {
                MaterialButtonToggleGroup tgOrientation = header.findViewById(R.id.drawer_tg_orientation);
                View tbOrientAuto = header.findViewById(R.id.drawer_tb_orientation_auto);
                View tbOrientLand = header.findViewById(R.id.drawer_tb_orientation_landscape);
                View tbOrientPort = header.findViewById(R.id.drawer_tb_orientation_portrait);
                if (tgOrientation != null && tbOrientAuto != null && tbOrientLand != null && tbOrientPort != null) {
                    int savedOrientation = normalizeOrientationPref(prefs.getInt("orientation_lock", ORIENTATION_AUTO));
                    int checkId = savedOrientation == ORIENTATION_LANDSCAPE ? tbOrientLand.getId()
                            : (savedOrientation == ORIENTATION_PORTRAIT ? tbOrientPort.getId() : tbOrientAuto.getId());
                    tgOrientation.check(checkId);
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Error refreshing orientation toggle: " + e.getMessage());
            }

            // Refresh spinner values to reflect current settings
            try {
                Spinner spAspect = header.findViewById(R.id.drawer_sp_aspect_ratio);
                if (spAspect != null && spAspect.getAdapter() != null) {
                    int savedAspect = prefs.getInt("aspect_ratio", 1);
                    ArrayAdapter<?> aspectAdapter = (ArrayAdapter<?>) spAspect.getAdapter();
                    if (savedAspect >= 0 && savedAspect < aspectAdapter.getCount()) {
                        spAspect.setSelection(savedAspect, false);
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Error refreshing aspect ratio spinner: " + e.getMessage());
            }

            try {
                Spinner spScale = header.findViewById(R.id.drawer_sp_scale);
                if (spScale != null && spScale.getAdapter() != null) {
                    float savedScale = prefs.getFloat("upscale_multiplier", 1.0f);
                    ArrayAdapter<?> scaleAdapter = (ArrayAdapter<?>) spScale.getAdapter();
                    int scaleIndex = Math.max(0, Math.min(scaleAdapter.getCount() - 1, Math.round(savedScale) - 1));
                    spScale.setSelection(scaleIndex, false);
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Error refreshing scale spinner: " + e.getMessage());
            }

            try {
                Spinner spBlending = header.findViewById(R.id.drawer_sp_blending_accuracy);
                if (spBlending != null && spBlending.getAdapter() != null) {
                    int savedBlend = prefs.getInt("blending_accuracy", 1);
                    ArrayAdapter<?> blendAdapter = (ArrayAdapter<?>) spBlending.getAdapter();
                    if (savedBlend >= 0 && savedBlend < blendAdapter.getCount()) {
                        spBlending.setSelection(savedBlend, false);
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Error refreshing blending spinner: " + e.getMessage());
            }
            
            // Refresh switch states with individual error handling. Detach listeners so
            // programmatic drawer sync never applies native settings from the UI thread.
            clearDrawerSwitchListeners(header);
            try {
                com.google.android.material.materialswitch.MaterialSwitch swVsync = header.findViewById(R.id.drawer_sw_vsync);
                if (swVsync != null) {
                    swVsync.setChecked(prefs.getBoolean("vsync_enabled", false));
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Error refreshing VSync switch: " + e.getMessage());
            }

            try {
                com.google.android.material.materialswitch.MaterialSwitch swWide = header.findViewById(R.id.drawer_sw_widescreen);
                if (swWide != null) {
                    swWide.setChecked(prefs.getBoolean("widescreen_patches", true));
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Error refreshing widescreen switch: " + e.getMessage());
            }

            try {
                com.google.android.material.materialswitch.MaterialSwitch swNoInt = header.findViewById(R.id.drawer_sw_no_interlacing);
                if (swNoInt != null) {
                    swNoInt.setChecked(prefs.getBoolean("no_interlacing_patches", true));
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Error refreshing no interlacing switch: " + e.getMessage());
            }

            try {
                com.google.android.material.materialswitch.MaterialSwitch swLoadTex = header.findViewById(R.id.drawer_sw_load_textures);
                if (swLoadTex != null) {
                    swLoadTex.setChecked(prefs.getBoolean("load_textures", false));
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Error refreshing load textures switch: " + e.getMessage());
            }

            try {
                com.google.android.material.materialswitch.MaterialSwitch swAsyncTex = header.findViewById(R.id.drawer_sw_async_textures);
                if (swAsyncTex != null) {
                    swAsyncTex.setChecked(prefs.getBoolean("async_texture_loading", true));
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Error refreshing async textures switch: " + e.getMessage());
            }

            try {
                com.google.android.material.materialswitch.MaterialSwitch swPrecache = header.findViewById(R.id.drawer_sw_precache_textures);
                if (swPrecache != null) {
                    swPrecache.setChecked(prefs.getBoolean("precache_textures", false));
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Error refreshing precache textures switch: " + e.getMessage());
            }

            try {
                com.google.android.material.materialswitch.MaterialSwitch swDevHud = header.findViewById(R.id.drawer_sw_dev_hud);
                if (swDevHud != null) {
                    swDevHud.setChecked(prefs.getBoolean("hud_visible", false));
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Error refreshing dev HUD switch: " + e.getMessage());
            }

            try {
                com.google.android.material.materialswitch.MaterialSwitch swTouchRightStick = header.findViewById(R.id.drawer_sw_touch_right_stick);
                if (swTouchRightStick != null) {
                    swTouchRightStick.setChecked(prefs.getBoolean(PREF_TOUCH_RIGHT_STICK, false));
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Error refreshing touch right stick switch: " + e.getMessage());
            }

            setupDrawerSwitchListeners(header, prefs);
            
        } catch (Throwable t) {
            android.util.Log.e("MainActivity", "Unexpected error in refreshDrawerSettings: " + t.getMessage());
        }
    }

    private void setupDrawerBiosControls(View header) {
        if (header == null) return;
        refreshDrawerBiosStatus(header);

        View btnBios = header.findViewById(R.id.drawer_btn_bios);
        if (btnBios != null) {
            btnBios.setOnClickListener(v -> {
                try {
                    DrawerLayout drawer = findViewById(R.id.drawer_layout);
                    if (drawer != null) drawer.closeDrawer(GravityCompat.START);
                } catch (Throwable ignored) {}
                v.post(this::showBiosPrompt);
            });
        }
    }

    private void refreshDrawerBiosStatus(View header) {
        if (header == null) return;
        TextView tvBiosStatus = header.findViewById(R.id.drawer_tv_bios_status);
        if (tvBiosStatus != null) {
            tvBiosStatus.setText(BiosVerifier.describeVerifiedRegions(this));
        }
    }

    private void loadAndApplyStoredSettings() {
        try {
            SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            
            // Apply aspect ratio setting
            int aspectRatio = prefs.getInt("aspect_ratio", 1);
            NativeApp.setAspectRatio(aspectRatio);
            
            // Apply blending accuracy setting
            int blendingAccuracy = prefs.getInt("blending_accuracy", 1);
            NativeApp.setBlendingAccuracy(blendingAccuracy);
            
            // Apply other settings
            boolean widescreenPatches = prefs.getBoolean("widescreen_patches", true);
            NativeApp.setWidescreenPatches(widescreenPatches);
            
            boolean noInterlacing = prefs.getBoolean("no_interlacing_patches", true);
            NativeApp.setNoInterlacingPatches(noInterlacing);
            
            boolean loadTextures = prefs.getBoolean("load_textures", false);
            NativeApp.setLoadTextures(loadTextures);
            
            boolean asyncTextures = prefs.getBoolean("async_texture_loading", true);
            NativeApp.setAsyncTextureLoading(asyncTextures);
            
            boolean precacheTextures = prefs.getBoolean("precache_textures", false);
            NativeApp.setPrecacheTextureReplacements(precacheTextures);

            boolean vsyncEnabled = prefs.getBoolean("vsync_enabled", false);
            NativeApp.setVsyncEnabled(vsyncEnabled);
            
            // Apply renderer setting
            int renderer = prefs.getInt("renderer", -1);
            NativeApp.renderGpu(renderer);

            MemoryCardSettings.applyForGame(this, "");

            applyOrientationPreference();
            
        } catch (Throwable ignored) {}
    }

    private void showControllerHintIfNeeded() {
        // Prevent multiple dialogs from showing
        if (mControllerHintShowing) return;
        
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        boolean hasShownHint = prefs.getBoolean("controller_hint_shown", false);
        
        // Only show the hint once, and only if setup is complete
        if (!hasShownHint && prefs.getBoolean("first_run_done", false)) {
            mControllerHintShowing = true;
            // Small delay to avoid showing immediately on startup
            findViewById(android.R.id.content).postDelayed(() -> {
                if (!isFinishing() && !mSetupWizardActive && mControllerHintShowing) {
                    showControllerHintDialog();
                }
            }, 2000); // 2 second delay
        }
    }

    private void showControllerHintDialog() {
        // Double-check to prevent multiple dialogs
        if (!mControllerHintShowing) return;
        
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_controller_hint, null);
        
        com.google.android.material.materialswitch.MaterialSwitch swDontShow = dialogView.findViewById(R.id.sw_dont_show_again);
        
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setCustomTitle(UiUtils.centeredDialogTitle(this, "Controller Tip"))
                .setView(dialogView)
                .setPositiveButton("Got it!", (dialog, which) -> {
                    SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
                    if (swDontShow != null && swDontShow.isChecked()) {
                        prefs.edit().putBoolean("controller_hint_shown", true).apply();
                    }
                    mControllerHintShowing = false;
                    dialog.dismiss();
                })
                .setCancelable(true)
                .setOnDismissListener(dialog -> {
                    mControllerHintShowing = false;
                });
        
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    // Pause/Play toggle functionality
    public void togglePauseState() {
        try {
            boolean hasGame = hasSelectedGame() && isThread();
            if (!hasGame) {
                updatePausePlayButton();
                return;
            }

            // Rebuild transient owners before honoring the button. This clears stale
            // dialog/drawer counts left by unusual dismiss or configuration paths.
            reconcileAutomaticPauseOwners();
            final boolean isPaused = NativeApp.isPaused();
            // The button always describes the action the user wants. If a real menu
            // still owns the pause, clearing this flag makes the game resume as soon
            // as that menu closes; if no owner remains, it resumes immediately.
            mUserPauseRequested = !isPaused;
            applyRequestedPauseState("user toggle");
            android.util.Log.d("PauseState", "user toggle requested "
                    + (mUserPauseRequested ? "pause" : "resume")
                    + ", automatic=" + isAutomaticPauseRequested());
        } catch (Throwable e) {
            android.util.Log.e("TogglePause", "Error in togglePauseState: " + e.getMessage());
        }
    }

    public void updatePausePlayButton() {
        MaterialButton btn_pause_play = findViewById(R.id.btn_pause_play);
        if (btn_pause_play != null) {
            try {
                boolean isPaused = NativeApp.isPaused();
                boolean hasGame = hasSelectedGame() && isThread();
                
                // Show button only when a game is running
                btn_pause_play.setVisibility(hasGame ? View.VISIBLE : View.GONE);
                
                if (hasGame) {
                    btn_pause_play.setIcon(ContextCompat.getDrawable(
                            this, R.drawable.play_pause_24px));
                    if (isPaused) {
                        btn_pause_play.setContentDescription("Resume game");
                    } else {
                        btn_pause_play.setContentDescription("Pause game");
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    // Call this method when game starts/stops to update button visibility
    public void updateGameState() {
        updatePausePlayButton();
    }

    public void showAboutDialog() {
        String aboutMessage = "PSX2 - PlayStation 2 Emulator for Android\n\n" +
                "This is an Android port of PCSX2, the renowned PlayStation 2 emulator.\n\n" +
                "Based on:\n" +
                "• PCSX2: https://github.com/PCSX2/pcsx2\n" +
                "• PCSX2_ARM64: https://github.com/pontos2024/PCSX2_ARM64\n\n" +
                "Free Version: Follow the build instructions in the repository to compile from source.\n" +
                "Paid Version: Get convenient automatic updates through the Play Store.\n\n" +
                "Important:\n" +
                "• No games or BIOS files are included\n" +
                "• You must own original PlayStation 2 games and console\n" +
                "• This emulator is for educational and preservation purposes\n\n" +
                "Licensed under GNU General Public License v3.0\n" +
                "Source code: https://github.com/izzy2lost/PSX2\n" +
                "View full license: https://github.com/izzy2lost/PSX2/blob/master/LICENSE";

        new MaterialAlertDialogBuilder(this,
                com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle("About PSX2")
                .setMessage(aboutMessage)
                .setPositiveButton("OK", null)
                .setNeutralButton("Privacy Policy", (dialog, which) -> {
                    // Open Privacy Policy on GitHub Pages
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse("https://izzy2lost.github.io/PSX2/privacy-policy.html"));
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, "Could not open privacy policy link", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("View License", (dialog, which) -> {
                    // Open official GNU GPL v3 license
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse("https://www.gnu.org/licenses/gpl-3.0.html"));
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, "Could not open license link", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // Global dialog tracking methods
    public void onDialogOpened() {
        mOpenDialogCount++;
        android.util.Log.d("DialogTracking", "Dialog opened. Count: " + mOpenDialogCount);
        applyRequestedPauseState("dialog opened");
    }
    
    public void onDialogClosed() {
        mOpenDialogCount = Math.max(0, mOpenDialogCount - 1);
        android.util.Log.d("DialogTracking", "Dialog closed. Count: " + mOpenDialogCount
                + ", drawers: " + mOpenDrawerCount);
        applyRequestedPauseStateDelayed("dialog closed");
    }
    
    public void onDrawerOpened() {
        mOpenDrawerCount++;
        android.util.Log.d("DrawerTracking", "Drawer opened. Count: " + mOpenDrawerCount);
        
        // Refresh drawer settings to reflect current state
        try {
            refreshDrawerSettings();
        } catch (Exception e) {
            android.util.Log.e("DrawerTracking", "Error refreshing drawer settings: " + e.getMessage());
        }
        
        applyRequestedPauseState("drawer opened");
    }
    
    public void onDrawerClosed() {
        mOpenDrawerCount = Math.max(0, mOpenDrawerCount - 1);
        android.util.Log.d("DrawerTracking", "Drawer closed. Count: " + mOpenDrawerCount
                + ", dialogs: " + mOpenDialogCount);
        applyRequestedPauseStateDelayed("drawer closed");
    }

    private boolean isAutomaticPauseRequested() {
        return mActivityPauseRequested || mOpenDialogCount > 0 || mOpenDrawerCount > 0;
    }

    private void applyRequestedPauseStateDelayed(String reason) {
        View root = findViewById(android.R.id.content);
        if (root == null) {
            reconcileAutomaticPauseOwners();
            applyRequestedPauseState(reason);
            return;
        }
        root.postDelayed(() -> {
            reconcileAutomaticPauseOwners();
            applyRequestedPauseState(reason);
        }, 100);
    }

    private void setupDialogPauseTracking() {
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentStarted(
                            @NonNull androidx.fragment.app.FragmentManager fragmentManager,
                            @NonNull androidx.fragment.app.Fragment fragment) {
                        if (fragment instanceof androidx.fragment.app.DialogFragment) {
                            reconcileAutomaticPauseOwners();
                            applyRequestedPauseState("dialog fragment started");
                            applyRequestedPauseStateDelayed("dialog fragment started");
                        }
                    }

                    @Override
                    public void onFragmentStopped(
                            @NonNull androidx.fragment.app.FragmentManager fragmentManager,
                            @NonNull androidx.fragment.app.Fragment fragment) {
                        if (fragment instanceof androidx.fragment.app.DialogFragment) {
                            applyRequestedPauseStateDelayed("dialog fragment stopped");
                        }
                    }

                    @Override
                    public void onFragmentDetached(
                            @NonNull androidx.fragment.app.FragmentManager fragmentManager,
                            @NonNull androidx.fragment.app.Fragment fragment) {
                        if (fragment instanceof androidx.fragment.app.DialogFragment) {
                            applyRequestedPauseStateDelayed("dialog fragment detached");
                        }
                    }
                }, true);
    }

    private void reconcileAutomaticPauseOwners() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return;

        int visibleDialogs = 0;
        try {
            visibleDialogs = countVisibleDialogFragments(getSupportFragmentManager());
        } catch (Throwable error) {
            android.util.Log.w("PauseState", "Unable to reconcile dialogs", error);
        }

        int openDrawers = 0;
        try {
            DrawerLayout drawer = findViewById(R.id.drawer_layout);
            if (drawer != null) {
                if (drawer.isDrawerOpen(androidx.core.view.GravityCompat.START)) openDrawers++;
                if (drawer.isDrawerOpen(androidx.core.view.GravityCompat.END)) openDrawers++;
            }
        } catch (Throwable error) {
            android.util.Log.w("PauseState", "Unable to reconcile drawers", error);
        }

        if (visibleDialogs != mOpenDialogCount || openDrawers != mOpenDrawerCount) {
            android.util.Log.d("PauseState", "Reconciled pause owners: dialogs="
                    + mOpenDialogCount + "->" + visibleDialogs + ", drawers="
                    + mOpenDrawerCount + "->" + openDrawers);
        }
        mOpenDialogCount = visibleDialogs;
        mOpenDrawerCount = openDrawers;
    }

    private int countVisibleDialogFragments(
            androidx.fragment.app.FragmentManager fragmentManager) {
        int count = 0;
        for (androidx.fragment.app.Fragment fragment : fragmentManager.getFragments()) {
            if (fragment instanceof androidx.fragment.app.DialogFragment) {
                android.app.Dialog dialog =
                        ((androidx.fragment.app.DialogFragment) fragment).getDialog();
                if (dialog != null && dialog.isShowing()) count++;
            }
            if (fragment.isAdded()) {
                count += countVisibleDialogFragments(fragment.getChildFragmentManager());
            }
        }
        return count;
    }

    private void applyRequestedPauseState(String reason) {
        try {
            if (!hasSelectedGame() || !isThread()) {
                updatePausePlayButton();
                return;
            }
            boolean shouldPause = mUserPauseRequested || isAutomaticPauseRequested();
            boolean isPaused = NativeApp.isPaused();
            if (shouldPause != isPaused) {
                android.util.Log.d("PauseState", reason + ": "
                        + (shouldPause ? "pause" : "resume")
                        + " user=" + mUserPauseRequested
                        + " activity=" + mActivityPauseRequested
                        + " dialogs=" + mOpenDialogCount
                        + " drawers=" + mOpenDrawerCount);
                if (shouldPause) NativeApp.pause();
                else NativeApp.resume();
            }
            updatePausePlayButton();
        } catch (Throwable error) {
            android.util.Log.e("PauseState", "Unable to apply pause state", error);
        }
    }

    private void setupDrawerListeners() {
        try {
            DrawerLayout drawer = findViewById(R.id.drawer_layout);
            if (drawer != null) {
                drawer.addDrawerListener(new androidx.drawerlayout.widget.DrawerLayout.DrawerListener() {
                    @Override
                    public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {}

                    @Override
                    public void onDrawerOpened(@NonNull View drawerView) {
                        MainActivity.this.onDrawerOpened();
                    }

                    @Override
                    public void onDrawerClosed(@NonNull View drawerView) {
                        MainActivity.this.onDrawerClosed();
                    }

                    @Override
                    public void onDrawerStateChanged(int newState) {}
                });
            }
        } catch (Throwable ignored) {}
    }

    private void setupRightDrawerActions() {
        try {
            View rightDrawer = findViewById(R.id.end_drawer);
            if (rightDrawer != null) {
                // Games button
                View btnGames = rightDrawer.findViewById(R.id.right_drawer_btn_games);
                if (btnGames != null) {
                    btnGames.setOnClickListener(v -> {
                        try {
                            // Close right drawer first
                            DrawerLayout drawer = findViewById(R.id.drawer_layout);
                            if (drawer != null) drawer.closeDrawer(androidx.core.view.GravityCompat.END);
                            // Open games dialog
                            openGamesDialog();
                        } catch (Throwable ignored) {}
                    });
                }

                // Saves button
                View btnSaves = rightDrawer.findViewById(R.id.right_drawer_btn_saves);
                if (btnSaves != null) {
                    btnSaves.setOnClickListener(v -> {
                        try {
                            // Close right drawer first
                            DrawerLayout drawer = findViewById(R.id.drawer_layout);
                            if (drawer != null) drawer.closeDrawer(androidx.core.view.GravityCompat.END);
                            // Open saves dialog
                            SavesDialogFragment dialog = new SavesDialogFragment();
                            dialog.show(getSupportFragmentManager(), "saves_dialog");
                        } catch (Throwable ignored) {}
                    });
                }

                // Picture-in-Picture button
                View btnPip = rightDrawer.findViewById(R.id.right_drawer_btn_pip);
                if (btnPip != null) {
                    btnPip.setOnClickListener(v -> {
                        try {
                            // Close right drawer first
                            DrawerLayout drawer = findViewById(R.id.drawer_layout);
                            if (drawer != null) drawer.closeDrawer(androidx.core.view.GravityCompat.END);
                            // Enter Picture-in-Picture mode
                            enterPictureInPictureMode();
                        } catch (Throwable ignored) {}
                    });
                }

                // Exit Game button (open games dialog)
                View btnExitGame = rightDrawer.findViewById(R.id.right_drawer_btn_exit_game);
                if (btnExitGame != null) {
                    btnExitGame.setOnClickListener(v -> {
                        try {
                            // Close right drawer first
                            DrawerLayout drawer = findViewById(R.id.drawer_layout);
                            if (drawer != null) drawer.closeDrawer(androidx.core.view.GravityCompat.END);
                            // Open games dialog after a short delay
                            findViewById(android.R.id.content).postDelayed(() -> {
                                openGamesDialog();
                            }, 300);
                        } catch (Throwable ignored) {}
                    });
                }

                // Restart Game button
                View btnRestartGame = rightDrawer.findViewById(R.id.right_drawer_btn_restart_game);
                if (btnRestartGame != null) {
                    btnRestartGame.setOnClickListener(v -> {
                        try {
                            // Close right drawer first
                            DrawerLayout drawer = findViewById(R.id.drawer_layout);
                            if (drawer != null) drawer.closeDrawer(androidx.core.view.GravityCompat.END);
                            // Show confirmation dialog
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("Restart Game")
                                    .setMessage("Restart the current game?")
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton("Restart", (d,w) -> rebootEmu())
                                    .show();
                        } catch (Throwable ignored) {}
                    });
                }

                // Exit App button
                View btnExitApp = rightDrawer.findViewById(R.id.right_drawer_btn_exit_app);
                if (btnExitApp != null) {
                    btnExitApp.setOnClickListener(v -> {
                        try {
                            // Close right drawer first
                            DrawerLayout drawer = findViewById(R.id.drawer_layout);
                            if (drawer != null) drawer.closeDrawer(androidx.core.view.GravityCompat.END);
                            // Show confirmation dialog
                            new MaterialAlertDialogBuilder(this)
                                    .setTitle("Exit App")
                                    .setMessage("Quit PSX2?")
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton("Quit", (d,w) -> { 
                                        try { NativeApp.shutdown(); } catch (Throwable ignored) {} 
                                        finishAffinity(); 
                                        finishAndRemoveTask(); 
                                        System.exit(0); 
                                    })
                                    .show();
                        } catch (Throwable ignored) {}
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    // Picture-in-Picture support methods
    
    public void enterPictureInPictureMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                if (hasSelectedGame() && isThread()) {
                    try {
                        PictureInPictureParams.Builder pipBuilder = new PictureInPictureParams.Builder()
                                .setAspectRatio(new Rational(16, 9)) // 16:9 aspect ratio for gaming
                                .setSourceRectHint(null); // Use entire activity
                        
                        // Add custom actions for PiP mode
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            java.util.ArrayList<RemoteAction> actions = new java.util.ArrayList<>();
                            
                            // Play/Pause action
                            Intent playPauseIntent = new Intent("TOGGLE_PAUSE");
                            PendingIntent playPausePendingIntent = PendingIntent.getBroadcast(
                                this, 0, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                            
                            boolean isPaused = false;
                            try { isPaused = NativeApp.isPaused(); } catch (Throwable ignored) {}
                            
                            Icon playPauseIcon = Icon.createWithResource(
                                    this, R.drawable.play_pause_24px);
                            RemoteAction playPauseAction = new RemoteAction(playPauseIcon, 
                                isPaused ? "Resume" : "Pause", 
                                isPaused ? "Resume Game" : "Pause Game", 
                                playPausePendingIntent);
                            actions.add(playPauseAction);
                            
                            pipBuilder.setActions(actions);
                        }
                        
                        enterPictureInPictureMode(pipBuilder.build());
                    } catch (Exception e) {
                        Toast.makeText(this, "Picture-in-Picture not available", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(this, "Start a game to use Picture-in-Picture", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Picture-in-Picture not supported on this device", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        
        if (isInPictureInPictureMode) {
            // PiP is still visible playback, so it must not inherit the ordinary
            // background-activity pause request.
            mActivityPauseRequested = false;
            applyRequestedPauseState("entered picture-in-picture");
            // Hide UI elements that are not needed in PiP mode
            hideUiForPiP();
        } else {
            // Restore UI when exiting PiP mode
            showUiAfterPiP();
        }
    }
    
    private void hideUiForPiP() {
        // Hide control buttons, drawers, etc. in PiP mode
        View settingsBtn = findViewById(R.id.btn_settings);
        if (settingsBtn != null) settingsBtn.setVisibility(View.GONE);
        
        View pauseBtn = findViewById(R.id.btn_pause_play);
        if (pauseBtn != null) pauseBtn.setVisibility(View.GONE);
        
        View quickActions = findViewById(R.id.ll_quick_actions);
        if (quickActions != null) quickActions.setVisibility(View.GONE);
        
        // Hide all gamepad controls
        hideGamepadControls();
    }
    
    private void showUiAfterPiP() {
        // Restore UI elements when exiting PiP mode
        View settingsBtn = findViewById(R.id.btn_settings);
        if (settingsBtn != null) settingsBtn.setVisibility(View.VISIBLE);
        
        updatePausePlayButton(); // This will show/hide based on game state
        
        View quickActions = findViewById(R.id.ll_quick_actions);
        if (quickActions != null) quickActions.setVisibility(View.VISIBLE);
        
        // Show gamepad controls based on preferences
        updateUiForControllerPresence();
    }
    
    private void hideGamepadControls() {
        View joy = findViewById(R.id.ll_pad_joy);
        if (joy != null) joy.setVisibility(View.GONE);

        View rjoy = findViewById(R.id.ll_pad_rjoy);
        if (rjoy != null) rjoy.setVisibility(View.GONE);
        
        View dpad = findViewById(R.id.ll_pad_dpad);
        if (dpad != null) dpad.setVisibility(View.GONE);
        
        View rightButtons = findViewById(R.id.ll_pad_right_buttons);
        if (rightButtons != null) rightButtons.setVisibility(View.GONE);
        
        View selectStart = findViewById(R.id.ll_pad_select_start);
        if (selectStart != null) selectStart.setVisibility(View.GONE);
    }
    
    // Add PiP button to quick actions or settings
    public void addPictureInPictureSupport() {
        // This method can be called to add a PiP button to your UI
        // For now, users can enter PiP mode by pressing home button during gameplay
        
        // Register broadcast receiver for PiP actions
        registerPiPBroadcastReceiver();
    }

    private void registerPiPBroadcastReceiver() {
        // Register receiver for PiP remote actions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.content.BroadcastReceiver pipReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, Intent intent) {
                    String action = intent.getAction();
                    if ("TOGGLE_PAUSE".equals(action)) {
                        togglePauseState();
                        // Update PiP params with new play/pause state
                        if (isInPictureInPictureMode()) {
                            updatePictureInPictureParams();
                        }
                    }
                }
            };
            
            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction("TOGGLE_PAUSE");
            registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
    }
    
    private void updatePictureInPictureParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode()) {
            try {
                boolean isPaused = NativeApp.isPaused();
                
                Intent playPauseIntent = new Intent("TOGGLE_PAUSE");
                PendingIntent playPausePendingIntent = PendingIntent.getBroadcast(
                    this, 0, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                
                Icon playPauseIcon = Icon.createWithResource(
                        this, R.drawable.play_pause_24px);
                RemoteAction playPauseAction = new RemoteAction(playPauseIcon, 
                    isPaused ? "Resume" : "Pause", 
                    isPaused ? "Resume Game" : "Pause Game", 
                    playPausePendingIntent);
                
                java.util.ArrayList<RemoteAction> actions = new java.util.ArrayList<>();
                actions.add(playPauseAction);
                
                PictureInPictureParams params = new PictureInPictureParams.Builder()
                        .setActions(actions)
                        .build();
                        
                setPictureInPictureParams(params);
            } catch (Exception ignored) {}
        }
    }

    private void scheduleRetroAchievementsReLoginPrompt() {
        if (mRaLoginPromptScheduled) return;
        mRaLoginPromptScheduled = true;

        SharedPreferences prefs = getSharedPreferences("RetroAchievements", MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("enabled", false);
        String username = prefs.getString("username", "");
        String token = prefs.getString("token", "");
        if (!enabled || TextUtils.isEmpty(username) || TextUtils.isEmpty(token)) {
            return;
        }

        View decor = (getWindow() != null) ? getWindow().getDecorView() : null;
        if (decor == null) return;

        decor.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            showRetroAchievementsReLoginDialog(username, token);
        }, 5000);
    }

    private void showRetroAchievementsReLoginDialog(String username, String token) {
        try {
            new MaterialAlertDialogBuilder(this,
                    com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                    .setTitle("RetroAchievements")
                    .setMessage("Sign back in as " + username + "?")
                    .setNegativeButton("Not now", (d, w) -> clearRetroAchievementsSession())
                    .setPositiveButton("Sign in", (d, w) -> startRetroAchievementsLogin(username, token))
                    .show();
        } catch (Throwable t) {
            android.util.Log.w("MainActivity", "Failed to show RetroAchievements prompt: " + t.getMessage());
        }
    }

    private void startRetroAchievementsLogin(String username, String token) {
        new Thread(() -> {
            try {
                NativeApp.achievementsInitialize();
                Thread.sleep(500); // let init settle before logging in
                NativeApp.achievementsLoginWithToken(username, token);
                android.util.Log.i("MainActivity", "RetroAchievements token login started for " + username);
            } catch (Throwable t) {
                android.util.Log.e("MainActivity", "RetroAchievements token login failed: " + t.getMessage());
            }
        }).start();
    }

    private void clearRetroAchievementsSession() {
        try {
            NativeApp.achievementsLogout();
        } catch (Throwable t) {
            android.util.Log.w("MainActivity", "RA logout error: " + t.getMessage());
        }
        try {
            SharedPreferences prefs = getSharedPreferences("RetroAchievements", MODE_PRIVATE);
            prefs.edit()
                    .remove("token")
                    .remove("login_timestamp")
                    .remove("saved_password")
                    .putBoolean("remember_me", false)
                    .apply();
        } catch (Throwable t) {
            android.util.Log.w("MainActivity", "RA prefs clear error: " + t.getMessage());
        }
    }

}
