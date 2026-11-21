package com.telelibre.app;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
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
}
