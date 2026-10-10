package com.elia.evoconti;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class BudgetCalendarViewInstrumentedTest {
    @Test
    public void tapSelectsTheRealDayAndExposesItsEvents() {
        Context context = ApplicationProvider.getApplicationContext();
        AtomicInteger selected = new AtomicInteger(-1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            BudgetCalendarView view = new BudgetCalendarView(context);
            Calendar due = Calendar.getInstance();
            due.set(2026, Calendar.JULY, 9, 14, 45, 0);
            due.set(Calendar.MILLISECOND, 0);
            EvoDb.CalendarItem item = new EvoDb.CalendarItem();
            item.kind = "planned_expense";
            item.title = "Affitto";
            item.whenAt = due.getTimeInMillis();
            List<EvoDb.CalendarItem> items = new ArrayList<>();
            items.add(item);
            view.setMonth(2026, Calendar.JULY, items);
            view.setOnDaySelectedListener((year, month, day) -> selected.set(day));
            int width = 700;
            int height = 600;
            view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, width, height);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));

            float density = context.getResources().getDisplayMetrics().density;
            float gridTop = 38 * density;
            float cellHeight = (height - gridTop - 8 * density) / 6f;
            float x = 3.5f * width / 7f;
            float y = gridTop + 1.5f * cellHeight;
            long now = SystemClock.uptimeMillis();
            MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0);
            view.dispatchTouchEvent(up);
            up.recycle();

            assertEquals(9, view.selectedDay());
            assertEquals(9, selected.get());
            assertTrue(view.getContentDescription().toString().contains("1 eventi"));
            bitmap.recycle();
        });
    }
}
