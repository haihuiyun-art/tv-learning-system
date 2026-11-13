package com.tvlearning.system;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AppRepository apps;
    private DeviceControlManager deviceControl;
    private TextView status;
    private TextView connection;
    private TextView pairing;
    private TextView recovery;
    private TextView protectionBadge;
    private TextView heroTitle;
    private TextView timeValue;
    private TextView timeCaption;
    private Button acknowledgeRecovery;
    private Button returnToLock;
    private boolean recoveryRevealed;
    private String revealedRecoveryCode = "";
    private LinearLayout appList;
    private String renderedAppsKey;
    private boolean renderedDark;
    private Button usageButton;
    private Button firstAppButton;
    private final ExecutorService authExecutor = Executors.newSingleThreadExecutor();

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            updateAppList();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        renderedDark = Ui.isDark(this);
        apps = new AppRepository(this);
        deviceControl = DeviceControlManager.get(this);
        WatchPolicyService.start(this);
        buildInterface();
        deviceControl.clearTemporaryAccess();
        deviceControl.syncProtection();
    }

    private void buildInterface() {
        boolean narrow = getResources().getDisplayMetrics().widthPixels < Ui.dp(this, 620);
        int padding = Ui.dp(this, 42);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.backgroundColor(this));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFocusable(false);
        LinearLayout content = Ui.vertical(this);
        content.setPadding(padding, Ui.dp(this, 32), padding, Ui.dp(this, narrow ? 190 : 130));
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = Ui.horizontal(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.brandMark(this));
        TextView title = Ui.title(this, "电视学习系统");
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = Ui.dp(this, 14);
        header.addView(title, titleParams);
        protectionBadge = Ui.badge(this, "", Ui.successColor(this));
        header.addView(protectionBadge);
        TextView network = Ui.badge(this, "局域网家长控制", Ui.accentColor(this));
        LinearLayout.LayoutParams networkParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        networkParams.leftMargin = Ui.dp(this, 10);
        header.addView(network, networkParams);
        Ui.add(content, header, 0);

        LinearLayout hero = Ui.horizontal(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setMinimumHeight(Ui.dp(this, 132));
        hero.setPadding(Ui.dp(this, 26), Ui.dp(this, 20), Ui.dp(this, 26), Ui.dp(this, 20));
        GradientStyles.panel(hero, this);
        hero.setElevation(Ui.dp(this, 2));
        LinearLayout copy = Ui.vertical(this);
        Ui.add(copy, Ui.text(this, "今天也要快乐学习", 12, Ui.accentColor(this)), 0);
        heroTitle = Ui.text(this, "", 26, Ui.textColor(this));
        heroTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        Ui.add(copy, heroTitle, 6);
        Ui.add(copy, Ui.text(this, "计时、答题和家长管理都在这台设备上完成", 14,
                Ui.mutedColor(this)), 5);
        hero.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout time = Ui.vertical(this);
        timeCaption = Ui.text(this, "", 12, Ui.mutedColor(this));
        timeCaption.setGravity(Gravity.END);
        Ui.add(time, timeCaption, 0);
        timeValue = Ui.text(this, "", 44, Ui.accentColor(this));
        timeValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        timeValue.setGravity(Gravity.END);
        Ui.add(time, timeValue, 4);
        TextView timeNote = Ui.text(this, "本地规则 · 家长可管理", 12, Ui.mutedColor(this));
        timeNote.setGravity(Gravity.END);
        Ui.add(time, timeNote, 4);
        hero.addView(time);
        Ui.add(content, hero, 22);

        pairing = Ui.text(this, "", 15, Ui.warningColor(this));
        pairing.setTextIsSelectable(false);
        Ui.add(content, pairing, 10);
        recovery = Ui.text(this, "", 16, Ui.textColor(this));
        recovery.setTextIsSelectable(false);
        Ui.add(content, recovery, 10);
        acknowledgeRecovery = Ui.button(this, "家长验证后查看恢复码");
        acknowledgeRecovery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!recoveryRevealed) {
                    runAfterParentAuth(new Runnable() {
                        @Override
                        public void run() {
                            recoveryRevealed = true;
                            revealedRecoveryCode = PolicyStore.get(HomeActivity.this).getPendingRecoveryCode();
                            updateStatus();
                        }
                    });
                } else {
                    PolicyStore.get(HomeActivity.this).acknowledgeRecoveryCode();
                    revealedRecoveryCode = "";
                    recoveryRevealed = false;
                    updateStatus();
                }
            }
        });
        Ui.add(content, acknowledgeRecovery, 10);

        LinearLayout sectionHeading = Ui.horizontal(this);
        sectionHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView sectionTitle = Ui.text(this, "选择要观看的应用", 20, Ui.textColor(this));
        sectionTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sectionHeading.addView(sectionTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sectionHeading.addView(Ui.text(this, "由家长管理 · 可在手机网页中增减应用",
                13, Ui.mutedColor(this)));
        Ui.add(content, sectionHeading, 24);
        appList = Ui.vertical(this);
        Ui.add(content, appList, 14);
        updateAppList();

        LinearLayout footer = Ui.vertical(this);
        footer.setPadding(padding, Ui.dp(this, 10), padding, Ui.dp(this, 18));
        footer.setBackgroundColor(Ui.backgroundColor(this));
        LinearLayout actions = Ui.horizontal(this);
        returnToLock = Ui.button(this, "继续答题");
        returnToLock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showLock();
            }
        });
        addAction(actions, returnToLock);
        usageButton = Ui.button(this, "观看统计权限");
        usageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                runAfterParentAuth(new Runnable() {
                    @Override
                    public void run() {
                        if (apps.hasUsageAccess()) {
                            Toast.makeText(HomeActivity.this, "观看统计已授权，无需重复设置", Toast.LENGTH_LONG).show();
                        } else {
                            openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                        }
                    }
                });
            }
        });
        addAction(actions, usageButton);
        Button home = Ui.button(this, "默认桌面设置");
        home.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                runAfterParentAuth(new Runnable() {
                    @Override
                    public void run() {
                        if (isDefaultHome()) {
                            Toast.makeText(HomeActivity.this, "当前已是默认桌面，按主页键会回到这里", Toast.LENGTH_LONG).show();
                            return;
                        }
                        openSettings(Settings.ACTION_HOME_SETTINGS);
                    }
                });
            }
        });
        addAction(actions, home);
        Button original = Ui.button(this, "家长验证后打开原系统桌面");
        original.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                runAfterParentAuth(new Runnable() {
                    @Override
                    public void run() {
                        openOriginalHome();
                    }
                });
            }
        });
        addAction(actions, original);
        Button theme = Ui.themeButton(this);
        Ui.add(footer, actions, 0);
        if (narrow) {
            actions.removeView(original);
            LinearLayout secondaryActions = Ui.horizontal(this);
            addAction(secondaryActions, original);
            addAction(secondaryActions, theme);
            Ui.add(footer, secondaryActions, 8);
        } else {
            addAction(actions, theme);
        }

        LinearLayout hints = Ui.horizontal(this);
        hints.setGravity(Gravity.CENTER_VERTICAL);
        hints.addView(Ui.text(this, "方向键  选择应用　　确认  进入", 12, Ui.mutedColor(this)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        connection = Ui.text(this, "", 12, Ui.mutedColor(this));
        connection.setTextIsSelectable(false);
        connection.setGravity(Gravity.END);
        hints.addView(connection);
        Ui.add(footer, hints, 12);
        status = Ui.text(this, "", 11, Ui.warningColor(this));
        Ui.add(footer, status, 5);
        FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(footer, footerParams);
        setContentView(root);
        if (firstAppButton != null) {
            firstAppButton.requestFocus();
        } else {
            usageButton.requestFocus();
        }
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, 0);
            }
        });
    }

    private void addAction(LinearLayout actions, Button button) {
        button.setTextSize(12);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Ui.dp(this, 8);
        actions.addView(button, params);
    }

    private void updateAppList() {
        List<AppRepository.AppItem> items = apps.getLaunchableApps();
        List<AppRepository.AppItem> enabledItems = new ArrayList<>();
        StringBuilder key = new StringBuilder();
        for (AppRepository.AppItem item : items) {
            if (item.enabled) {
                enabledItems.add(item);
                key.append(item.packageName).append('|').append(item.label).append('\n');
            }
        }
        String currentKey = key.toString();
        if (currentKey.equals(renderedAppsKey)) {
            return;
        }
        String focusedPackage = null;
        View focused = getCurrentFocus();
        if (focused != null && focused.getTag() instanceof String) {
            focusedPackage = (String) focused.getTag();
        }

        appList.removeAllViews();
        firstAppButton = null;
        Button restoreFocus = null;
        int availableWidth = appList.getWidth();
        if (availableWidth <= 0) {
            availableWidth = getResources().getDisplayMetrics().widthPixels - 2 * Ui.dp(this, 42);
        }
        int columns = columnsFor(enabledItems.size(), availableWidth);
        int gap = Ui.dp(this, 16);
        int cardWidth = Math.min(Ui.dp(this, 380),
                (availableWidth - (columns - 1) * gap) / columns);
        cardWidth = Math.max(Math.min(availableWidth, Ui.dp(this, 240)), cardWidth);
        int rowWidth = cardWidth * columns + (columns - 1) * gap;
        LinearLayout row = null;
        for (int i = 0; i < enabledItems.size(); i++) {
            final AppRepository.AppItem item = enabledItems.get(i);
            int column = i % columns;
            if (column == 0) {
                row = Ui.horizontal(this);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        rowWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowParams.topMargin = Ui.dp(this, 14);
                appList.addView(row, rowParams);
            }
            final Button app = Ui.button(this, "");
            app.setTag(item.packageName);
            styleAppButton(app, item.label, false);
            app.setTextSize(13);
            app.setMaxLines(2);
            app.setEllipsize(TextUtils.TruncateAt.END);
            app.setGravity(Gravity.CENTER);
            app.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
            app.setElevation(Ui.dp(this, 2));
            app.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    styleAppButton(app, item.label, hasFocus);
                }
            });
            Drawable icon = item.icon;
            if (icon != null) {
                icon = Ui.roundedIcon(this, icon, 16);
                int iconSize = Ui.dp(this, 80);
                icon.setBounds(0, 0, iconSize, iconSize);
                app.setCompoundDrawables(null, icon, null, null);
                app.setCompoundDrawablePadding(Ui.dp(this, 10));
            }
            app.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (PolicyEngine.isLockedState(PolicyStore.get(HomeActivity.this).getRuntime().state)) {
                        showLock();
                        return;
                    }
                    Intent intent = apps.getLaunchIntent(item.packageName);
                    if (intent != null) {
                        try {
                            startActivity(intent);
                        } catch (RuntimeException e) {
                            Toast.makeText(HomeActivity.this, "应用暂时无法打开", Toast.LENGTH_LONG).show();
                        }
                    }
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    cardWidth, Ui.dp(this, 176));
            if (column > 0) {
                params.leftMargin = gap;
            }
            row.addView(app, params);
            if (firstAppButton == null) {
                firstAppButton = app;
            }
            if (item.packageName.equals(focusedPackage)) {
                restoreFocus = app;
            }
            if (column == columns - 1 || i == enabledItems.size() - 1) {
                for (int empty = column + 1; empty < columns; empty++) {
                    Space space = new Space(this);
                    LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(cardWidth, 1);
                    emptyParams.leftMargin = gap;
                    row.addView(space, emptyParams);
                }
            }
        }
        if (enabledItems.isEmpty()) {
            Ui.add(appList, Ui.text(this, "请家长在手机网页中勾选需要计时的视频应用", 18,
                    Ui.mutedColor(this)), 8);
        }
        renderedAppsKey = currentKey;
        if (restoreFocus != null) {
            restoreFocus.requestFocus();
        } else if (focusedPackage != null) {
            if (firstAppButton != null) {
                firstAppButton.requestFocus();
            } else {
                usageButton.requestFocus();
            }
        }
    }

    private void styleAppButton(Button button, String label, boolean focused) {
        String detail = focused ? "已选中 · 观看计时已开启" : "观看计时已开启";
        SpannableString text = new SpannableString(label + "\n" + detail);
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(1.35f), 0, label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        button.setText(text);
    }

    private int columnsFor(int count, int availableWidth) {
        if (count <= 1) {
            return 1;
        }
        int maxByWidth = Math.max(1, availableWidth / Ui.dp(this, 240));
        if (count == 4) {
            return Math.min(2, maxByWidth);
        }
        return Math.min(count, Math.min(3, maxByWidth));
    }

    private void updateStatus() {
        PolicyStore store = PolicyStore.get(this);
        PolicyStore.RuntimeState state = store.getRuntime();
        ProtectionHealth.Snapshot health = WatchPolicyService.getHealth(this);
        protectionBadge.setText(!store.isConfigured() ? "待初始化"
                : !store.isProtectionEnabled() ? "已暂停"
                : health.isHealthy() ? "● 保护中" : "● 需检查");
        heroTitle.setText(heroText(state.state));
        timeCaption.setText(timeCaption(state.state));
        if (PolicyStore.WATCHING.equals(state.state)) {
            timeValue.setText(formatSeconds(state.remainingWatchSeconds));
        } else if (PolicyStore.COOLDOWN.equals(state.state) || PolicyStore.REST.equals(state.state)) {
            timeValue.setText(formatSeconds(state.cooldownRemainingSeconds));
        } else if (PolicyStore.IDLE.equals(state.state)) {
            timeValue.setText("--:--");
        } else {
            timeValue.setText("00:00");
        }
        status.setText(String.format(Locale.getDefault(),
                "Android %s（API %d） · 管控模式：%s · 当前状态：%s\n%s",
                android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT,
                health.controlMode, stateText(state.state), health.displayText()));
        status.setVisibility(health.isHealthy() ? View.GONE : View.VISIBLE);
        connection.setText("手机网页 " + WatchPolicyService.getAddress());
        pairing.setVisibility(store.isConfigured() ? View.GONE : View.VISIBLE);
        if (!store.isConfigured()) {
            pairing.setText("一次性配对码：" + store.getPairingCode()
                    + "\n配对码 15 分钟内有效，请家长立即完成设置并创建 6～8 位数字 PIN。");
        }
        returnToLock.setVisibility(store.isProtectionEnabled()
                && PolicyEngine.isLockedState(state.state) ? View.VISIBLE : View.GONE);
        returnToLock.setText(PolicyStore.COOLDOWN.equals(state.state) ? "查看等待倒计时" : "继续答题");
        String pending = store.getPendingRecoveryCode();
        if (!pending.equals(revealedRecoveryCode)) {
            recoveryRevealed = false;
        }
        if (pending.isEmpty()) {
            recovery.setVisibility(View.GONE);
            acknowledgeRecovery.setVisibility(View.GONE);
        } else {
            recovery.setVisibility(View.VISIBLE);
            acknowledgeRecovery.setVisibility(View.VISIBLE);
            if (recoveryRevealed) {
                recovery.setText("请立即抄写并离线保存家长恢复码：\n" + pending
                        + "\n此码用于网页不可用时的本地解锁，不要交给孩子。");
                acknowledgeRecovery.setText("我已离线保存恢复码");
            } else {
                recovery.setText("恢复码已生成，验证家长身份后才显示。");
                acknowledgeRecovery.setText("家长验证后查看恢复码");
            }
        }
    }

    private static String heroText(String state) {
        if (PolicyStore.WATCHING.equals(state)) {
            return "先看一会儿，再答一道题";
        }
        if (PolicyStore.LOCKED.equals(state)) {
            return "还有一道题，完成后继续观看";
        }
        if (PolicyStore.COOLDOWN.equals(state)) {
            return "看看解析，再挑战下一题";
        }
        if (PolicyStore.REST.equals(state)) {
            return "休息一下，放松眼睛";
        }
        if (PolicyStore.QUIET.equals(state)) {
            return "当前为晚间禁用时间";
        }
        if (PolicyStore.DAILY_LIMIT.equals(state)) {
            return "今日观看时长已用完";
        }
        return "学习主页已就绪";
    }

    private static String timeCaption(String state) {
        if (PolicyStore.WATCHING.equals(state)) {
            return "本段剩余观看时间";
        }
        if (PolicyStore.COOLDOWN.equals(state)) {
            return "距离下一题";
        }
        if (PolicyStore.REST.equals(state)) {
            return "剩余休息时间";
        }
        return "当前状态：" + stateText(state);
    }

    private static String stateText(String state) {
        if (PolicyStore.WATCHING.equals(state)) {
            return "观看中";
        }
        if (PolicyStore.LOCKED.equals(state)) {
            return "待答题";
        }
        if (PolicyStore.COOLDOWN.equals(state)) {
            return "答错冷却";
        }
        if (PolicyStore.REST.equals(state)) {
            return "强制休息";
        }
        if (PolicyStore.QUIET.equals(state)) {
            return "晚间禁用";
        }
        if (PolicyStore.DAILY_LIMIT.equals(state)) {
            return "达到上限";
        }
        return "空闲";
    }

    private static String formatSeconds(int seconds) {
        int minutes = Math.max(0, seconds) / 60;
        int remaining = Math.max(0, seconds) % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, remaining);
    }

    private boolean isDefaultHome() {
        return apps.isDefaultHome();
    }

    private void showLock() {
        Intent intent = new Intent(this, LockActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void openOriginalHome() {
        String failure = new SystemActions(this).openOriginalHome(false);
        if (failure != null) {
            Toast.makeText(this, failure, Toast.LENGTH_LONG).show();
        }
    }

    private void openTemporaryExternal(Intent intent, String errorMessage) {
        if (!new SystemActions(this).openTemporaryExternal(intent)) {
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
        }
    }

    private void runAfterParentAuth(final Runnable action) {
        final PolicyStore store = PolicyStore.get(this);
        if (!store.isConfigured()) {
            action.run();
            return;
        }
        final EditText secret = new EditText(this);
        secret.setHint("家长 PIN 或恢复码");
        secret.setHintTextColor(Ui.mutedColor(this));
        secret.setTextColor(Ui.textColor(this));
        secret.setSingleLine(true);
        secret.setTextSize(20);
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setBackground(Ui.inputBackground(this));
        int padding = Ui.dp(this, 20);
        secret.setPadding(padding, padding, padding, padding);
        new AlertDialog.Builder(this, Ui.dialogTheme(this))
                .setTitle("家长身份验证")
                .setView(secret)
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) -> {
                    final String value = secret.getText().toString().trim();
                    if (store.isLocalAuthBlocked()) {
                        Toast.makeText(this, "尝试次数过多，请稍后再试", Toast.LENGTH_LONG).show();
                        return;
                    }
                    authExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            final boolean success = PolicyEngine.verifyParentSecret(store, value);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (isDestroyed()) {
                                        return;
                                    }
                                    if (success) {
                                        store.clearLocalAuthFailures();
                                        store.addLog("家长验证后进入系统设置或原桌面");
                                        action.run();
                                    } else {
                                        store.recordLocalAuthFailure();
                                        Toast.makeText(HomeActivity.this, "家长 PIN 或恢复码不正确", Toast.LENGTH_LONG).show();
                                    }
                                }
                            });
                        }
                    });
                })
                .show();
    }

    private void openSettings(String action) {
        openTemporaryExternal(new Intent(action), "此固件没有提供所需设置入口");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (renderedDark != Ui.isDark(this)) {
            recreate();
            return;
        }
        deviceControl.clearTemporaryAccess();
        PolicyEngine.get(this).clearOriginalHomeAccess();
        deviceControl.syncProtection();
        WatchPolicyService.start(this);
        updateAppList();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override
    public void onBackPressed() {
        if (PolicyEngine.isLockedState(PolicyStore.get(this).getRuntime().state)) {
            showLock();
        } else if (!isDefaultHome()) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        authExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private static final class GradientStyles {
        static void panel(View view, Context context) {
            android.graphics.drawable.GradientDrawable background = Ui.panel(context,
                    Ui.panelColor(context), 22);
            background.setStroke(Ui.dp(context, 1), Ui.borderColor(context));
            view.setBackground(background);
        }
    }
}
