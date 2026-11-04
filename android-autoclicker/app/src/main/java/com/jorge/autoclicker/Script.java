package com.jorge.autoclicker;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Una secuencia de acciones con opciones de repetición. */
public class Script {
    public String name = "Mi script";
    /** Número de vueltas; 0 = infinito. */
    public int loops = 0;
    /** Pausa entre vueltas en ms. */
    public long loopDelay = 0;
    /** Desplazamiento aleatorio máximo en píxeles para cada toque. */
    public int jitterPx = 0;
    /** Variación aleatoria (%) aplicada a pausas. */
    public int randomDelayPct = 0;
    /** Detener tras N minutos; 0 = sin límite. */
    public int stopAfterMin = 0;
    public final List<Action> actions = new ArrayList<>();

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("loops", loops);
        o.put("loopDelay", loopDelay);
        o.put("jitterPx", jitterPx);
        o.put("randomDelayPct", randomDelayPct);
        o.put("stopAfterMin", stopAfterMin);
        JSONArray arr = new JSONArray();
        for (Action a : actions) arr.put(a.toJson());
        o.put("actions", arr);
        return o;
    }

    public String toJsonString() {
        try {
            return toJson().toString(2);
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static Script fromJson(String s) throws JSONException {
        JSONObject o = new JSONObject(s);
        Script sc = new Script();
        sc.name = o.optString("name", "Mi script");
        sc.loops = Math.max(0, o.optInt("loops", 0));
        sc.loopDelay = Math.max(0, o.optLong("loopDelay", 0));
        sc.jitterPx = Math.max(0, o.optInt("jitterPx", 0));
        sc.randomDelayPct = Math.max(0, Math.min(100, o.optInt("randomDelayPct", 0)));
        sc.stopAfterMin = Math.max(0, o.optInt("stopAfterMin", 0));
        JSONArray arr = o.optJSONArray("actions");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ao = arr.optJSONObject(i);
                if (ao != null) sc.actions.add(Action.fromJson(ao));
            }
        }
        return sc;
    }
}
