package it.evocore.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.util.Base64;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class PersonalMemoryImportInstrumentedTest {
    private static final String PERSONAL_SCOPE = "it.evocore.host:personal";

    @Test
    public void importsExplicitConfirmedProfileWithoutDuplicates() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        String encoded = arguments.getString("profileBase64", "");
        assertFalse("Profilo Base64 mancante", encoded.isEmpty());

        String json = new String(Base64.decode(encoded, Base64.NO_WRAP), StandardCharsets.UTF_8);
        JSONArray entries = new JSONArray(json);
        assertTrue("Il profilo deve contenere almeno un ricordo", entries.length() > 0);

        Context context = ApplicationProvider.getApplicationContext();
        EvoCoreDb db = new EvoCoreDb(context);
        MemoryPolicyGate policy = new MemoryPolicyGate();
        Set<String> existing = new HashSet<>();
        for (EvoCoreDb.MemoryRecord row : db.list(PERSONAL_SCOPE, true)) {
            existing.add(row.text);
        }

        int added = 0;
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.getJSONObject(index);
            String text = entry.getString("text").trim();
            if (existing.contains(text)) continue;

            MemoryPolicyGate.Decision decision = policy.evaluate(text, "manual",
                    MemoryPolicyGate.USER_CONFIRMED, System.currentTimeMillis());
            assertTrue("Ricordo rifiutato: " + decision.reason, decision.accepted);
            assertEquals(MemoryPolicyGate.USER_CONFIRMED, decision.state);
            db.add(PERSONAL_SCOPE, text, entry.optString("topic", "personale"),
                    decision.kind, decision.state, "manual", decision.sensitivity,
                    entry.optString("sourceRef", "Codex Atlas import"),
                    decision.expiresAt, 0L);
            existing.add(text);
            added++;
        }

        List<EvoCoreDb.MemoryRecord> imported = db.list(PERSONAL_SCOPE, true);
        for (int index = 0; index < entries.length(); index++) {
            String expected = entries.getJSONObject(index).getString("text").trim();
            boolean found = false;
            for (EvoCoreDb.MemoryRecord row : imported) {
                if (expected.equals(row.text) && row.isConfirmed()) {
                    found = true;
                    break;
                }
            }
            assertTrue("Ricordo confermato non trovato: " + expected, found);
        }

        String memoryContext = db.contextFor(PERSONAL_SCOPE, 20);
        assertFalse("Il contesto personale e vuoto dopo l'importazione", memoryContext.isEmpty());
        System.out.println("EVOCore Atlas import: added=" + added + ", active=" + imported.size());
    }
}
