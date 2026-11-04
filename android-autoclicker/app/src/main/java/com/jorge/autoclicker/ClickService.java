package com.jorge.autoclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Servicio de accesibilidad: ejecuta los gestos, muestra el panel flotante,
 * los marcadores arrastrables y el modo grabación.
 */
public class ClickService extends AccessibilityService {

    public interface Listener {
        void onServiceStateChanged();
    }

    public static volatile ClickService instance;
    public static Listener listener;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private WindowManager wm;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView btnPlay, btnRecord, statusView;
    private final List<View> editButtons = new ArrayList<>();

    private final List<View> markers = new ArrayList<>();
    private boolean markersVisible = true;
    private View markerMenu;

    private volatile boolean running;
    private Thread worker;

    private View recordView;
    private Script recordScript;
    private long lastRecordUp;

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        notifyListener();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        stop();
        stopRecording(false);
        hidePanel();
        instance = null;
        notifyListener();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No necesitamos procesar eventos; se usa el árbol de ventanas bajo demanda.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (running && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                stop();
                toast("Ejecución detenida");
            }
            return true;
        }
        return false;
    }

    private void notifyListener() {
        main.post(() -> {
            if (listener != null) listener.onServiceStateChanged();
        });
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPanelShown() {
        return panel != null;
    }

    // ------------------------------------------------------------------ utilidades

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @SuppressWarnings("deprecation")
    public Point screenSize() {
        Point p = new Point();
        if (wm != null) {
            wm.getDefaultDisplay().getRealSize(p);
        } else {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            p.set(dm.widthPixels, dm.heightPixels);
        }
        return p;
    }

    private WindowManager.LayoutParams overlayParams(int w, int h, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    private void safeRemove(View v) {
        if (v == null) return;
        try {
            wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ panel flotante

    public void showPanel() {
        if (panel != null || wm == null) return;
        panel = new DragPanel(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(4);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE0202124);
        bg.setCornerRadius(dp(24));
        panel.setBackground(bg);

        TextView handle = panelButton("⠿");
        btnPlay = panelButton("▶");
        TextView add = panelButton("＋");
        TextView swipe = panelButton("⇢");
        btnRecord = panelButton("●");
        TextView undo = panelButton("⌫");
        TextView eye = panelButton("◎");
        TextView settings = panelButton("⚙");
        TextView close = panelButton("✕");
        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(12);
        statusView.setSingleLine(true);
        statusView.setMaxWidth(dp(120));
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusView.setPadding(dp(2), 0, dp(10), 0);
        statusView.setVisibility(View.GONE);

        btnPlay.setOnClickListener(v -> {
            if (running) stop();
            else start();
        });
        add.setOnClickListener(v -> addActionAtCenter(Action.Type.TAP));
        swipe.setOnClickListener(v -> addActionAtCenter(Action.Type.SWIPE));
        undo.setOnClickListener(v -> deleteLastAction());
        btnRecord.setOnClickListener(v -> {
            if (recordView != null) stopRecording(true);
            else startRecording();
        });
        eye.setOnClickListener(v -> {
            markersVisible = !markersVisible;
            rebuildMarkers();
        });
        settings.setOnClickListener(v -> {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        });
        close.setOnClickListener(v -> hidePanel());

        panel.addView(handle);
        panel.addView(btnPlay);
        panel.addView(add);
        panel.addView(swipe);
        panel.addView(btnRecord);
        panel.addView(undo);
        panel.addView(eye);
        panel.addView(settings);
        panel.addView(close);
        panel.addView(statusView);
        editButtons.clear();
        editButtons.add(add);
        editButtons.add(swipe);
        editButtons.add(undo);
        editButtons.add(eye);
        editButtons.add(settings);
        editButtons.add(close);

        panelLp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true);
        panelLp.x = dp(8);
        panelLp.y = dp(120);
        makeDraggable(handle, panel, panelLp, null, null);
        wm.addView(panel, panelLp);
        markersVisible = true;
        rebuildMarkers();
        updatePanel();
        notifyListener();
    }

    public void hidePanel() {
        removeMarkers();
        safeRemove(panel);
        panel = null;
        notifyListener();
    }

    private TextView panelButton(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        int s = dp(38);
        t.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        if (tv.resourceId != 0) t.setBackgroundResource(tv.resourceId);
        return t;
    }

    private void updatePanel() {
        main.post(() -> {
            if (panel == null) return;
            btnPlay.setText(running ? "■" : "▶");
            btnPlay.setTextColor(running ? 0xFFFF5252 : 0xFF69F0AE);
            btnRecord.setTextColor(recordView != null ? 0xFFFF5252 : Color.WHITE);
            // Mientras se ejecuta, el panel se reduce a ⠿ ■ y un estado corto.
            int editVis = running ? View.GONE : View.VISIBLE;
            for (View b : editButtons) b.setVisibility(editVis);
            btnRecord.setVisibility(running ? View.GONE : View.VISIBLE);
            boolean showStatus = running || recordView != null;
            statusView.setVisibility(showStatus ? View.VISIBLE : View.GONE);
            if (!showStatus) statusView.setText("");
        });
    }

    private void setStatus(String s) {
        main.post(() -> {
            if (statusView != null) statusView.setText(s);
        });
    }

    /** Re-añade el panel para que quede por encima de otras capas. */
    private void bringPanelToFront() {
        if (panel == null) return;
        safeRemove(panel);
        wm.addView(panel, panelLp);
    }

    private interface DragEnd {
        void onDragEnd(View v);
    }

    private interface Tap {
        void onTap(View v);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void makeDraggable(View touchTarget, View window, WindowManager.LayoutParams lp, DragEnd end, Tap tap) {
        touchTarget.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = lp.x;
                        startY = lp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dx) > 4 || Math.abs(dy) > 4) moved = true;
                        lp.x = startX + dx;
                        lp.y = startY + dy;
                        try {
                            wm.updateViewLayout(window, lp);
                        } catch (Exception ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved && tap != null) tap.onTap(window);
                        else if (end != null) end.onDragEnd(window);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        if (end != null) end.onDragEnd(window);
                        return true;
                }
                return false;
            }
        });
    }

    // ------------------------------------------------------------------ marcadores

    private void addActionAtCenter(Action.Type type) {
        Script s = ScriptStore.loadCurrent(this);
        Point sz = screenSize();
        Action a = Action.of(type, sz.x / 2, sz.y / 2);
        if (type.hasSecondPoint()) {
            a.x = sz.x / 2 - sz.x / 4;
            a.x2 = sz.x / 2 + sz.x / 4;
        }
        s.actions.add(a);
        ScriptStore.saveCurrent(this, s);
        markersVisible = true;
        rebuildMarkers();
        notifyListener();
    }

    private void removeMarkers() {
        closeMarkerMenu();
        for (View m : markers) safeRemove(m);
        markers.clear();
    }

    private void closeMarkerMenu() {
        safeRemove(markerMenu);
        markerMenu = null;
    }

    /** Editor flotante al tocar un marcador: tipo, coordenadas, tiempos y eliminar. */
    private void showMarkerEditor(int index) {
        closeMarkerMenu();
        Script script = ScriptStore.loadCurrent(this);
        if (index >= script.actions.size()) return;
        Action a = script.actions.get(index);
        Context ctx = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
        Point sz = screenSize();

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, dp(8));

        TextView title = new TextView(ctx);
        title.setText("Acción " + (index + 1));
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        box.addView(title);

        // Tipos compatibles con los puntos de este marcador.
        Action.Type[] options = a.type.hasSecondPoint()
                ? new Action.Type[]{Action.Type.SWIPE, Action.Type.PINCH_IN, Action.Type.PINCH_OUT, Action.Type.MULTI_TAP}
                : new Action.Type[]{Action.Type.TAP, Action.Type.DOUBLE_TAP, Action.Type.LONG_PRESS};
        final Action.Type[] chosen = {a.type};
        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.VERTICAL);
        box.addView(editorLabel(ctx, "Tipo"));
        box.addView(chips);

        EditText ex = editorNum(ctx, a.x), ey = editorNum(ctx, a.y);
        EditText ex2 = editorNum(ctx, a.x2), ey2 = editorNum(ctx, a.y2);
        EditText eDur = editorNum(ctx, a.duration);
        EditText eRep = editorNum(ctx, a.repeat);
        EditText eDelay = editorNum(ctx, a.delayAfter);

        box.addView(editorLabel(ctx, a.type.hasSecondPoint() ? "Punto A (X, Y)" : "Posición (X, Y)"));
        box.addView(editorRow(ctx, ex, ey));
        if (a.type.hasSecondPoint()) {
            box.addView(editorLabel(ctx, "Punto B (X, Y)"));
            box.addView(editorRow(ctx, ex2, ey2));
        }
        TextView lDur = editorLabel(ctx, "Duración (ms)");
        box.addView(lDur);
        box.addView(eDur);
        box.addView(editorLabel(ctx, "Repetir (veces seguidas)"));
        box.addView(eRep);
        box.addView(editorLabel(ctx, "Pausa después (ms)"));
        box.addView(eDelay);

        Runnable renderChips = new Runnable() {
            @Override
            public void run() {
                chips.removeAllViews();
                LinearLayout row = null;
                for (int i = 0; i < options.length; i++) {
                    if (i % 2 == 0) {
                        row = new LinearLayout(ctx);
                        chips.addView(row);
                    }
                    Action.Type t = options[i];
                    TextView c = new TextView(ctx);
                    c.setText(t.label);
                    c.setTextSize(14);
                    c.setGravity(Gravity.CENTER);
                    c.setSingleLine(true);
                    c.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    c.setPadding(dp(8), dp(8), dp(8), dp(8));
                    boolean sel = t == chosen[0];
                    GradientDrawable d = new GradientDrawable();
                    d.setCornerRadius(dp(18));
                    d.setColor(sel ? 0xFF1E88E5 : 0x00000000);
                    d.setStroke(dp(1), sel ? 0xFF1E88E5 : 0xFF777777);
                    c.setBackground(d);
                    c.setTextColor(Color.WHITE);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                    c.setOnClickListener(v -> {
                        if (chosen[0] != t) {
                            chosen[0] = t;
                            Action tmp = new Action();
                            tmp.type = t;
                            tmp.applyDefaultDuration();
                            eDur.setText(String.valueOf(tmp.duration));
                        }
                        run();
                    });
                    row.addView(c, lp);
                }
                boolean dur = chosen[0].hasDuration();
                lDur.setVisibility(dur ? View.VISIBLE : View.GONE);
                eDur.setVisibility(dur ? View.VISIBLE : View.GONE);
            }
        };
        renderChips.run();

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        buttons.setPadding(0, dp(12), 0, 0);
        TextView del = editorButton(ctx, "Eliminar", 0xFFFF6E6E);
        TextView cancel = editorButton(ctx, "Cancelar", 0xFFBBBBBB);
        TextView save = editorButton(ctx, "Guardar", 0xFF64B5F6);
        buttons.addView(del, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(buttons);

        del.setOnClickListener(v -> {
            closeMarkerMenu();
            deleteAction(index);
        });
        cancel.setOnClickListener(v -> closeMarkerMenu());
        save.setOnClickListener(v -> {
            Script s2 = ScriptStore.loadCurrent(this);
            if (index < s2.actions.size()) {
                Action b = s2.actions.get(index);
                b.type = chosen[0];
                b.x = (int) editorParse(ex, b.x);
                b.y = (int) editorParse(ey, b.y);
                b.x2 = (int) editorParse(ex2, b.x2);
                b.y2 = (int) editorParse(ey2, b.y2);
                b.duration = Math.max(0, editorParse(eDur, b.duration));
                b.repeat = (int) Math.max(1, editorParse(eRep, 1));
                b.delayAfter = Math.max(0, editorParse(eDelay, b.delayAfter));
                ScriptStore.saveCurrent(this, s2);
                toast("Acción " + (index + 1) + " guardada");
            }
            closeMarkerMenu();
            rebuildMarkers();
            notifyListener();
        });

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(box);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2202124);
        bg.setCornerRadius(dp(20));
        scroll.setBackground(bg);

        int w = Math.min(sz.x - dp(32), dp(360));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        lp.dimAmount = 0.4f;
        lp.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        scroll.setOnTouchListener((v, e) -> {
            boolean outside = e.getActionMasked() == MotionEvent.ACTION_OUTSIDE
                    || (e.getActionMasked() == MotionEvent.ACTION_DOWN
                    && (e.getX() < 0 || e.getY() < 0 || e.getX() > v.getWidth() || e.getY() > v.getHeight()));
            if (outside) {
                closeMarkerMenu();
                return true;
            }
            return false;
        });
        wm.addView(scroll, lp);
        markerMenu = scroll;
    }

    private TextView editorLabel(Context ctx, String s) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(0xFFB0BEC5);
        t.setTextSize(13);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private EditText editorNum(Context ctx, long v) {
        EditText e = new EditText(ctx);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        e.setText(String.valueOf(v));
        e.setTextColor(Color.WHITE);
        e.setSelectAllOnFocus(true);
        return e;
    }

    private LinearLayout editorRow(Context ctx, EditText a, EditText b) {
        LinearLayout row = new LinearLayout(ctx);
        row.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView editorButton(Context ctx, String label, int color) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setTextColor(color);
        t.setTextSize(15);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(dp(4), dp(12), dp(4), dp(12));
        return t;
    }

    private static long editorParse(EditText e, long def) {
        try {
            return Long.parseLong(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** Panel que se arrastra manteniendo pulsado cualquier punto (o al instante desde ⠿). */
    private class DragPanel extends LinearLayout {
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;
        private final int slop = dp(8);
        private final Runnable arm = () -> {
            dragging = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            setAlpha(0.75f);
        };

        DragPanel(Context c) {
            super(c);
        }

        private void begin(MotionEvent e) {
            downX = e.getRawX();
            downY = e.getRawY();
            startX = panelLp.x;
            startY = panelLp.y;
            dragging = false;
            removeCallbacks(arm);
            postDelayed(arm, 400);
        }

        private boolean movedFar(MotionEvent e) {
            return Math.abs(e.getRawX() - downX) > slop || Math.abs(e.getRawY() - downY) > slop;
        }

        private void finish() {
            removeCallbacks(arm);
            dragging = false;
            setAlpha(1f);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    begin(e);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) return true;
                    if (movedFar(e)) removeCallbacks(arm);
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    boolean was = dragging;
                    finish();
                    return was;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    begin(e);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        panelLp.x = startX + (int) (e.getRawX() - downX);
                        panelLp.y = startY + (int) (e.getRawY() - downY);
                        try {
                            wm.updateViewLayout(this, panelLp);
                        } catch (Exception ignored) {
                        }
                    } else if (movedFar(e)) {
                        removeCallbacks(arm);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    finish();
                    return true;
            }
            return true;
        }
    }

    private void deleteAction(int index) {
        Script s = ScriptStore.loadCurrent(this);
        if (index < s.actions.size()) {
            s.actions.remove(index);
            ScriptStore.saveCurrent(this, s);
            toast("Acción " + (index + 1) + " eliminada");
        }
        rebuildMarkers();
        notifyListener();
    }

    private void deleteLastAction() {
        Script s = ScriptStore.loadCurrent(this);
        if (s.actions.isEmpty()) {
            toast("No hay acciones");
            return;
        }
        deleteAction(s.actions.size() - 1);
    }


    /** Vuelve a dibujar los marcadores de todas las acciones con coordenadas. */
    public void rebuildMarkers() {
        main.post(() -> {
            removeMarkers();
            if (panel == null || !markersVisible || running || recordView != null) return;
            Script s = ScriptStore.loadCurrent(this);
            for (int i = 0; i < s.actions.size(); i++) {
                Action a = s.actions.get(i);
                if (!a.type.hasPoint()) continue;
                boolean two = a.type.hasSecondPoint();
                addMarker(i, false, a.x, a.y, (i + 1) + (two ? "a" : ""), colorFor(a.type));
                if (two) addMarker(i, true, a.x2, a.y2, (i + 1) + "b", colorFor(a.type));
            }
            bringPanelToFront();
        });
    }

    private int colorFor(Action.Type t) {
        switch (t) {
            case SWIPE: return 0xCC43A047;
            case LONG_PRESS: return 0xCCFB8C00;
            case DOUBLE_TAP: return 0xCC8E24AA;
            case PINCH_IN: case PINCH_OUT: case MULTI_TAP: return 0xCC00897B;
            default: return 0xCC1E88E5;
        }
    }

    private void addMarker(int index, boolean second, int cx, int cy, String label, int color) {
        int size = dp(44);
        TextView m = new TextView(this);
        m.setText(label);
        m.setTextColor(Color.WHITE);
        m.setTextSize(12);
        m.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(dp(2), Color.WHITE);
        m.setBackground(d);

        WindowManager.LayoutParams lp = overlayParams(size, size, true);
        lp.x = cx - size / 2;
        lp.y = cy - size / 2;
        makeDraggable(m, m, lp, v -> {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            int nx = loc[0] + v.getWidth() / 2;
            int ny = loc[1] + v.getHeight() / 2;
            Script s = ScriptStore.loadCurrent(this);
            if (index < s.actions.size()) {
                Action a = s.actions.get(index);
                if (second) {
                    a.x2 = nx;
                    a.y2 = ny;
                } else {
                    a.x = nx;
                    a.y = ny;
                }
                ScriptStore.saveCurrent(this, s);
                notifyListener();
            }
        }, v -> showMarkerEditor(index));
        wm.addView(m, lp);
        markers.add(m);
        // Corrige un posible desplazamiento (barra de estado / notch) entre la ventana y la pantalla.
        m.post(() -> {
            int[] loc = new int[2];
            m.getLocationOnScreen(loc);
            int offX = loc[0] - lp.x, offY = loc[1] - lp.y;
            if (offX != 0 || offY != 0) {
                lp.x -= offX;
                lp.y -= offY;
                try {
                    wm.updateViewLayout(m, lp);
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ------------------------------------------------------------------ grabación

    @SuppressLint("ClickableViewAccessibility")
    public void startRecording() {
        if (wm == null || running || recordView != null) return;
        if (panel == null) showPanel();
        recordScript = ScriptStore.loadCurrent(this);
        lastRecordUp = 0;
        removeMarkers();
        View v = new View(this);
        v.setBackgroundColor(0x3300B0FF);
        WindowManager.LayoutParams lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, true);
        v.setOnTouchListener(new View.OnTouchListener() {
            final List<float[]> pts = new ArrayList<>();
            long downTime;

            @Override
            public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        pts.clear();
                        downTime = e.getEventTime();
                        pts.add(new float[]{e.getRawX(), e.getRawY()});
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        pts.add(new float[]{e.getRawX(), e.getRawY()});
                        return true;
                    case MotionEvent.ACTION_UP:
                        pts.add(new float[]{e.getRawX(), e.getRawY()});
                        recordGesture(pts, downTime, e.getEventTime());
                        return true;
                }
                return true;
            }
        });
        wm.addView(v, lp);
        recordView = v;
        bringPanelToFront();
        updatePanel();
        setStatus("Grabando");
        toast("Grabando: toca, mantén o desliza sobre la pantalla. Pulsa ● para terminar.");
    }

    private void recordGesture(List<float[]> pts, long down, long up) {
        if (recordScript == null || pts.isEmpty()) return;
        float[] first = pts.get(0);
        float[] last = pts.get(pts.size() - 1);
        float maxDist = 0;
        for (float[] p : pts) maxDist = Math.max(maxDist, (float) Math.hypot(p[0] - first[0], p[1] - first[1]));
        long dt = Math.max(1, up - down);
        int slop = dp(12);

        // Pausa entre el gesto anterior y éste.
        List<Action> list = recordScript.actions;
        if (lastRecordUp > 0 && !list.isEmpty()) {
            list.get(list.size() - 1).delayAfter = Math.max(0, down - lastRecordUp);
        }

        Action a;
        if (maxDist < slop) {
            int x = (int) first[0], y = (int) first[1];
            Action prev = list.isEmpty() ? null : list.get(list.size() - 1);
            if (dt < 400 && prev != null && prev.type == Action.Type.TAP && lastRecordUp > 0
                    && down - lastRecordUp < 300
                    && Math.hypot(prev.x - x, prev.y - y) < dp(30)) {
                prev.type = Action.Type.DOUBLE_TAP;
                prev.delayAfter = 500;
                lastRecordUp = up;
                setStatus("REC " + list.size());
                return;
            }
            a = Action.of(dt >= 450 ? Action.Type.LONG_PRESS : Action.Type.TAP, x, y);
            a.duration = dt >= 450 ? dt : Math.min(dt, 100);
        } else {
            // ¿Es una línea recta? Distancia máxima de los puntos a la recta inicio-fin.
            float lx = last[0] - first[0], ly = last[1] - first[1];
            float len = (float) Math.hypot(lx, ly);
            float maxDev = 0;
            if (len > 0) {
                for (float[] p : pts) {
                    float dev = Math.abs(lx * (first[1] - p[1]) - (first[0] - p[0]) * ly) / len;
                    maxDev = Math.max(maxDev, dev);
                }
            } else {
                maxDev = maxDist;
            }
            if (maxDev < dp(20)) {
                a = Action.of(Action.Type.SWIPE, (int) first[0], (int) first[1]);
                a.x2 = (int) last[0];
                a.y2 = (int) last[1];
            } else {
                a = Action.of(Action.Type.PATH, (int) first[0], (int) first[1]);
                StringBuilder sb = new StringBuilder();
                int step = Math.max(1, pts.size() / 60); // máx ~60 puntos
                for (int i = 0; i < pts.size(); i += step) appendPoint(sb, pts.get(i));
                if ((pts.size() - 1) % step != 0) appendPoint(sb, last);
                a.path = sb.toString();
            }
            a.duration = dt;
        }
        a.delayAfter = 500;
        list.add(a);
        lastRecordUp = up;
        setStatus("REC " + list.size());
    }

    private static void appendPoint(StringBuilder sb, float[] p) {
        if (sb.length() > 0) sb.append(';');
        sb.append((int) p[0]).append(',').append((int) p[1]);
    }

    public void stopRecording(boolean save) {
        if (recordView == null) return;
        safeRemove(recordView);
        recordView = null;
        if (save && recordScript != null) {
            ScriptStore.saveCurrent(this, recordScript);
            toast("Grabación guardada: " + recordScript.actions.size() + " acciones");
        }
        recordScript = null;
        updatePanel();
        rebuildMarkers();
        notifyListener();
    }

    // ------------------------------------------------------------------ ejecución

    public void start() {
        if (running) return;
        if (recordView != null) stopRecording(true);
        Script s = ScriptStore.loadCurrent(this);
        if (s.actions.isEmpty()) {
            toast("El script no tiene acciones");
            return;
        }
        running = true;
        removeMarkers();
        updatePanel();
        notifyListener();
        toast("Ejecutando… (Volumen- o ■ para parar)");
        worker = new Thread(() -> runScript(s), "autoclicker");
        worker.start();
    }

    public void stop() {
        running = false;
        Thread w = worker;
        if (w != null) w.interrupt();
    }

    private void runScript(Script s) {
        long endAt = s.stopAfterMin > 0 ? SystemClock.uptimeMillis() + s.stopAfterMin * 60_000L : Long.MAX_VALUE;
        int loop = 0;
        try {
            while (running && (s.loops == 0 || loop < s.loops) && SystemClock.uptimeMillis() < endAt) {
                loop++;
                for (int i = 0; i < s.actions.size() && running; i++) {
                    Action a = s.actions.get(i);
                    for (int r = 0; r < a.repeat && running; r++) {
                        setStatus("V" + loop + (s.loops > 0 ? "/" + s.loops : "") + " · A" + (i + 1));
                        execute(a, s);
                        sleepVar(a.delayAfter, s);
                        if (SystemClock.uptimeMillis() >= endAt) break;
                    }
                }
                if (running && s.loopDelay > 0) sleepVar(s.loopDelay, s);
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            toast("Error: " + e.getMessage());
        }
        running = false;
        worker = null;
        updatePanel();
        rebuildMarkers();
        notifyListener();
    }

    private void sleepVar(long ms, Script s) throws InterruptedException {
        if (ms <= 0) return;
        if (s.randomDelayPct > 0) {
            double f = 1 + (random.nextDouble() * 2 - 1) * s.randomDelayPct / 100.0;
            ms = Math.max(0, (long) (ms * f));
        }
        Thread.sleep(ms);
    }

    private int jit(int v, Script s) {
        if (s.jitterPx <= 0) return v;
        return v + random.nextInt(s.jitterPx * 2 + 1) - s.jitterPx;
    }

    private static long clampDur(long d) {
        return Math.max(1, Math.min(d, GestureDescription.getMaxGestureDuration()));
    }

    private void execute(Action a, Script s) throws InterruptedException {
        int x = jit(a.x, s), y = jit(a.y, s), x2 = jit(a.x2, s), y2 = jit(a.y2, s);
        switch (a.type) {
            case TAP:
                gesture(new GestureDescription.Builder()
                        .addStroke(stroke(point(x, y), 0, Math.max(1, a.duration))).build());
                break;
            case DOUBLE_TAP:
                gesture(new GestureDescription.Builder()
                        .addStroke(stroke(point(x, y), 0, 40))
                        .addStroke(stroke(point(x, y), 140, 40)).build());
                break;
            case LONG_PRESS:
                gesture(new GestureDescription.Builder()
                        .addStroke(stroke(point(x, y), 0, clampDur(Math.max(400, a.duration)))).build());
                break;
            case SWIPE:
                gesture(new GestureDescription.Builder()
                        .addStroke(stroke(line(x, y, x2, y2), 0, clampDur(Math.max(10, a.duration)))).build());
                break;
            case MULTI_TAP:
                gesture(new GestureDescription.Builder()
                        .addStroke(stroke(point(x, y), 0, Math.max(1, a.duration)))
                        .addStroke(stroke(point(x2, y2), 0, Math.max(1, a.duration))).build());
                break;
            case PINCH_IN:
            case PINCH_OUT: {
                int cx = (x + x2) / 2, cy = (y + y2) / 2;
                // Los dedos no pueden empezar en el mismo punto: deja un pequeño hueco central.
                int gx = (x2 - x) / 10, gy = (y2 - y) / 10;
                Path p1, p2;
                if (a.type == Action.Type.PINCH_IN) {
                    p1 = line(x, y, cx - gx, cy - gy);
                    p2 = line(x2, y2, cx + gx, cy + gy);
                } else {
                    p1 = line(cx - gx, cy - gy, x, y);
                    p2 = line(cx + gx, cy + gy, x2, y2);
                }
                long d = clampDur(Math.max(50, a.duration));
                gesture(new GestureDescription.Builder()
                        .addStroke(stroke(p1, 0, d)).addStroke(stroke(p2, 0, d)).build());
                break;
            }
            case PATH: {
                Path p = parsePath(a.path, s);
                if (p != null) {
                    gesture(new GestureDescription.Builder()
                            .addStroke(stroke(p, 0, clampDur(Math.max(10, a.duration)))).build());
                }
                break;
            }
            case CLICK_TEXT:
                clickByText(a.text, Math.max(0, a.duration));
                break;
            case INPUT_TEXT:
                inputText(a.text);
                break;
            case GLOBAL:
                performGlobalAction(a.global);
                break;
            case LAUNCH_APP: {
                Intent i = getPackageManager().getLaunchIntentForPackage(a.text.trim());
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } else {
                    toast("App no encontrada: " + a.text);
                }
                break;
            }
            case WAIT:
                Thread.sleep(Math.max(0, a.duration));
                break;
        }
    }

    private static Path point(int x, int y) {
        Path p = new Path();
        p.moveTo(Math.max(0, x), Math.max(0, y));
        return p;
    }

    private static Path line(int x1, int y1, int x2, int y2) {
        Path p = new Path();
        p.moveTo(Math.max(0, x1), Math.max(0, y1));
        p.lineTo(Math.max(0, x2), Math.max(0, y2));
        return p;
    }

    private Path parsePath(String spec, Script s) {
        if (spec == null || spec.trim().isEmpty()) return null;
        Path p = new Path();
        boolean firstPt = true;
        for (String part : spec.split(";")) {
            String[] xy = part.trim().split(",");
            if (xy.length < 2) continue;
            try {
                int px = Math.max(0, jit(Integer.parseInt(xy[0].trim()), s));
                int py = Math.max(0, jit(Integer.parseInt(xy[1].trim()), s));
                if (firstPt) {
                    p.moveTo(px, py);
                    firstPt = false;
                } else {
                    p.lineTo(px, py);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return firstPt ? null : p;
    }

    private static GestureDescription.StrokeDescription stroke(Path p, long start, long dur) {
        return new GestureDescription.StrokeDescription(p, start, dur);
    }

    /** Envía un gesto y espera a que termine. */
    private boolean gesture(GestureDescription g) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                ok[0] = true;
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                latch.countDown();
            }
        }, main);
        if (!sent) return false;
        latch.await(GestureDescription.getMaxGestureDuration() + 2000, TimeUnit.MILLISECONDS);
        return ok[0];
    }

    // ------------------------------------------------------------------ acciones por contenido

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> list = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && !getPackageName().contentEquals(r.getPackageName() == null ? "" : r.getPackageName())) {
                    list.add(r);
                }
            }
        } catch (Exception ignored) {
        }
        if (list.isEmpty()) {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) list.add(r);
        }
        return list;
    }

    private void clickByText(String query, long timeoutMs) throws InterruptedException {
        if (query == null || query.trim().isEmpty()) return;
        String q = query.trim();
        long until = SystemClock.uptimeMillis() + timeoutMs;
        do {
            for (AccessibilityNodeInfo root : roots()) {
                List<AccessibilityNodeInfo> found = q.startsWith("id:")
                        ? root.findAccessibilityNodeInfosByViewId(q.substring(3).trim())
                        : root.findAccessibilityNodeInfosByText(q);
                if (found == null) continue;
                for (AccessibilityNodeInfo n : found) {
                    if (n == null || !n.isVisibleToUser()) continue;
                    if (clickNode(n)) return;
                }
            }
            Thread.sleep(300);
        } while (running && SystemClock.uptimeMillis() < until);
        toast("No se encontró: " + q);
    }

    private boolean clickNode(AccessibilityNodeInfo n) throws InterruptedException {
        AccessibilityNodeInfo c = n;
        while (c != null && !c.isClickable()) c = c.getParent();
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        return gesture(new GestureDescription.Builder()
                .addStroke(stroke(point(r.centerX(), r.centerY()), 0, 50)).build());
    }

    private void inputText(String text) {
        AccessibilityNodeInfo target = null;
        for (AccessibilityNodeInfo root : roots()) {
            target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target != null && target.isEditable()) break;
            target = findEditable(root);
            if (target != null) break;
        }
        if (target == null) {
            toast("No hay ningún campo de texto");
            return;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }
}
