package com.dsh.notifbutler;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * 本地存储。
 *
 * 存三份集合：
 *   blocked  —— 被拦截通知的包名
 *   known    —— 已知的包名（用来发现新装的 App）
 *   newApps  —— 上次扫描后发现的新装 App
 *
 * ★ 性能要点：
 *   isBlocked() 在「每收到一条通知」时都会被调用，
 *   而通知可能密集到每秒几十条。所以这里做了内存缓存，
 *   让热路径【零分配、零 IO】。
 *   原始实现每次都把整个集合复制一遍，通知多时会造成卡顿。
 */
public class Prefs {

    private static final String FILE = "notifbutler";
    private static final String K_BLOCKED = "blocked";
    private static final String K_KNOWN = "known";
    private static final String K_NEW = "newapps";
    private static final String K_SHOW_SYS = "show_system";
    private static final String K_ONLY_NEW = "only_new";
    private static final String K_BATT_HINT = "battery_hint_dismissed";
    private static final String K_LISTENER_OK = "listener_connected";

    /** blocked 集合的内存缓存（进程内单例，热路径读它）。 */
    private static volatile Set<String> sBlockedCache = null;

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext()
                .getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 屏蔽集合
    /** 带缓存地拿到被拦截集合（可变，仅供内部修改）。 */
    private Set<String> cache() {
        Set<String> c = sBlockedCache;
        if (c == null) {
            synchronized (Prefs.class) {
                c = sBlockedCache;
                if (c == null) {
                    c = copy(sp.getStringSet(K_BLOCKED, null));
                    sBlockedCache = c;
                }
            }
        }
        return c;
    }

    /** ★ 热路径：每条通知都会调用，必须零分配。 */
    public boolean isBlocked(String pkg) {
        return pkg != null && cache().contains(pkg);
    }

    /** 给界面用的副本。 */
    public Set<String> blocked() {
        return new HashSet<String>(cache());
    }

    public void setBlocked(String pkg, boolean blocked) {
        Set<String> s = cache();
        boolean changed;
        if (blocked) {
            changed = s.add(pkg);
        } else {
            changed = s.remove(pkg);
        }
        if (changed) {
            sp.edit().putStringSet(K_BLOCKED,
                    new HashSet<String>(s)).apply();
        }
    }

    public void setBlockedAll(Set<String> pkgs, boolean blocked) {
        Set<String> s = cache();
        boolean changed;
        if (blocked) {
            changed = s.addAll(pkgs);
        } else {
            changed = s.removeAll(pkgs);
        }
        if (changed) {
            sp.edit().putStringSet(K_BLOCKED,
                    new HashSet<String>(s)).apply();
        }
    }

    /** ★ 整份替换（供"撤销"用）。 */
    public void replaceBlocked(Set<String> pkgs) {
        Set<String> s = cache();
        s.clear();
        if (pkgs != null) {
            s.addAll(pkgs);
        }
        sp.edit().putStringSet(K_BLOCKED, new HashSet<String>(s)).apply();
    }

    public int blockedCount() {
        return cache().size();
    }

    // ---------------------------------------------------------------- 已知集合
    public Set<String> known() {
        return copy(sp.getStringSet(K_KNOWN, null));
    }

    public void setKnown(Set<String> pkgs) {
        sp.edit().putStringSet(K_KNOWN, new HashSet<String>(pkgs)).apply();
    }

    /** 是否第一次运行（第一次不把全部 App 当成"新装的"）。 */
    public boolean isFirstRun() {
        return known().isEmpty();
    }

    // ---------------------------------------------------------------- 新装集合
    public Set<String> newApps() {
        return copy(sp.getStringSet(K_NEW, null));
    }

    public void setNewApps(Set<String> pkgs) {
        sp.edit().putStringSet(K_NEW, new HashSet<String>(pkgs)).apply();
    }

    /** 收到安装广播时，记下一个新装 App。 */
    public void addNewApp(String pkg) {
        Set<String> s = newApps();
        s.add(pkg);
        sp.edit().putStringSet(K_NEW, s).apply();
    }

    /** 只移除指定的几个（★ 别整个清空，否则别的筛选下的标记也会丢）。 */
    public void removeNewApps(Set<String> pkgs) {
        Set<String> s = newApps();
        if (s.removeAll(pkgs)) {
            sp.edit().putStringSet(K_NEW, s).apply();
        }
    }

    public void clearNewApps() {
        sp.edit().remove(K_NEW).apply();
    }

    // ---------------------------------------------------------------- 界面选项
    public boolean showSystem() {
        return sp.getBoolean(K_SHOW_SYS, false);
    }

    public void setShowSystem(boolean v) {
        sp.edit().putBoolean(K_SHOW_SYS, v).apply();
    }

    public boolean onlyNew() {
        return sp.getBoolean(K_ONLY_NEW, false);
    }

    public void setOnlyNew(boolean v) {
        sp.edit().putBoolean(K_ONLY_NEW, v).apply();
    }

    public boolean batteryHintDismissed() {
        return sp.getBoolean(K_BATT_HINT, false);
    }

    public void setBatteryHintDismissed(boolean v) {
        sp.edit().putBoolean(K_BATT_HINT, v).apply();
    }

    // ---------------------------------------------------------------- 连接状态
    /**
     * ★ 监听服务的真实连接状态（由 NotifListener 自己写入）。
     *
     * 为什么需要：系统设置里"通知使用权"是开着的，
     * 但服务可能其实【没连上】（force-stop 后、被系统冻结后等），
     * 这时拦截是完全失效的，界面上却看不出来。
     */
    public boolean isListenerConnected() {
        return sp.getBoolean(K_LISTENER_OK, false);
    }

    public void setListenerConnected(boolean v) {
        sp.edit().putBoolean(K_LISTENER_OK, v).apply();
    }

    // ---------------------------------------------------------------- 工具
    private static Set<String> copy(Set<String> src) {
        if (src == null) {
            return new HashSet<String>();
        }
        return new HashSet<String>(src);
    }
}
