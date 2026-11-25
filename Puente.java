package com.telelibre.app;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Puente para Smart TV: el celular recibe la señal HLS del canal y la entrega a la tele
 * por el WiFi como un solo video MPEG-TS continuo (lo que entienden casi todas las teles por DLNA).
 */
public class Puente {
    private static ServerSocket servidor;
    private static final int PUERTO = 8899;
    private static volatile String origen = null;
    private static volatile int generacion = 0;

    /** Prepara el puente con un canal y devuelve la dirección que hay que darle a la tele. */
    public static synchronized String iniciar(String url) throws Exception {
        origen = url;
        generacion++;
        if (servidor == null || servidor.isClosed()) {
            servidor = new ServerSocket(PUERTO);
            Thread t = new Thread(Puente::escuchar, "puente-tv");
            t.setDaemon(true);
            t.start();
        }
        String ip = miIp();
        if (ip == null) throw new Exception("Sin WiFi");
        return "http://" + ip + ":" + PUERTO + "/tele.ts?g=" + generacion;
    }

    public static synchronized void detener() {
        origen = null;
        generacion++;
    }

    private static void escuchar() {
        while (servidor != null && !servidor.isClosed()) {
            try {
                Socket s = servidor.accept();
                Thread t = new Thread(() -> atender(s), "puente-cliente");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                // seguir escuchando
            }
        }
    }

    private static void atender(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"));
            String linea = in.readLine();
            if (linea == null) return;
            String l;
            while ((l = in.readLine()) != null && !l.isEmpty()) { /* cabeceras */ }
            OutputStream out = sock.getOutputStream();
            String url = origen;
            int gen = generacion;
            if (url == null) {
                out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
                return;
            }
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: video/mp2t\r\n"
                    + "Connection: close\r\n"
                    + "Accept-Ranges: none\r\n"
                    + "transferMode.dlna.org: Streaming\r\n"
                    + "contentFeatures.dlna.org: DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000\r\n"
                    + "\r\n").getBytes("UTF-8"));
            out.flush();
            if (linea.startsWith("HEAD")) return;
            transmitir(url, gen, out);
        } catch (Exception e) {
            // la tele cerró la conexión o cambió de canal
        }
    }

    /** Lee la lista HLS y va mandando los segmentos .ts uno tras otro, como un video continuo. */
    private static void transmitir(String url, int gen, OutputStream out) throws Exception {
        String media = Teles.resolver(url);
        String texto = bajarTexto(media);
        if (texto.contains("#EXT-X-STREAM-INF")) {
            media = elegirCalidad(media, texto);
            texto = bajarTexto(media);
        }
        long ultimo = -1;
        byte[] buf = new byte[65536];
        while (gen == generacion) {
            long seq = 0;
            int objetivo = 6;
            List<String> segs = new ArrayList<>();
            for (String l : texto.split("\r?\n")) {
                l = l.trim();
                if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) seq = Long.parseLong(l.substring(22).trim());
                else if (l.startsWith("#EXT-X-TARGETDURATION:")) objetivo = (int) Double.parseDouble(l.substring(22).trim());
                else if (l.startsWith("#EXT-X-MAP") || (l.startsWith("#EXT-X-KEY") && !l.contains("METHOD=NONE")))
                    throw new Exception("Formato no compatible con el puente");
                else if (!l.isEmpty() && !l.startsWith("#")) segs.add(new URL(new URL(media), l).toString());
            }
            if (ultimo < 0) ultimo = seq + Math.max(0, segs.size() - 3) - 1; // empezar cerca de lo más reciente
            for (int i = 0; i < segs.size() && gen == generacion; i++) {
                long n = seq + i;
                if (n <= ultimo) continue;
                HttpURLConnection c = abrir(segs.get(i));
                try (InputStream is = c.getInputStream()) {
                    int r;
                    while ((r = is.read(buf)) > 0) {
                        if (gen != generacion) return;
                        out.write(buf, 0, r);
                    }
                } finally {
                    c.disconnect();
                }
                out.flush();
                ultimo = n;
            }
            if (texto.contains("#EXT-X-ENDLIST")) return;
            Thread.sleep(Math.max(1000, objetivo * 500L));
            texto = bajarTexto(media);
        }
    }

    /** De una lista con varias calidades, elige una buena para la tele (hasta ~4 Mbps). */
    private static String elegirCalidad(String base, String texto) throws Exception {
        String[] ls = texto.split("\r?\n");
        String mejor = null, menor = null;
        long bwMejor = -1, bwMenor = Long.MAX_VALUE;
        for (int i = 0; i < ls.length; i++) {
            if (!ls[i].startsWith("#EXT-X-STREAM-INF")) continue;
            long bw = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("BANDWIDTH=(\\d+)").matcher(ls[i]);
            if (m.find()) bw = Long.parseLong(m.group(1));
            String u = null;
            for (int j = i + 1; j < ls.length; j++) {
                String t = ls[j].trim();
                if (!t.isEmpty() && !t.startsWith("#")) { u = new URL(new URL(base), t).toString(); break; }
            }
            if (u == null) continue;
            if (bw <= 4500000 && bw > bwMejor) { bwMejor = bw; mejor = u; }
            if (bw < bwMenor) { bwMenor = bw; menor = u; }
        }
        return mejor != null ? mejor : (menor != null ? menor : base);
    }

    private static HttpURLConnection abrir(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) TeleLibre");
        return c;
    }

    private static String bajarTexto(String url) throws Exception {
        HttpURLConnection c = abrir(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** IP del celular en el WiFi. */
    private static String miIp() {
        String fija = System.getProperty("puente.ip");
        if (fija != null) return fija;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception e) { /* nada */ }
        return null;
    }
}
