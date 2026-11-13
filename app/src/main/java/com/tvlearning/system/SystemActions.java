package com.tvlearning.system;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import java.util.List;

final class SystemActions {
    private final Context context;
    private final DeviceControlManager deviceControl;

    SystemActions(Context context) {
        this.context = context;
        deviceControl = DeviceControlManager.get(context);
    }

    String openOriginalHome(boolean fromWeb) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> homes = context.getPackageManager().queryIntentActivities(query, 0);
        ResolveInfo selected = null;
        for (ResolveInfo item : homes) {
            if (item.activityInfo == null
                    || context.getPackageName().equals(item.activityInfo.packageName)) {
                continue;
            }
            if ("com.ktcp.launcher".equals(item.activityInfo.packageName)) {
                selected = item;
                break;
            }
            if (selected == null) {
                selected = item;
            }
        }
        if (selected == null) {
            return "未找到原系统桌面";
        }
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.setComponent(new ComponentName(selected.activityInfo.packageName, selected.activityInfo.name));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!openTemporaryExternal(intent)) {
            return "原桌面暂时无法打开";
        }
        if (fromWeb) {
            PolicyStore.get(context).addLog("家长从手机网页临时打开原系统桌面");
        }
        return null;
    }

    boolean openTemporaryExternal(Intent intent) {
        ComponentName target = intent.getComponent();
        if (target == null) {
            target = intent.resolveActivity(context.getPackageManager());
        }
        if (target == null || !deviceControl.allowTemporaryPackage(target.getPackageName())) {
            return false;
        }
        PolicyEngine.get(context).allowOriginalHome(target.getPackageName());
        try {
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            PolicyEngine.get(context).clearOriginalHomeAccess();
            deviceControl.clearTemporaryAccess();
            deviceControl.syncProtection();
            return false;
        }
    }
}
