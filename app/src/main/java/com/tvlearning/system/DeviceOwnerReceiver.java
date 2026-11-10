package com.tvlearning.system;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

public class DeviceOwnerReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        PolicyStore.get(context).addLog("设备管理已启用");
        WatchPolicyService.start(context);
    }

    @Override
    public void onLockTaskModeEntering(Context context, Intent intent, String pkg) {
        PolicyStore.get(context).addLog("系统锁任务已进入");
    }

    @Override
    public void onLockTaskModeExiting(Context context, Intent intent) {
        PolicyStore.get(context).addLog("系统锁任务已退出");
        WatchPolicyService.start(context);
    }
}
