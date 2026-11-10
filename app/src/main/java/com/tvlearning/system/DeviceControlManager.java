package com.tvlearning.system;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;

import java.util.HashSet;
import java.util.Set;

final class DeviceControlManager {
    private static final long RETRY_FAILURE_MS = 60_000L;
    private static final String[] PROTECTION_RESTRICTIONS = {
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_CONFIG_DEFAULT_APPS,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT
    };

    private static DeviceControlManager instance;

    private final Context context;
    private final DevicePolicyManager dpm;
    private final ComponentName admin;
    private final PolicyStore store;
    private final Set<String> failures = new HashSet<>();
    private boolean initialized;
    private boolean lastProtection;
    private boolean lastApplySucceeded;
    private long lastSyncMs;
    private String temporaryPackage = "";

    static synchronized DeviceControlManager get(Context context) {
        if (instance == null) {
            instance = new DeviceControlManager(context.getApplicationContext());
        }
        return instance;
    }

    private DeviceControlManager(Context context) {
        this.context = context;
        dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(context, DeviceOwnerReceiver.class);
        store = PolicyStore.get(context);
    }

    boolean isOwner() {
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    synchronized void syncProtection() {
        if (!store.isProtectionEnabled() && !temporaryPackage.isEmpty()) {
            temporaryPackage = "";
            PolicyEngine.get(context).clearOriginalHomeAccess();
            initialized = false;
        }
        if (!temporaryPackage.isEmpty()) {
            return;
        }
        if (!isOwner()) {
            initialized = false;
            lastProtection = false;
            return;
        }
        boolean active = store.isConfigured() && store.isProtectionEnabled();
        long now = SystemClock.elapsedRealtime();
        if (initialized && active == lastProtection
                && (lastApplySucceeded || now - lastSyncMs < RETRY_FAILURE_MS)) {
            return;
        }
        initialized = true;
        lastProtection = active;
        lastSyncMs = now;
        lastApplySucceeded = active ? applyProtection() : releaseProtection();
    }

    private boolean applyProtection() {
        boolean success = setLockTaskPackages(new String[]{context.getPackageName()});
        try {
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_HOME);
            failures.remove("锁任务主页键");
        } catch (RuntimeException e) {
            success = false;
            logFailure("锁任务主页键", e);
        }
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
            filter.addCategory(Intent.CATEGORY_HOME);
            filter.addCategory(Intent.CATEGORY_DEFAULT);
            dpm.addPersistentPreferredActivity(admin, filter,
                    new ComponentName(context, HomeActivity.class));
            failures.remove("默认桌面");
        } catch (RuntimeException e) {
            success = false;
            logFailure("默认桌面", e);
        }
        try {
            dpm.setUninstallBlocked(admin, context.getPackageName(), true);
            failures.remove("防卸载");
        } catch (RuntimeException e) {
            success = false;
            logFailure("防卸载", e);
        }
        for (String restriction : PROTECTION_RESTRICTIONS) {
            if (!setRestriction(restriction, true)) {
                success = false;
            }
        }
        return success;
    }

    private boolean releaseProtection() {
        boolean success = setLockTaskPackages(new String[0]);
        try {
            dpm.setUninstallBlocked(admin, context.getPackageName(), false);
            failures.remove("防卸载");
        } catch (RuntimeException e) {
            success = false;
            logFailure("解除防卸载", e);
        }
        for (String restriction : PROTECTION_RESTRICTIONS) {
            if (!setRestriction(restriction, false)) {
                success = false;
            }
        }
        return success;
    }

    private boolean setRestriction(String restriction, boolean enabled) {
        try {
            if (enabled) {
                dpm.addUserRestriction(admin, restriction);
            } else {
                dpm.clearUserRestriction(admin, restriction);
            }
            failures.remove(restriction);
            return true;
        } catch (RuntimeException e) {
            logFailure(restriction, e);
            return false;
        }
    }

    private boolean setLockTaskPackages(String[] packages) {
        try {
            dpm.setLockTaskPackages(admin, packages);
            failures.remove("锁任务白名单");
            return true;
        } catch (RuntimeException e) {
            logFailure("锁任务白名单", e);
            return false;
        }
    }

    private void logFailure(String operation, RuntimeException e) {
        if (failures.add(operation)) {
            store.addLog("设备策略 " + operation + " 未获系统接受：" + e.getClass().getSimpleName());
        }
    }

    boolean canUseLockTask() {
        if (!isOwner() || !store.isConfigured() || !store.isProtectionEnabled()
                || !temporaryPackage.isEmpty()) {
            return false;
        }
        try {
            return dpm.isLockTaskPermitted(context.getPackageName());
        } catch (RuntimeException e) {
            return false;
        }
    }

    synchronized boolean allowTemporaryPackage(String packageName) {
        if (packageName == null
                || !packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) {
            return false;
        }
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
        if (!isOwner()) {
            temporaryPackage = packageName;
            return true;
        }
        if (!setLockTaskPackages(new String[]{packageName})) {
            return false;
        }
        for (String restriction : PROTECTION_RESTRICTIONS) {
            if (!setRestriction(restriction, false)) {
                initialized = false;
                syncProtection();
                return false;
            }
        }
        temporaryPackage = packageName;
        initialized = false;
        return true;
    }

    synchronized void clearTemporaryAccess() {
        temporaryPackage = "";
        initialized = false;
    }

    synchronized boolean hasTemporaryAccess() {
        return !temporaryPackage.isEmpty();
    }

    boolean isProtectionPolicyActive() {
        if (!isOwner() || !store.isConfigured() || !store.isProtectionEnabled()) {
            return false;
        }
        try {
            if (!dpm.isLockTaskPermitted(context.getPackageName())
                    || !dpm.isUninstallBlocked(admin, context.getPackageName())
                    || (dpm.getLockTaskFeatures(admin) & DevicePolicyManager.LOCK_TASK_FEATURE_HOME) == 0) {
                return false;
            }
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolved = context.getPackageManager().resolveActivity(home,
                    PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved == null || resolved.activityInfo == null
                    || !context.getPackageName().equals(resolved.activityInfo.packageName)) {
                return false;
            }
            Bundle restrictions = dpm.getUserRestrictions(admin);
            for (String restriction : PROTECTION_RESTRICTIONS) {
                if (android.os.Build.VERSION.SDK_INT < 29
                        && UserManager.DISALLOW_CONFIG_DEFAULT_APPS.equals(restriction)) {
                    continue;
                }
                if (!restrictions.getBoolean(restriction)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    String modeText() {
        if (!isOwner()) {
            return "基础管控";
        }
        return isProtectionPolicyActive() ? "Device Owner 已配置" : "Device Owner 未完全生效";
    }
}
