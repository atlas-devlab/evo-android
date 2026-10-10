package com.elia.evoconti;

import static org.junit.Assert.assertEquals;

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

import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class CreatureViewInstrumentedTest {
    @Test
    public void everyCreatureStageAndSceneRendersAndAnimationMoves() {
        Context context = ApplicationProvider.getApplicationContext();
        AtomicReference<String> failure = new AtomicReference<>("");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CreatureView view = new CreatureView(context);
            int width = 320;
            int height = 210;
            view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, width, height);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            long[] sceneHashes = new long[4];

            for (int species = 0; species < CreatureCatalog.SPECIES_COUNT && failure.get().isEmpty(); species++) {
                for (int stage = 0; stage < CreatureCatalog.STAGES && failure.get().isEmpty(); stage++) {
                    view.setCreature(species, stage, 85);
                    for (int scene = 0; scene < 4; scene++) {
                        view.setSceneMode(scene);
                        bitmap.eraseColor(0);
                        view.draw(canvas);
                        sceneHashes[scene] = sampledHash(bitmap);
                        if (sceneHashes[scene] == 0) {
                            failure.set("Frame vuoto per specie " + species + ", stadio " + stage + ", scena " + scene);
                            break;
                        }
                    }
                    if (failure.get().isEmpty() && !allDifferent(sceneHashes)) {
                        failure.set("Scene non distinguibili per specie " + species + ", stadio " + stage);
                    }
                }
            }

            if (failure.get().isEmpty()) {
                view.setCreature(4, 0, 85);
                view.setSceneMode(CreatureView.SCENE_MONEY);
                bitmap.eraseColor(0);
                view.draw(canvas);
                long first = sampledHash(bitmap);
                SystemClock.sleep(140);
                bitmap.eraseColor(0);
                view.draw(canvas);
                long second = sampledHash(bitmap);
                if (first == second) failure.set("La scena animata non cambia nel tempo");
            }
            bitmap.recycle();
        });

        assertEquals(failure.get(), "", failure.get());
    }

    @Test
    public void touchReactsWithoutChangingTimeOrGpsScene() {
        Context context = ApplicationProvider.getApplicationContext();
        AtomicReference<String> failure = new AtomicReference<>("");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CreatureView view = new CreatureView(context);
            view.setCreature(1, 3, 82);
            view.setAutoSceneMode(CreatureView.SCENE_EAT);
            view.setEnvironmentMode(CreatureView.ENV_NATURE);
            view.measure(
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, 360, 260);
            Bitmap bitmap = Bitmap.createBitmap(360, 260, Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));
            long now = SystemClock.uptimeMillis();
            MotionEvent touch = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 180, 110, 0);
            view.dispatchTouchEvent(touch);
            touch.recycle();
            if (view.sceneModeForTest() != CreatureView.SCENE_EAT) failure.set("Il tocco ha cambiato l'attivita");
            if (view.environmentModeForTest() != CreatureView.ENV_NATURE) failure.set("Il tocco ha cambiato il luogo");
            bitmap.recycle();
        });

        assertEquals(failure.get(), "", failure.get());
    }

    private static boolean allDifferent(long[] values) {
        for (int i = 0; i < values.length; i++) {
            for (int j = i + 1; j < values.length; j++) {
                if (values[i] == values[j]) return false;
            }
        }
        return true;
    }

    private static long sampledHash(Bitmap bitmap) {
        long hash = 1;
        int visible = 0;
        for (int y = 2; y < bitmap.getHeight(); y += 7) {
            for (int x = 2; x < bitmap.getWidth(); x += 7) {
                int color = bitmap.getPixel(x, y);
                if ((color >>> 24) != 0) visible++;
                hash = hash * 1_000_003L + color;
            }
        }
        return visible < 200 ? 0 : hash;
    }
}
