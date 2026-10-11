package com.dsh.notifbutler;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

/** 应用列表适配器。 */
public class AppListAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<AppEntry> data;

    public AppListAdapter(Context ctx, List<AppEntry> data) {
        this.ctx = ctx;
        this.data = data;
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Object getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    static class Holder {
        ImageView icon;
        TextView label;
        TextView pkg;
        TextView badgeNew;
        Switch sw;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            convertView = LayoutInflater.from(ctx)
                    .inflate(R.layout.item_app, parent, false);
            h = new Holder();
            h.icon = (ImageView) convertView.findViewById(R.id.icon);
            h.label = (TextView) convertView.findViewById(R.id.label);
            h.pkg = (TextView) convertView.findViewById(R.id.pkg);
            h.badgeNew = (TextView) convertView.findViewById(R.id.badge_new);
            h.sw = (Switch) convertView.findViewById(R.id.sw);
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }

        AppEntry e = data.get(position);
        h.icon.setImageDrawable(e.icon);
        h.label.setText(e.label);
        h.pkg.setText(e.pkg + (e.system ? "  · 系统" : ""));
        h.badgeNew.setVisibility(e.isNew ? View.VISIBLE : View.GONE);

        // 开关只作指示，点击由整行处理（避免 ListView 复用错乱）
        h.sw.setClickable(false);
        h.sw.setFocusable(false);
        h.sw.setChecked(!e.blocked);

        // 被拦截的显示为灰色，形成对比
        h.label.setTextColor(e.blocked
                ? Color.parseColor("#A8ADB6") : Color.parseColor("#16181D"));
        h.pkg.setTextColor(Color.parseColor("#8A8F99"));
        return convertView;
    }
}
