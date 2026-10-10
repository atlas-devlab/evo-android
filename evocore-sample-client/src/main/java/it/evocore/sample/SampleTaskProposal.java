package it.evocore.sample;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SampleTaskProposal {
    private static final Pattern COMMAND = Pattern.compile(
            "(?i)^(?:ricordami(?:\\s+di)?|aggiungi|crea)\\s+(.+?)(?:\\s+(?:oggi\\s+)?alle\\s+(\\d{1,2})(?::(\\d{2}))?)\\s*[.!]?$");

    final String proposalId;
    final String title;
    final String due;

    SampleTaskProposal(String proposalId, String title, String due) {
        this.proposalId = proposalId;
        this.title = title;
        this.due = due;
    }

    static SampleTaskProposal parse(String question, String proposalId) {
        Matcher match = COMMAND.matcher(question == null ? "" : question.trim());
        if (!match.matches()) return null;
        int hour = Integer.parseInt(match.group(2));
        int minute = match.group(3) == null ? 0 : Integer.parseInt(match.group(3));
        if (hour > 23 || minute > 59) return null;
        String due = String.format(Locale.ITALY, "oggi, %02d:%02d", hour, minute);
        return new SampleTaskProposal(proposalId, match.group(1).trim(), due);
    }

    String toResultJson() {
        JSONObject output = new JSONObject();
        try {
            output.put("proposalId", proposalId);
            output.put("title", title);
            output.put("due", due);
            output.put("requiresUserConfirmation", true);
            output.put("message", "Proposta pronta: " + title + " - " + due);
        } catch (Exception ignored) {
        }
        return output.toString();
    }
}
