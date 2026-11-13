package com.tvlearning.system;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;

final class PolicyEngine {
    static final String ACTION_STATE_CHANGED = "com.tvlearning.system.STATE_CHANGED";
    private static PolicyEngine instance;

    private final Context context;
    private final PolicyStore store;
    private final QuestionBank questions;
    private int ticksSinceDurableSave;
    private String allowedHomePackage = "";

    static synchronized PolicyEngine get(Context context) {
        if (instance == null) {
            instance = new PolicyEngine(context.getApplicationContext());
        }
        return instance;
    }

    private PolicyEngine(Context context) {
        this.context = context;
        store = PolicyStore.get(context);
        questions = QuestionBank.get(context);
    }

    synchronized void tick(String foregroundPackage) {
        PolicyStore.Policy policy = store.getPolicy();
        PolicyStore.RuntimeState state = store.getRuntime();
        long nowWall = System.currentTimeMillis();
        long nowElapsed = SystemClock.elapsedRealtime();
        int currentBootCount = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        long wallAdvanceMs = nowWall - state.lastWallClockMs;
        long elapsedAdvanceMs = nowElapsed - state.lastElapsedMs;
        boolean definiteRestart = nowElapsed < state.lastElapsedMs;
        boolean restarted = definiteRestart;
        if (currentBootCount >= 0 && state.bootCount != Integer.MIN_VALUE) {
            definiteRestart = definiteRestart || currentBootCount != state.bootCount;
            restarted = definiteRestart;
        } else {
            long previousBootEpoch = state.lastWallClockMs - state.lastElapsedMs;
            long currentBootEpoch = nowWall - nowElapsed;
            restarted = restarted || Math.abs(currentBootEpoch - previousBootEpoch) > 5 * 60 * 1000L
                    || wallAdvanceMs - elapsedAdvanceMs > 60 * 1000L;
        }
        long deltaSeconds = restarted ? 0 : Math.max(0, elapsedAdvanceMs / 1000);
        state.bootCount = currentBootCount;
        boolean durable = false;

        if (restarted) {
            restoreAfterReboot(state, nowWall, definiteRestart);
            store.addLog(definiteRestart
                    ? "检测到设备重启，保护状态已恢复：" + state.state
                    : "检测到计时基准变化，已重新同步保护状态");
            durable = true;
        }

        if (!currentDate(state)) {
            state.dailyDate = PolicyStore.currentDate();
            state.dailyUsedSeconds = 0;
            state.sinceBreakSeconds = 0;
            if (PolicyStore.DAILY_LIMIT.equals(state.state)) {
                state.state = PolicyStore.WATCHING;
                state.remainingWatchSeconds = policy.initialWatchMinutes * 60;
                state.watchKind = PolicyStore.WATCH_INITIAL;
            }
            durable = true;
        }

        if (!store.isConfigured() || !store.isProtectionEnabled()) {
            state.state = PolicyStore.IDLE;
            state.parentOverride = false;
        } else {
            if (PolicyStore.IDLE.equals(state.state)) {
                state.state = PolicyStore.WATCHING;
                if (state.remainingWatchSeconds <= 0 || state.lastVideoPackage.isEmpty()) {
                    state.remainingWatchSeconds = policy.initialWatchMinutes * 60;
                    state.watchKind = PolicyStore.WATCH_INITIAL;
                } else if (state.watchKind.isEmpty()) {
                    state.watchKind = PolicyStore.WATCH_INITIAL;
                }
                durable = true;
            }

            boolean watchedApp = foregroundPackage != null && store.getEnabledApps().contains(foregroundPackage);
            if (watchedApp) {
                state.lastVideoPackage = foregroundPackage;
            }

            if (PolicyStore.WATCHING.equals(state.state) && !state.parentOverride
                    && (PolicyStore.WATCH_INITIAL.equals(state.watchKind)
                    || PolicyStore.WATCH_REWARD.equals(state.watchKind))) {
                int total = (PolicyStore.WATCH_REWARD.equals(state.watchKind)
                        ? policy.rewardMinutes : policy.initialWatchMinutes) * 60;
                if (state.remainingWatchSeconds > total) {
                    state.remainingWatchSeconds = total;
                    store.addLog("旧版剩余额度已按当前规则校正");
                    durable = true;
                }
            }

            switch (state.state) {
                case PolicyStore.COOLDOWN:
                    durable = advanceCooldown(state, deltaSeconds) || durable;
                    break;
                case PolicyStore.REST:
                    if (advanceCooldown(state, deltaSeconds)) {
                        state.sinceBreakSeconds = 0;
                        if (state.remainingWatchSeconds > 0) {
                            state.state = PolicyStore.WATCHING;
                        } else {
                            lockForQuestion(state, policy);
                        }
                        durable = true;
                    }
                    break;
                case PolicyStore.QUIET:
                    if (!isQuietNow(policy)) {
                        if (state.remainingWatchSeconds > 0) {
                            state.state = PolicyStore.WATCHING;
                        } else {
                            lockForQuestion(state, policy);
                        }
                        durable = true;
                    }
                    break;
                case PolicyStore.LOCKED:
                    ensureQuestion(state);
                    break;
                case PolicyStore.DAILY_LIMIT:
                    break;
                case PolicyStore.WATCHING:
                    durable = advanceWatching(state, policy, watchedApp, deltaSeconds) || durable;
                    break;
                default:
                    state.state = PolicyStore.WATCHING;
                    state.remainingWatchSeconds = policy.initialWatchMinutes * 60;
                    state.watchKind = PolicyStore.WATCH_INITIAL;
                    durable = true;
                    break;
            }
        }

        state.lastWallClockMs = nowWall;
        state.lastElapsedMs = nowElapsed;
        ticksSinceDurableSave++;
        if (durable || ticksSinceDurableSave >= 5) {
            store.saveRuntime(state, true);
            ticksSinceDurableSave = 0;
        } else {
            store.saveRuntime(state, false);
        }
        if (durable) {
            notifyChanged();
        }
    }

