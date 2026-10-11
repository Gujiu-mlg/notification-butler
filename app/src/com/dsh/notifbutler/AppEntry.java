package com.dsh.notifbutler;

import android.graphics.drawable.Drawable;

/** 列表里的一行。 */
public class AppEntry {

    public final String label;
    public final String pkg;
    public final int uid;
    public final Drawable icon;
    public final boolean system;
    public boolean isNew;            // 是否是最近新装的
    public boolean blocked;

    public AppEntry(String label, String pkg, int uid, Drawable icon,
                    boolean system, boolean isNew, boolean blocked) {
        this.label = label;
        this.pkg = pkg;
        this.uid = uid;
        this.icon = icon;
        this.system = system;
        this.isNew = isNew;
        this.blocked = blocked;
    }

    /** 搜索匹配：名称或包名。 */
    public boolean matches(String q) {
        if (q == null || q.length() == 0) {
            return true;
        }
        String s = q.toLowerCase();
        return label.toLowerCase().contains(s)
                || pkg.toLowerCase().contains(s);
    }
}
