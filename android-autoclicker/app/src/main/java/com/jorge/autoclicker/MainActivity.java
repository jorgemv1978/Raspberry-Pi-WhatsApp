package com.jorge.autoclicker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity implements ClickService.Listener {

    private Script script;
    private TextView serviceStatus;
    private Button btnService, btnPanel, btnRun;
    private EditText etName, etLoops, etLoopDelay, etJitter, etRandom, etStopAfter;
    private LinearLayout actionsBox;
    private boolean bindingFields;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.app_name);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ClickService.listener = this;
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveFields();
        if (ClickService.listener == this) ClickService.listener = null;
    }

    @Override
    public void onServiceStateChanged() {
        saveFields();
        reload();
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        scroll.addView(root);

        // --- Servicio
        root.addView(header("1. Servicio de accesibilidad"));
        serviceStatus = new TextView(this);
        serviceStatus.setTextSize(15);
        root.addView(serviceStatus);
        btnService = button("Abrir ajustes de accesibilidad", v -> openAccessibilitySettings());
        root.addView(btnService);
        root.addView(note("Necesario para tocar la pantalla en otras apps. Busca «AutoClicker» en Accesibilidad "
                + "(a veces dentro de «Apps instaladas» o «Servicios descargados») y actívalo."));

        // --- Panel
        root.addView(header("2. Panel flotante"));
        btnPanel = button("Mostrar panel flotante", v -> togglePanel());
        root.addView(btnPanel);
        root.addView(note("Mantén pulsada la barra (o arrastra ⠿) para moverla · ▶/■ ejecutar/parar · ＋ toque · ⇢ deslizar · "
                + "● grabar gestos · ⌫ borrar la última acción · "
                + "◎ mostrar/ocultar marcadores · ⚙ abrir app · ✕ cerrar.\n"
                + "Arrastra los círculos numerados para colocarlos; tócalos para editar tipo, tiempos, repeticiones o eliminarlos. Volumen- detiene la ejecución."));

        // --- Opciones del script
        root.addView(header("3. Script"));
        etName = field(root, "Nombre", InputType.TYPE_CLASS_TEXT);
        etLoops = field(root, "Repeticiones del script (0 = infinito)", InputType.TYPE_CLASS_NUMBER);
        etLoopDelay = field(root, "Pausa entre repeticiones (ms)", InputType.TYPE_CLASS_NUMBER);
        etJitter = field(root, "Desplazamiento aleatorio de toques (px, anti-detección)", InputType.TYPE_CLASS_NUMBER);
        etRandom = field(root, "Variación aleatoria de pausas (%)", InputType.TYPE_CLASS_NUMBER);
        etStopAfter = field(root, "Detener tras (minutos, 0 = nunca)", InputType.TYPE_CLASS_NUMBER);

        // --- Acciones
        root.addView(header("4. Acciones"));
        actionsBox = new LinearLayout(this);
        actionsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(actionsBox);
        root.addView(button("＋ Añadir acción", v -> {
            saveFields();
            Action a = Action.of(Action.Type.TAP, 0, 0);
            android.graphics.Point sz = screenSize();
            a.x = a.x2 = sz.x / 2;
            a.y = a.y2 = sz.y / 2;
            ActionEditor.show(this, a, true, edited -> {
                script.actions.add(edited);
                commit();
            });
        }));

        LinearLayout row = new LinearLayout(this);
        row.addView(button("Guardar como…", v -> saveAsDialog()), weight());
        row.addView(button("Cargar…", v -> loadDialog()), weight());
        root.addView(row);
        LinearLayout row2 = new LinearLayout(this);
        row2.addView(button("Exportar JSON", v -> exportJson()), weight());
        row2.addView(button("Importar JSON", v -> importJson()), weight());
        root.addView(row2);
        root.addView(button("Vaciar acciones", v -> new AlertDialog.Builder(this)
                .setMessage("¿Borrar todas las acciones del script actual?")
                .setPositiveButton("Borrar", (d, w) -> {
                    script.actions.clear();
                    commit();
                })
                .setNegativeButton("Cancelar", null).show()));

        // --- Ejecutar
        root.addView(header("5. Ejecutar"));
        btnRun = button("▶ Ejecutar script", v -> toggleRun());
        root.addView(btnRun);
        root.addView(note("Al ejecutar desde aquí, la app se minimiza y el script comienza tras 3 segundos."));
        return scroll;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private TextView header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF1565C0);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(0xFF666666);
        t.setPadding(0, dp(2), 0, dp(4));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private EditText field(LinearLayout parent, String label, int inputType) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13);
        parent.addView(l);
        EditText e = new EditText(this);
        e.setInputType(inputType);
        e.setSingleLine(true);
        parent.addView(e);
        return e;
    }

    // ------------------------------------------------------------------ estado

    private static boolean isServiceEnabled(Context c) {
        String enabled = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName me = new ComponentName(c, ClickService.class);
        TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
        sp.setString(enabled);
        for (String s : sp) {
            ComponentName cn = ComponentName.unflattenFromString(s);
            if (me.equals(cn)) return true;
        }
        return false;
    }

    private void reload() {
        script = ScriptStore.loadCurrent(this);
        ClickService svc = ClickService.instance;
        boolean on = svc != null;
        if (on) {
            serviceStatus.setText("✅ Servicio activo");
            serviceStatus.setTextColor(0xFF2E7D32);
        } else if (isServiceEnabled(this)) {
            serviceStatus.setText("⏳ Servicio habilitado, conectando…");
            serviceStatus.setTextColor(0xFFF57C00);
        } else {
            serviceStatus.setText("❌ Servicio desactivado");
            serviceStatus.setTextColor(0xFFC62828);
        }
        btnPanel.setEnabled(on);
        btnRun.setEnabled(on);
        btnPanel.setText(on && svc.isPanelShown() ? "Ocultar panel flotante" : "Mostrar panel flotante");
        btnRun.setText(on && svc.isRunning() ? "■ Detener" : "▶ Ejecutar script");

        bindingFields = true;
        etName.setText(script.name);
        etLoops.setText(String.valueOf(script.loops));
        etLoopDelay.setText(String.valueOf(script.loopDelay));
        etJitter.setText(String.valueOf(script.jitterPx));
        etRandom.setText(String.valueOf(script.randomDelayPct));
        etStopAfter.setText(String.valueOf(script.stopAfterMin));
        bindingFields = false;
        renderActions();
    }

    private static long num(EditText e, long def) {
        try {
            return Long.parseLong(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** Copia los campos de opciones al script y lo guarda. */
    private void saveFields() {
        if (script == null || bindingFields) return;
        Script stored = ScriptStore.loadCurrent(this);
        // Conserva cambios de coordenadas hechos desde el panel flotante.
        script.actions.clear();
        script.actions.addAll(stored.actions);
        String n = etName.getText().toString().trim();
        script.name = n.isEmpty() ? "Mi script" : n;
        script.loops = (int) Math.max(0, num(etLoops, 0));
        script.loopDelay = Math.max(0, num(etLoopDelay, 0));
        script.jitterPx = (int) Math.max(0, num(etJitter, 0));
        script.randomDelayPct = (int) Math.min(100, Math.max(0, num(etRandom, 0)));
        script.stopAfterMin = (int) Math.max(0, num(etStopAfter, 0));
        ScriptStore.saveCurrent(this, script);
    }

    private void commit() {
        ScriptStore.saveCurrent(this, script);
        renderActions();
        ClickService svc = ClickService.instance;
        if (svc != null) svc.rebuildMarkers();
    }

    private void renderActions() {
        actionsBox.removeAllViews();
        if (script.actions.isEmpty()) {
            actionsBox.addView(note("Sin acciones. Añádelas aquí, con ＋/⇢ en el panel flotante o grabándolas con ●."));
            return;
        }
        for (int i = 0; i < script.actions.size(); i++) {
            final int idx = i;
            Action a = script.actions.get(i);
            TextView t = new TextView(this);
            t.setText((i + 1) + ". " + a.describe());
            t.setTextSize(14);
            t.setTextColor(Color.BLACK);
            int pd = dp(10);
            t.setPadding(pd, pd, pd, pd);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFFF1F5FB);
            bg.setCornerRadius(dp(8));
            t.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            t.setLayoutParams(lp);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setOnClickListener(v -> actionMenu(idx));
            actionsBox.addView(t);
        }
    }

    private void actionMenu(int idx) {
        saveFields();
        if (idx >= script.actions.size()) return;
        String[] opts = {"Editar", "Duplicar", "Subir", "Bajar", "Eliminar"};
        new AlertDialog.Builder(this)
                .setTitle((idx + 1) + ". " + script.actions.get(idx).type.label)
                .setItems(opts, (d, w) -> {
                    List<Action> l = script.actions;
                    switch (w) {
                        case 0:
                            ActionEditor.show(this, l.get(idx).copy(), false, edited -> {
                                l.set(idx, edited);
                                commit();
                            });
                            return;
                        case 1:
                            l.add(idx + 1, l.get(idx).copy());
                            break;
                        case 2:
                            if (idx > 0) l.add(idx - 1, l.remove(idx));
                            break;
                        case 3:
                            if (idx < l.size() - 1) l.add(idx + 1, l.remove(idx));
                            break;
                        case 4:
                            l.remove(idx);
                            break;
                    }
                    commit();
                }).show();
    }

    android.graphics.Point screenSize() {
        ClickService svc = ClickService.instance;
        if (svc != null) return svc.screenSize();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return new android.graphics.Point(dm.widthPixels, dm.heightPixels);
    }

    // ------------------------------------------------------------------ acciones de botones

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void togglePanel() {
        ClickService svc = ClickService.instance;
        if (svc == null) return;
        saveFields();
        if (svc.isPanelShown()) {
            svc.hidePanel();
        } else {
            svc.showPanel();
            moveTaskToBack(true);
        }
        reload();
    }

    private void toggleRun() {
        ClickService svc = ClickService.instance;
        if (svc == null) return;
        saveFields();
        if (svc.isRunning()) {
            svc.stop();
            return;
        }
        if (script.actions.isEmpty()) {
            Toast.makeText(this, "Añade al menos una acción", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!svc.isPanelShown()) svc.showPanel();
        moveTaskToBack(true);
        btnRun.postDelayed(() -> {
            ClickService s = ClickService.instance;
            if (s != null) s.start();
        }, 3000);
    }

    private void saveAsDialog() {
        saveFields();
        EditText e = new EditText(this);
        e.setText(script.name);
        new AlertDialog.Builder(this)
                .setTitle("Guardar script como")
                .setView(e)
                .setPositiveButton("Guardar", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (n.isEmpty()) return;
                    ScriptStore.saveAs(this, n, script);
                    Toast.makeText(this, "Guardado: " + n, Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void loadDialog() {
        saveFields();
        List<String> names = ScriptStore.savedNames(this);
        if (names.isEmpty()) {
            Toast.makeText(this, "No hay scripts guardados", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] arr = names.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Cargar script")
                .setItems(arr, (d, w) -> {
                    Script s = ScriptStore.loadSaved(this, arr[w]);
                    if (s != null) {
                        script = s;
                        ScriptStore.saveCurrent(this, s);
                        reload();
                        ClickService svc = ClickService.instance;
                        if (svc != null) svc.rebuildMarkers();
                    }
                })
                .setNeutralButton("Eliminar…", (d, w) -> new AlertDialog.Builder(this)
                        .setTitle("Eliminar script guardado")
                        .setItems(arr, (d2, w2) -> {
                            ScriptStore.deleteSaved(this, arr[w2]);
                            Toast.makeText(this, "Eliminado: " + arr[w2], Toast.LENGTH_SHORT).show();
                        }).show())
                .setNegativeButton("Cancelar", null).show();
    }

    private void exportJson() {
        saveFields();
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("autoclicker-script", script.toJsonString()));
        Toast.makeText(this, "JSON copiado al portapapeles", Toast.LENGTH_SHORT).show();
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, script.toJsonString());
        try {
            startActivity(Intent.createChooser(send, "Compartir script"));
        } catch (Exception ignored) {
        }
    }

    private void importJson() {
        saveFields();
        EditText e = new EditText(this);
        e.setHint("Pega aquí el JSON del script");
        e.setMinLines(6);
        e.setGravity(Gravity.TOP);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence txt = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (txt != null && txt.toString().trim().startsWith("{")) e.setText(txt);
        }
        new AlertDialog.Builder(this)
                .setTitle("Importar script")
                .setView(e)
                .setPositiveButton("Importar", (d, w) -> {
                    try {
                        script = Script.fromJson(e.getText().toString());
                        ScriptStore.saveCurrent(this, script);
                        reload();
                        ClickService svc = ClickService.instance;
                        if (svc != null) svc.rebuildMarkers();
                        Toast.makeText(this, "Importadas " + script.actions.size() + " acciones", Toast.LENGTH_SHORT).show();
                    } catch (Exception ex) {
                        Toast.makeText(this, "JSON no válido: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancelar", null).show();
    }
}
