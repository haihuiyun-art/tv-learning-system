package com.tvlearning.system;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LockActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PolicyEngine engine;
    private PolicyStore store;
    private DeviceControlManager deviceControl;
    private LinearLayout content;
    private LinearLayout main;
    private String renderedKey = "";
    private boolean renderedDark;
    private TextView countdown;
    private CountdownRingView countdownRing;
    private TextView footerLeft;
    private TextView footerRight;
    private boolean authInFlight;
    private boolean answerInFlight;
    private Button firstAnswerOption;
    private Button lastAnswerOption;
    private Button parentUnlockButton;
    private final ExecutorService authExecutor = Executors.newSingleThreadExecutor();

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render(false);
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        renderedDark = Ui.isDark(this);
        engine = PolicyEngine.get(this);
        store = PolicyStore.get(this);
        deviceControl = DeviceControlManager.get(this);
        WatchPolicyService.start(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.backgroundColor(this));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setFocusable(false);
        content = Ui.vertical(this);
        int padding = Ui.dp(this, 42);
        content.setPadding(padding, Ui.dp(this, 28), padding, Ui.dp(this, 100));
        scroll.addView(content);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout footer = Ui.horizontal(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(padding, Ui.dp(this, 8), padding, Ui.dp(this, 18));
        footer.setBackgroundColor(Ui.backgroundColor(this));
        footerLeft = Ui.text(this, "", 12, Ui.mutedColor(this));
        footer.addView(footerLeft, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        footerRight = Ui.text(this, "", 12, Ui.mutedColor(this));
        footerRight.setGravity(Gravity.END);
        footer.addView(footerRight);
        FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        root.addView(footer, footerParams);
        setContentView(root);
        render(true);
    }

    private void render(boolean force) {
        PolicyStore.RuntimeState state = store.getRuntime();
        if (!PolicyEngine.isLockedState(state.state)) {
            leaveLockTask();
            finish();
            return;
        }
        String key = state.state + "|" + state.currentQuestionId + "|" + state.lastQuestionId;
        if (force || !key.equals(renderedKey)) {
            renderedKey = key;
            rebuild(state);
        }
        if (countdown != null) {
            countdown.setText(formatSeconds(state.cooldownRemainingSeconds));
        }
        if (countdownRing != null) {
            countdownRing.setSeconds(state.cooldownRemainingSeconds);
        }
    }

    private void rebuild(PolicyStore.RuntimeState state) {
        content.removeAllViews();
        countdown = null;
        countdownRing = null;
        firstAnswerOption = null;
        lastAnswerOption = null;
        parentUnlockButton = null;
        main = Ui.vertical(this);
        int inset = getResources().getDisplayMetrics().widthPixels >= Ui.dp(this, 700)
                ? Ui.dp(this, 86) : Ui.dp(this, 16);
        LinearLayout.LayoutParams mainParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mainParams.leftMargin = inset;
        mainParams.rightMargin = inset;
        mainParams.topMargin = Ui.dp(this, 24);
        switch (state.state) {
            case PolicyStore.LOCKED:
                buildHeader("答题解锁", "答对后恢复观看");
                buildQuestion();
                break;
            case PolicyStore.COOLDOWN:
                buildHeader("答题解锁", "等待后换下一题");
                buildCooldown();
                break;
            case PolicyStore.REST:
                buildHeader("请休息一会儿", "保护仍在运行");
                addTag(main, "保护眼睛", Ui.accentColor(this));
                buildTimerCard("离开屏幕，活动一下", "休息一下，放松眼睛。休息结束后继续原来的规则。",
                        "", "剩余休息时间");
                break;
            case PolicyStore.QUIET: {
                buildHeader("当前为晚间禁用时间", "保护仍在运行");
                PolicyStore.Policy policy = store.getPolicy();
                buildNotice("家长设置的禁用时段：" + policy.quietStart + " 至 " + policy.quietEnd);
                break;
            }
            case PolicyStore.DAILY_LIMIT:
                buildHeader("今日观看时长已用完", "保护仍在运行");
                buildNotice("可以明天再看；如确有需要，请由家长操作。");
                break;
            default:
                break;
        }
        content.addView(main, mainParams);
        buildParentRecovery();
        footerLeft.setText(PolicyStore.COOLDOWN.equals(state.state)
                ? "可以离开屏幕，活动一下并放松眼睛"
                : PolicyStore.LOCKED.equals(state.state)
                        ? "方向键  选择答案　　确认  提交" : "家长可立即解锁或管理当前规则");
        footerRight.setText("Home  回学习主页　　返回  "
                + (PolicyStore.COOLDOWN.equals(state.state) ? "查看等待页"
                        : PolicyStore.LOCKED.equals(state.state) ? "继续答题" : "查看当前状态"));
        focusInitialControl();
    }

    private void buildHeader(String title, String statusText) {
        LinearLayout header = Ui.horizontal(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.brandMark(this));
        TextView titleView = Ui.title(this, title);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = Ui.dp(this, 14);
        header.addView(titleView, titleParams);
        header.addView(Ui.badge(this, statusText, Ui.accentColor(this)));
        TextView protection = Ui.badge(this, "保护中", Ui.successColor(this));
        LinearLayout.LayoutParams protectionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        protectionParams.leftMargin = Ui.dp(this, 10);
        header.addView(protection, protectionParams);
        Ui.add(content, header, 0);
    }

    private void addTag(LinearLayout parent, String text, int color) {
        TextView tag = Ui.badge(parent.getContext(), text, color);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Ui.dp(parent.getContext(), 8);
        parent.addView(tag, params);
    }

    private void buildNotice(String text) {
        TextView notice = Ui.text(this, text, 19, Ui.textColor(this));
        notice.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
        notice.setBackground(Ui.panel(this, Ui.panelColor(this), 20));
        Ui.add(main, notice, 12);
    }

    private void buildCooldown() {
        LinearLayout tags = Ui.horizontal(this);
        addTag(tags, "回答错误", Ui.mutedColor(this));
        addTag(tags, "查看解析 · 再试一次", Ui.accentColor(this));
        Ui.add(main, tags, 0);
        buildTimerCard("别着急，先理解这一道题",
                "等待时间不会减少观看额度。冷静下来，下一题仍然有机会。",
                engine.getLastExplanation(), "距离下一题");
    }

    private void buildTimerCard(String title, String subtitle, String explanation, String caption) {
        LinearLayout card = Ui.horizontal(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(Ui.dp(this, 280));
        card.setPadding(Ui.dp(this, 28), Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24));
        android.graphics.drawable.GradientDrawable background = Ui.panel(this, Ui.panelColor(this), 22);
        background.setStroke(Ui.dp(this, 1), Ui.borderColor(this));
        card.setBackground(background);
        Ui.add(main, card, 20);

        FrameLayout timer = new FrameLayout(this);
        int timerSize = Ui.dp(this, 170);
        int total = PolicyStore.COOLDOWN.equals(store.getRuntime().state)
                ? store.getPolicy().retryDelayMinutes * 60 : store.getPolicy().breakDurationMinutes * 60;
        countdownRing = new CountdownRingView(this, total);
        timer.addView(countdownRing, new FrameLayout.LayoutParams(timerSize, timerSize));
        LinearLayout timerText = Ui.vertical(this);
        timerText.setGravity(Gravity.CENTER);
        countdown = Ui.text(this, "", 40, Ui.warningColor(this));
        countdown.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        countdown.setGravity(Gravity.CENTER);
        Ui.add(timerText, countdown, 0);
        TextView timerCaption = Ui.text(this, caption, 12, Ui.mutedColor(this));
        timerCaption.setGravity(Gravity.CENTER);
        Ui.add(timerText, timerCaption, 8);
        FrameLayout.LayoutParams timerTextParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        timer.addView(timerText, timerTextParams);
        card.addView(timer, new LinearLayout.LayoutParams(timerSize, timerSize));

        LinearLayout copy = Ui.vertical(this);
        TextView heading = Ui.text(this, title, 24, Ui.textColor(this));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        Ui.add(copy, heading, 0);
        Ui.add(copy, Ui.text(this, subtitle, 14, Ui.mutedColor(this)), 10);
        if (explanation != null && !explanation.isEmpty()) {
            TextView detail = Ui.text(this, "本题解析\n" + explanation, 14, Ui.textColor(this));
            detail.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14));
            detail.setBackground(Ui.panel(this, Ui.backgroundColor(this), 10));
            Ui.add(copy, detail, 16);
        }
        Ui.add(copy, Ui.text(this, "结束后会自动更换题目或恢复原规则，请耐心等待。",
                12, Ui.mutedColor(this)), 14);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        copyParams.leftMargin = Ui.dp(this, 28);
        card.addView(copy, copyParams);
    }

    private void focusInitialControl() {
        View target = null;
        if (PolicyStore.LOCKED.equals(store.getRuntime().state)
                && firstAnswerOption != null && firstAnswerOption.isEnabled()) {
            target = firstAnswerOption;
        } else if (parentUnlockButton != null && parentUnlockButton.isEnabled()) {
            target = parentUnlockButton;
        }
        if (target == null) {
            return;
        }
        final View focusTarget = target;
        target.post(new Runnable() {
            @Override
            public void run() {
                View focused = getCurrentFocus();
                if (focusTarget.isAttachedToWindow()
                        && (focused == null || focused instanceof EditText)) {
                    focusTarget.requestFocus();
                }
            }
        });
    }

    private void buildQuestion() {
        answerInFlight = false;
        final QuestionBank.Question question = engine.getCurrentQuestion();
        if (question == null) {
            buildNotice("当前题库不可用，请由家长在手机网页中导入题库。");
            return;
        }
        LinearLayout tags = Ui.horizontal(this);
        addTag(tags, question.grade + " · " + question.subject, Ui.accentColor(this));
        addTag(tags, question.topic, Ui.mutedColor(this));
        addTag(tags, question.difficulty, Ui.mutedColor(this));
        Ui.add(main, tags, 0);
        Ui.add(main, Ui.text(this, "请选择正确答案", 19, Ui.mutedColor(this)), 16);
        TextView stem = Ui.text(this, question.stem, 32, Ui.textColor(this));
        stem.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        stem.setTextIsSelectable(false);
        Ui.add(main, stem, 8);

        int optionCount = question.options.size();
        int availableWidth = main.getWidth();
        if (availableWidth <= 0) {
            int inset = getResources().getDisplayMetrics().widthPixels >= Ui.dp(this, 700)
                    ? Ui.dp(this, 86) : Ui.dp(this, 16);
            availableWidth = getResources().getDisplayMetrics().widthPixels
                    - 2 * Ui.dp(this, 42) - 2 * inset;
        }
        int columns = optionCount > 1 && availableWidth >= Ui.dp(this, 500) ? 2 : 1;
        LinearLayout row = null;
        for (int i = 0; i < optionCount; i++) {
            final int answer = i;
            int column = i % columns;
            if (column == 0) {
                row = Ui.horizontal(this);
                row.setWeightSum(columns);
                Ui.add(main, row, 12);
            }
            final Button option = Ui.button(this, "");
            option.setId(View.generateViewId());
            styleAnswerOption(option, answer, question.options.get(answer), false);
            option.setTextSize(20);
            option.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            option.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            option.setMaxLines(3);
            option.setEllipsize(TextUtils.TruncateAt.END);
            option.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
            option.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean hasFocus) {
                    styleAnswerOption(option, answer, question.options.get(answer), hasFocus);
                }
            });
            option.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (answerInFlight || !PolicyStore.LOCKED.equals(store.getRuntime().state)) {
                        return;
                    }
                    answerInFlight = true;
                    view.requestFocus();
                    PolicyEngine.AnswerResult result = engine.answer(answer);
                    Toast.makeText(LockActivity.this,
                            result.correct ? "回答正确" : "回答错误，请查看解析",
                            Toast.LENGTH_SHORT).show();
                    if (result.correct) {
                        resumeVideoIfAllowed();
                    }
                    render(true);
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, Ui.dp(this, 74), 1f);
            if (column > 0) {
                params.leftMargin = Ui.dp(this, 14);
            }
            row.addView(option, params);
            if (firstAnswerOption == null) {
                firstAnswerOption = option;
            }
            lastAnswerOption = option;
            if (column == columns - 1 || i == optionCount - 1) {
                for (int empty = column + 1; empty < columns; empty++) {
                    Space space = new Space(this);
                    LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(0, 1, 1f);
                    emptyParams.leftMargin = Ui.dp(this, 14);
                    row.addView(space, emptyParams);
                }
            }
        }
        if (firstAnswerOption != null) {
            firstAnswerOption.requestFocus();
        }
    }

    private void styleAnswerOption(Button button, int index, String value, boolean focused) {
        String text = String.valueOf((char) ('A' + index)) + "    " + value;
        int noteStart = text.length();
        if (focused) {
            text += "　　确认键作答";
        }
        SpannableString styled = new SpannableString(text);
        styled.setSpan(new StyleSpan(Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new RelativeSizeSpan(1.1f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (focused) {
            styled.setSpan(new RelativeSizeSpan(0.6f), noteStart, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new ForegroundColorSpan(Ui.accentColor(this)), noteStart, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        button.setText(styled);
    }

    private void buildParentRecovery() {
        final boolean inlineInput = PolicyStore.LOCKED.equals(store.getRuntime().state);
        View divider = new View(this);
        divider.setBackgroundColor(Ui.borderColor(this));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1));
        dividerParams.topMargin = Ui.dp(this, 16);
        main.addView(divider, dividerParams);

        LinearLayout actions = Ui.horizontal(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        final EditText secret;
        if (inlineInput) {
            TextView label = Ui.text(this, "家长操作", 13, Ui.mutedColor(this));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    Ui.dp(this, 112), ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.rightMargin = Ui.dp(this, 12);
            actions.addView(label, labelParams);
            secret = new EditText(this);
            configureSecretInput(secret);
            LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                    Ui.dp(this, 240), Ui.dp(this, 40));
            inputParams.rightMargin = Ui.dp(this, 12);
            actions.addView(secret, inputParams);
        } else {
            secret = null;
        }

        final Button unlock = Ui.button(this, "家长本地解锁");
        parentUnlockButton = unlock;
        unlock.setId(View.generateViewId());
        unlock.setTextSize(13);
        unlock.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        unlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (inlineInput) {
                    unlockWithSecret(secret.getText().toString().trim(), unlock, secret);
                } else {
                    showParentUnlockDialog();
                }
            }
        });
        actions.addView(unlock);
        if (inlineInput) {
            TextView web = Ui.text(this, "也可通过手机网页管理", 11, Ui.mutedColor(this));
            web.setGravity(Gravity.END);
            LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            webParams.leftMargin = Ui.dp(this, 16);
            actions.addView(web, webParams);
        } else {
            Button web = Ui.button(this, "使用手机网页管理");
            web.setTextSize(13);
            web.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
            web.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Toast.makeText(LockActivity.this,
                            "请在同一 Wi-Fi 下访问 " + WatchPolicyService.getAddress(),
                            Toast.LENGTH_LONG).show();
                }
            });
            LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            webParams.leftMargin = Ui.dp(this, 12);
            actions.addView(web, webParams);
        }
        Ui.add(main, actions, 12);
        if (!inlineInput) {
            return;
        }
        secret.setNextFocusDownId(unlock.getId());
        unlock.setNextFocusUpId(secret.getId());
        if (lastAnswerOption != null) {
            lastAnswerOption.setNextFocusDownId(secret.getId());
            secret.setNextFocusUpId(lastAnswerOption.getId());
        }
        secret.setImeOptions(EditorInfo.IME_ACTION_DONE);
        secret.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                View target = null;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    target = unlock;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && lastAnswerOption != null) {
                    target = lastAnswerOption;
                }
                if (target == null) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (keyboard != null) {
                        keyboard.hideSoftInputFromWindow(secret.getWindowToken(), 0);
                    }
                    target.requestFocus();
                }
                return true;
            }
        });
        secret.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                if (actionId != EditorInfo.IME_ACTION_DONE) {
                    return false;
                }
                InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) {
                    keyboard.hideSoftInputFromWindow(secret.getWindowToken(), 0);
                }
                unlock.requestFocus();
                return true;
            }
        });
    }

    private void configureSecretInput(EditText secret) {
        secret.setId(View.generateViewId());
        secret.setHint("输入家长 PIN 或恢复码");
        secret.setHintTextColor(Ui.mutedColor(this));
        secret.setTextColor(Ui.textColor(this));
        secret.setTextSize(15);
        secret.setSingleLine(true);
        secret.setFocusable(true);
        secret.setFocusableInTouchMode(true);
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setBackground(Ui.inputBackground(this));
        secret.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        secret.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                view.requestFocus();
                InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) {
                    keyboard.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
    }

    private void showParentUnlockDialog() {
        final EditText secret = new EditText(this);
        configureSecretInput(secret);
        new AlertDialog.Builder(this, Ui.dialogTheme(this))
                .setTitle("家长身份验证")
                .setView(secret)
                .setNegativeButton("取消", null)
                .setPositiveButton("解锁", (dialog, which) ->
                        unlockWithSecret(secret.getText().toString().trim(), parentUnlockButton, secret))
                .show();
    }

    private void unlockWithSecret(final String value, final Button unlock, final EditText secret) {
        if (authInFlight || unlock == null) {
            return;
        }
        if (store.isLocalAuthBlocked()) {
            Toast.makeText(this, "尝试次数过多，请稍后再试", Toast.LENGTH_LONG).show();
            return;
        }
        if (value.isEmpty()) {
            return;
        }
        authInFlight = true;
        unlock.setEnabled(false);
        unlock.setText("正在验证…");
        authExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final boolean success = engine.unlockWithParentSecret(value);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isDestroyed()) {
                            return;
                        }
                        authInFlight = false;
                        unlock.setEnabled(true);
                        unlock.setText("家长本地解锁");
                        if (success) {
                            store.clearLocalAuthFailures();
                            if (secret != null) {
                                secret.setText("");
                            }
                            resumeVideoIfAllowed();
                        } else {
                            store.recordLocalAuthFailure();
                            Toast.makeText(LockActivity.this, "PIN 或恢复码不正确", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private static final class CountdownRingView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private final int totalSeconds;
        private int seconds;

        CountdownRingView(Context context, int totalSeconds) {
            super(context);
            this.totalSeconds = Math.max(1, totalSeconds);
        }

        void setSeconds(int seconds) {
            this.seconds = Math.max(0, seconds);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float stroke = Ui.dp(getContext(), 7);
            float inset = stroke / 2 + Ui.dp(getContext(), 2);
            bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Ui.borderColor(getContext()));
            canvas.drawArc(bounds, 0, 360, false, paint);
            paint.setColor(Ui.warningColor(getContext()));
            canvas.drawArc(bounds, -90, 360f * Math.min(seconds, totalSeconds) / totalSeconds,
                    false, paint);
        }
    }

    private void resumeVideoIfAllowed() {
        PolicyStore.RuntimeState state = store.getRuntime();
        if (!PolicyStore.WATCHING.equals(state.state)) {
            render(true);
            return;
        }
        leaveLockTask();
        Intent intent = new AppRepository(this).getLaunchIntent(state.lastVideoPackage);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                startActivity(intent);
                finish();
                return;
            } catch (RuntimeException ignored) {
                store.addLog("原视频应用无法自动恢复，请从桌面重新打开");
            }
        }
        finish();
    }

    private static String formatSeconds(int seconds) {
        int minutes = Math.max(0, seconds) / 60;
        int remaining = Math.max(0, seconds) % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, remaining);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            focusInitialControl();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (renderedDark != Ui.isDark(this)) {
            recreate();
            return;
        }
        WatchPolicyService.start(this);
        if (deviceControl.canUseLockTask()
                && PolicyEngine.isLockedState(store.getRuntime().state)) {
            try {
                startLockTask();
            } catch (RuntimeException e) {
                store.addLog("系统未允许进入锁任务模式：" + e.getClass().getSimpleName());
            }
        }
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    private void leaveLockTask() {
        if (!deviceControl.isOwner()) {
            return;
        }
        try {
            stopLockTask();
        } catch (RuntimeException e) {
            store.addLog("锁任务退出失败：" + e.getClass().getSimpleName());
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        authExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (!PolicyEngine.isLockedState(store.getRuntime().state)) {
            super.onBackPressed();
        }
    }
}
