package com.izzy2lost.psx2;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MemoryCardManagerDialogFragment extends DialogFragment {
    
    private ListView memcardListView;
    private ArrayAdapter<String> memcardAdapter;
    private List<String> memcardFiles;
    private ActivityResultLauncher<Intent> importLauncher;
    private ActivityResultLauncher<Intent> exportLauncher;
    private String selectedMemcard;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Import launcher - pick memory card files to import
        importLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleImport(result.getData());
                }
            }
        );
        
        // Export launcher - pick destination folder
        exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleExport(result.getData());
                }
            }
        );
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_memcard_manager, null);
        
        memcardListView = view.findViewById(R.id.memcard_list);
        MaterialButton btnImport = view.findViewById(R.id.btn_import_memcard);
        MaterialButton btnExport = view.findViewById(R.id.btn_export_memcard);
        MaterialButton btnSlot1 = view.findViewById(R.id.btn_use_memcard_slot1);
        MaterialButton btnSlot2 = view.findViewById(R.id.btn_use_memcard_slot2);
        
        // Load existing memory cards
        loadMemoryCards();
        
        memcardAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_single_choice, memcardFiles);
        memcardListView.setAdapter(memcardAdapter);
        memcardListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        
        memcardListView.setOnItemClickListener((parent, v, position, id) -> {
            selectedMemcard = memcardFiles.get(position);
        });
        
        btnImport.setOnClickListener(v -> showImportDialog());
        btnExport.setOnClickListener(v -> {
            if (selectedMemcard == null) {
                Toast.makeText(requireContext(), "Select a memory card first", Toast.LENGTH_SHORT).show();
            } else {
                showExportDialog();
            }
        });
        btnSlot1.setOnClickListener(v -> assignSelectedCardToSlot(1, view));
        btnSlot2.setOnClickListener(v -> assignSelectedCardToSlot(2, view));
        refreshSlotLabels(view);
        
        return new MaterialAlertDialogBuilder(requireContext())
            .setCustomTitle(UiUtils.centeredDialogTitle(requireContext(), "MEMORY CARD MANAGER"))
            .setView(view)
            .setNegativeButton("Close", null)
            .create();
    }
    
    private void loadMemoryCards() {
        memcardFiles = new ArrayList<>();
        
        // Always use the internal Android/data location where the emulator actually stores memory cards
        File memcardDir = MemoryCardSettings.getDirectory(requireContext());
        if (memcardDir.exists() && memcardDir.isDirectory()) {
            File[] files = memcardDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().toLowerCase().endsWith(".ps2")) {
                        memcardFiles.add(file.getName());
                    }
                }
            }
        }
    }
    
    private void showImportDialog() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        importLauncher.launch(intent);
    }
    
    private void handleImport(Intent data) {
        try {
            if (data.getClipData() != null) {
                // Multiple files
                int count = data.getClipData().getItemCount();
                int imported = 0;
                for (int i = 0; i < count; i++) {
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (importMemoryCard(uri)) {
                        imported++;
                    }
                }
                Toast.makeText(requireContext(), "Imported " + imported + " memory card(s)", Toast.LENGTH_SHORT).show();
            } else if (data.getData() != null) {
                // Single file
                if (importMemoryCard(data.getData())) {
                    Toast.makeText(requireContext(), "Memory card imported successfully", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), "Failed to import memory card", Toast.LENGTH_SHORT).show();
                }
            }
            loadMemoryCards();
            memcardAdapter.clear();
            memcardAdapter.addAll(memcardFiles);
            memcardAdapter.notifyDataSetChanged();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error importing: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private boolean importMemoryCard(Uri sourceUri) {
        try {
            String filename = getFileName(sourceUri);
            if (filename == null || !filename.toLowerCase().endsWith(".ps2")) {
                return false;
            }
            
            InputStream in = requireContext().getContentResolver().openInputStream(sourceUri);
            if (in == null) return false;
            
            // Always import to internal Android/data location where emulator uses them
            File memcardDir = MemoryCardSettings.getDirectory(requireContext());
            File destFile = uniqueDestination(memcardDir, filename);
            
            boolean success;
            try (OutputStream out = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                success = true;
            }
            in.close();
            return success;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    
    private void showExportDialog() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        exportLauncher.launch(intent);
    }
    
    private void handleExport(Intent data) {
        try {
            Uri treeUri = data.getData();
            if (treeUri == null || selectedMemcard == null) return;
            
            DocumentFile destDir = DocumentFile.fromTreeUri(requireContext(), treeUri);
            if (destDir == null) return;
            
            DocumentFile destFile = destDir.createFile("application/octet-stream", selectedMemcard);
            if (destFile == null) return;
            
            // Always export from internal Android/data location
            File memcardFile = new File(MemoryCardSettings.getDirectory(requireContext()), selectedMemcard);
            InputStream in = new FileInputStream(memcardFile);
            
            if (in == null) {
                Toast.makeText(requireContext(), "Failed to read memory card", Toast.LENGTH_SHORT).show();
                return;
            }
            
            OutputStream out = requireContext().getContentResolver().openOutputStream(destFile.getUri());
            if (out == null) {
                in.close();
                Toast.makeText(requireContext(), "Failed to write to destination", Toast.LENGTH_SHORT).show();
                return;
            }
            
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            
            in.close();
            out.close();
            
            Toast.makeText(requireContext(), "Memory card exported successfully", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showDeleteConfirmation() {
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Memory Card")
            .setMessage("Are you sure you want to delete " + selectedMemcard + "? This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> deleteMemoryCard())
            .show();
    }
    
    private void deleteMemoryCard() {
        try {
            // Always delete from internal Android/data location
            File memcardFile = new File(MemoryCardSettings.getDirectory(requireContext()), selectedMemcard);
            boolean success = memcardFile.delete();
            
            if (success) {
                Toast.makeText(requireContext(), "Memory card deleted", Toast.LENGTH_SHORT).show();
                selectedMemcard = null;
                loadMemoryCards();
                memcardAdapter.clear();
                memcardAdapter.addAll(memcardFiles);
                memcardAdapter.notifyDataSetChanged();
            } else {
                Toast.makeText(requireContext(), "Failed to delete memory card", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void showCreateNewDialog() {
        // Check which slots are available
        boolean slot1Exists = memcardFiles.contains("Mcd001.ps2");
        boolean slot2Exists = memcardFiles.contains("Mcd002.ps2");
        
        // Build list of available slots
        List<String> availableSlots = new ArrayList<>();
        List<String> availableFilenames = new ArrayList<>();
        
        if (!slot1Exists) {
            availableSlots.add("Mcd001.ps2 (Slot 1)");
            availableFilenames.add("Mcd001.ps2");
        }
        if (!slot2Exists) {
            availableSlots.add("Mcd002.ps2 (Slot 2)");
            availableFilenames.add("Mcd002.ps2");
        }
        
        if (availableSlots.isEmpty()) {
            Toast.makeText(requireContext(), "Both memory card slots are already in use", Toast.LENGTH_SHORT).show();
            return;
        }
        
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("Create New Memory Card")
            .setMessage("Choose a memory card slot:")
            .setItems(availableSlots.toArray(new String[0]), (dialog, which) -> {
                String filename = availableFilenames.get(which);
                createNewMemoryCard(filename);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void createNewMemoryCard(String filename) {
        try {
            // Create an 8MB empty memory card file
            byte[] emptyCard = new byte[8 * 1024 * 1024];
            
            // Always create in internal Android/data location
            File memcardDir = MemoryCardSettings.getDirectory(requireContext());
            File memcardFile = new File(memcardDir, filename);
            
            boolean success;
            try (FileOutputStream out = new FileOutputStream(memcardFile)) {
                out.write(emptyCard);
                success = true;
            }
            
            if (success) {
                Toast.makeText(requireContext(), "Memory card created: " + filename, Toast.LENGTH_SHORT).show();
                loadMemoryCards();
                memcardAdapter.clear();
                memcardAdapter.addAll(memcardFiles);
                memcardAdapter.notifyDataSetChanged();
            } else {
                Toast.makeText(requireContext(), "Failed to create memory card", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(requireContext(), "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        result = cursor.getString(nameIndex);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            if (result != null) {
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
        }
        return result;
    }

    private void assignSelectedCardToSlot(int slot, View root) {
        if (selectedMemcard == null) {
            Toast.makeText(requireContext(), "Select a memory card first", Toast.LENGTH_SHORT).show();
            return;
        }
        MemoryCardSettings.setGlobalCard(requireContext(), slot, selectedMemcard);
        Activity activity = getActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).applyCurrentMemoryCardSettings();
        } else {
            MemoryCardSettings.applyForGame(requireContext(), "");
        }
        refreshSlotLabels(root);
        Toast.makeText(requireContext(), selectedMemcard + " assigned to Slot " + slot, Toast.LENGTH_SHORT).show();
    }

    private void refreshSlotLabels(View root) {
        TextView slot1 = root.findViewById(R.id.tv_memcard_slot1);
        TextView slot2 = root.findViewById(R.id.tv_memcard_slot2);
        slot1.setText("Global Slot 1: " + MemoryCardSettings.describeGlobalSlot(requireContext(), 1));
        slot2.setText("Global Slot 2: " + MemoryCardSettings.describeGlobalSlot(requireContext(), 2));
    }

    private static File uniqueDestination(File directory, String filename) {
        File destination = new File(directory, filename);
        if (!destination.exists()) return destination;
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";
        for (int copy = 2; ; copy++) {
            destination = new File(directory, base + " (" + copy + ")" + extension);
            if (!destination.exists()) return destination;
        }
    }
}
