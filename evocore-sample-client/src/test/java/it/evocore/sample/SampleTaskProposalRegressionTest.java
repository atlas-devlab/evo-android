// SPDX-License-Identifier: AGPL-3.0-only
package it.evocore.sample;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class SampleTaskProposalRegressionTest {

    @Test
    public void rejectsReminderIntroducerWithoutTask() {
        String[] commands = {
                "ricordami di alle 14:30",
                "  RICORDAMI DI alle 14:30!  ",
                "ricordami   di   alle 14:30.",
                "ricordami di\talle 14:30"
        };
        for (String command : commands) {
            assertNull(command, SampleTaskProposal.parse(command, "missing-task"));
        }
    }

    @Test
    public void rejectsWhitespaceOnlyTasksForEveryPrefix() {
        String[] commands = {
                "ricordami    alle 14:30",
                "aggiungi    alle 14:30",
                "crea\t \talle 14:30"
        };
        for (String command : commands) {
            assertNull(command, SampleTaskProposal.parse(command, "blank-task"));
        }
    }

    @Test
    public void preservesTaskWordsBeginningWithDi() {
        assertProposal("ricordami dipingere alle 14:30", "dipingere", "oggi, 14:30");
        assertProposal("ricordami discutere di acqua alle 14:30",
                "discutere di acqua", "oggi, 14:30");
    }

    @Test
    public void preservesIntroducerAndTaskWordsInTheirOwnPositions() {
        assertProposal("ricordami di discutere di acqua oggi alle 14:30!",
                "discutere di acqua", "oggi, 14:30");
        assertProposal("ricordami di comprare oggi il latte alle 14:30",
                "comprare oggi il latte", "oggi, 14:30");
        assertProposal("aggiungi di tutto alle 9", "di tutto", "oggi, 09:00");
    }

    private static void assertProposal(String command, String title, String due) {
        SampleTaskProposal result = SampleTaskProposal.parse(command, "valid-task");
        assertNotNull(command, result);
        assertEquals(command, title, result.title);
        assertEquals(command, due, result.due);
    }
}
