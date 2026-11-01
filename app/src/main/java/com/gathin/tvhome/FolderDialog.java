package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Folder view (v3.22): a dialog grid of the folder's member
 * apps. D-pad first: the first member takes focus on open,
 * BACK closes and the caller refocuses the folder tile.
 * Long-press on a member asks the caller to offer removal
 * (the dialog itself never mutates the store).
 */
final class FolderDialog {
    private FolderDialog() {}

    static final class Member {
        final String pkg;
        final String label;
        final Drawable icon;
        final ComponentName component;

        Member(String pkg, String label, Drawable icon, ComponentName component) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.component = component;
        }
    }

    interface Listener {
        void onMemberClick(Member m);
        void onMemberLongPress(Member m);
    }

    static Dialog show(Activity act, String folderName, List<Member> members, int columns,
                     Listener listener, Runnable onDismissed) {
        Dialog d = new Dialog(act);
        d.setCancelable(true);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * act.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(act);
        title.setText(folderName);
        title.setTextSize(20);
        title.setTextColor(0xFFFFFFFF);
        root.addView(title);
        if (members.isEmpty()) {
            TextView hint = new TextView(act);
            hint.setText(R.string.folder_empty_hint);
            hint.setTextSize(14);
            hint.setTextColor(0xFFACB8C1);
            hint.setPadding(0, pad, 0, 0);
            root.addView(hint);
        } else {
            RecyclerView grid = new RecyclerView(act);
            grid.setLayoutManager(new GridLayoutManager(act, Math.max(1, columns)));
            int density = Math.round(act.getResources().getDisplayMetrics().density);
            int rows = (members.size() + columns - 1) / columns;
            grid.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.min(rows, 3) * 120 * density));
            grid.setAdapter(new RecyclerView.Adapter<Holder>() {
                @Override
                public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
                    LinearLayout cell = new LinearLayout(act);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER);
                    cell.setPadding(8 * density, 8 * density, 8 * density, 8 * density);
                    cell.setFocusable(true);
                    ImageView icon = new ImageView(act);
                    icon.setLayoutParams(new LinearLayout.LayoutParams(64 * density, 64 * density));
                    TextView label = new TextView(act);
                    label.setTextSize(14);
                    label.setTextColor(0xFFFFFFFF);
                    label.setGravity(Gravity.CENTER);
                    label.setMaxLines(1);
                    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    cell.addView(icon);
                    cell.addView(label);
                    cell.setOnFocusChangeListener((v, focused) -> {
                        v.setScaleX(focused ? 1.1f : 1f);
                        v.setScaleY(focused ? 1.1f : 1f);
                        v.setBackgroundColor(focused ? 0x33FFFFFF : 0x00000000);
                    });
                    return new Holder(cell, icon, label);
                }

                @Override
                public void onBindViewHolder(Holder h, int position) {
                    Member m = members.get(position);
                    if (m.icon != null) h.icon.setImageDrawable(m.icon);
                    h.label.setText(m.label);
                    h.cell.setOnClickListener(v -> listener.onMemberClick(m));
                    h.cell.setOnLongClickListener(v -> {
                        listener.onMemberLongPress(m);
                        return true;
                    });
                }

                @Override
                public int getItemCount() {
                    return members.size();
                }
            });
            root.addView(grid);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(0);
                if (h != null) h.itemView.requestFocus();
                else grid.requestFocus();
            });
        }
        d.setContentView(root);
        d.setOnDismissListener(dlg -> {
            if (onDismissed != null) onDismissed.run();
        });
        d.show();
        return d;
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final View cell;
        final ImageView icon;
        final TextView label;

        Holder(View cell, ImageView icon, TextView label) {
            super(cell);
            this.cell = cell;
            this.icon = icon;
            this.label = label;
        }
    }
}
