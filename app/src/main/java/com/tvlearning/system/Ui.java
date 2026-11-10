package com.tvlearning.system;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    private static final String PREFS = "tv_learning_ui";
    private static final String DARK_THEME = "dark_theme";

    private Ui() {
    }

    static boolean isDark(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(DARK_THEME, false);
    }

    static int backgroundColor(Context context) {
        return isDark(context) ? Color.rgb(9, 21, 36) : Color.rgb(237, 243, 248);
    }

    static int panelColor(Context context) {
        return isDark(context) ? Color.rgb(20, 39, 58) : Color.WHITE;
    }

    static int textColor(Context context) {
        return isDark(context) ? Color.rgb(239, 247, 253) : Color.rgb(22, 44, 64);
    }

    static int mutedColor(Context context) {
        return isDark(context) ? Color.rgb(155, 178, 198) : Color.rgb(85, 112, 134);
    }

    static int accentColor(Context context) {
        return isDark(context) ? Color.rgb(91, 216, 235) : Color.rgb(40, 109, 219);
    }

    static int successColor(Context context) {
        return isDark(context) ? Color.rgb(91, 222, 172) : Color.rgb(22, 123, 82);
    }

    static int warningColor(Context context) {
        return isDark(context) ? Color.rgb(255, 198, 128) : Color.rgb(174, 94, 13);
    }

    static int borderColor(Context context) {
        return isDark(context) ? Color.rgb(42, 67, 89) : Color.rgb(215, 226, 236);
    }

    static int dialogTheme(Context context) {
        return isDark(context)
                ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.15f);
        return view;
    }

    static TextView title(Context context, String value) {
        TextView view = text(context, value, 24, textColor(context));
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    static TextView brandMark(Context context) {
        TextView mark = text(context, "学", 20,
                isDark(context) ? Color.rgb(7, 26, 37) : Color.WHITE);
        mark.setGravity(Gravity.CENTER);
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mark.setBackground(panel(context,
                isDark(context) ? Color.rgb(32, 184, 216) : Color.rgb(40, 109, 219), 12));
        mark.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)));
        return mark;
    }

    static TextView badge(Context context, String value, int color) {
        TextView badge = text(context, value, 13, color);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        GradientDrawable background = panel(context, panelColor(context), 20);
        background.setStroke(dp(context, 1), color);
        badge.setBackground(background);
        return badge;
    }

    static Button button(Context context, String value) {
        Button button = new Button(context);
        button.setText(value);
        button.setTextSize(18);
        button.setTextColor(textColor(context));
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused},
                buttonBackground(context, isDark(context) ? Color.rgb(21, 62, 81) : Color.rgb(232, 242, 255),
                        accentColor(context), 2));
        background.addState(new int[]{android.R.attr.state_pressed},
                buttonBackground(context, isDark(context) ? Color.rgb(24, 73, 93) : Color.rgb(210, 230, 252),
                        accentColor(context), 2));
        background.addState(new int[]{},
                buttonBackground(context, panelColor(context), borderColor(context), 1));
        button.setBackground(background);
        return button;
    }

    static Drawable roundedIcon(Context context, Drawable icon, int radiusDp) {
        Drawable.ConstantState state = icon.getConstantState();
        Drawable source = state == null ? icon : state.newDrawable(context.getResources());
        final float radius = dp(context, radiusDp);
        final Path clip = new Path();
        return new DrawableWrapper(source) {
            @Override
            public void draw(Canvas canvas) {
                clip.reset();
                clip.addRoundRect(getBounds().left, getBounds().top, getBounds().right,
                        getBounds().bottom, radius, radius, Path.Direction.CW);
                int saved = canvas.save();
                canvas.clipPath(clip);
                super.draw(canvas);
                canvas.restoreToCount(saved);
            }
        };
    }

    static Button themeButton(Context context) {
        final Button button = button(context, isDark(context) ? "切换为浅色" : "切换为深色");
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Context context = view.getContext();
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putBoolean(DARK_THEME, !isDark(context)).apply();
                if (context instanceof Activity) {
                    ((Activity) context).recreate();
                }
            }
        });
        return button;
    }

    static LinearLayout vertical(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout horizontal(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    static void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(parent.getContext(), topMargin);
        parent.addView(child, params);
    }

    static StateListDrawable inputBackground(Context context) {
        GradientDrawable focused = panel(context, panelColor(context), 12);
        focused.setStroke(dp(context, 2), accentColor(context));
        GradientDrawable normal = panel(context, panelColor(context), 12);
        normal.setStroke(dp(context, 1), borderColor(context));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, focused);
        background.addState(new int[]{}, normal);
        return background;
    }

    static GradientDrawable panel(Context context, int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    private static GradientDrawable buttonBackground(Context context, int color, int border, int widthDp) {
        GradientDrawable drawable = panel(context, color, 16);
        drawable.setStroke(dp(context, widthDp), border);
        return drawable;
    }
}
