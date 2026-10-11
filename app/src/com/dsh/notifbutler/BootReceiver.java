package com.dsh.notifbutler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机 / 应用更新后的处理。
 *
 * ★ 这里【故意什么都不做】。
 *
 * 早期版本试图通过「禁用再启用服务组件」来强制重新绑定，
 * 但那有个严重隐患：
 *     如果进程恰好在 DISABLED 和 ENABLED 之间被杀（开机时很常见），
 *     服务组件就会【永久停留在禁用状态】，用户再也收不到拦截，
 *     而且从界面上完全看不出来。
 *
 * 实际上系统在开机会自动重新绑定 NotificationListenerService，
 * 万一没绑上，服务自己的 onListenerDisconnected() 里会调用
 * requestRebind() 补救。所以这里只需要记一条日志。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            boolean granted = NotifAccess.isGranted(ctx);
            Log.i(NotifListener.TAG, "BootReceiver: action="
                    + (intent == null ? "?" : intent.getAction())
                    + " 通知使用权=" + granted);
        } catch (Throwable ignored) {
        }
    }
}
