package com.telelibre.app;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.provider.Settings;
import androidx.mediarouter.app.MediaRouteChooserDialog;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import android.os.Build;
import android.view.Window;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import android.view.WindowManager;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Pantalla completa real y "mandar a la tele" (abre el canal en una app de transmisión). */
@CapacitorPlugin(name = "Pantalla")
public class PantallaPlugin extends Plugin {

    @PluginMethod
    public void completa(PluginCall call) {
        final boolean girar = Boolean.TRUE.equals(call.getBoolean("girar", true));
        getActivity().runOnUiThread(() -> {
            Window w = getActivity().getWindow();
            WindowCompat.setDecorFitsSystemWindows(w, false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                w.setAttributes(lp);
            }
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
            c.hide(WindowInsetsCompat.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (girar) {
                getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void normal(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            Window w = getActivity().getWindow();
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
            c.show(WindowInsetsCompat.Type.systemBars());
            WindowCompat.setDecorFitsSystemWindows(w, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
                w.setAttributes(lp);
            }
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            call.resolve();
        });
    }

    @PluginMethod
    public void enviar(PluginCall call) {
        String url = call.getString("url");
        String titulo = call.getString("titulo", "Tele Libre");
        if (url == null || url.isEmpty()) {
            call.reject("Sin canal");
            return;
        }
        Intent ver = new Intent(Intent.ACTION_VIEW);
        ver.setDataAndType(Uri.parse(url), "application/x-mpegURL");
        ver.putExtra("title", titulo);
        Intent elegir = Intent.createChooser(ver, "Mandar a la tele con…");
        elegir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getActivity().startActivity(elegir);
            call.resolve();
        } catch (Exception e) {
            call.reject("No hay apps para abrir video");
        }
    }

    @PluginMethod
    public void reproducir(PluginCall call) {
        JSArray lista = call.getArray("lista");
        java.util.ArrayList<String> urls = new java.util.ArrayList<>(), nombres = new java.util.ArrayList<>();
        try {
            if (lista != null) {
                for (int i = 0; i < lista.length(); i++) {
                    org.json.JSONObject o = lista.getJSONObject(i);
                    urls.add(o.getString("url"));
                    nombres.add(o.optString("nombre", "Tele Libre"));
                }
            }
        } catch (Exception e) { /* usa lo que haya */ }
        if (urls.isEmpty()) {
            call.reject("Sin canal");
            return;
        }
        PlayerActivity.ultima = null;
        Intent i = new Intent(getContext(), PlayerActivity.class);
        i.putStringArrayListExtra("urls", urls);
        i.putStringArrayListExtra("nombres", nombres);
        int indice = call.getInt("indice", 0);
        i.putExtra("indice", indice);
        getActivity().startActivity(i);
        call.resolve();
    }

    /** Al volver del reproductor nativo, avisa a la app en qué canal te quedaste. */
    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        String u = PlayerActivity.ultima;
        if (u != null) {
            PlayerActivity.ultima = null;
            JSObject d = new JSObject();
            d.put("url", u);
            notifyListeners("nativoCerrado", d);
        }
    }

    @PluginMethod
    public void abrir(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("Sin enlace");
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getActivity().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("No hay navegador");
        }
    }

    /** Revisa señales desde el internet del celular: {urls:[...]} -> {ok:[...], mal:[...]} */
    @PluginMethod
    public void probar(PluginCall call) {
        JSArray arr = call.getArray("urls");
        new Thread(() -> {
            JSArray ok = new JSArray(), mal = new JSArray();
            try {
                List<String> urls = arr == null ? new ArrayList<>() : arr.toList();
                ExecutorService ex = Executors.newFixedThreadPool(12);
                List<Future<Boolean>> fs = new ArrayList<>();
                for (String u : urls) fs.add(ex.submit(() -> responde(u)));
                for (int i = 0; i < urls.size(); i++) {
                    boolean b;
                    try { b = fs.get(i).get(); } catch (Exception e) { b = false; }
                    (b ? ok : mal).put(urls.get(i));
                }
                ex.shutdown();
            } catch (Exception e) { /* devuelve lo que haya */ }
            JSObject r = new JSObject();
            r.put("ok", ok);
            r.put("mal", mal);
            call.resolve(r);
        }).start();
    }

