package com.izzy2lost.psx2;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Library-scoped browser and installer for the EmuCoreX texture catalog. */
public final class TexturePackManagerDialogFragment extends DialogFragment {
    private static final String ARG_TITLES = "titles";
    private static final String ARG_SERIALS = "serials";
    private static final ExecutorService CATALOG_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "TextureCatalog");
                thread.setDaemon(true);
                return thread;
            });

    private final Map<String, String> serialToTitle = new LinkedHashMap<>();
    private final Map<String, TexturePackAdapter.WorkState> activeStates = new HashMap<>();
    private final Set<UUID> activeWorkIds = new HashSet<>();
    private TexturePackAdapter adapter;
    private ProgressBar loading;
    private TextView message;
    private MaterialButton retry;

    public static TexturePackManagerDialogFragment newInstance(
            String[] titles, String[] serials) {
        final TexturePackManagerDialogFragment fragment =
                new TexturePackManagerDialogFragment();
        final Bundle arguments = new Bundle();
        arguments.putStringArray(ARG_TITLES, titles);
        arguments.putStringArray(ARG_SERIALS, serials);
        fragment.setArguments(arguments);
        return fragment;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context context = requireContext();
        final View root = getLayoutInflater()
                .inflate(R.layout.dialog_texture_pack_manager, null, false);
        loading = root.findViewById(R.id.texture_catalog_loading);
        message = root.findViewById(R.id.texture_catalog_message);
        retry = root.findViewById(R.id.texture_catalog_retry);
        final TextView catalogLink = root.findViewById(R.id.texture_catalog_link);
        final RecyclerView recycler = root.findViewById(R.id.texture_pack_list);
        recycler.setLayoutManager(new LinearLayoutManager(context));
        recycler.setHasFixedSize(false);
        adapter = new TexturePackAdapter(this::confirmInstall);
        recycler.setAdapter(adapter);

        readLibraryArguments();
        refreshInstalledIds();
        retry.setOnClickListener(v -> loadCatalog());
        catalogLink.setOnClickListener(v -> openUrl(TexturePackCatalog.RELEASES_URL));
        loadCatalog();
        observeWork();

        return new MaterialAlertDialogBuilder(context,
                com.google.android.material.R.style
                        .ThemeOverlay_Material3_MaterialAlertDialog)
                .setCustomTitle(UiUtils.centeredDialogTitle(context, "TEXTURE PACKS"))
                .setView(root)
                .setNegativeButton("Close", null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        final Dialog dialog = getDialog();
        if (dialog == null) return;
        final Window window = dialog.getWindow();
        if (window == null) return;
        final android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        window.setLayout(
                Math.min((int) (metrics.widthPixels * 0.94f),
                        (int) (720 * metrics.density)),
                (int) (metrics.heightPixels * 0.88f));
        final WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = 0.65f;
        window.setAttributes(params);
    }

    private void readLibraryArguments() {
        final Bundle arguments = getArguments();
        final String[] titles =
                arguments != null ? arguments.getStringArray(ARG_TITLES) : null;
        final String[] serials =
                arguments != null ? arguments.getStringArray(ARG_SERIALS) : null;
        if (titles == null || serials == null) return;
        final int count = Math.min(titles.length, serials.length);
        for (int index = 0; index < count; index++) {
            final String serial = serials[index] == null
                    ? "" : serials[index].trim().toUpperCase(Locale.ROOT);
            final String title = titles[index] == null || titles[index].isBlank()
                    ? serial : titles[index].trim();
            if (serial.matches("^[A-Z]{4}-[0-9]{5}$")) {
                serialToTitle.putIfAbsent(serial, title);
            }
        }
    }

    private void loadCatalog() {
        loading.setVisibility(View.VISIBLE);
        retry.setVisibility(View.GONE);
        message.setVisibility(View.VISIBLE);
        message.setText("Checking EmuCoreX-Textures releases…");
        final Context appContext = requireContext().getApplicationContext();
        CATALOG_EXECUTOR.execute(() -> {
            try {
                final TexturePackCatalog.Result catalog =
                        TexturePackCatalog.load(appContext);
                final ArrayList<TexturePackAdapter.Row> rows = matchingRows(catalog.entries);
                final android.app.Activity activity = getActivity();
                if (activity == null) return;
                activity.runOnUiThread(() -> showCatalog(catalog, rows));
            } catch (Throwable error) {
                final String detail = error.getMessage() == null
                        ? "Unable to load the texture catalog" : error.getMessage();
                final android.app.Activity activity = getActivity();
                if (activity == null) return;
                activity.runOnUiThread(() -> showCatalogError(detail));
            }
        });
    }

    private ArrayList<TexturePackAdapter.Row> matchingRows(
            List<TexturePackCatalog.Entry> entries) {
        final ArrayList<TexturePackAdapter.Row> rows = new ArrayList<>();
        for (TexturePackCatalog.Entry entry : entries) {
            for (String serial : entry.serials) {
                final String title = serialToTitle.get(serial);
                if (title != null) rows.add(new TexturePackAdapter.Row(entry, serial, title));
            }
        }
        rows.sort(Comparator
                .comparing((TexturePackAdapter.Row row) ->
                        row.libraryTitle.toLowerCase(Locale.ROOT))
                .thenComparing(row -> row.entry.name.toLowerCase(Locale.ROOT)));
        return rows;
    }

    private void showCatalog(
            TexturePackCatalog.Result catalog, List<TexturePackAdapter.Row> rows) {
        if (!isAdded()) return;
        loading.setVisibility(View.GONE);
        retry.setVisibility(View.GONE);
        adapter.setRows(rows);
        refreshInstalledIds();
        if (rows.isEmpty()) {
            message.setVisibility(View.VISIBLE);
            message.setText(serialToTitle.isEmpty()
                    ? "Game serials are still being identified. Close and reopen the library, then try again."
                    : "No catalog texture packs match the games currently in your library.");
        } else {
            message.setVisibility(View.VISIBLE);
            message.setText(catalog.fromCache
                    ? rows.size() + " matching packs • showing the saved catalog (offline)"
                    : rows.size() + " matching packs from GitHub Releases");
        }
    }

    private void showCatalogError(String detail) {
        loading.setVisibility(View.GONE);
        message.setVisibility(View.VISIBLE);
        message.setText(detail);
        retry.setVisibility(View.VISIBLE);
    }

    private void confirmInstall(TexturePackAdapter.Row row) {
        if (activeStates.containsKey(row.serial)) return;
        final TexturePackCatalog.Entry entry = row.entry;
        final StringBuilder details = new StringBuilder();
        details.append(row.libraryTitle).append(" (").append(row.serial).append(")\n\n")
                .append(entry.description.isBlank() ? entry.credits : entry.description)
                .append("\n\nBy ").append(String.join(", ", entry.authors))
                .append("\nVersion: ").append(entry.version)
                .append("\nDownload: ")
                .append(TexturePackDownloadWorker.formatBytes(entry.sizeBytes))
                .append(entry.isSplitArchive()
                        ? " in " + entry.parts.size() + " parts" : "")
                .append("\nFiles: ").append(entry.fileCount)
                .append("\n\nThe GitHub Release asset will be SHA-256 verified before installation. ")
                .append("Installing another catalog pack for this game replaces only the ")
                .append("EmuCoreX-managed pack; your other texture files are kept.");
        if (!entry.license.isBlank()) {
            details.append("\n\nLicense: ").append(entry.license);
        }

        new MaterialAlertDialogBuilder(requireContext(),
                com.google.android.material.R.style
                        .ThemeOverlay_Material3_MaterialAlertDialog)
                .setTitle(entry.name)
                .setMessage(details.toString())
                .setNeutralButton("Original source", (dialog, which) ->
                        openUrl(entry.sourceUrl))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Download", (dialog, which) -> enqueue(row))
                .show();
    }

    private void enqueue(TexturePackAdapter.Row row) {
        final TexturePackCatalog.Entry entry = row.entry;
        final androidx.work.Data.Builder builder = new androidx.work.Data.Builder()
                .putString(TexturePackDownloadWorker.KEY_PACK_ID, entry.id)
                .putString(TexturePackDownloadWorker.KEY_PACK_NAME, entry.name)
                .putString(TexturePackDownloadWorker.KEY_SERIAL, row.serial)
                .putString(TexturePackDownloadWorker.KEY_DOWNLOAD_URL, entry.downloadUrl)
                .putString(TexturePackDownloadWorker.KEY_SHA256, entry.sha256)
                .putLong(TexturePackDownloadWorker.KEY_SIZE_BYTES, entry.sizeBytes)
                .putInt(TexturePackDownloadWorker.KEY_FILE_COUNT, entry.fileCount);
        if (entry.isSplitArchive()) {
            final int count = entry.parts.size();
            final String[] urls = new String[count];
            final long[] sizes = new long[count];
            final String[] hashes = new String[count];
            for (int index = 0; index < count; index++) {
                final TexturePackCatalog.Part part = entry.parts.get(index);
                urls[index] = part.downloadUrl;
                sizes[index] = part.sizeBytes;
                hashes[index] = part.sha256;
            }
            builder.putStringArray(TexturePackDownloadWorker.KEY_PART_URLS, urls)
                    .putLongArray(TexturePackDownloadWorker.KEY_PART_SIZES, sizes)
                    .putStringArray(TexturePackDownloadWorker.KEY_PART_HASHES, hashes);
        }
        final androidx.work.Data input = builder.build();
        final Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        final OneTimeWorkRequest request =
                new OneTimeWorkRequest.Builder(TexturePackDownloadWorker.class)
                        .setInputData(input)
                        .setConstraints(constraints)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
                                30, TimeUnit.SECONDS)
                        .addTag(TexturePackDownloadWorker.TAG_ALL)
                        .addTag(TexturePackDownloadWorker.TAG_SERIAL_PREFIX + row.serial)
                        .addTag(TexturePackDownloadWorker.TAG_PACK_PREFIX + entry.id)
                        .build();
        WorkManager.getInstance(requireContext().getApplicationContext())
                .enqueueUniqueWork(
                        TexturePackDownloadWorker.uniqueWorkName(row.serial),
                        ExistingWorkPolicy.KEEP,
                        request);
        Toast.makeText(requireContext(),
                "Texture download queued for " + row.libraryTitle,
                Toast.LENGTH_SHORT).show();
    }

    private void observeWork() {
        WorkManager.getInstance(requireContext().getApplicationContext())
                .getWorkInfosByTagLiveData(TexturePackDownloadWorker.TAG_ALL)
                .observe(this, this::updateWork);
    }

    private void updateWork(@Nullable List<WorkInfo> workInfos) {
        if (workInfos == null || adapter == null) return;
        activeStates.clear();
        final Set<UUID> currentlyActive = new HashSet<>();

        for (WorkInfo info : workInfos) {
            final String serial = tagValue(info, TexturePackDownloadWorker.TAG_SERIAL_PREFIX);
            final String packId = tagValue(info, TexturePackDownloadWorker.TAG_PACK_PREFIX);
            if (serial == null || packId == null) continue;
            if (!info.getState().isFinished()) {
                currentlyActive.add(info.getId());
                activeWorkIds.add(info.getId());
                activeStates.put(serial, new TexturePackAdapter.WorkState(
                        packId, workLabel(info)));
            } else if (activeWorkIds.remove(info.getId())) {
                handleFinished(info);
            }
        }
        activeWorkIds.retainAll(currentlyActive);
        adapter.setWorkStates(activeStates);
        refreshInstalledIds();
    }

    private void handleFinished(WorkInfo info) {
        if (!isAdded()) return;
        if (info.getState() == WorkInfo.State.SUCCEEDED) {
            final String name = info.getOutputData()
                    .getString(TexturePackDownloadWorker.KEY_PACK_NAME);
            Toast.makeText(requireContext(),
                    (name == null ? "Texture pack" : name) + " installed",
                    Toast.LENGTH_LONG).show();
        } else if (info.getState() == WorkInfo.State.FAILED) {
            String error = info.getOutputData().getString(TexturePackDownloadWorker.KEY_ERROR);
            if (error == null || error.isBlank()) error = "Texture pack installation failed";
            Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
        }
    }

    private String workLabel(WorkInfo info) {
        if (info.getState() == WorkInfo.State.ENQUEUED
                || info.getState() == WorkInfo.State.BLOCKED) {
            return "Queued";
        }
        final String phase =
                info.getProgress().getString(TexturePackDownloadWorker.KEY_PHASE);
        if (TexturePackDownloadWorker.PHASE_INSTALLING.equals(phase)) {
            final int done = info.getProgress()
                    .getInt(TexturePackDownloadWorker.KEY_FILES_DONE, 0);
            final int total = info.getProgress()
                    .getInt(TexturePackDownloadWorker.KEY_FILES_TOTAL, 0);
            return total > 0 ? "Installing " + done + "/" + total : "Installing";
        }
        if (TexturePackDownloadWorker.PHASE_VERIFYING.equals(phase)) return "Verifying";
        final long done = info.getProgress()
                .getLong(TexturePackDownloadWorker.KEY_BYTES_DONE, 0);
        final long total = info.getProgress()
                .getLong(TexturePackDownloadWorker.KEY_BYTES_TOTAL, 0);
        if (total > 0) {
            return "Downloading " + Math.min(100, (done * 100L) / total) + "%";
        }
        return "Starting";
    }

    private void refreshInstalledIds() {
        if (adapter == null) return;
        final SharedPreferences prefs = requireContext()
                .getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        final HashMap<String, String> installed = new HashMap<>();
        for (String serial : serialToTitle.keySet()) {
            final String id = prefs.getString(
                    TexturePackDownloadWorker.PREF_INSTALLED_ID_PREFIX + serial, null);
            if (id != null && !id.isBlank()) installed.put(serial, id);
        }
        adapter.setInstalledIds(installed);
    }

    private static String tagValue(WorkInfo info, String prefix) {
        for (String tag : info.getTags()) {
            if (tag.startsWith(prefix)) return tag.substring(prefix.length());
        }
        return null;
    }

    private void openUrl(String url) {
        try {
            final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Throwable error) {
            Toast.makeText(requireContext(), "Unable to open link", Toast.LENGTH_SHORT).show();
        }
    }
}
