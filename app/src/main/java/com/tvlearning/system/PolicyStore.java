package com.tvlearning.system;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PolicyStore {
    static final String IDLE = "idle";
    static final String WATCHING = "watching";
    static final String LOCKED = "locked";
    static final String COOLDOWN = "cooldown";
    static final String REST = "break";
    static final String QUIET = "quiet";
    static final String DAILY_LIMIT = "daily_limit";
    static final String WATCH_INITIAL = "initial";
    static final String WATCH_REWARD = "reward";
    static final String WATCH_PARENT = "parent";

    private static final String PREFS = "tv_learning_store_v1";
    private static final Set<String> DEFAULT_VIDEO_APPS = new HashSet<>(Arrays.asList(
            "com.ktcp.video",
            "com.qiyi.video.pad",
            "com.qiyi.video",
            "com.youku.tv",
            "com.hunantv.imgo.activity",
            "com.xiaodianshi.tv.yst",
            "tv.bro"
    ));

    private static PolicyStore instance;

    private final SharedPreferences prefs;

    static synchronized PolicyStore get(Context context) {
        if (instance == null) {
            instance = new PolicyStore(context.getApplicationContext());
        }
        return instance;
    }

    private PolicyStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains("apps_initialized")) {
            prefs.edit()
                    .putStringSet("enabled_apps", new HashSet<>(DEFAULT_VIDEO_APPS))
                    .putBoolean("apps_initialized", true)
                    .apply();
        }
    }

    synchronized Policy getPolicy() {
        Policy policy = new Policy();
        policy.initialWatchMinutes = prefs.getInt("initial_watch", 10);
        policy.rewardMinutes = prefs.getInt("reward", 10);
        policy.retryDelayMinutes = prefs.getInt("retry_delay", 5);
        policy.dailyLimitMinutes = prefs.getInt("daily_limit", 0);
        policy.breakIntervalMinutes = prefs.getInt("break_interval", 0);
        policy.breakDurationMinutes = prefs.getInt("break_duration", 5);
        policy.quietStart = prefs.getString("quiet_start", "22:00");
        policy.quietEnd = prefs.getString("quiet_end", "07:00");
        return policy;
    }

    synchronized void updatePolicy(JSONObject value) throws JSONException {
        Policy policy = new Policy();
        policy.initialWatchMinutes = requiredInt(value, "initialWatchMinutes", 1, 180);
        policy.rewardMinutes = requiredInt(value, "rewardMinutes", 1, 180);
        policy.retryDelayMinutes = requiredInt(value, "retryDelayMinutes", 1, 120);
        policy.dailyLimitMinutes = requiredInt(value, "dailyLimitMinutes", 0, 720);
        policy.breakIntervalMinutes = requiredInt(value, "breakIntervalMinutes", 0, 180);
        policy.breakDurationMinutes = requiredInt(value, "breakDurationMinutes", 1, 60);
        policy.quietStart = requiredTime(value, "quietStart");
        policy.quietEnd = requiredTime(value, "quietEnd");
        prefs.edit()
                .putInt("initial_watch", policy.initialWatchMinutes)
                .putInt("reward", policy.rewardMinutes)
                .putInt("retry_delay", policy.retryDelayMinutes)
                .putInt("daily_limit", policy.dailyLimitMinutes)
                .putInt("break_interval", policy.breakIntervalMinutes)
                .putInt("break_duration", policy.breakDurationMinutes)
                .putString("quiet_start", policy.quietStart)
                .putString("quiet_end", policy.quietEnd)
                .commit();
        addLog("家长时间规则已保存");
    }

    private static int requiredInt(JSONObject value, String name, int min, int max) throws JSONException {
        int number = value.getInt(name);
        if (number < min || number > max) {
            throw new IllegalArgumentException(name + " 超出允许范围");
        }
        return number;
    }

    private static String requiredTime(JSONObject value, String name) throws JSONException {
        String time = value.getString(name);
        if (!time.matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
            throw new IllegalArgumentException(name + " 时间格式不正确");
        }
        return time;
    }

    synchronized boolean isProtectionEnabled() {
        return prefs.getBoolean("protection_enabled", true);
    }

    synchronized void setProtectionEnabled(boolean enabled) {
        prefs.edit().putBoolean("protection_enabled", enabled).commit();
    }

    synchronized RuntimeState getRuntime() {
        RuntimeState state = new RuntimeState();
        state.state = prefs.getString("state", IDLE);
        state.remainingWatchSeconds = prefs.getInt("remaining_watch", getPolicy().initialWatchMinutes * 60);
        state.cooldownRemainingSeconds = prefs.getInt("cooldown_remaining", 0);
        state.dailyDate = prefs.getString("daily_date", currentDate());
        state.dailyUsedSeconds = prefs.getLong("daily_used", 0L);
        state.sinceBreakSeconds = prefs.getLong("since_break", 0L);
        state.currentQuestionId = prefs.getString("current_question", "");
        state.lastQuestionId = prefs.getString("last_question", "");
        state.lastVideoPackage = prefs.getString("last_video", "");
        state.lastWallClockMs = prefs.getLong("last_wall", System.currentTimeMillis());
        state.lastElapsedMs = prefs.getLong("last_elapsed", SystemClock.elapsedRealtime());
        state.bootCount = prefs.getInt("boot_count", Integer.MIN_VALUE);
        state.parentOverride = prefs.getBoolean("parent_override", false);
        state.watchKind = prefs.getString("watch_kind", state.parentOverride ? WATCH_PARENT : WATCH_INITIAL);
        return state;
    }

    synchronized void saveRuntime(RuntimeState state, boolean durable) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString("state", state.state)
                .putInt("remaining_watch", Math.max(0, state.remainingWatchSeconds))
                .putInt("cooldown_remaining", Math.max(0, state.cooldownRemainingSeconds))
                .putString("daily_date", state.dailyDate)
                .putLong("daily_used", Math.max(0L, state.dailyUsedSeconds))
                .putLong("since_break", Math.max(0L, state.sinceBreakSeconds))
                .putString("current_question", nullToEmpty(state.currentQuestionId))
                .putString("last_question", nullToEmpty(state.lastQuestionId))
                .putString("last_video", nullToEmpty(state.lastVideoPackage))
                .putLong("last_wall", state.lastWallClockMs)
                .putLong("last_elapsed", state.lastElapsedMs)
                .putInt("boot_count", state.bootCount)
                .putString("watch_kind", nullToEmpty(state.watchKind))
                .putBoolean("parent_override", state.parentOverride);
        if (durable) {
            editor.commit();
        } else {
            editor.apply();
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static String currentDate() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    synchronized boolean isConfigured() {
        return prefs.getBoolean("configured", false);
    }

    synchronized String getPairingCode() {
        if (isConfigured()) {
            return "";
        }
        String value = prefs.getString("pairing_code", "");
        long createdAt = prefs.getLong("pairing_created_at", 0L);
        long now = System.currentTimeMillis();
        if (value.isEmpty() || createdAt <= 0 || now < createdAt || now - createdAt > 15 * 60 * 1000L) {
            value = PasswordHasher.newPairingCode();
            prefs.edit()
                    .putString("pairing_code", value)
                    .putLong("pairing_created_at", now)
                    .apply();
        }
        return value;
    }

    synchronized boolean completeSetup(String pairingCode, String pin) {
        if (isConfigured() || !getPairingCode().equals(pairingCode) || pin == null || !pin.matches("\\d{6,8}")) {
            return false;
        }
        String pinSalt = PasswordHasher.newSalt();
        String recoverySalt = PasswordHasher.newSalt();
        String recoveryCode = PasswordHasher.newRecoveryCode();
        boolean saved = prefs.edit()
                .putString("pin_salt", pinSalt)
                .putString("pin_hash", PasswordHasher.hash(pin, pinSalt))
                .putString("recovery_salt", recoverySalt)
                .putString("recovery_hash", PasswordHasher.hash(recoveryCode, recoverySalt))
                .putString("pending_recovery", recoveryCode)
                .remove("pairing_code")
                .putBoolean("configured", true)
                .commit();
        if (saved) {
            addLog("家长控制已完成初始化");
        }
        return saved;
    }

    synchronized boolean verifyPin(String pin) {
        if (!isConfigured() || pin == null) {
            return false;
        }
        return PasswordHasher.matches(pin, prefs.getString("pin_salt", ""), prefs.getString("pin_hash", ""));
    }

    synchronized boolean verifyRecoveryCode(String code) {
        if (!isConfigured() || code == null) {
            return false;
        }
        return PasswordHasher.matches(code.trim().toUpperCase(Locale.US),
                prefs.getString("recovery_salt", ""), prefs.getString("recovery_hash", ""));
    }

    synchronized boolean changePin(String currentSecret, String newPin) {
        String nextPin = newPin == null ? "" : newPin.trim();
        if (!isConfigured() || !nextPin.matches("\\d{6,8}")
                || !PolicyEngine.verifyParentSecret(this, currentSecret)) {
            return false;
        }
        String pinSalt = PasswordHasher.newSalt();
        String recoverySalt = PasswordHasher.newSalt();
        String recoveryCode = PasswordHasher.newRecoveryCode();
        boolean saved = prefs.edit()
                .putString("pin_salt", pinSalt)
                .putString("pin_hash", PasswordHasher.hash(nextPin, pinSalt))
                .putString("recovery_salt", recoverySalt)
                .putString("recovery_hash", PasswordHasher.hash(recoveryCode, recoverySalt))
                .putString("pending_recovery", recoveryCode)
                .commit();
        if (saved) {
            addLog("家长 PIN 和本地恢复码已更新");
        }
        return saved;
    }

    synchronized boolean isLocalAuthBlocked() {
        return prefs.getLong("local_auth_blocked_until", 0L) > System.currentTimeMillis();
    }

    synchronized void recordLocalAuthFailure() {
        long now = System.currentTimeMillis();
        long blockedUntil = prefs.getLong("local_auth_blocked_until", 0L);
        int count = blockedUntil > 0 && blockedUntil <= now ? 0 : prefs.getInt("local_auth_failures", 0);
        count++;
        SharedPreferences.Editor editor = prefs.edit();
        if (count >= 5) {
            editor.putInt("local_auth_failures", 0)
                    .putLong("local_auth_blocked_until", now + 30000);
        } else {
            editor.putInt("local_auth_failures", count);
        }
        editor.commit();
    }

    synchronized void clearLocalAuthFailures() {
        prefs.edit().remove("local_auth_failures").remove("local_auth_blocked_until").apply();
    }

    synchronized String getPendingRecoveryCode() {
        return prefs.getString("pending_recovery", "");
    }

    synchronized void acknowledgeRecoveryCode() {
        prefs.edit().remove("pending_recovery").commit();
        addLog("家长已确认保存恢复码");
    }

    String getDeviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "电视设备" : Build.MODEL;
        return manufacturer.isEmpty() ? model : manufacturer + " " + model;
    }

    synchronized Set<String> getEnabledApps() {
        return new HashSet<>(prefs.getStringSet("enabled_apps", Collections.emptySet()));
    }

    synchronized void setAppEnabled(String packageName, boolean enabled) {
        if (packageName == null || !packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException("应用包名不正确");
        }
        Set<String> apps = getEnabledApps();
        if (enabled) {
            apps.add(packageName);
        } else {
            apps.remove(packageName);
        }
        prefs.edit().putStringSet("enabled_apps", apps).commit();
        addLog((enabled ? "开始计时：" : "停止计时：") + packageName);
    }

    synchronized void addLog(String message) {
        try {
            String timestamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            JSONArray logs = new JSONArray(prefs.getString("logs", "[]"));
            JSONArray updated = new JSONArray();
            updated.put(timestamp + "  " + message);
            int keep = Math.min(logs.length(), 99);
            for (int i = 0; i < keep; i++) {
                updated.put(logs.getString(i));
            }
            prefs.edit().putString("logs", updated.toString()).apply();
        } catch (JSONException ignored) {
            prefs.edit().putString("logs", "[]").apply();
        }
    }

    synchronized List<String> getLogs() {
        List<String> result = new ArrayList<>();
        try {
            JSONArray logs = new JSONArray(prefs.getString("logs", "[]"));
            for (int i = 0; i < logs.length(); i++) {
                result.add(logs.getString(i));
            }
        } catch (JSONException ignored) {
            return result;
        }
        return result;
    }

    static final class Policy {
        int initialWatchMinutes;
        int rewardMinutes;
        int retryDelayMinutes;
        int dailyLimitMinutes;
        int breakIntervalMinutes;
        int breakDurationMinutes;
        String quietStart;
        String quietEnd;

        JSONObject toJson() {
            try {
                return new JSONObject()
                        .put("initialWatchMinutes", initialWatchMinutes)
                        .put("rewardMinutes", rewardMinutes)
                        .put("retryDelayMinutes", retryDelayMinutes)
                        .put("dailyLimitMinutes", dailyLimitMinutes)
                        .put("breakIntervalMinutes", breakIntervalMinutes)
                        .put("breakDurationMinutes", breakDurationMinutes)
                        .put("quietStart", quietStart)
                        .put("quietEnd", quietEnd);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static final class RuntimeState {
        String state;
        int remainingWatchSeconds;
        int cooldownRemainingSeconds;
        String dailyDate;
        long dailyUsedSeconds;
        long sinceBreakSeconds;
        String currentQuestionId;
        String lastQuestionId;
        String lastVideoPackage;
        long lastWallClockMs;
        long lastElapsedMs;
        int bootCount;
        String watchKind;
        boolean parentOverride;
    }
}
