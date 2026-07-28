package com.izzy2lost.psx2;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TexturePackAdapter
        extends RecyclerView.Adapter<TexturePackAdapter.ViewHolder> {
    interface Listener {
        void onInstallRequested(Row row);
    }

    static final class Row {
        final TexturePackCatalog.Entry entry;
        final String serial;
        final String libraryTitle;

        Row(TexturePackCatalog.Entry entry, String serial, String libraryTitle) {
            this.entry = entry;
            this.serial = serial;
            this.libraryTitle = libraryTitle;
        }
    }

    static final class WorkState {
        final String packId;
        final String label;

        WorkState(String packId, String label) {
            this.packId = packId;
            this.label = label;
        }
    }

    private final Listener listener;
    private final ArrayList<Row> rows = new ArrayList<>();
    private final Map<String, WorkState> workStates = new HashMap<>();
    private final Map<String, String> installedIds = new HashMap<>();

    TexturePackAdapter(Listener listener) {
        this.listener = listener;
    }

    void setRows(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    void setWorkStates(Map<String, WorkState> states) {
        workStates.clear();
        workStates.putAll(states);
        notifyDataSetChanged();
    }

    void setInstalledIds(Map<String, String> ids) {
        installedIds.clear();
        installedIds.putAll(ids);
        notifyDataSetChanged();
    }

    int getRowCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_texture_pack, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final Row row = rows.get(position);
        final TexturePackCatalog.Entry entry = row.entry;
        holder.game.setText(row.libraryTitle + "  •  " + row.serial);
        holder.name.setText(entry.name);
        holder.author.setText("By " + String.join(", ", entry.authors)
                + "  •  " + entry.version);
        holder.meta.setText(TexturePackDownloadWorker.formatBytes(entry.sizeBytes)
                + "  •  " + entry.fileCount + " textures");
        holder.description.setText(entry.description.isBlank()
                ? entry.credits : entry.description);

        final WorkState work = workStates.get(row.serial);
        final String installedId = installedIds.get(row.serial);
        final boolean thisInstalled = entry.id.equals(installedId);
        if (work != null) {
            holder.status.setVisibility(View.VISIBLE);
            holder.status.setText(work.label);
            holder.install.setEnabled(false);
            holder.install.setText(entry.id.equals(work.packId) ? work.label : "Game busy");
        } else {
            holder.install.setEnabled(true);
            if (thisInstalled) {
                holder.status.setVisibility(View.VISIBLE);
                holder.status.setText("Installed and enabled");
                holder.install.setText("Reinstall");
            } else if (installedId != null && !installedId.isBlank()) {
                holder.status.setVisibility(View.VISIBLE);
                holder.status.setText("A different pack is installed for this game");
                holder.install.setText("Switch");
            } else {
                holder.status.setVisibility(View.GONE);
                holder.install.setText("Download");
            }
        }
        holder.install.setOnClickListener(v -> listener.onInstallRequested(row));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final TextView game;
        final TextView name;
        final TextView author;
        final TextView meta;
        final TextView description;
        final TextView status;
        final MaterialButton install;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            game = itemView.findViewById(R.id.texture_pack_game);
            name = itemView.findViewById(R.id.texture_pack_name);
            author = itemView.findViewById(R.id.texture_pack_author);
            meta = itemView.findViewById(R.id.texture_pack_meta);
            description = itemView.findViewById(R.id.texture_pack_description);
            status = itemView.findViewById(R.id.texture_pack_status);
            install = itemView.findViewById(R.id.texture_pack_install);
        }
    }
}