    private void restoreAfterReboot(PolicyStore.RuntimeState state, long nowWall, boolean countOfflineTime) {
        if (countOfflineTime && (PolicyStore.COOLDOWN.equals(state.state) || PolicyStore.REST.equals(state.state))
                && state.cooldownRemainingSeconds > 0) {
            if (nowWall >= state.lastWallClockMs) {
                PolicyStore.Policy policy = store.getPolicy();
                int originalSeconds = PolicyStore.COOLDOWN.equals(state.state)
                        ? policy.retryDelayMinutes * 60 : policy.breakDurationMinutes * 60;
                long offlineSeconds = Math.min(originalSeconds, (nowWall - state.lastWallClockMs) / 1000);
                state.cooldownRemainingSeconds = (int) Math.max(0,
                        state.cooldownRemainingSeconds - offlineSeconds);
            } else {
                store.addLog("检测到系统时间回拨，保留原有锁定状态");
            }
        }
    }

    synchronized void onScreenRestored() {
        PolicyStore.RuntimeState state = store.getRuntime();
        long nowWall = System.currentTimeMillis();
        restoreAfterReboot(state, nowWall, true);
        state.lastWallClockMs = nowWall;
        state.lastElapsedMs = SystemClock.elapsedRealtime();
        state.bootCount = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        store.saveRuntime(state, true);
        notifyChanged();
    }

    private static boolean currentDate(PolicyStore.RuntimeState state) {
        return PolicyStore.currentDate().equals(state.dailyDate);
    }

