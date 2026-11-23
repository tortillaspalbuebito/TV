package com.telelibre.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;

/** Reproductor nativo de Android con cambio de canal (botones, deslizar y control remoto). */
public class PlayerActivity extends Activity {
    /** Último canal visto, para que la app sepa dónde te quedaste. */
    public static volatile String ultima = null;

    private ExoPlayer player;
    private ArrayList<String> urls = new ArrayList<>();
    private ArrayList<String> nombres = new ArrayList<>();
    private int pos = 0, fallos = 0;
    private TextView nombre, aviso, btnAnt, btnSig, btnFav;
    private boolean[] favs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private float x0, y0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ArrayList<String> u = getIntent().getStringArrayListExtra("urls");
        ArrayList<String> n = getIntent().getStringArrayListExtra("nombres");
        if (u == null || u.isEmpty()) { finish(); return; }
        urls = u;
        nombres = n != null ? n : new ArrayList<>(u);
        pos = Math.max(0, Math.min(getIntent().getIntExtra("indice", 0), urls.size() - 1));
        favs = getIntent().getBooleanArrayExtra("favs");
        if (favs == null || favs.length != urls.size()) favs = new boolean[urls.size()];

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(w, false);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.BLACK);
        PlayerView vista = new PlayerView(this);
        raiz.addView(vista, new FrameLayout.LayoutParams(-1, -1));

        nombre = etiqueta(18);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(40, 40, 0, 0);
        raiz.addView(nombre, lp);

        aviso = etiqueta(16);
        aviso.setVisibility(View.GONE);
        raiz.addView(aviso, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        btnAnt = boton("◀");
        btnSig = boton("▶");
        FrameLayout.LayoutParams la = new FrameLayout.LayoutParams(150, 150, Gravity.CENTER_VERTICAL | Gravity.START);
        la.setMargins(30, 0, 0, 0);
        FrameLayout.LayoutParams ls = new FrameLayout.LayoutParams(150, 150, Gravity.CENTER_VERTICAL | Gravity.END);
        ls.setMargins(0, 0, 30, 0);
        raiz.addView(btnAnt, la);
        raiz.addView(btnSig, ls);
        btnFav = boton("☆");
        btnFav.setTextSize(26);
        FrameLayout.LayoutParams lf = new FrameLayout.LayoutParams(140, 140, Gravity.TOP | Gravity.END);
        lf.setMargins(0, 30, 30, 0);
        raiz.addView(btnFav, lf);
        btnFav.setOnClickListener(v -> alternarFavorito());
        btnAnt.setOnClickListener(v -> cambiar(-1));
        btnSig.setOnClickListener(v -> cambiar(1));
        vista.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) vis -> {
            btnAnt.setVisibility(vis);
            btnSig.setVisibility(vis);
            btnFav.setVisibility(vis);
        });
        setContentView(raiz);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android) TeleLibre")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        vista.setPlayer(player);
        vista.setShowSubtitleButton(true);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                fallos++;
                if (fallos >= 5 || urls.size() < 2) {
                    mostrarAviso("Ningún canal respondió. Toca ◀ ▶ para probar otro.");
                    fallos = 0;
                } else {
                    mostrarAviso(nombres.get(pos) + " no está disponible. Pasando al siguiente…");
                    h.postDelayed(() -> cambiar(1), 1500);
                }
            }

            @Override
            public void onPlaybackStateChanged(int estado) {
                if (estado == Player.STATE_READY) {
                    fallos = 0;
                    aviso.setVisibility(View.GONE);
                    ultima = urls.get(pos);
                    getSharedPreferences("telelibre", MODE_PRIVATE).edit()
                            .putString("ultima", ultima)
                            .putLong("ultimaTs", System.currentTimeMillis())
                            .apply();
                }
            }
        });
        abrir();
    }

    private TextView etiqueta(int tam) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(tam);
        t.setBackgroundColor(0xB3000000);
        t.setPadding(32, 16, 32, 16);
        return t;
    }

    private TextView boton(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(30);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(0x88000000);
        t.setClickable(true);
        return t;
    }

    private void mostrarAviso(String s) {
        aviso.setText(s);
        aviso.setVisibility(View.VISIBLE);
    }

    private void abrir() {
        String url = urls.get(pos);
        ultima = url;
        nombre.setText(nombres.get(pos));
        pintarFavorito();
        nombre.setVisibility(View.VISIBLE);
        h.removeCallbacksAndMessages("nombre");
        h.postAtTime(() -> nombre.setVisibility(View.GONE), "nombre", android.os.SystemClock.uptimeMillis() + 4000);
        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        if (url.toLowerCase().contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(item.build());
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void pintarFavorito() {
        btnFav.setText(favs[pos] ? "★" : "☆");
        btnFav.setTextColor(favs[pos] ? 0xFFF5B301 : Color.WHITE);
    }

    /** Los cambios se guardan aquí y la app los aplica al volver o al abrirse. */
    private void alternarFavorito() {
        favs[pos] = !favs[pos];
        pintarFavorito();
        getSharedPreferences("telelibre_favs", MODE_PRIVATE).edit()
                .putBoolean(urls.get(pos), favs[pos]).apply();
        mostrarAviso(favs[pos] ? "Agregado a favoritos" : "Quitado de favoritos");
        h.postAtTime(() -> aviso.setVisibility(View.GONE), "fav", android.os.SystemClock.uptimeMillis() + 1500);
    }

    private void cambiar(int d) {
        h.removeCallbacksAndMessages(null);
        pos = (pos + d + urls.size()) % urls.size();
        aviso.setVisibility(View.GONE);
        abrir();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) { x0 = e.getX(); y0 = e.getY(); }
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float dx = e.getX() - x0, dy = e.getY() - y0;
            if (Math.abs(dx) > 200 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                cambiar(dx < 0 ? 1 : -1);
                return true;
            }
        }
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) { cambiar(1); return true; }
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { cambiar(-1); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onStop() {
        super.onStop();
        h.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        finish();
    }
}
