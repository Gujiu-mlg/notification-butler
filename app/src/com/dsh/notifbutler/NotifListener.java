package com.dsh.notifbutler;

import android.app.Notification;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * ★ 核心：通知拦截服务（无需 root）。
 *
 * 原理：
 *   系统把所有 App 的通知都投递给本服务，本服务在通知【显示之前】
 *   把它取消掉 —— 用户就看不到了，效果等同于"禁止通知"。
 *
 * 注意：
 *   · 需要用户在系统设置里授予「通知使用权」
 *   · cancelNotification 是异步的，极短时间内通知可能一闪而过，
 *     但绝大多数情况下用户看不到
 *   · 某些系统（含 ColorOS）的"前台服务通知"无法被取消，属于正常现象
 */
public class NotifListener extends NotificationListenerService {

    public static final String TAG = "NotifButler";

    /** 判断某个包当前是否在"拦截名单"里。 */
    public static boolean isBlockedPackage(String pkg, Prefs prefs) {
        if (pkg == null) {
            return false;
        }
        // 不要拦自己，否则用户看不到本应用的提示
        return prefs.isBlocked(pkg);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        try {
            String pkg = sbn.getPackageName();
            if (pkg == null) {
                return;
            }
            Prefs prefs = new Prefs(this);
            if (!isBlockedPackage(pkg, prefs)) {
                return;
            }
            // 系统前台服务通知 / 本应用自身通知 不拦
            if (pkg.equals(getPackageName())) {
                return;
            }
            if (isForegroundService(sbn)) {
                return;
            }
            // 取消这条通知（用户就看不到了）
            cancelNotification(sbn.getKey());
        } catch (Throwable t) {
            Log.w(TAG, "onNotificationPosted: " + t);
        }
    }

    /** 是否属于前台服务通知（这类取消掉会导致服务被杀，通常不拦）。 */
    private static boolean isForegroundService(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            if (n == null) {
                return false;
            }
            Bundle ex = n.extras;
            if (ex == null) {
                return false;
            }
            return (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0
                    || ex.getBoolean("android.foregroundApps", false);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "通知使用权已连接");
        // ★ 记录真实连接状态，供界面显示
        try {
            new Prefs(this).setListenerConnected(true);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.i(TAG, "通知使用权已断开");
        try {
            new Prefs(this).setListenerConnected(false);
        } catch (Throwable ignored) {
        }
        // 尝试请求系统重新绑定（Android 11+ 有效）
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                requestRebind(new android.content.ComponentName(this,
                        NotifListener.class));
            } catch (Throwable ignored) {
            }
        }
    }
}