    private boolean advanceWatching(PolicyStore.RuntimeState state, PolicyStore.Policy policy,
                                    boolean watchedApp, long deltaSeconds) {
        if (isQuietNow(policy) && watchedApp && !state.parentOverride) {
            state.state = PolicyStore.QUIET;
            store.addLog("进入晚间禁用时段");
            return true;
        }
        if (!watchedApp || deltaSeconds <= 0) {
            return false;
        }
        int elapsed = (int) Math.min(deltaSeconds, 24L * 60 * 60);
        state.remainingWatchSeconds = Math.max(0, state.remainingWatchSeconds - elapsed);
        state.dailyUsedSeconds += elapsed;
        state.sinceBreakSeconds += elapsed;
        if (!state.parentOverride) {
            if (policy.dailyLimitMinutes > 0
                    && state.dailyUsedSeconds >= policy.dailyLimitMinutes * 60L) {
                state.state = PolicyStore.DAILY_LIMIT;
                state.remainingWatchSeconds = 0;
                store.addLog("达到每日观看上限");
                return true;
            }
            if (policy.breakIntervalMinutes > 0
                    && state.sinceBreakSeconds >= policy.breakIntervalMinutes * 60L) {
                startCooldown(state, PolicyStore.REST, policy.breakDurationMinutes * 60);
                store.addLog("进入强制休息");
                return true;
            }
        }
        if (state.remainingWatchSeconds <= 0) {
            state.parentOverride = false;
            lockForQuestion(state, policy);
            return true;
        }
        return false;
    }

    private boolean advanceCooldown(PolicyStore.RuntimeState state, long deltaSeconds) {
        if (deltaSeconds > 0) {
            state.cooldownRemainingSeconds = (int) Math.max(0,
                    state.cooldownRemainingSeconds - Math.min(deltaSeconds, 24L * 60 * 60));
        }
        if (state.cooldownRemainingSeconds > 0) {
            return false;
        }
        if (PolicyStore.COOLDOWN.equals(state.state)) {
            state.state = PolicyStore.LOCKED;
            state.currentQuestionId = "";
            ensureQuestion(state);
            store.addLog("冷却结束，已更换下一题");
            notifyChanged();
            return true;
        }
        return true;
    }

    private void lockForQuestion(PolicyStore.RuntimeState state, PolicyStore.Policy policy) {
        state.state = PolicyStore.LOCKED;
        state.remainingWatchSeconds = 0;
        state.cooldownRemainingSeconds = 0;
        state.watchKind = "";

        ensureQuestion(state);
        store.addLog("观看时间结束，进入答题锁定");
    }

    private void ensureQuestion(PolicyStore.RuntimeState state) {
        if (questions.getQuestion(state.currentQuestionId) == null) {
            QuestionBank.Question next = questions.chooseNext(state.lastQuestionId);
            state.currentQuestionId = next == null ? "" : next.id;
        }
    }

    synchronized AnswerResult answer(int selectedAnswer) {
        PolicyStore.RuntimeState state = store.getRuntime();
        if (!PolicyStore.LOCKED.equals(state.state)) {
            return new AnswerResult(false, "当前不是可答题状态");
        }
        String questionId = state.currentQuestionId;
        if (!questions.isCorrect(questionId, selectedAnswer)) {
            String explanation = questions.getExplanation(questionId);
            state.lastQuestionId = questionId;
            state.currentQuestionId = "";
            startCooldown(state, PolicyStore.COOLDOWN, store.getPolicy().retryDelayMinutes * 60);
            store.saveRuntime(state, true);
            store.addLog("回答错误，进入换题等待");
            notifyChanged();
            return new AnswerResult(false, explanation);
        }

        state.lastQuestionId = questionId;
        state.currentQuestionId = "";
        state.cooldownRemainingSeconds = 0;
        
        state.parentOverride = false;
        state.watchKind = PolicyStore.WATCH_REWARD;
        PolicyStore.Policy policy = store.getPolicy();
        if (isQuietNow(policy)) {
            state.state = PolicyStore.QUIET;
            state.remainingWatchSeconds = policy.rewardMinutes * 60;
        } else if (policy.dailyLimitMinutes > 0
                && state.dailyUsedSeconds >= policy.dailyLimitMinutes * 60L) {
            state.state = PolicyStore.DAILY_LIMIT;
            state.remainingWatchSeconds = 0;
        } else {
            state.state = PolicyStore.WATCHING;
            state.remainingWatchSeconds = policy.rewardMinutes * 60;
        }
        store.saveRuntime(state, true);
        store.addLog("回答正确，观看额度已更新");
        notifyChanged();
        return new AnswerResult(true, "回答正确");
    }

