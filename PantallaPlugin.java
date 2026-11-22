package com.telelibre.app;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
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
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("Sin canal");
            return;
        }
        Intent i = new Intent(getContext(), PlayerActivity.class);
        i.putExtra("url", url);
        i.putExtra("titulo", call.getString("titulo", "Tele Libre"));
        getActivity().startActivity(i);
        call.resolve();
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
}
