package com.tvlearning.system;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AppRepository {
    private final Context context;
    private final PackageManager packageManager;
    private long lastUsageQueryMs;
    private long lastUsageEventMs;
    private String foregroundPackage = "";
    private boolean usageInitialized;
    private boolean usageEventSeen;
    private boolean usageAccessDenied;
    private int lastUsageMode = -1;
    private boolean usageFailureLogged;
    private final Set<String> homePackages = new HashSet<>();
    private final Map<String, Drawable> iconCache = new HashMap<>();

    AppRepository(Context context) {
        this.context = context.getApplicationContext();
        packageManager = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo info : packageManager.queryIntentActivities(intent, 0)) {
            if (info.activityInfo != null && !context.getPackageName().equals(info.activityInfo.packageName)) {
                homePackages.add(info.activityInfo.packageName);
            }
        }
    }

    List<AppItem> getLaunchableApps() {
        Set<String> enabled = PolicyStore.get(context).getEnabledApps();
        Map<String, AppItem> apps = new HashMap<>();
        collectApps(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), enabled, apps);
        collectApps(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), enabled, apps);
        List<AppItem> result = new ArrayList<>(apps.values());
        final Collator collator = Collator.getInstance();
        Collections.sort(result, new Comparator<AppItem>() {
            @Override
            public int compare(AppItem left, AppItem right) {
                return collator.compare(left.label, right.label);
            }
        });
        return result;
    }

    int countEnabledLaunchableApps() {
        Set<String> packages = new HashSet<>();
        collectLaunchablePackages(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), packages);
        collectLaunchablePackages(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), packages);
        packages.retainAll(PolicyStore.get(context).getEnabledApps());
        return packages.size();
    }

    private void collectLaunchablePackages(Intent intent, Set<String> packages) {
        for (ResolveInfo info : packageManager.queryIntentActivities(intent, 0)) {
            if (info.activityInfo != null
                    && !context.getPackageName().equals(info.activityInfo.packageName)) {
                packages.add(info.activityInfo.packageName);
            }
        }
    }

    private void collectApps(Intent intent, Set<String> enabled, Map<String, AppItem> apps) {
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(intent, 0);
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (context.getPackageName().equals(packageName) || apps.containsKey(packageName)) {
                continue;
            }
            CharSequence label = info.loadLabel(packageManager);
            Drawable icon = iconCache.get(packageName);
            if (icon == null) {
                try {
                    icon = info.loadIcon(packageManager);
                } catch (RuntimeException e) {
                    icon = null;
                }
                if (icon == null) {
                    icon = packageManager.getDefaultActivityIcon();
                }
                iconCache.put(packageName, icon);
            }
            apps.put(packageName, new AppItem(packageName,
                    label == null ? packageName : label.toString(), enabled.contains(packageName), icon));
        }
    }

    JSONArray toJson() {
        JSONArray result = new JSONArray();
        try {
            for (AppItem app : getLaunchableApps()) {
                result.put(new JSONObject()
                        .put("packageName", app.packageName)
                        .put("label", app.label)
                        .put("enabled", app.enabled));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return result;
    }

    synchronized boolean hasUsageAccess() {
        usageAccessDenied = false;
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps != null) {
            try {
                int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName());
                if (mode != lastUsageMode) {
                    lastUsageMode = mode;
                    android.util.Log.i("TvLearning", "usage access mode=" + mode + " uid=" + Process.myUid());
                }
                if (mode == AppOpsManager.MODE_ALLOWED) {
                    return true;
                }
                if (mode == AppOpsManager.MODE_DEFAULT) {
                    return usageEventSeen;
                }
                usageAccessDenied = true;
                return false;
            } catch (RuntimeException e) {
                if (!usageFailureLogged) {
                    usageFailureLogged = true;
                    android.util.Log.w("TvLearning", "usage access check failed", e);
                }
                return usageEventSeen;
            }
        }
        return usageEventSeen;
    }

    synchronized String getForegroundPackage() {
        if (!hasUsageAccess() && usageAccessDenied) {
            usageInitialized = false;
            foregroundPackage = "";
            lastUsageQueryMs = 0;
            lastUsageEventMs = 0;
            return "";
        }
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            return "";
        }
        long now = System.currentTimeMillis();
        long bootStart = now - android.os.SystemClock.elapsedRealtime() - 1000L;
        if (!usageInitialized || now < lastUsageQueryMs) {
            lastUsageEventMs = bootStart;
            foregroundPackage = "";
        }
        long start = Math.max(bootStart, lastUsageEventMs);
        try {
            UsageEvents events = manager.queryEvents(start, now + 1000L);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                if (!usageEventSeen) {
                    android.util.Log.i("TvLearning", "usage event stream available");
                }
                usageEventSeen = true;
                events.getNextEvent(event);
                long timestamp = event.getTimeStamp();
                String packageName = event.getPackageName();
                if (packageName == null || timestamp < lastUsageEventMs) {
                    continue;
                }
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    foregroundPackage = packageName;
                } else if (event.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND
                        && packageName.equals(foregroundPackage)) {
                    foregroundPackage = "";
                }
                lastUsageEventMs = timestamp;
            }
            lastUsageQueryMs = now;
            usageInitialized = true;
            return foregroundPackage;
        } catch (RuntimeException e) {
            if (lastUsageQueryMs >= 0) {
                android.util.Log.w("TvLearning", "usage event query failed", e);
            }
            lastUsageQueryMs = -1;
            usageInitialized = false;
            foregroundPackage = "";
            return "";
        }
    }

    synchronized boolean hasReceivedUsageEvents() {
        return usageEventSeen;
    }

    boolean isHomePackage(String packageName) {
        return packageName != null && homePackages.contains(packageName);
    }

    boolean isDefaultHome() {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        return info != null && info.activityInfo != null
                && context.getPackageName().equals(info.activityInfo.packageName);
    }

    Intent getLaunchIntent(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return null;
        }
        return packageManager.getLaunchIntentForPackage(packageName);
    }

    static final class AppItem {
        final String packageName;
        final String label;
        final boolean enabled;
        final Drawable icon;

        AppItem(String packageName, String label, boolean enabled, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.enabled = enabled;
            this.icon = icon;
        }
    }
}