    synchronized void updatePolicy(JSONObject value) throws JSONException {
        PolicyStore.Policy oldPolicy = store.getPolicy();
        store.updatePolicy(value);
        PolicyStore.Policy newPolicy = store.getPolicy();
        PolicyStore.RuntimeState state = store.getRuntime();
        boolean adjusted = false;
        if (PolicyStore.WATCHING.equals(state.state) && !state.parentOverride
                && (PolicyStore.WATCH_INITIAL.equals(state.watchKind)
                || PolicyStore.WATCH_REWARD.equals(state.watchKind))) {
            int oldTotal = (PolicyStore.WATCH_REWARD.equals(state.watchKind)
                    ? oldPolicy.rewardMinutes : oldPolicy.initialWatchMinutes) * 60;
            int newTotal = (PolicyStore.WATCH_REWARD.equals(state.watchKind)
                    ? newPolicy.rewardMinutes : newPolicy.initialWatchMinutes) * 60;
            if (oldTotal != newTotal) {
                int elapsed = Math.max(0, oldTotal - state.remainingWatchSeconds);
                state.remainingWatchSeconds = Math.max(0, newTotal - elapsed);
                adjusted = true;
                if (state.remainingWatchSeconds == 0) {
                    lockForQuestion(state, newPolicy);
                }
            }
        } else if (PolicyStore.COOLDOWN.equals(state.state) || PolicyStore.REST.equals(state.state)) {
            boolean questionCooldown = PolicyStore.COOLDOWN.equals(state.state);
            int oldTotal = (questionCooldown
                    ? oldPolicy.retryDelayMinutes : oldPolicy.breakDurationMinutes) * 60;
            int newTotal = (questionCooldown
                    ? newPolicy.retryDelayMinutes : newPolicy.breakDurationMinutes) * 60;
            if (oldTotal != newTotal) {
                int elapsed = Math.max(0, oldTotal - state.cooldownRemainingSeconds);
                state.cooldownRemainingSeconds = Math.max(0, newTotal - elapsed);
                adjusted = true;
                if (advanceCooldown(state, 0) && PolicyStore.REST.equals(state.state)) {
                    state.sinceBreakSeconds = 0;
                    if (state.remainingWatchSeconds > 0) {
                        state.state = PolicyStore.WATCHING;
                    } else {
                        lockForQuestion(state, newPolicy);
                    }
                }
            }
        }
        if (adjusted) {
            store.addLog("当前周期已按新时间规则调整");
        }
        store.saveRuntime(state, true);
        notifyChanged();
    }

    synchronized void allowOriginalHome(String packageName) {
        allowedHomePackage = packageName == null ? "" : packageName;
    }

    synchronized boolean isOriginalHomeAllowed(String packageName) {
        return packageName != null && !packageName.isEmpty() && packageName.equals(allowedHomePackage);
    }

    synchronized void clearOriginalHomeAccess() {
        allowedHomePackage = "";
    }

    synchronized void control(JSONObject request) throws JSONException {
        String action = request.getString("action");
        PolicyStore.RuntimeState state = store.getRuntime();
        switch (action) {
            case "unlock": {
                int minutes = request.optInt("minutes", store.getPolicy().rewardMinutes);
                grantWatchTime(state, minutes);
                store.addLog("家长远程限时解锁 " + minutes + " 分钟");
                break;
            }
            case "grant_without_answer":
                grantWatchTime(state, store.getPolicy().rewardMinutes);
                store.addLog("家长本次免答解锁");
                break;
            case "skip_question":
                if (!PolicyStore.LOCKED.equals(state.state) && !PolicyStore.COOLDOWN.equals(state.state)) {
                    throw new IllegalArgumentException("当前没有可跳过的题目");
                }
                state.lastQuestionId = state.currentQuestionId == null || state.currentQuestionId.isEmpty()
                        ? state.lastQuestionId : state.currentQuestionId;
                state.currentQuestionId = "";
                state.cooldownRemainingSeconds = 0;
                
                state.state = PolicyStore.LOCKED;
                ensureQuestion(state);
                store.addLog("家长已跳过当前题");
                break;
            case "pause_protection":
                store.setProtectionEnabled(false);
                state.state = PolicyStore.IDLE;
                state.parentOverride = false;
                store.addLog("家长暂停了管控");
                break;
            case "resume_protection":
                clearOriginalHomeAccess();
                store.setProtectionEnabled(true);
                state.state = PolicyStore.WATCHING;
                if (state.remainingWatchSeconds <= 0) {
                    state.remainingWatchSeconds = store.getPolicy().initialWatchMinutes * 60;
                    state.watchKind = PolicyStore.WATCH_INITIAL;
                }
                store.addLog("家长恢复了管控");
                break;
            default:
                throw new IllegalArgumentException("不支持的控制命令");
        }
        store.saveRuntime(state, true);
        notifyChanged();
    }

