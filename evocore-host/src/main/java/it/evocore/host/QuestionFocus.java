package it.evocore.host;

final class QuestionFocus {
    private static final String CURRENT_QUESTION = "DOMANDA ATTUALE:";
    private static final String USER_QUESTION = "Domanda dell'utente:";

    private QuestionFocus() {
    }

    static String current(String prompt) {
        String value = prompt == null ? "" : prompt;
        String focused = afterLastMarker(value, CURRENT_QUESTION);
        if (!focused.isEmpty()) return focused;
        focused = afterLastMarker(value, USER_QUESTION);
        return focused.isEmpty() ? value.trim() : focused;
    }

    private static String afterLastMarker(String value, String marker) {
        int markerIndex = value.lastIndexOf(marker);
        if (markerIndex < 0) return "";
        int start = markerIndex + marker.length();
        int end = value.indexOf('\n', start);
        return (end < 0 ? value.substring(start) : value.substring(start, end)).trim();
    }
}
