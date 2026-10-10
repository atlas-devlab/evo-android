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
import java.util.Map;

final class FinanceChartView extends View {
    interface ModeChangeListener {
        void onModeChanged(int mode);
    }

    static final int MODE_DAILY = 0;
    static final int MODE_CATEGORY = 1;
    static final int MODE_ACCOUNTS = 2;
    static final int MODE_GOALS = 3;
    static final int MODE_DEBTS = 4;
    static final int MODE_COUNT = 5;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] palette = {
            0xFF1FD6A3, 0xFFFF6A3D, 0xFF2BA8FF, 0xFFFFC533,
            0xFFFF5FA2, 0xFF27D5E8, 0xFFB45CFF, 0xFF86E35D
    };
    private int mode = MODE_DAILY;
    private double[] daily = new double[0];
    private Map<String, Double> categories;
    private Map<String, Double> accounts;
    private List<EvoDb.Goal> goals = new ArrayList<>();
    private List<EvoDb.Debt> debts = new ArrayList<>();
    private double plannedTotal;
    private String detail = "Dati del mese";
    private long animStart = SystemClock.uptimeMillis();
    private ModeChangeListener modeChangeListener;
    private float touchDownX;

    FinanceChartView(Context context) {
        super(context);
        setMinimumHeight(dp(300));
        setClickable(true);
    }

    void setMode(int mode) {
        this.mode = Math.floorMod(mode, MODE_COUNT);
        detail = titleForMode();
        animStart = SystemClock.uptimeMillis();
        invalidate();
    }

    void setData(double[] daily, Map<String, Double> categories, Map<String, Double> accounts,
                 List<EvoDb.Goal> goals, List<EvoDb.Debt> debts) {
        this.daily = daily == null ? new double[0] : daily;
        this.categories = categories;
        this.accounts = accounts;
        this.goals = goals == null ? new ArrayList<>() : goals;
        this.debts = debts == null ? new ArrayList<>() : debts;
        animStart = SystemClock.uptimeMillis();
        invalidate();
    }

    void setPlannedTotal(double plannedTotal) {
        this.plannedTotal = Math.max(0, plannedTotal);
        animStart = SystemClock.uptimeMillis();
        invalidate();
    }

    void setModeChangeListener(ModeChangeListener listener) {
        modeChangeListener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, resolveSize(dp(330), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawMonitor(canvas);
        if (mode == MODE_CATEGORY) drawMapBars(canvas, categories, "Categorie del mese", "Nessuna spesa categorizzata");
        else if (mode == MODE_ACCOUNTS) drawMapBars(canvas, accounts, "Conti e carte", "Nessun movimento per conto");
        else if (mode == MODE_GOALS) drawGoals(canvas);
        else if (mode == MODE_DEBTS) drawDebts(canvas);
        else drawDaily(canvas);
        drawFooter(canvas);
        if (SystemClock.uptimeMillis() - animStart < 450) postInvalidateDelayed(16);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            touchDownX = event.getX();
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            if (Math.abs(event.getX() - touchDownX) > dp(24)) return true;
            if (event.getX() >= getWidth() * 0.5f) mode = (mode + 1) % MODE_COUNT;
            else mode = (mode + MODE_COUNT - 1) % MODE_COUNT;
            detail = "Vista attiva: " + titleForMode();
            animStart = SystemClock.uptimeMillis();
            performClick();
            invalidate();
            if (modeChangeListener != null) modeChangeListener.onModeChanged(mode);
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void drawDaily(Canvas canvas) {
        drawTitle(canvas, "Giorni", "reali e impegni programmati");
        double max = 0;
        double total = 0;
        for (double v : daily) {
            max = Math.max(max, v);
            total += v;
        }
        double plannedDaily = daily.length == 0 ? 0 : plannedTotal / daily.length;
        max = Math.max(max, plannedDaily);
        if (max <= 0) {
            drawEmpty(canvas, "Nessuna uscita questo mese", "Reali e programmate appariranno qui appena salvate.");
            return;
        }
        float left = dp(24);
        float right = getWidth() - dp(24);
        float base = getHeight() - dp(82);
        float top = dp(82);
        int count = Math.max(1, daily.length);
        float gap = dp(2);
        float barW = Math.max(dp(3), (right - left - gap * (count - 1)) / count);
        float anim = anim();
        for (int i = 0; i < count; i++) {
            double value = i < daily.length ? daily[i] : 0;
            float plannedHeight = (float) ((base - top) * (plannedDaily / max) * anim());
            float h = (float) ((base - top) * (value / max) * anim);
            float x = left + i * (barW + gap);
            if (plannedDaily > 0) {
                paint.setColor(0x55F0C96D);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(new RectF(x, base - plannedHeight, x + barW, base), dp(2), dp(2), paint);
            }
            paint.setColor(palette[i % palette.length]);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(x, base - h, x + barW, base), dp(3), dp(3), paint);
        }
        label(canvas, "Reali " + money(total), left, getHeight() - dp(48), 0xFFEFF6FF, false, true);
        label(canvas, plannedTotal > 0 ? "Programmate " + money(plannedTotal) : "Picco " + money(max),
                right, getHeight() - dp(48), plannedTotal > 0 ? 0xFFF0C96D : 0xFFA0B4C4, true, false);
    }

    private void drawMapBars(Canvas canvas, Map<String, Double> data, String title, String empty) {
        drawTitle(canvas, title, mode == MODE_CATEGORY ? "reali + impegni programmati" : "valori aggiornati");
        if (data == null || data.isEmpty()) {
            drawEmpty(canvas, empty, "Aggiungi dati e il grafico si aggiorna.");
            return;
        }
        double max = 1;
        for (double v : data.values()) max = Math.max(max, v);
        float left = dp(24);
        float right = getWidth() - dp(28);
        float y = dp(82);
        int i = 0;
        for (Map.Entry<String, Double> e : data.entrySet()) {
            if (i >= 7) break;
            float w = (float) ((right - left - dp(130)) * (e.getValue() / max) * anim());
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF111722);
            canvas.drawRoundRect(new RectF(left + dp(120), y, right, y + dp(26)), dp(6), dp(6), paint);
            paint.setColor(palette[i % palette.length]);
            canvas.drawRoundRect(new RectF(left + dp(120), y, left + dp(120) + w, y + dp(26)), dp(6), dp(6), paint);
            label(canvas, shorten(e.getKey(), 13), left, y + dp(19), 0xFFEFF6FF, false, true);
            label(canvas, money(e.getValue()), right, y + dp(19), 0xFFA0B4C4, true, false);
            y += dp(38);
            i++;
        }
    }

    private void drawGoals(Canvas canvas) {
        drawTitle(canvas, "Obiettivi", "progressi e avatar");
        if (goals.isEmpty()) {
            drawEmpty(canvas, "Nessun obiettivo attivo", "Crea un obiettivo: qui apparira il progresso.");
            return;
        }
        float left = dp(24);
        float right = getWidth() - dp(28);
        float y = dp(82);
        int i = 0;
        for (EvoDb.Goal g : goals) {
            if (i >= 6) break;
            float progress = (float) (g.target <= 0 ? 0 : Math.min(1, g.saved / g.target));
            paint.setColor(0xFF111722);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(left, y, right, y + dp(32)), dp(7), dp(7), paint);
            paint.setColor(palette[i % palette.length]);
            canvas.drawRoundRect(new RectF(left, y, left + (right - left) * progress * anim(), y + dp(32)), dp(7), dp(7), paint);
            label(canvas, shorten(g.name, 20), left + dp(9), y + dp(21), 0xFFFFFFFF, false, true);
            label(canvas, Math.round(progress * 100) + "%", right - dp(8), y + dp(21), 0xFFFFFFFF, true, true);
            y += dp(44);
            i++;
        }
    }

    private void drawDebts(Canvas canvas) {
        drawTitle(canvas, "Debiti", "rate e residuo");
        if (debts.isEmpty()) {
            drawEmpty(canvas, "Nessun debito registrato", "Se aggiungi un debito, comparira qui e nella salute avatar.");
            return;
        }
        double max = 1;
        for (EvoDb.Debt d : debts) max = Math.max(max, d.monthlyPayment);
        float left = dp(24);
        float right = getWidth() - dp(28);
        float y = dp(82);
        int i = 0;
        for (EvoDb.Debt d : debts) {
            if (i >= 6) break;
            float w = (float) ((right - left - dp(110)) * (d.monthlyPayment / max) * anim());
            paint.setColor(0xFF111722);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(left + dp(110), y, right, y + dp(28)), dp(7), dp(7), paint);
            paint.setColor(d.apr >= 10 ? 0xFFFF6A3D : palette[i % palette.length]);
            canvas.drawRoundRect(new RectF(left + dp(110), y, left + dp(110) + w, y + dp(28)), dp(7), dp(7), paint);
            label(canvas, shorten(d.name, 12), left, y + dp(20), 0xFFEFF6FF, false, true);
            label(canvas, "rata " + money(d.monthlyPayment), right, y + dp(20), 0xFFA0B4C4, true, false);
            y += dp(42);
            i++;
        }
    }

    private void drawMonitor(Canvas canvas) {
        float pad = dp(12);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF0A0F12);
        canvas.drawRoundRect(new RectF(pad, pad, getWidth() - pad, getHeight() - pad), dp(8), dp(8), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(0xFF3B675A);
        canvas.drawRoundRect(new RectF(pad, pad, getWidth() - pad, getHeight() - pad), dp(8), dp(8), paint);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0x662D4054);
        for (int y = dp(68); y < getHeight() - dp(58); y += dp(28)) {
            canvas.drawLine(dp(24), y, getWidth() - dp(24), y, paint);
        }
    }

    private void drawTitle(Canvas canvas, String title, String sub) {
        label(canvas, title, dp(24), dp(42), 0xFFEFF6FF, false, true);
        label(canvas, sub, dp(24), dp(62), 0xFFA0B4C4, false, false);
    }

    private void drawFooter(Canvas canvas) {
        label(canvas, "Vista: " + titleForMode(), dp(24), getHeight() - dp(22), 0xFFF0C96D, false, true);
        label(canvas, footerDetail(), getWidth() - dp(24), getHeight() - dp(22), 0xFFA0B4C4, true, false);
    }

    private String footerDetail() {
        if (mode == MODE_DAILY) return plannedTotal > 0 ? money(plannedTotal) + " programmati" : "Dati del mese";
        if (mode == MODE_CATEGORY) return (categories == null ? 0 : categories.size()) + " categorie";
        if (mode == MODE_ACCOUNTS) return (accounts == null ? 0 : accounts.size()) + " conti";
        if (mode == MODE_GOALS) return goals.size() + " obiettivi";
        return debts.size() + " debiti";
    }

    private void drawEmpty(Canvas canvas, String title, String sub) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF111722);
        canvas.drawRoundRect(new RectF(dp(34), dp(92), getWidth() - dp(34), getHeight() - dp(92)), dp(8), dp(8), paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTextSize(dp(14));
        paint.setColor(0xFFEFF6FF);
        canvas.drawText(title, getWidth() / 2f, getHeight() / 2f - dp(4), paint);
        paint.setFakeBoldText(false);
        paint.setTextSize(dp(12));
        paint.setColor(0xFFA0B4C4);
        canvas.drawText(sub, getWidth() / 2f, getHeight() / 2f + dp(24), paint);
    }

    private String titleForMode() {
        if (mode == MODE_CATEGORY) return "Categorie";
        if (mode == MODE_ACCOUNTS) return "Conti";
        if (mode == MODE_GOALS) return "Obiettivi";
        if (mode == MODE_DEBTS) return "Debiti";
        return "Giorni";
    }

    private float anim() {
        return Math.min(1f, (SystemClock.uptimeMillis() - animStart) / 420f);
    }

    private void label(Canvas canvas, String text, float x, float y, int color, boolean right, boolean bold) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(dp(bold ? 14 : 12));
        paint.setFakeBoldText(bold);
        paint.setTextAlign(right ? Paint.Align.RIGHT : Paint.Align.LEFT);
        canvas.drawText(text, x, y, paint);
        paint.setFakeBoldText(false);
    }

    private String money(double value) {
        return String.format(Locale.ITALY, "%.0f EUR", value);
    }

    private String shorten(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, Math.max(1, max - 1)) + ".";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
