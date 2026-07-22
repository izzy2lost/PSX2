package com.izzy2lost.psx2;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import java.io.File;

public class CoversAdapter extends RecyclerView.Adapter<CoversAdapter.VH> {
    private static final String NO_COVER_ASSET_URI =
            "file:///android_asset/resources/no-cover.png";

    public interface OnItemClick {
        void onClick(int position);
    }

    public interface OnItemLongClick {
        void onLongClick(int position);
    }

    private final Context context;
    private final String[] titles;
    private final String[] coverUrls;
    private final String[] localPaths; // absolute file paths for cached covers (may be null)
    private final OnItemClick onItemClick;
    private final OnItemLongClick onItemLongClick;
    private final int itemLayoutResId;
    private int overrideItemWidthPx = 0;

    public CoversAdapter(Context context, String[] titles, String[] coverUrls, String[] localPaths, OnItemClick click) {
        this(context, titles, coverUrls, localPaths, R.layout.item_cover, click, null);
    }

    public CoversAdapter(Context context, String[] titles, String[] coverUrls, String[] localPaths, int itemLayoutResId, OnItemClick click, OnItemLongClick longClick) {
        this.context = context;
        this.titles = titles;
        this.coverUrls = coverUrls;
        this.localPaths = localPaths;
        this.itemLayoutResId = itemLayoutResId;
        this.onItemClick = click;
        this.onItemLongClick = longClick;
        // This adapter repeats items for infinite scrolling. Stable IDs would collide,
        // especially when modded games resolve to the same title.
        setHasStableIds(false);
    }

    public void setItemWidthPx(int widthPx) {
        if (widthPx != overrideItemWidthPx) {
            overrideItemWidthPx = widthPx;
            notifyDataSetChanged();
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(itemLayoutResId, parent, false);
        if (overrideItemWidthPx > 0) {
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) v.getLayoutParams();
            lp.width = overrideItemWidthPx;
            v.setLayoutParams(lp);
        }
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        int count = titles.length;
        int real = (count == 0) ? 0 : (position % count);
        // Ensure dynamic width is applied
        if (overrideItemWidthPx > 0) {
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
            if (lp.width != overrideItemWidthPx) {
                lp.width = overrideItemWidthPx;
                holder.itemView.setLayoutParams(lp);
            }
        }
        holder.title.setText(titles[real]);
        String local = (localPaths != null && real < localPaths.length) ? localPaths[real] : null;
        boolean loadedImage = false;
        if (local != null && local.startsWith("content://")) {
            android.net.Uri uri = android.net.Uri.parse(local);
            // Existence/validation is handled before paths reach the adapter. Avoid a
            // synchronous DocumentProvider query on the main thread for every bind.
            Glide.with(context)
                    .load(uri)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .fitCenter()
                    .thumbnail(noCoverRequest())
                    .error(noCoverRequest())
                    .into(holder.cover);
            loadedImage = true;
        } else if (local != null) {
            File f = new File(local);
            if (f.exists() && f.length() > 0) {
                Glide.with(context)
                        .load(f)
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .fitCenter()
                        .thumbnail(noCoverRequest())
                        .error(noCoverRequest())
                        .into(holder.cover);
                loadedImage = true;
            }
        }

        if (!loadedImage) {
            // Use the bundled no-cover art directly. This avoids the old asynchronous
            // SAF lookup race while keeping the intended placeholder artwork.
            noCoverRequest().into(holder.cover);
        }
        holder.itemView.setOnClickListener(v -> {
            if (onItemClick != null) {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) pos = position; // fallback
                int count2 = titles.length;
                int realNow = (count2 == 0) ? 0 : (pos % count2);
                onItemClick.onClick(realNow);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (onItemLongClick != null) {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) pos = position; // fallback
                int count2 = titles.length;
                int realNow = (count2 == 0) ? 0 : (pos % count2);
                onItemLongClick.onLongClick(realNow);
                return true;
            }
            return false;
        });
    }

    private RequestBuilder<Drawable> noCoverRequest() {
        return Glide.with(context)
                .load(NO_COVER_ASSET_URI)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .fitCenter();
    }

    @Override
    public int getItemCount() {
        return titles.length == 0 ? 0 : Integer.MAX_VALUE;
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title;
        VH(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.image_cover);
            title = itemView.findViewById(R.id.text_title);
        }
    }
}
