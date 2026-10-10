package it.evocore.lab;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TelemetryChartView extends View {
    private static final int MEMORY = 0;
    private static final int BATTERY = 1;
    private static final int THERMAL = 2;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final List<TelemetryRepository.ChartSample> samples = new ArrayList<>();
    private int mode = MEMORY;
    private float reveal = 1f;

    TelemetryChartView(Context context) {
        super(context);
        setClickable(true);
        setContentDescription("Grafico telemetria. Tocca per cambiare metrica");
        setMinimumHeight(Math.round(dp(210)));
    }

    void setSamples(List<TelemetryRepository.ChartSample> values) {
        samples.clear();
        if (values != null) samples.addAll(values);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(260L);
        animator.addUpdateListener(value -> {
            reveal = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    String activeMetric() {
        if (mode == BATTERY) return "Batteria";
        if (mode == THERMAL) return "Margine termico";
        return "Memoria app";
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            mode = (mode + 1) % 3;
            performClick();
            invalidate();
            return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        announceForAccessibility(activeMetric());
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(18);
        float top = dp(44);
        float right = getWidth() - dp(14);
        float bottom = getHeight() - dp(28);
        RectF plot = new RectF(left, top, right, bottom);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF0D1519);
        canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), dp(8), dp(8), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xFF284047);
        canvas.drawRoundRect(new RectF(dp(0.5f), dp(0.5f),
                getWidth() - dp(0.5f), getHeight() - dp(0.5f)), dp(8), dp(8), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFF1F6F4);
        paint.setTextSize(dp(15));
        paint.setFakeBoldText(true);
        canvas.drawText(activeMetric(), left, dp(27), paint);
        paint.setFakeBoldText(false);
        paint.setTextSize(dp(11));
        paint.setColor(0xFF8DA5A7);
        canvas.drawText("tocca per cambiare", right - dp(108), dp(27), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xFF1B2A30);
        for (int row = 0; row <= 3; row++) {
            float y = top + plot.height() * row / 3f;
            canvas.drawLine(left, y, right, y, paint);
        }

        List<Float> values = selectedValues();
        if (values.size() < 2) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF8DA5A7);
            paint.setTextSize(dp(13));
            canvas.drawText("In attesa di misure...", left, top + plot.height() / 2f, paint);
            return;
        }

        float min = values.get(0);
        float max = values.get(0);
        for (float value : values) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (Math.abs(max - min) < 0.01f) {
            min -= 1f;
            max += 1f;
        }

        path.reset();
        int visible = Math.max(2, Math.round(values.size() * reveal));
        for (int index = 0; index < visible; index++) {
            float x = left + plot.width() * index / Math.max(1f, values.size() - 1f);
            float y = bottom - plot.height() * (values.get(index) - min) / (max - min);
            if (index == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(mode == BATTERY ? 0xFFF0C96D
                : mode == THERMAL ? 0xFFFF7B68 : 0xFF53D6A0);
        canvas.drawPath(path, paint);

        float current = values.get(values.size() - 1);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFF1F6F4);
        paint.setTextSize(dp(12));
        canvas.drawText(format(current), left, getHeight() - dp(9), paint);
        paint.setColor(0xFF8DA5A7);
        String range = format(min) + " - " + format(max);
        canvas.drawText(range, right - paint.measureText(range), getHeight() - dp(9), paint);
    }

    private List<Float> selectedValues() {
        List<Float> values = new ArrayList<>();
        for (TelemetryRepository.ChartSample sample : samples) {
            float value = mode == BATTERY ? sample.batteryPct
                    : mode == THERMAL ? sample.thermalHeadroom : sample.memoryMb;
            if (!Float.isNaN(value) && !Float.isInfinite(value)) values.add(value);
        }
        return values;
    }

    private String format(float value) {
        if (mode == BATTERY) return String.format(Locale.ITALY, "%.0f%%", value);
        if (mode == MEMORY) return String.format(Locale.ITALY, "%.0f MB", value);
        return String.format(Locale.ITALY, "%.2f", value);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
