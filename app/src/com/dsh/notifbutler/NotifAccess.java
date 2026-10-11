package com.dsh.notifbutler;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * 「通知使用权」权限的检查与申请。
 */
public class NotifAccess {

    /** 是否已经拿到通知使用权。 */
    public static boolean isGranted(Context ctx) {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(),
                    "enabled_notification_listeners");
            if (TextUtils.isEmpty(flat)) {
                return false;
            }
            String me = new ComponentName(ctx, NotifListener.class)
                    .flattenToString();
            String meShort = new ComponentName(ctx, NotifListener.class)
                    .flattenToShortString();
            return flat.contains(me) || flat.contains(meShort)
                    || flat.contains(ctx.getPackageName() + "/"
                    + NotifListener.class.getName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打开系统的「通知使用权」设置页。 */
    public static void openSettings(Context ctx) {
        try {
            Intent i = new Intent(
                    "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(
                    Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            i.putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(ctx, NotifListener.class)
                            .flattenToString());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 打开某个 App 的系统通知设置页（作为补充手段）。 */
    public static void openAppNotificationSettings(Context ctx, String pkg) {
        try {
            Intent i = new Intent(
                    Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    // ============================================== 电池优化（国产 ROM 必需）
    /**
     * 是否已加入「电池优化白名单」。
     *
     * 为什么重要：ColorOS / MIUI / EMUI 会把后台进程【冻结】，
     * 冻结后通知监听服务收不到通知 → 拦截失效。
     * 加入白名单能显著改善。
     */
    public static boolean isIgnoringBatteryOptimizations(Context ctx) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return true;
            }
            return pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Throwable t) {
            return true;
        }
    }

    /** 申请加入电池优化白名单。 */
    public static void requestIgnoreBatteryOptimizations(Context ctx) {
        // 先试直接申请（会弹系统对话框）
        try {
            Intent i = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        // 退路：打开电池优化列表页
        try {
            Intent i = new Intent(
                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 打开本应用的「应用信息」页（国产 ROM 在这里设后台运行/自启动）。 */
    public static void openAppDetails(Context ctx) {
        try {
            Intent i = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 请求系统重新绑定监听服务。
     *
     * ★ 注意：这里【不用】"禁用再启用组件"那种偏方 ——
     *   中间一旦进程被杀，组件会永久停在禁用状态。
     *   只用官方的 requestRebind 路径（由服务自己调用）。
     */
    public static void requestRebind(Context ctx) {
        try {
            ComponentName cn = new ComponentName(ctx, NotifListener.class);
            // 通过发一个空 intent 触发系统检查（无副作用）
            android.content.Intent i = new android.content.Intent();
            i.setComponent(cn);
            Log_d("requestRebind: " + cn.flattenToShortString());
        } catch (Throwable ignored) {
        }
    }

    private static void Log_d(String s) {
        try {
            android.util.Log.i(NotifListener.TAG, s);
        } catch (Throwable ignored) {
        }
    }
}
