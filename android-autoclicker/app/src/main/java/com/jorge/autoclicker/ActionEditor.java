package com.jorge.autoclicker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Diálogo para crear / editar una acción. */
public final class ActionEditor {

    public interface Callback {
        void onSaved(Action a);
    }

    private ActionEditor() {}

    public static void show(Activity act, Action action, boolean isNew, Callback cb) {
        float dens = act.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dens);

        ScrollView scroll = new ScrollView(act);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        scroll.addView(box);

        Action.Type[] types = Action.Type.values();
        String[] typeNames = new String[types.length];
        for (int i = 0; i < types.length; i++) typeNames[i] = types[i].label;
        Spinner spType = new Spinner(act);
        spType.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, typeNames));
        spType.setSelection(action.type.ordinal());
        box.addView(label(act, "Tipo de acción"));
        box.addView(spType);

        TextView lPoint = label(act, "Punto (X, Y) en píxeles");
        LinearLayout rowP1 = new LinearLayout(act);
        EditText ex = num(act, action.x), ey = num(act, action.y);
        rowP1.addView(ex, weight());
        rowP1.addView(ey, weight());
        TextView lPoint2 = label(act, "Punto final (X2, Y2)");
        LinearLayout rowP2 = new LinearLayout(act);
        EditText ex2 = num(act, action.x2), ey2 = num(act, action.y2);
        rowP2.addView(ex2, weight());
        rowP2.addView(ey2, weight());
        TextView pointHint = hint(act, "Consejo: también puedes colocar el punto arrastrando su marcador en el panel flotante.");

        TextView lPath = label(act, "Trazo: x,y;x,y;… (se rellena al grabar)");
        EditText ePath = new EditText(act);
        ePath.setText(action.path);
        ePath.setMinLines(2);

        TextView lDur = label(act, "Duración (ms)");
        EditText eDur = num(act, action.duration);

        TextView lText = label(act, "Texto");
        EditText eText = new EditText(act);
        eText.setText(action.text);
        Button pickApp = new Button(act);
        pickApp.setText("Elegir app instalada…");
        pickApp.setAllCaps(false);
        pickApp.setOnClickListener(v -> pickApp(act, eText));

        TextView lGlobal = label(act, "Acción del sistema");
        Spinner spGlobal = new Spinner(act);
        spGlobal.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_spinner_dropdown_item, Action.GLOBAL_NAMES));
        for (int i = 0; i < Action.GLOBAL_IDS.length; i++) if (Action.GLOBAL_IDS[i] == action.global) spGlobal.setSelection(i);

        EditText eRepeat = num(act, action.repeat);
        EditText eDelay = num(act, action.delayAfter);

        for (View v : new View[]{lPoint, rowP1, lPoint2, rowP2, pointHint, lPath, ePath, lDur, eDur,
                lText, eText, pickApp, lGlobal, spGlobal}) {
            box.addView(v);
        }
        box.addView(label(act, "Repetir esta acción (veces seguidas)"));
        box.addView(eRepeat);
        box.addView(label(act, "Pausa después (ms)"));
        box.addView(eDelay);

        final Action.Type[] lastType = {action.type};
        Runnable refresh = () -> {
            Action.Type t = types[spType.getSelectedItemPosition()];
            vis(t.hasPoint(), lPoint, rowP1, pointHint);
            vis(t.hasSecondPoint(), lPoint2, rowP2);
            vis(t == Action.Type.PATH, lPath, ePath);
            vis(t.hasDuration(), lDur, eDur);
            vis(t.hasText(), lText, eText);
            vis(t == Action.Type.LAUNCH_APP, pickApp);
            vis(t == Action.Type.GLOBAL, lGlobal, spGlobal);
            switch (t) {
                case CLICK_TEXT:
                    lText.setText("Texto visible del botón/elemento (o «id:paquete:id/nombre»)");
                    lDur.setText("Tiempo máximo esperando a que aparezca (ms)");
                    break;
                case INPUT_TEXT:
                    lText.setText("Texto a escribir en el campo enfocado");
                    break;
                case LAUNCH_APP:
                    lText.setText("Paquete de la app (p. ej. com.whatsapp)");
                    break;
                case WAIT:
                    lDur.setText("Tiempo de espera (ms)");
                    break;
                case PINCH_IN: case PINCH_OUT: case MULTI_TAP:
                    lPoint.setText("Dedo 1 (X, Y)");
                    lPoint2.setText("Dedo 2 (X2, Y2)");
                    lDur.setText("Duración (ms)");
                    break;
                case SWIPE:
                    lPoint.setText("Inicio (X, Y)");
                    lPoint2.setText("Fin (X2, Y2)");
                    lDur.setText("Duración del deslizamiento (ms)");
                    break;
                default:
                    lPoint.setText("Punto (X, Y) en píxeles");
                    lDur.setText("Duración de la pulsación (ms)");
                    break;
            }
            if (t != lastType[0]) {
                Action tmp = new Action();
                tmp.type = t;
                tmp.applyDefaultDuration();
                eDur.setText(String.valueOf(tmp.duration));
                lastType[0] = t;
            }
        };
        spType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refresh.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        refresh.run();

        new AlertDialog.Builder(act)
                .setTitle(isNew ? "Nueva acción" : "Editar acción")
                .setView(scroll)
                .setPositiveButton("Guardar", (d, w) -> {
                    Action a = action;
                    a.type = types[spType.getSelectedItemPosition()];
                    a.x = (int) parse(ex, a.x);
                    a.y = (int) parse(ey, a.y);
                    a.x2 = (int) parse(ex2, a.x2);
                    a.y2 = (int) parse(ey2, a.y2);
                    a.path = ePath.getText().toString().trim();
                    a.duration = Math.max(0, parse(eDur, a.duration));
                    a.text = eText.getText().toString();
                    a.global = Action.GLOBAL_IDS[spGlobal.getSelectedItemPosition()];
                    a.repeat = (int) Math.max(1, parse(eRepeat, 1));
                    a.delayAfter = Math.max(0, parse(eDelay, a.delayAfter));
                    cb.onSaved(a);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private static void pickApp(Activity act, EditText target) {
        PackageManager pm = act.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        List<String[]> apps = new ArrayList<>();
        for (ResolveInfo ri : infos) {
            apps.add(new String[]{String.valueOf(ri.loadLabel(pm)), ri.activityInfo.packageName});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i)[0] + "\n" + apps.get(i)[1];
        new AlertDialog.Builder(act)
                .setTitle("Elegir app")
                .setItems(labels, (d, w) -> target.setText(apps.get(w)[1]))
                .show();
    }

    private static void vis(boolean show, View... vs) {
        for (View v : vs) v.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static TextView label(Activity act, String s) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(13);
        t.setPadding(0, (int) (8 * act.getResources().getDisplayMetrics().density), 0, 0);
        return t;
    }

    private static TextView hint(Activity act, String s) {
        TextView t = label(act, s);
        t.setTextSize(12);
        t.setTextColor(0xFF777777);
        return t;
    }

    private static EditText num(Activity act, long v) {
        EditText e = new EditText(act);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        e.setText(String.valueOf(v));
        return e;
    }

    private static long parse(EditText e, long def) {
        try {
            return Long.parseLong(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
