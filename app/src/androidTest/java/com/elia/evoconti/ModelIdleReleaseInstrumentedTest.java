package com.elia.evoconti;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import it.evocore.client.EvoCoreClient;

@RunWith(AndroidJUnit4.class)
public final class ModelIdleReleaseInstrumentedTest {
    private static final String TAG = "EvoCoreIdleTest";

    @Test
    public void gemmaLeavesRamAfterTheIdleWindow() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        assumeTrue("EvoCore host must be installed", EvoCoreClient.isInstalled(context));
        try (EvoCoreClient client = new EvoCoreClient(context, "evoconti", null)) {
            assertTrue("EvoCore connection failed", client.connect());
            EvoCoreClient.Result initial = client.status();
            assertTrue(initial.error, initial.ok);
            assumeTrue("A local model is required for this device test",
                    initial.data.getBoolean("modelAvailable", false));

            EvoCoreClient.Result answer = client.askBlocking(
                    "{\"redditoNetto\":2300,\"saldoMensile\":250,\"debitiPct\":6,"
                            + "\"fondoEmergenzaMesi\":3,\"saluteBudget\":78}",
                    "Riassumi con prudenza la salute del budget",
                    "custodian", 120_000L);
            assertTrue(answer.error, answer.ok);

            EvoCoreClient.Result loaded = client.status();
            assertTrue(loaded.error, loaded.ok);
            assertTrue("Gemma should be loaded immediately after inference",
                    loaded.data.getBoolean("modelLoaded", false));

            long idleMs = loaded.data.getLong("modelIdleUnloadMs", 15_000L);
            Thread.sleep(idleMs + 5_000L);

            EvoCoreClient.Result released = client.status();
            assertTrue(released.error, released.ok);
            assertFalse("Gemma must leave RAM after the idle window",
                    released.data.getBoolean("modelLoaded", true));
            Log.i(TAG, "Model released after " + idleMs + " ms while client stayed connected");
        }
    }
}