    private static boolean responde(String u) {
        HttpURLConnection c = null;
        try {
            for (int saltos = 0; saltos < 4; saltos++) {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TeleLibre");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    u = new URL(new URL(u), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    continue;
                }
                if (code >= 400) return false;
                InputStream in = c.getInputStream();
                byte[] buf = new byte[2048];
                int n = in.read(buf);
                in.close();
                if (n <= 0) return false;
                String head = new String(buf, 0, n, "UTF-8");
                return head.contains("#EXTM3U") || !u.toLowerCase().split("\\?")[0].endsWith(".m3u8");
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /* ---------- Chromecast / Google TV ---------- */
    private String pendUrl, pendTit;
    private boolean escuchando = false;

    @PluginMethod
    public void transmitir(PluginCall call) {
        final String url = call.getString("url");
        final String tit = call.getString("titulo", "Tele Libre");
        if (url == null) { call.reject("Sin canal"); return; }
        getActivity().runOnUiThread(() -> {
            try {
                CastContext cc = CastContext.getSharedInstance(getContext());
                if (!escuchando) {
                    cc.getSessionManager().addSessionManagerListener(new Escucha(), CastSession.class);
                    escuchando = true;
                }
                CastSession ses = cc.getSessionManager().getCurrentCastSession();
                if (ses != null && ses.isConnected()) {
                    cargar(ses, url, tit);
                } else {
                    pendUrl = url;
                    pendTit = tit;
                    MediaRouteChooserDialog d = new MediaRouteChooserDialog(getActivity());
                    if (cc.getMergedSelector() != null) d.setRouteSelector(cc.getMergedSelector());
                    d.show();
                }
                call.resolve();
            } catch (Exception e) {
                call.reject("Chromecast no disponible");
            }
        });
    }

    @PluginMethod
    public void detenerTele(PluginCall call) {
        getActivity().runOnUiThread(() -> {
            try { CastContext.getSharedInstance(getContext()).getSessionManager().endCurrentSession(true); } catch (Exception e) { }
            call.resolve();
        });
    }

    /** Abre "Transmitir pantalla" de Android (sirve para Roku y la mayoría de Smart TV). */
    @PluginMethod
    public void duplicarPantalla(PluginCall call) {
        try {
            Intent i = new Intent("android.settings.CAST_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(i);
        } catch (Exception e) {
            Intent i = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(i);
        }
        call.resolve();
    }

    private void cargar(CastSession ses, String url, String tit) {
        MediaMetadata md = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        md.putString(MediaMetadata.KEY_TITLE, tit);
        MediaInfo info = new MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                .setContentType("application/x-mpegURL")
                .setMetadata(md)
                .build();
        RemoteMediaClient r = ses.getRemoteMediaClient();
        if (r != null) r.load(new MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build());
        avisar("enviando", tit);
    }

    private void avisar(String estado, String tit) {
        JSObject d = new JSObject();
        d.put("estado", estado);
        d.put("titulo", tit == null ? "" : tit);
        notifyListeners("tele", d);
    }

    private class Escucha implements SessionManagerListener<CastSession> {
        @Override public void onSessionStarting(CastSession s) { }
        @Override public void onSessionStarted(CastSession s, String id) {
            if (pendUrl != null) { cargar(s, pendUrl, pendTit); pendUrl = null; }
        }
        @Override public void onSessionStartFailed(CastSession s, int e) { pendUrl = null; avisar("fallo", ""); }
        @Override public void onSessionEnding(CastSession s) { }
        @Override public void onSessionEnded(CastSession s, int e) { avisar("desconectado", ""); }
        @Override public void onSessionResuming(CastSession s, String id) { }
        @Override public void onSessionResumed(CastSession s, boolean b) {
            if (pendUrl != null) { cargar(s, pendUrl, pendTit); pendUrl = null; }
        }
        @Override public void onSessionResumeFailed(CastSession s, int e) { }
        @Override public void onSessionSuspended(CastSession s, int r) { }
    }
}
