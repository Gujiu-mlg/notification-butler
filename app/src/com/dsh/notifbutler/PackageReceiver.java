package com.dsh.notifbutler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * ★ 新装 App 自动识别。
 *
 * PACKAGE_ADDED / PACKAGE_REMOVED / PACKAGE_REPLACED 属于
 * Android 8+ 后台执行限制的【豁免广播】，所以静态注册也能收到。
 */
public class PackageReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }
        String action = intent.getAction();
        String pkg = intent.getData().getSchemeSpecificPart();
        if (pkg == null || pkg.length() == 0) {
            return;
        }
        Prefs prefs = new Prefs(ctx);

        if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            // 替换安装时也会发 ADDED，用 EXTRA_REPLACING 区分
            boolean replacing = intent.getBooleanExtra(
                    Intent.EXTRA_REPLACING, false);
            if (!replacing) {
                prefs.addNewApp(pkg);
                Log.i(NotifListener.TAG, "发现新装应用: " + pkg);
                // 通知界面刷新（如果界面开着）
                MainActivity.notifyDataChanged();
            } else {
                // 更新安装：如果本来没记录过，也算新的
                if (!prefs.known().contains(pkg)) {
                    prefs.addNewApp(pkg);
                    MainActivity.notifyDataChanged();
                }
            }
        } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
            boolean replacing = intent.getBooleanExtra(
                    Intent.EXTRA_REPLACING, false);
            if (!replacing) {
                // 卸载了：从各个集合里清掉
                prefs.setBlocked(pkg, false);
                java.util.Set<String> known = prefs.known();
                known.remove(pkg);
                prefs.setKnown(known);
                java.util.Set<String> fresh = prefs.newApps();
                fresh.remove(pkg);
                prefs.setNewApps(fresh);
                MainActivity.notifyDataChanged();
            }
        }
    }
}
