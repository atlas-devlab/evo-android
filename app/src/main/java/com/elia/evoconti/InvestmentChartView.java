package com.elia.evoconti;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class InvestmentChartView extends View {
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<EvoDb.InvestmentAsset> assets = new ArrayList<>();
    private final Bubble[] bubbles = new Bubble[12];
    private int bubbleCount;
    private long animationStart = SystemClock.uptimeMillis();
    private int selected = -1;

    InvestmentChartView(Context context) {
        super(context);
        setMinimumHeight(dp(300));
        setClickable(true);
        for (int i = 0; i < bubbles.length; i++) bubbles[i] = new Bubble();
    }

    void setData(List<EvoDb.InvestmentAsset> rows) {
        assets.clear();
        if (rows != null) assets.addAll(rows);
        selected = assets.isEmpty() ? -1 : 0;
        animationStart = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, resolveSize(dp(320), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawMonitor(canvas);
        label(canvas, "Mappa rischio e orizzonte", dp(28), dp(42), 0xFFF3F6F4, false, true);
        label(canvas, "dati della tua watchlist", dp(28), dp(62), 0xFF9CA8A3, false, false);
        if (assets.isEmpty()) {
            drawEmpty(canvas);
            return;
        }

        float left = dp(46);
        float right = getWidth() - dp(30);
        float top = dp(88);
        float bottom = getHeight() - dp(70);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x66375366);
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(left, y, right, y, paint);
        }
        canvas.drawLine(left, top, left, bottom, paint);
        canvas.drawLine(left, bottom, right, bottom, paint);
        label(canvas, "basso", left, bottom + dp(20), 0xFFA0B4C4, false, false);
        label(canvas, "rischio alto", right, bottom + dp(20), 0xFFFF6A3D, true, false);
        label(canvas, "lungo", left - dp(8), top + dp(5), 0xFFA0B4C4, true, false);
        label(canvas, "breve", left - dp(8), bottom, 0xFFA0B4C4, true, false);

        double maxInvested = 1;
        for (EvoDb.InvestmentAsset asset : assets) maxInvested = Math.max(maxInvested, Math.max(asset.invested, asset.currentValue));
        bubbleCount = 0;
        float animation = Math.min(1f, (SystemClock.uptimeMillis() - animationStart) / 420f);
        for (int i = 0; i < Math.min(12, assets.size()); i++) {
            EvoDb.InvestmentAsset asset = assets.get(i);
            float x = left + (right - left) * risk(asset.risk);
            float y = bottom - (bottom - top) * horizon(asset.horizon) * animation;
            float weight = (float) Math.sqrt(Math.max(asset.invested, asset.currentValue) / maxInvested);
            float radius = dp(18) + dp(12) * weight;
            Bubble bubble = bubbles[bubbleCount++];
            bubble.set(x, y, radius, i);
            drawBubble(canvas, bubble, asset, i == selected);
        }

        if (selected >= 0 && selected < assets.size()) {
            EvoDb.InvestmentAsset asset = assets.get(selected);
            String detail = shortText(asset.name, 18) + " | " + asset.risk + " | " +
                    String.format(Locale.ITALY, "%.0f EUR", asset.currentValue);
            label(canvas, detail, dp(28), getHeight() - dp(24), 0xFFFFC533, false, true);
        }
        if (animation < 1f) postInvalidateDelayed(16);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        int nearest = -1;
        float distance = Float.MAX_VALUE;
        for (int i = 0; i < bubbleCount; i++) {
            Bubble bubble = bubbles[i];
            float dx = event.getX() - bubble.x;
            float dy = event.getY() - bubble.y;
            float value = dx * dx + dy * dy;
            if (value < distance && value <= bubble.radius * bubble.radius * 2.25f) {
                distance = value;
                nearest = bubble.index;
            }
        }
        if (nearest >= 0) {
            selected = nearest;
            performClick();
            invalidate();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void drawMonitor(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF0A0F12);
        canvas.drawRoundRect(new RectF(dp(12), dp(12), getWidth() - dp(12), getHeight() - dp(12)), dp(8), dp(8), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0xFF3B675A);
        canvas.drawRoundRect(new RectF(dp(12), dp(12), getWidth() - dp(12), getHeight() - dp(12)), dp(8), dp(8), paint);
    }

    private void drawEmpty(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF13181D);
        canvas.drawRoundRect(new RectF(dp(34), dp(92), getWidth() - dp(34), getHeight() - dp(70)), dp(8), dp(8), paint);
        label(canvas, "Watchlist vuota", getWidth() / 2f, getHeight() / 2f - dp(4), 0xFFEFF6FF, false, true, true);
        label(canvas, "Aggiungi un asset con rischio e orizzonte.", getWidth() / 2f,
                getHeight() / 2f + dp(24), 0xFF9CA8A3, false, false, true);
    }

    private void drawBubble(Canvas canvas, Bubble bubble, EvoDb.InvestmentAsset asset, boolean active) {
        int color = color(asset.assetType);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((active ? 0x66 : 0x33) << 24 | color & 0x00FFFFFF);
        canvas.drawCircle(bubble.x, bubble.y, bubble.radius + (active ? dp(7) : dp(3)), paint);
        paint.setColor(color);
        canvas.drawCircle(bubble.x, bubble.y, bubble.radius, paint);
        String symbol = asset.ticker == null || asset.ticker.trim().isEmpty()
                ? shortText(asset.name, 4).toUpperCase(Locale.ITALY)
                : shortText(asset.ticker, 5).toUpperCase(Locale.ITALY);
        label(canvas, symbol, bubble.x, bubble.y + dp(5), 0xFFFFFFFF, false, true, true);
    }

    private float risk(String raw) {
        String value = clean(raw);
        if (value.contains("molto")) return 0.94f;
        if (value.contains("alto")) return 0.78f;
        if (value.contains("medio")) return 0.53f;
        if (value.contains("basso")) return 0.18f;
        return 0.40f;
    }

    private float horizon(String raw) {
        String value = clean(raw);
        if (value.contains("lung")) return 0.86f;
        if (value.contains("medio")) return 0.55f;
        if (value.contains("breve")) return 0.22f;
        Matcher matcher = NUMBER.matcher(value);
        if (matcher.find()) {
            int years = Integer.parseInt(matcher.group(1));
            if (value.contains("mes")) return Math.min(0.45f, years / 36f);
            return Math.min(0.92f, 0.18f + years / 15f);
        }
        return 0.45f;
    }

    private int color(String type) {
        String value = clean(type);
        if (value.contains("crypto")) return 0xFFFF7165;
        if (value.contains("etf")) return 0xFF43D19A;
        if (value.contains("azione")) return 0xFF57B5E8;
        return 0xFFF0C96D;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ITALY);
    }

    private String shortText(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private void label(Canvas canvas, String text, float x, float y, int color, boolean right, boolean bold) {
        label(canvas, text, x, y, color, right, bold, false);
    }

    private void label(Canvas canvas, String text, float x, float y, int color, boolean right, boolean bold, boolean center) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(dp(bold ? 13 : 11));
        paint.setFakeBoldText(bold);
        paint.setTextAlign(center ? Paint.Align.CENTER : (right ? Paint.Align.RIGHT : Paint.Align.LEFT));
        canvas.drawText(text, x, y, paint);
        paint.setFakeBoldText(false);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class Bubble {
        float x;
        float y;
        float radius;
        int index;

        void set(float x, float y, float radius, int index) {
            this.x = x;
            this.y = y;
            this.radius = radius;
            this.index = index;
        }
    }
}
