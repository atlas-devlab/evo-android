package com.elia.evoconti;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BudgetCalendarView extends View {
    interface OnDaySelectedListener {
        void onDaySelected(int year, int month, int day);
    }

    private static final int BG = 0xFF0E1317;
    private static final int LINE = 0xFF30383E;
    private static final int INK = 0xFFF3F6F4;
    private static final int MUTED = 0xFF9CA8A3;
    private static final int GREEN = 0xFF43D19A;
    private static final int CORAL = 0xFFFF7165;
    private static final int BLUE = 0xFF57B5E8;
    private static final int GOLD = 0xFFF0C96D;
    private static final String[] WEEK = {"L", "M", "M", "G", "V", "S", "D"};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Integer, List<EvoDb.CalendarItem>> events = new HashMap<>();
    private final Calendar month = Calendar.getInstance();
    private final Calendar today = Calendar.getInstance();
    private int selectedDay;
    private int firstOffset;
    private int daysInMonth;
    private float gridTop;
    private float cellWidth;
    private float cellHeight;
    private long animationStarted;
    private OnDaySelectedListener listener;

    BudgetCalendarView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setBackgroundColor(BG);
        text.setTextAlign(Paint.Align.CENTER);
        setMonth(today.get(Calendar.YEAR), today.get(Calendar.MONTH), new ArrayList<>());
    }

    void setMonth(int year, int monthIndex, List<EvoDb.CalendarItem> items) {
        month.set(year, monthIndex, 1, 0, 0, 0);
        month.set(Calendar.MILLISECOND, 0);
        firstOffset = Math.floorMod(month.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY, 7);
        daysInMonth = month.getActualMaximum(Calendar.DAY_OF_MONTH);
        boolean current = year == today.get(Calendar.YEAR) && monthIndex == today.get(Calendar.MONTH);
        selectedDay = current ? today.get(Calendar.DAY_OF_MONTH) : 1;
        setEvents(items);
    }

    void setEvents(List<EvoDb.CalendarItem> items) {
        events.clear();
        for (EvoDb.CalendarItem item : items) {
            Calendar when = Calendar.getInstance();
            when.setTimeInMillis(item.whenAt);
            if (when.get(Calendar.YEAR) != month.get(Calendar.YEAR) ||
                    when.get(Calendar.MONTH) != month.get(Calendar.MONTH)) continue;
            int day = when.get(Calendar.DAY_OF_MONTH);
            events.computeIfAbsent(day, ignored -> new ArrayList<>()).add(item);
        }
        animationStarted = SystemClock.uptimeMillis();
        updateDescription();
        invalidate();
    }

    void setSelectedDay(int day) {
        selectedDay = Math.max(1, Math.min(daysInMonth, day));
        updateDescription();
        invalidate();
    }

    int selectedDay() {
        return selectedDay;
    }

    void setOnDaySelectedListener(OnDaySelectedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int desired = Math.round(width * 0.72f);
        setMeasuredDimension(width, resolveSize(Math.max(dp(300), desired), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        gridTop = dp(38);
        cellWidth = width / 7f;
        cellHeight = (height - gridTop - dp(8)) / 6f;

        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        text.setTextSize(dp(11));
        text.setColor(MUTED);
        for (int i = 0; i < 7; i++) canvas.drawText(WEEK[i], cellWidth * (i + 0.5f), dp(24), text);

        paint.setStrokeWidth(dp(1));
        paint.setColor(LINE);
        canvas.drawLine(0, gridTop - dp(5), width, gridTop - dp(5), paint);

        float animation = Math.min(1f, (SystemClock.uptimeMillis() - animationStarted) / 280f);
        for (int day = 1; day <= daysInMonth; day++) {
            int slot = firstOffset + day - 1;
            int row = slot / 7;
            int col = slot % 7;
            float cx = col * cellWidth + cellWidth / 2f;
            float top = gridTop + row * cellHeight;
            float cy = top + cellHeight * 0.36f;
            boolean isToday = month.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    month.get(Calendar.MONTH) == today.get(Calendar.MONTH) &&
                    day == today.get(Calendar.DAY_OF_MONTH);
            boolean selected = day == selectedDay;

            if (selected) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(GREEN);
                canvas.drawCircle(cx, cy - dp(1), dp(16), paint);
            } else if (isToday) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.5f));
                paint.setColor(GREEN);
                canvas.drawCircle(cx, cy - dp(1), dp(15), paint);
                paint.setStyle(Paint.Style.FILL);
            }

            text.setTypeface(isToday || selected ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            text.setTextSize(dp(13));
            text.setColor(selected ? BG : INK);
            canvas.drawText(String.valueOf(day), cx, cy + dp(4), text);

            List<EvoDb.CalendarItem> dayEvents = events.get(day);
            if (dayEvents != null && !dayEvents.isEmpty()) {
                int count = Math.min(3, dayEvents.size());
                float dotY = top + cellHeight * 0.72f;
                float startX = cx - (count - 1) * dp(4);
                for (int i = 0; i < count; i++) {
                    paint.setColor(eventColor(dayEvents.get(i).kind));
                    canvas.drawCircle(startX + i * dp(8), dotY, dp(2.6f) * animation, paint);
                }
                if (dayEvents.size() > 3) {
                    text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    text.setTextSize(dp(8));
                    text.setColor(MUTED);
                    canvas.drawText("+" + (dayEvents.size() - 3), cx, top + cellHeight - dp(4), text);
                }
            }
        }
        if (animation < 1f) postInvalidateDelayed(16);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_UP) return true;
        if (event.getY() < gridTop) return true;
        int col = Math.max(0, Math.min(6, (int) (event.getX() / Math.max(1f, cellWidth))));
        int row = Math.max(0, Math.min(5, (int) ((event.getY() - gridTop) / Math.max(1f, cellHeight))));
        int day = row * 7 + col - firstOffset + 1;
        if (day < 1 || day > daysInMonth) return true;
        selectedDay = day;
        updateDescription();
        performClick();
        invalidate();
        if (listener != null) listener.onDaySelected(month.get(Calendar.YEAR), month.get(Calendar.MONTH), day);
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void updateDescription() {
        Calendar selected = (Calendar) month.clone();
        selected.set(Calendar.DAY_OF_MONTH, selectedDay);
        int count = events.containsKey(selectedDay) ? events.get(selectedDay).size() : 0;
        setContentDescription("Calendario. " + new SimpleDateFormat("d MMMM yyyy", Locale.ITALY).format(selected.getTime()) +
                ". " + count + " eventi. Tocca un giorno per aprirlo.");
    }

    private int eventColor(String kind) {
        if ("income".equals(kind) || "planned_income".equals(kind)) return GREEN;
        if ("planned_expense".equals(kind)) return GOLD;
        if ("debt".equals(kind)) return CORAL;
        return BLUE;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
