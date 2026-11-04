package com.jorge.autoclicker;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Persistencia del script actual y de los scripts guardados. */
public final class ScriptStore {
    private static final String PREFS = "scripts";
    private static final String CURRENT = "current";
    private static final String SAVED_PREFIX = "saved:";

    private ScriptStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Script loadCurrent(Context c) {
        String s = prefs(c).getString(CURRENT, null);
        if (s != null) {
            try {
                return Script.fromJson(s);
            } catch (Exception ignored) {
            }
        }
        return new Script();
    }

    public static void saveCurrent(Context c, Script script) {
        prefs(c).edit().putString(CURRENT, script.toJsonString()).apply();
    }

    public static void saveAs(Context c, String name, Script script) {
        script.name = name;
        prefs(c).edit()
                .putString(SAVED_PREFIX + name, script.toJsonString())
                .putString(CURRENT, script.toJsonString())
                .apply();
    }

    public static Script loadSaved(Context c, String name) {
        String s = prefs(c).getString(SAVED_PREFIX + name, null);
        if (s == null) return null;
        try {
            return Script.fromJson(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static void deleteSaved(Context c, String name) {
        prefs(c).edit().remove(SAVED_PREFIX + name).apply();
    }

    public static List<String> savedNames(Context c) {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            if (e.getKey().startsWith(SAVED_PREFIX)) names.add(e.getKey().substring(SAVED_PREFIX.length()));
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }
}
