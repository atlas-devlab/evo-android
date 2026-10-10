package com.elia.evoconti;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

import it.evocore.client.EvoCoreClient;
import it.evocore.client.EvoCoreProtocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class DialogueCandidateCleanupInstrumentedTest {
    @Test public void removesOnlyUnconfirmedDialogueCandidatesWhenExplicitlyEnabled() {
        assertEquals("yes", InstrumentationRegistry.getArguments()
                .getString("confirmCleanup", ""));
        Context context = ApplicationProvider.getApplicationContext();
        int removed = 0;
        try (EvoCoreClient client = new EvoCoreClient(context, "evoconti", null)) {
            assertTrue(client.connect());
            EvoCoreClient.Result listed = client.listMemories();
            assertTrue(listed.error, listed.ok);
            ArrayList<Bundle> rows = listed.data.getParcelableArrayList(EvoCoreProtocol.KEY_MEMORIES);
            if (rows != null) {
                for (Bundle row : rows) {
                    if (!"MODEL_HYPOTHESIS".equals(row.getString(EvoCoreProtocol.KEY_STATE, ""))
                            || !"client-dialogue".equals(row.getString(EvoCoreProtocol.KEY_SOURCE, ""))) {
                        continue;
                    }
                    long id = row.getLong(EvoCoreProtocol.KEY_MEMORY_ID, 0L);
                    EvoCoreClient.Result forgotten = client.updateMemory(id, "forget", "");
                    assertTrue(forgotten.error, forgotten.ok);
                    removed++;
                }
            }
        }
        Log.i("EvoCoreCleanupTest", "Removed dialogue candidates: " + removed);
    }
}
