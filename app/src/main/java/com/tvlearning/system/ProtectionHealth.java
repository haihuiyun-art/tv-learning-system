package com.tvlearning.system;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class ProtectionHealth {
    static Snapshot evaluate(Context context, AppRepository apps, DeviceControlManager control,
                             boolean serviceRunning, boolean monitorAlive, boolean webServerRunning,
                             long serviceUptimeSeconds, long lastMonitorAgeSeconds, String startReason) {
        PolicyStore store = PolicyStore.get(context);
        Snapshot snapshot = new Snapshot();
        snapshot.configured = store.isConfigured();
        snapshot.protectionEnabled = store.isProtectionEnabled();
        snapshot.serviceRunning = serviceRunning;
        snapshot.monitorAlive = monitorAlive;
        snapshot.webServerRunning = webServerRunning;
        snapshot.usagePermissionGranted = apps.hasUsageAccess();
        snapshot.usageEventsReceived = apps.hasReceivedUsageEvents();
        snapshot.defaultHome = apps.isDefaultHome();
        snapshot.managedAppCount = apps.countEnabledLaunchableApps();
        snapshot.deviceOwner = control.isOwner();
        snapshot.devicePolicyActive = control.isProtectionPolicyActive();
        snapshot.controlMode = control.modeText();
        snapshot.serviceUptimeSeconds = Math.max(0L, serviceUptimeSeconds);
        snapshot.lastMonitorAgeSeconds = lastMonitorAgeSeconds;
        snapshot.startReason = startReason == null ? "unknown" : startReason;
        snapshot.bootCountAvailable = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.BOOT_COUNT, -1) >= 0;

        boolean severe = false;
        if (!serviceRunning) {
            snapshot.issues.add("监控服务未运行，请打开学习主页或重启设备后检查");
            severe = true;
        } else if (!monitorAlive) {
            snapshot.issues.add("监控心跳已停止，请重新打开学习主页检查");
            severe = true;
        }
        if (!webServerRunning) {
            snapshot.issues.add("家长网页未启动，手机暂时无法管理此设备");
        }
        if (snapshot.configured && snapshot.protectionEnabled) {
            if (!snapshot.usagePermissionGranted) {
                snapshot.issues.add("观看统计未授权，无法可靠识别受管应用");
                severe = true;
            } else if (!snapshot.usageEventsReceived) {
                snapshot.issues.add("尚未收到前台应用事件，请打开受管应用验证计时");
            }
            if (snapshot.managedAppCount == 0) {
                snapshot.issues.add("尚未选择受管应用，当前不会计算观看时长");
            }
            if (!snapshot.defaultHome) {
                snapshot.issues.add("学习系统不是默认桌面，主页键和部分入口可能绕过管控");
            }
            if (snapshot.deviceOwner && !snapshot.devicePolicyActive) {
                snapshot.issues.add("Device Owner 策略未完整生效，当前继续使用基础管控");
            }
        }

        if (!snapshot.configured) {
            snapshot.level = "setup";
            snapshot.summary = "等待家长初始化";
        } else if (!snapshot.protectionEnabled) {
            snapshot.level = "paused";
            snapshot.summary = "保护已暂停";
        } else if (severe) {
            snapshot.level = "error";
            snapshot.summary = "保护已降级，需要检查";
        } else if (!snapshot.issues.isEmpty()) {
            snapshot.level = "warning";
            snapshot.summary = "保护不完整，需要检查";
        } else {
            snapshot.level = "ok";
            snapshot.summary = snapshot.deviceOwner ? "Device Owner 管控运行正常" : "基础管控运行正常";
        }
        return snapshot;
    }

    static String startReasonText(String reason) {
        if ("boot".equals(reason)) {
            return "正常开机";
        }
        if ("quickboot".equals(reason)) {
            return "厂商快速开机";
        }
        if ("unlock".equals(reason)) {
            return "用户解锁";
        }
        if ("update".equals(reason)) {
            return "应用覆盖升级";
        }
        if ("system_restart".equals(reason)) {
            return "系统重建服务";
        }
        if ("activity".equals(reason)) {
            return "学习界面";
        }
        if ("stopped".equals(reason)) {
            return "未运行";
        }
        return "其他启动";
    }

    static final class Snapshot {
        String level;
        String summary;
        String controlMode;
        String startReason;
        boolean configured;
        boolean protectionEnabled;
        boolean serviceRunning;
        boolean monitorAlive;
        boolean webServerRunning;
        boolean usagePermissionGranted;
        boolean usageEventsReceived;
        boolean defaultHome;
        boolean deviceOwner;
        boolean devicePolicyActive;
        boolean bootCountAvailable;
        int managedAppCount;
        long serviceUptimeSeconds;
        long lastMonitorAgeSeconds;
        final List<String> issues = new ArrayList<>();

        JSONObject toJson() throws JSONException {
            JSONObject checks = new JSONObject()
                    .put("configured", configured)
                    .put("protectionEnabled", protectionEnabled)
                    .put("serviceRunning", serviceRunning)
                    .put("monitorAlive", monitorAlive)
                    .put("webServerRunning", webServerRunning)
                    .put("usagePermissionGranted", usagePermissionGranted)
                    .put("usageEventsReceived", usageEventsReceived)
                    .put("defaultHome", defaultHome)
                    .put("managedAppCount", managedAppCount)
                    .put("deviceOwner", deviceOwner)
                    .put("devicePolicyActive", devicePolicyActive)
                    .put("bootCountAvailable", bootCountAvailable);
            return new JSONObject()
                    .put("level", level)
                    .put("summary", summary)
                    .put("issues", new JSONArray(issues))
                    .put("checks", checks)
                    .put("controlMode", controlMode)
                    .put("startReason", startReason)
                    .put("startReasonText", ProtectionHealth.startReasonText(startReason))
                    .put("serviceUptimeSeconds", serviceUptimeSeconds)
                    .put("lastMonitorAgeSeconds", lastMonitorAgeSeconds);
        }

        boolean isHealthy() {
            return "ok".equals(level);
        }

        String displayText() {
            StringBuilder text = new StringBuilder(summary);
            for (String issue : issues) {
                text.append("\n• ").append(issue);
            }
            return text.toString();
        }
    }
}
