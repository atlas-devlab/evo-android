package com.elia.evoconti;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@RunWith(AndroidJUnit4.class)
@LargeTest
public final class CustodianProgressInstrumentedTest {
    private static final String TAG = "EvoCustodianProgress";

    @Test
    public void usefulFirstReadingArrivesBeforeTheModelAnswer() {
        Context context = ApplicationProvider.getApplicationContext();
        EvoDb db = new EvoDb(context);
        try {
            LocalInvestmentCustodian custodian =
                    new LocalInvestmentCustodian(context, db);
            AtomicLong firstProgressAt = new AtomicLong(-1L);
            AtomicLong firstModelProgressAt = new AtomicLong(-1L);
            AtomicInteger progressCount = new AtomicInteger();
            AiContext snapshot = custodian.context();
            String question = "Collega margine, debiti e prossime scadenze.";
            long started = SystemClock.elapsedRealtime();

            AiAnswer answer = custodian.ask(
                    question,
                    text -> {
                        long now = SystemClock.elapsedRealtime();
                        int callback = progressCount.incrementAndGet();
                        if (callback == 1) firstProgressAt.compareAndSet(-1L, now);
                        else firstModelProgressAt.compareAndSet(-1L, now);
                    });
            long finished = SystemClock.elapsedRealtime();

            assertNotNull(answer);
            assertTrue("Nessuna prima lettura mostrata",
                    firstProgressAt.get() >= started);
            long firstProgressMs = firstProgressAt.get() - started;
            assertTrue("La prima lettura deve essere immediata: " + firstProgressMs,
                    firstProgressMs < 1_000L);
            Log.i(TAG, "firstProgressMs=" + firstProgressMs
                    + " firstModelProgressMs="
                    + (firstModelProgressAt.get() < started
                    ? -1L : firstModelProgressAt.get() - started)
                    + " totalMs=" + (finished - started)
                    + " contextChars=" + snapshot.toJson(question).length()
                    + " programmed=" + snapshot.programmate.size()
                    + " deadlines=" + snapshot.prossimeScadenze.size()
                    + " memoriesInQuestion=0"
                    + " engine=" + answer.motore);
        } finally {
            db.close();
        }
    }
}
