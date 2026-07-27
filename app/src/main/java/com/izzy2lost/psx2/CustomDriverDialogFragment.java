package com.izzy2lost.psx2;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Custom Vulkan GPU driver manager. Lets the user import a driver .zip
 * (e.g. a Mesa Turnip build from github.com/K11MCH1/AdrenoToolsDrivers)
 * and pick the active one; storage/extraction lives in
 * {@link CustomDriverManager}.
 */
public class CustomDriverDialogFragment extends DialogFragment {

    private static final String PREFS = "app_prefs";
    private static final String PREF_CUSTOM_DRIVER_ID = "custom_driver_id";

    private ListView driverListView;
    private ArrayAdapter<String> driverAdapter;
    private List<CustomDriverManager.InstalledDriver> installedDrivers;
    private ActivityResultLauncher<Intent> importLauncher;
    private int selectedIndex = 0; // 0 = System Default

    /** Reads the id of the driver the user last picked, or null for the
     *  system default. */
    public static String getSelectedDriverId(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_CUSTOM_DRIVER_ID, null);
    }

    /** Resolves the saved driver id against what's actually installed
     *  (a saved id can go stale if the driver was deleted outside this
     *  dialog) and pushes the result to native. Safe to call
     *  unconditionally before every VM start. */
    public static void applyStoredSelection(Context context) {
        String id = getSelectedDriverId(context);
        CustomDriverManager.InstalledDriver selected = null;
        if (id != null) {
            for (CustomDriverManager.InstalledDriver d : CustomDriverManager.listInstalled(context)) {
                if (d.id.equals(id)) {
                    selected = d;
                    break;
                }
            }
        }
        CustomDriverManager.applyToNative(context, selected);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null
                            && result.getData().getData() != null) {
                        importDriver(result.getData().getData());
                    }
                }
        );
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_driver, null);

        driverListView = view.findViewById(R.id.custom_driver_list);
        MaterialButton btnImport = view.findViewById(R.id.btn_import_custom_driver);
        MaterialButton btnDelete = view.findViewById(R.id.btn_delete_custom_driver);

        loadDrivers();

        driverListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        driverListView.setItemChecked(selectedIndex, true);
        driverListView.setOnItemClickListener((parent, v, position, id) -> {
            selectedIndex = position;
            Context ctx = requireContext();
            SharedPreferences.Editor editor = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            if (position == 0) {
                editor.remove(PREF_CUSTOM_DRIVER_ID).apply();
            } else {
                editor.putString(PREF_CUSTOM_DRIVER_ID, installedDrivers.get(position - 1).id).apply();
            }
            applyStoredSelection(ctx);
            Toast.makeText(ctx, "Applies the next time the game starts", Toast.LENGTH_SHORT).show();
        });

        btnImport.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            importLauncher.launch(intent);
        });

        btnDelete.setOnClickListener(v -> {
            if (selectedIndex == 0) {
                Toast.makeText(requireContext(), "Select an imported driver first", Toast.LENGTH_SHORT).show();
                return;
            }
            CustomDriverManager.InstalledDriver target = installedDrivers.get(selectedIndex - 1);
            Context ctx = requireContext();
            CustomDriverManager.delete(target);
            if (target.id.equals(getSelectedDriverId(ctx))) {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .remove(PREF_CUSTOM_DRIVER_ID).apply();
                applyStoredSelection(ctx);
            }
            Toast.makeText(ctx, "Deleted " + target.name, Toast.LENGTH_SHORT).show();
            loadDrivers();
            refreshAdapter();
        });

        return new MaterialAlertDialogBuilder(requireContext())
                .setCustomTitle(UiUtils.centeredDialogTitle(requireContext(), "CUSTOM GPU DRIVER"))
                .setView(view)
                .setNegativeButton("Close", null)
                .create();
    }

    private void loadDrivers() {
        installedDrivers = CustomDriverManager.listInstalled(requireContext());
        String currentId = getSelectedDriverId(requireContext());
        selectedIndex = 0;
        List<String> labels = new ArrayList<>();
        labels.add("System Default (no override)");
        for (int i = 0; i < installedDrivers.size(); i++) {
            CustomDriverManager.InstalledDriver d = installedDrivers.get(i);
            String label = d.version.isEmpty() ? d.name : d.name + " (" + d.version + ")";
            labels.add(label);
            if (d.id.equals(currentId))
                selectedIndex = i + 1;
        }
        driverAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_single_choice, labels);
    }

    private void refreshAdapter() {
        if (driverListView == null)
            return;
        driverListView.setAdapter(driverAdapter);
        driverListView.setItemChecked(selectedIndex, true);
    }

    @Override
    public void onStart() {
        super.onStart();
        refreshAdapter();
    }

    private void importDriver(Uri uri) {
        Context appCtx = requireContext().getApplicationContext();
        Toast.makeText(appCtx, "Importing driver...", Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            CustomDriverManager.InstalledDriver installed = CustomDriverManager.installFromUri(appCtx, uri);
            if (getActivity() == null)
                return;
            requireActivity().runOnUiThread(() -> {
                if (installed == null) {
                    Toast.makeText(appCtx, "Import failed — not a valid driver .zip", Toast.LENGTH_LONG).show();
                    return;
                }
                appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(PREF_CUSTOM_DRIVER_ID, installed.id).apply();
                applyStoredSelection(appCtx);
                Toast.makeText(appCtx, "Installed " + installed.name, Toast.LENGTH_SHORT).show();
                loadDrivers();
                refreshAdapter();
            });
        });
    }
}
