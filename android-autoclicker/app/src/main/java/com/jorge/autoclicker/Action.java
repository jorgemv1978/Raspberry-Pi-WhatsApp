package com.jorge.autoclicker;

import android.accessibilityservice.AccessibilityService;

import org.json.JSONException;
import org.json.JSONObject;

/** Una acción de un script de automatización. */
public class Action {

    public enum Type {
        TAP("Toque"),
        DOUBLE_TAP("Doble toque"),
        LONG_PRESS("Pulsación larga"),
        SWIPE("Deslizar"),
        PATH("Gesto libre (trazo)"),
        PINCH_IN("Pellizcar (alejar)"),
        PINCH_OUT("Separar dedos (acercar)"),
        MULTI_TAP("Toque con 2 dedos"),
        CLICK_TEXT("Pulsar elemento por texto / id"),
        INPUT_TEXT("Escribir texto"),
        GLOBAL("Acción del sistema"),
        LAUNCH_APP("Abrir app"),
        WAIT("Esperar");

        public final String label;

        Type(String label) {
            this.label = label;
        }

        public boolean hasPoint() {
            switch (this) {
                case TAP: case DOUBLE_TAP: case LONG_PRESS: case SWIPE:
                case PINCH_IN: case PINCH_OUT: case MULTI_TAP:
                    return true;
                default:
                    return false;
            }
        }

        public boolean hasSecondPoint() {
            return this == SWIPE || this == PINCH_IN || this == PINCH_OUT || this == MULTI_TAP;
        }

        public boolean hasDuration() {
            return this != INPUT_TEXT && this != GLOBAL && this != LAUNCH_APP && this != DOUBLE_TAP;
        }

        public boolean hasText() {
            return this == CLICK_TEXT || this == INPUT_TEXT || this == LAUNCH_APP;
        }
    }

    public static final String[] GLOBAL_NAMES = {
            "Atrás", "Inicio", "Apps recientes", "Notificaciones", "Ajustes rápidos",
            "Menú de apagado", "Bloquear pantalla (Android 9+)", "Captura de pantalla (Android 9+)"
    };
    public static final int[] GLOBAL_IDS = {
            AccessibilityService.GLOBAL_ACTION_BACK,
            AccessibilityService.GLOBAL_ACTION_HOME,
            AccessibilityService.GLOBAL_ACTION_RECENTS,
            AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS,
            AccessibilityService.GLOBAL_ACTION_POWER_DIALOG,
            8, // GLOBAL_ACTION_LOCK_SCREEN
            9  // GLOBAL_ACTION_TAKE_SCREENSHOT
    };

    public Type type = Type.TAP;
    public int x, y, x2, y2;
    /** Duración del gesto en ms (o tiempo de espera / timeout según el tipo). */
    public long duration = 50;
    /** Pausa tras la acción en ms. */
    public long delayAfter = 500;
    /** Veces que se repite la acción seguida. */
    public int repeat = 1;
    public String text = "";
    /** Trazo para PATH: "x,y;x,y;..." */
    public String path = "";
    public int global = AccessibilityService.GLOBAL_ACTION_BACK;

    public static Action of(Type t, int x, int y) {
        Action a = new Action();
        a.type = t;
        a.x = x;
        a.y = y;
        a.x2 = x;
        a.y2 = y;
        a.applyDefaultDuration();
        return a;
    }

    public void applyDefaultDuration() {
        switch (type) {
            case TAP: case MULTI_TAP: duration = 50; break;
            case LONG_PRESS: duration = 800; break;
            case SWIPE: case PINCH_IN: case PINCH_OUT: duration = 400; break;
            case PATH: duration = 600; break;
            case CLICK_TEXT: duration = 3000; break;
            case WAIT: duration = 1000; break;
            default: break;
        }
    }

    public static String globalName(int id) {
        for (int i = 0; i < GLOBAL_IDS.length; i++) if (GLOBAL_IDS[i] == id) return GLOBAL_NAMES[i];
        return "#" + id;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(type.label);
        switch (type) {
            case TAP: case DOUBLE_TAP: case LONG_PRESS:
                sb.append(" (").append(x).append(", ").append(y).append(")");
                break;
            case SWIPE: case PINCH_IN: case PINCH_OUT: case MULTI_TAP:
                sb.append(" (").append(x).append(", ").append(y).append(") → (")
                        .append(x2).append(", ").append(y2).append(")");
                break;
            case PATH:
                int n = path.isEmpty() ? 0 : path.split(";").length;
                sb.append(" · ").append(n).append(" puntos");
                break;
            case CLICK_TEXT: case INPUT_TEXT: case LAUNCH_APP:
                sb.append(": \"").append(text).append("\"");
                break;
            case GLOBAL:
                sb.append(": ").append(globalName(global));
                break;
            default:
                break;
        }
        if (type.hasDuration()) {
            sb.append(type == CLICK_TEXT ? " · espera máx " : " · ").append(duration).append(" ms");
        }
        if (repeat > 1) sb.append(" · x").append(repeat);
        sb.append(" · pausa ").append(delayAfter).append(" ms");
        return sb.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", type.name());
        o.put("x", x);
        o.put("y", y);
        o.put("x2", x2);
        o.put("y2", y2);
        o.put("duration", duration);
        o.put("delayAfter", delayAfter);
        o.put("repeat", repeat);
        o.put("text", text);
        o.put("path", path);
        o.put("global", global);
        return o;
    }

    public static Action fromJson(JSONObject o) {
        Action a = new Action();
        try {
            a.type = Type.valueOf(o.optString("type", "TAP"));
        } catch (IllegalArgumentException e) {
            a.type = Type.TAP;
        }
        a.x = o.optInt("x");
        a.y = o.optInt("y");
        a.x2 = o.optInt("x2");
        a.y2 = o.optInt("y2");
        a.duration = o.optLong("duration", 50);
        a.delayAfter = o.optLong("delayAfter", 500);
        a.repeat = Math.max(1, o.optInt("repeat", 1));
        a.text = o.optString("text", "");
        a.path = o.optString("path", "");
        a.global = o.optInt("global", AccessibilityService.GLOBAL_ACTION_BACK);
        return a;
    }

    public Action copy() {
        try {
            return fromJson(toJson());
        } catch (JSONException e) {
            return new Action();
        }
    }
}
