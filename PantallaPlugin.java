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
        java.util.ArrayList<Boolean> favList = new java.util.ArrayList<>();
        try {
            if (lista != null) {
                for (int i = 0; i < lista.length(); i++) {
                    org.json.JSONObject o = lista.getJSONObject(i);
                    urls.add(o.getString("url"));
                    nombres.add(o.optString("nombre", "Tele Libre"));
                    favList.add(o.optBoolean("fav", false));
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
        boolean[] fv = new boolean[favList.size()];
        for (int k = 0; k < fv.length; k++) fv[k] = favList.get(k);
        i.putExtra("favs", fv);
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

    /** Descarga el inicio de una dirección siguiendo redirecciones. Devuelve {urlFinal, texto} o null. */
    private static String[] bajar(String u, int max) {
        HttpURLConnection c = null;
        try {
            for (int saltos = 0; saltos < 5; saltos++) {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TeleLibre");
                int code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    u = new URL(new URL(u), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    continue;
                }
                if (code >= 400) return null;
                InputStream in = c.getInputStream();
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while (o.size() < max && (n = in.read(buf)) > 0) o.write(buf, 0, n);
                in.close();
                if (o.size() == 0) return null;
                return new String[]{u, o.toString("ISO-8859-1")};
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String primeraLinea(String base, String texto) throws Exception {
        for (String l : texto.split("\r?\n")) {
            l = l.trim();
            if (!l.isEmpty() && !l.startsWith("#")) return new URL(new URL(base), l).toString();
        }
        return null;
    }

    /** Un canal "funciona" solo si de verdad llega video: lista → calidad → primer pedazo de video. */
    private static boolean responde(String u) {
        try {
            String[] r = bajar(u, 300000);
            if (r == null) return false;
            if (!r[1].contains("#EXTM3U")) return !u.toLowerCase().split("\\?")[0].endsWith(".m3u8");
            if (r[1].contains("#EXT-X-STREAM-INF")) {
                String v = primeraLinea(r[0], r[1].substring(r[1].indexOf("#EXT-X-STREAM-INF")));
                if (v == null) return false;
                r = bajar(v, 300000);
                if (r == null || !r[1].contains("#EXTM3U")) return false;
            }
            String seg = primeraLinea(r[0], r[1]);
            if (seg == null) return false;
            return bajar(seg, 1024) != null;
        } catch (Exception e) {
            return false;
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

    /** Último canal que se vio en el reproductor de Android: {url, ts}. */
    @PluginMethod
    public void ultimoNativo(PluginCall call) {
        android.content.SharedPreferences p = getContext().getSharedPreferences("telelibre", android.content.Context.MODE_PRIVATE);
        JSObject r = new JSObject();
        r.put("url", p.getString("ultima", ""));
        r.put("ts", p.getLong("ultimaTs", 0));
        call.resolve(r);
    }

    /** Favoritos cambiados en el reproductor de Android; se borran al leerlos. */
    @PluginMethod
    public void favoritosNativo(PluginCall call) {
        android.content.SharedPreferences p = getContext().getSharedPreferences("telelibre_favs", android.content.Context.MODE_PRIVATE);
        JSArray agregar = new JSArray(), quitar = new JSArray();
        for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) agregar.put(e.getKey()); else quitar.put(e.getKey());
        }
        p.edit().clear().apply();
        JSObject r = new JSObject();
        r.put("agregar", agregar);
        r.put("quitar", quitar);
        call.resolve(r);
    }

    /* ---------- Teles en el WiFi: Roku y Smart TV (DLNA) ---------- */
    @PluginMethod
    public void buscarTeles(PluginCall call) {
        new Thread(() -> {
            android.net.wifi.WifiManager.MulticastLock lock = null;
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getContext().getApplicationContext()
                        .getSystemService(android.content.Context.WIFI_SERVICE);
                lock = wm.createMulticastLock("telelibre");
                lock.setReferenceCounted(false);
                lock.acquire();
            } catch (Exception e) { /* sin permiso de multicast */ }
            JSArray teles = new JSArray();
            try {
                for (org.json.JSONObject t : Teles.buscar()) teles.put(t);
            } catch (Exception e) { /* nada */ }
            if (lock != null) try { lock.release(); } catch (Exception e) { }
            JSObject r = new JSObject();
            r.put("teles", teles);
            call.resolve(r);
        }).start();
    }

    @PluginMethod
    public void enviarRed(PluginCall call) {
        final String tipo = call.getString("tipo", "");
        final String url = call.getString("url");
        final String titulo = call.getString("titulo", "Tele Libre");
        final String base = call.getString("base");
        final String control = call.getString("control");
        new Thread(() -> {
            try {
                int code;
                if ("roku".equals(tipo)) code = Teles.enviarRoku(base, Teles.resolver(url));
                else code = Teles.enviarDlna(control, url, titulo);
                if (code >= 200 && code < 300) call.resolve();
                else call.reject(Teles.ultimoError.isEmpty() ? "La tele respondió " + code : Teles.ultimoError, code == 404 && "roku".equals(tipo) ? "SIN_APP" : "TELE_" + code);
            } catch (Exception e) {
                call.reject("No se pudo conectar con la tele", "SIN_CONEXION");
            }
        }).start();
    }

    @PluginMethod
    public void detenerRed(PluginCall call) {
        final String tipo = call.getString("tipo", "");
        final String base = call.getString("base");
        final String control = call.getString("control");
        new Thread(() -> {
            try {
                if ("roku".equals(tipo)) Teles.teclaRoku(base, "Back");
                else Teles.detenerDlna(control);
            } catch (Exception e) { /* nada */ }
            call.resolve();
        }).start();
    }
}