    private void grantWatchTime(PolicyStore.RuntimeState state, int minutes) {
        if (minutes < 1 || minutes > 180) {
            throw new IllegalArgumentException("解锁时长应为 1 至 180 分钟");
        }
        if (!store.isProtectionEnabled()) {
            store.setProtectionEnabled(true);
        }
        state.state = PolicyStore.WATCHING;
        state.remainingWatchSeconds = Math.min(180 * 60,
                Math.max(0, state.remainingWatchSeconds) + minutes * 60);
        state.cooldownRemainingSeconds = 0;
        state.currentQuestionId = "";
        state.watchKind = PolicyStore.WATCH_PARENT;
        state.parentOverride = true;
    }

    static boolean verifyParentSecret(PolicyStore store, String secret) {
        String value = secret == null ? "" : secret.trim();
        if (isRecoveryCode(value)) {
            return store.verifyRecoveryCode(value);
        }
        if (value.matches("\\d{6,8}")) {
            return store.verifyPin(value);
        }
        return false;
    }

    private static boolean isRecoveryCode(String value) {
        return value.toUpperCase(java.util.Locale.US).matches("TV-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}");
    }

    boolean unlockWithParentSecret(String secret) {
        boolean recovery = isRecoveryCode(secret == null ? "" : secret.trim());
        if (!verifyParentSecret(store, secret)) {
            return false;
        }
        synchronized (this) {
            PolicyStore.RuntimeState state = store.getRuntime();
            grantWatchTime(state, store.getPolicy().rewardMinutes);
            store.saveRuntime(state, true);
            store.addLog(recovery ? "家长使用本地恢复码解锁" : "家长使用本地 PIN 解锁");
            notifyChanged();
            return true;
        }
    }

    QuestionBank.Question getCurrentQuestion() {
        PolicyStore.RuntimeState state = store.getRuntime();
        return questions.getQuestion(state.currentQuestionId);
    }

    String getLastExplanation() {
        return questions.getExplanation(store.getRuntime().lastQuestionId);
    }

    boolean isQuietNow(PolicyStore.Policy policy) {
        if (policy.quietStart.equals(policy.quietEnd)) {
            return false;
        }
        int start = minutesOfDay(policy.quietStart);
        int end = minutesOfDay(policy.quietEnd);
        Calendar calendar = Calendar.getInstance();
        int now = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        if (start < end) {
            return now >= start && now < end;
        }
        return now >= start || now < end;
    }

    private static int minutesOfDay(String value) {
        return Integer.parseInt(value.substring(0, 2)) * 60 + Integer.parseInt(value.substring(3, 5));
    }

    private static void startCooldown(PolicyStore.RuntimeState state, String newState, int seconds) {
        state.state = newState;
        state.cooldownRemainingSeconds = Math.max(0, seconds);
        state.currentQuestionId = "";
    }

    static boolean isLockedState(String state) {
        return PolicyStore.LOCKED.equals(state)
                || PolicyStore.COOLDOWN.equals(state)
                || PolicyStore.REST.equals(state)
                || PolicyStore.QUIET.equals(state)
                || PolicyStore.DAILY_LIMIT.equals(state);
    }

    private void notifyChanged() {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    static final class AnswerResult {
        final boolean correct;
        final String message;

        AnswerResult(boolean correct, String message) {
            this.correct = correct;
            this.message = message;
        }
    }
}
