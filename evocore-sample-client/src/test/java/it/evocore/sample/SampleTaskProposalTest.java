// SPDX-License-Identifier: AGPL-3.0-only
package it.evocore.sample;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SampleTaskProposalTest {

    @Test
    public void parsesValidReminder() {
        SampleTaskProposal result = SampleTaskProposal.parse(
                "ricordami di comprare il latte alle 14:30",
                "test-1");

        assertNotNull(result);
        assertEquals("test-1", result.proposalId);
        assertEquals("comprare il latte", result.title);
        assertEquals("oggi, 14:30", result.due);
    }

    @Test
    public void parsesSupportedCommandPrefixes() {
        SampleTaskProposal add = SampleTaskProposal.parse(
                "aggiungi comprare il pane alle 9", "test-2");
        SampleTaskProposal create = SampleTaskProposal.parse(
                "crea preparare la cena alle 18:15", "test-3");

        assertNotNull(add);
        assertEquals("comprare il pane", add.title);
        assertEquals("oggi, 09:00", add.due);

        assertNotNull(create);
        assertEquals("preparare la cena", create.title);
        assertEquals("oggi, 18:15", create.due);
    }

    @Test
    public void acceptsSurroundingWhitespaceAndTrailingPunctuation() {
        SampleTaskProposal withPeriod = SampleTaskProposal.parse(
                "  ricordami di comprare il pane alle 8:05.  ", "test-4");
        SampleTaskProposal withExclamation = SampleTaskProposal.parse(
                "crea preparare la cena oggi alle 19:30!", "test-5");

        assertNotNull(withPeriod);
        assertEquals("comprare il pane", withPeriod.title);
        assertEquals("oggi, 08:05", withPeriod.due);

        assertNotNull(withExclamation);
        assertEquals("preparare la cena", withExclamation.title);
        assertEquals("oggi, 19:30", withExclamation.due);
    }

    @Test
    public void acceptsValidTimeBoundaries() {
        SampleTaskProposal start = SampleTaskProposal.parse(
                "ricordami di iniziare alle 0:00", "test-6");
        SampleTaskProposal end = SampleTaskProposal.parse(
                "ricordami di finire alle 23:59", "test-7");

        assertNotNull(start);
        assertEquals("oggi, 00:00", start.due);

        assertNotNull(end);
        assertEquals("oggi, 23:59", end.due);
    }

    @Test
    public void rejectsMalformedCommands() {
        assertNull(SampleTaskProposal.parse(null, "test-8"));
        assertNull(SampleTaskProposal.parse("", "test-9"));
        assertNull(SampleTaskProposal.parse(
                "ricordami di comprare il pane", "test-10"));
        assertNull(SampleTaskProposal.parse(
                "fai qualcosa alle 14:30", "test-11"));
    }

    @Test
    public void rejectsOutOfRangeHoursAndMinutes() {
        assertNull(SampleTaskProposal.parse(
                "ricordami di iniziare alle 24:00", "test-13"));
        assertNull(SampleTaskProposal.parse(
                "ricordami di iniziare alle 12:60", "test-14"));
    }

    @Test
    public void serializedProposalRequiresUserConfirmation() throws Exception {
        SampleTaskProposal result = SampleTaskProposal.parse(
                "ricordami di comprare il latte alle 14:30", "test-15");

        assertNotNull(result);

        JSONObject json = new JSONObject(result.toResultJson());

        assertTrue(json.getBoolean("requiresUserConfirmation"));
        assertEquals("test-15", json.getString("proposalId"));
        assertEquals("comprare il latte", json.getString("title"));
        assertEquals("oggi, 14:30", json.getString("due"));
    }
}
