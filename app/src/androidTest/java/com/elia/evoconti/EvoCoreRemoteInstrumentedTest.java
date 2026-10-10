package com.elia.evoconti;

import android.content.Context;
import android.os.Bundle;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

import it.evocore.client.EvoCoreClient;
import it.evocore.client.EvoCoreProtocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class EvoCoreRemoteInstrumentedTest {
    @Test public void evocontiUsesSeparateCoreForInferenceAndMemory() {
        Context context = ApplicationProvider.getApplicationContext();
        assertTrue("EvoCore host must be installed", EvoCoreClient.isInstalled(context));
        try (EvoCoreClient client = new EvoCoreClient(context, "evoconti", null)) {
            assertTrue(client.connect());
            EvoCoreClient.Result answer = client.askBlocking(
                    "{\"redditoNetto\":2400,\"saldoMensile\":480,\"debitiPct\":4," +
                            "\"fondoEmergenzaMesi\":4,\"saluteBudget\":82}",
                    "Analizza il mese senza scegliere investimenti", "custodian", 30_000L);
            assertTrue(answer.error, answer.ok);
            JSONObject json = json(answer.data.getString(EvoCoreProtocol.KEY_ANSWER_JSON, "{}"));
            assertEquals("equilibrio", json.optString("stato"));
            assertTrue(json.optBoolean("puoInvestire"));
            String engine = answer.data.getString(EvoCoreProtocol.KEY_ENGINE, "");
            if (engine.contains("Gemma")) {
                assertFalse("Gemma must complete without rule fallback",
                        answer.data.getBoolean("modelFallback", false));
            }

            Bundle memory = new Bundle();
            memory.putString(EvoCoreProtocol.KEY_TEXT, "Ricorda che preferisco esempi concreti");
            memory.putString(EvoCoreProtocol.KEY_TOPIC, "preferenze");
            memory.putString(EvoCoreProtocol.KEY_SOURCE, "user");
            EvoCoreClient.Result stored = client.remember(memory);
            assertTrue(stored.error, stored.ok);
            long id = stored.data.getLong(EvoCoreProtocol.KEY_MEMORY_ID, 0L);
            assertTrue(id > 0L);

            EvoCoreClient.Result listed = client.listMemories();
            assertTrue(listed.error, listed.ok);
            ArrayList<Bundle> rows = listed.data.getParcelableArrayList(EvoCoreProtocol.KEY_MEMORIES);
            assertTrue(rows != null && contains(rows, id));

            EvoCoreClient.Result forgotten = client.updateMemory(id, "forget", "");
            assertTrue(forgotten.error, forgotten.ok);
            EvoCoreClient.Result after = client.listMemories();
            ArrayList<Bundle> remaining = after.data.getParcelableArrayList(EvoCoreProtocol.KEY_MEMORIES);
            assertFalse(remaining != null && contains(remaining, id));
        }
    }

    private static boolean contains(ArrayList<Bundle> rows, long id) {
        for (Bundle row : rows) if (row.getLong(EvoCoreProtocol.KEY_MEMORY_ID, 0L) == id) return true;
        return false;
    }

    private static JSONObject json(String value) {
        try {
            return new JSONObject(value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
