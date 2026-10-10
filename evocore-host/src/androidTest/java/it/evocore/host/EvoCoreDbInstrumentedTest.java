package it.evocore.host;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class EvoCoreDbInstrumentedTest {
    private EvoCoreDb db;
    private String scope;

    @Before public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = new EvoCoreDb(context);
        scope = "test.db:" + UUID.randomUUID();
    }

    @Test public void storesAndReadsEncryptedMemory() throws Exception {
        db.add(scope, "Preferisco risposte concrete", "profilo", "preference",
                MemoryPolicyGate.USER_CONFIRMED, "user", MemoryVault.OPERATIONAL, "test", 0L, 0L);
        List<EvoCoreDb.MemoryRecord> rows = db.list(scope, true);
        assertEquals(1, rows.size());
        assertEquals("Preferisco risposte concrete", rows.get(0).text);
    }

    @Test public void candidateCanBeConfirmed() throws Exception {
        EvoCoreDb.MemoryRecord row = db.add(scope, "Forse preferisce grafici", "profilo", "preference",
                MemoryPolicyGate.MODEL_HYPOTHESIS, "model", MemoryVault.OPERATIONAL, "test", 0L, 0L);
        EvoCoreDb.MemoryRecord confirmed = db.confirm(scope, row.id);
        assertNotNull(confirmed);
        assertEquals(MemoryPolicyGate.USER_CONFIRMED, confirmed.state);
    }

    @Test public void correctionCreatesNewVersion() throws Exception {
        EvoCoreDb.MemoryRecord row = db.add(scope, "Preferisco il blu", "profilo", "preference",
                MemoryPolicyGate.USER_CONFIRMED, "user", MemoryVault.OPERATIONAL, "test", 0L, 0L);
        EvoCoreDb.MemoryRecord corrected = db.correct(scope, row.id, "Preferisco il verde");
        assertTrue(corrected.id != row.id);
        assertEquals(row.id, corrected.supersedes);
        assertEquals("Preferisco il verde", db.list(scope, true).get(0).text);
    }

    @Test public void forgetHardDeletesRecord() throws Exception {
        EvoCoreDb.MemoryRecord row = db.add(scope, "Ricordo eliminabile", "profilo", "note",
                MemoryPolicyGate.USER_CONFIRMED, "user", MemoryVault.OPERATIONAL, "test", 0L, 0L);
        assertTrue(db.forget(scope, row.id));
        assertTrue(db.list(scope, true).isEmpty());
    }

    @Test public void expiredEphemeralMemoryIsPurged() throws Exception {
        db.add(scope, "Sono stressato oggi", "benessere", "wellbeing",
                MemoryPolicyGate.EPHEMERAL, "user", MemoryVault.SENSITIVE, "test",
                System.currentTimeMillis() - 1L, 0L);
        assertTrue(db.list(scope, true).isEmpty());
    }

    @Test public void forgettingCorrectionRemovesVisibleChain() throws Exception {
        EvoCoreDb.MemoryRecord original = db.add(scope, "Preferisco il blu", "profilo", "preference",
                MemoryPolicyGate.USER_CONFIRMED, "user", MemoryVault.OPERATIONAL, "test", 0L, 0L);
        EvoCoreDb.MemoryRecord corrected = db.correct(scope, original.id, "Preferisco il verde");
        assertTrue(db.forget(scope, corrected.id));
        assertTrue(db.list(scope, true).isEmpty());
    }

    @Test public void appContextIncludesPersonalButNotOtherAppMemory() throws Exception {
        String personalScope = "it.evocore.host:personal";
        EvoCoreDb.MemoryRecord personal = db.add(personalScope, "Profilo personale " + scope,
                "profilo", "preference", MemoryPolicyGate.USER_CONFIRMED, "user",
                MemoryVault.OPERATIONAL, "test", 0L, 0L);
        EvoCoreDb.MemoryRecord app = db.add(scope, "Contesto app " + scope,
                "app", "note", MemoryPolicyGate.USER_CONFIRMED, "user",
                MemoryVault.OPERATIONAL, "test", 0L, 0L);
        String otherScope = "test.other:" + UUID.randomUUID();
        EvoCoreDb.MemoryRecord other = db.add(otherScope, "Segreto altra app " + scope,
                "app", "note", MemoryPolicyGate.USER_CONFIRMED, "user",
                MemoryVault.OPERATIONAL, "test", 0L, 0L);
        try {
            String context = db.contextForAppAndPersonal(scope, 10, 10);
            assertTrue(context.contains(personal.text));
            assertTrue(context.contains(app.text));
            assertTrue(!context.contains(other.text));
        } finally {
            db.forget(personalScope, personal.id);
            db.forget(scope, app.id);
            db.forget(otherScope, other.id);
        }
    }

    @Test public void relevantOlderIdentitySurvivesManyNewerMemories() throws Exception {
        List<Long> ids = new java.util.ArrayList<>();
        try {
            EvoCoreDb.MemoryRecord identity = db.add(scope,
                    "Puoi chiamarmi Aurora e preferisco lavorare con compiti chiari",
                    "identita", "identity", MemoryPolicyGate.USER_CONFIRMED, "user",
                    MemoryVault.OPERATIONAL, "test", 0L, 0L);
            ids.add(identity.id);
            EvoCoreDb.MemoryRecord workStyle = db.add(scope,
                    "Preferisco lavorare su laboratori e progetti concreti",
                    "metodo", "preference", MemoryPolicyGate.USER_CONFIRMED, "user",
                    MemoryVault.OPERATIONAL, "test", 0L, 0L);
            ids.add(workStyle.id);
            for (int i = 0; i < 24; i++) {
                EvoCoreDb.MemoryRecord noise = db.add(scope,
                        "Nota tecnica non collegata numero " + i,
                        "archivio", "note", MemoryPolicyGate.USER_CONFIRMED, "user",
                        MemoryVault.OPERATIONAL, "test", 0L, 0L);
                ids.add(noise.id);
            }

            String context = db.contextFor(scope,
                    "DOMANDA ATTUALE: Cosa ricordi di me, quale nome puoi usare e come preferisco lavorare?",
                    4);

            assertTrue(context.contains("Puoi chiamarmi Aurora"));
            assertTrue(context.indexOf("Puoi chiamarmi Aurora")
                    < context.indexOf("Preferisco lavorare su laboratori"));
            assertTrue(!context.contains("Nota tecnica non collegata"));
        } finally {
            for (long id : ids) db.forget(scope, id);
        }
    }
}
