package it.evocore.sample;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SampleTaskStore {
    static final class Task {
        final String id;
        final String title;
        final String due;

        Task(String id, String title, String due) {
            this.id = id;
            this.title = title;
            this.due = due;
        }
    }

    private static final String PREFS = "sample_tasks";
    private static final String KEY_ROWS = "rows";
    private final SharedPreferences preferences;

    SampleTaskStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized List<Task> list() {
        ArrayList<Task> rows = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_ROWS, "[]"));
            for (int index = 0; index < array.length(); index++) {
                JSONObject row = array.optJSONObject(index);
                if (row == null) continue;
                String id = row.optString("id", "");
                String title = row.optString("title", "");
                if (!id.isEmpty() && !title.isEmpty()) {
                    rows.add(new Task(id, title, row.optString("due", "")));
                }
            }
        } catch (Exception ignored) {
        }
        return Collections.unmodifiableList(rows);
    }

    synchronized boolean addIfAbsent(String id, String title, String due) {
        if (id == null || id.isEmpty() || title == null || title.trim().isEmpty()) return false;
        ArrayList<Task> rows = new ArrayList<>(list());
        for (Task row : rows) {
            if (id.equals(row.id)) return true;
        }
        rows.add(new Task(id, title.trim(), due == null ? "" : due.trim()));
        JSONArray array = new JSONArray();
        try {
            for (Task row : rows) {
                JSONObject value = new JSONObject();
                value.put("id", row.id);
                value.put("title", row.title);
                value.put("due", row.due);
                array.put(value);
            }
        } catch (Exception ignored) {
            return false;
        }
        return preferences.edit().putString(KEY_ROWS, array.toString()).commit();
    }

    synchronized String contextJson() {
        JSONArray rows = new JSONArray();
        try {
            for (Task task : list()) {
                JSONObject row = new JSONObject();
                row.put("id", task.id);
                row.put("title", task.title);
                row.put("due", task.due);
                rows.put(row);
            }
            JSONObject context = new JSONObject();
            context.put("date", java.time.LocalDate.now().toString());
            context.put("timezone", java.time.ZoneId.systemDefault().getId());
            context.put("visibleTasks", rows);
            context.put("visibleCount", rows.length());
            return context.toString();
        } catch (Exception ignored) {
            return "{\"visibleTasks\":[],\"visibleCount\":0}";
        }
    }
}
