package com.telelibre.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Busca teles en el WiFi (SSDP) y les manda un canal:
 *  - Roku: abre Tele Libre de Roku con el canal (ECP, puerto 8060).
 *  - Smart TV con DLNA (Samsung, LG, Sony, Hisense...): le manda la señal (UPnP AVTransport).
 */
public class Teles {

    /** Devuelve una lista de teles: {tipo:"roku"|"dlna", nombre, base|control}. */
    public static List<JSONObject> buscar() {
        List<String[]> ubicaciones = new ArrayList<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(700);
            InetAddress grupo = InetAddress.getByName("239.255.255.250");
            String[] tipos = {"roku:ecp", "urn:schemas-upnp-org:device:MediaRenderer:1"};
            for (int vuelta = 0; vuelta < 2; vuelta++) {
                for (String st : tipos) {
                    String q = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n";
                    byte[] b = q.getBytes("UTF-8");
                    s.send(new DatagramPacket(b, b.length, grupo, 1900));
                }
            }
            long fin = System.currentTimeMillis() + 3500;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < fin) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    String r = new String(p.getData(), 0, p.getLength(), "UTF-8");
                    String loc = cabecera(r, "LOCATION");
                    String st = cabecera(r, "ST");
                    if (loc != null) ubicaciones.add(new String[]{loc, st == null ? "" : st});
                } catch (SocketTimeoutException te) {
                    // seguir esperando respuestas
                }
            }
        } catch (Exception e) {
            // sin red local
        }
        Map<String, JSONObject> porEquipo = new LinkedHashMap<>();
        for (String[] u : ubicaciones) {
            try {
                String host = new URL(u[0]).getHost();
                JSONObject ya = porEquipo.get(host);
                if (ya != null && "roku".equals(ya.optString("tipo"))) continue;
                JSONObject d = describir(u[0], u[1]);
                if (d != null && (ya == null || "roku".equals(d.optString("tipo")))) porEquipo.put(host, d);
            } catch (Exception e) {
                // equipo que no responde
            }
        }
        return new ArrayList<>(porEquipo.values());
    }

    private static String cabecera(String r, String nombre) {
        for (String l : r.split("\r?\n")) {
            int i = l.indexOf(':');
            if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase(nombre)) return l.substring(i + 1).trim();
        }
        return null;
    }

    private static String etiqueta(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">", Pattern.CASE_INSENSITIVE).matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static JSONObject describir(String loc, String st) throws Exception {
        String xml = leer(loc);
        String nombre = etiqueta(xml, "friendlyName");
        String fabricante = etiqueta(xml, "manufacturer");
        URL u = new URL(loc);
        JSONObject d = new JSONObject();
        if (st.toLowerCase().contains("roku") || fabricante.toLowerCase().contains("roku")) {
            int puerto = u.getPort() == -1 ? 8060 : u.getPort();
            d.put("tipo", "roku");
            d.put("nombre", nombre.isEmpty() ? "Roku" : nombre);
            d.put("base", "http://" + u.getHost() + ":" + puerto + "/");
            return d;
        }
        Matcher m = Pattern.compile("<service>(.*?)</service>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(xml);
        while (m.find()) {
            String srv = m.group(1);
            if (srv.contains("AVTransport")) {
                String control = etiqueta(srv, "controlURL");
                if (control.isEmpty()) return null;
                d.put("tipo", "dlna");
                d.put("nombre", (nombre.isEmpty() ? "Smart TV" : nombre));
                d.put("marca", fabricante);
                d.put("control", new URL(u, control).toString());
                return d;
            }
        }
        return null;
    }

    private static String leer(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0 && o.size() < 200000) o.write(b, 0, n);
            return o.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static int post(String url, String cuerpo, String soapAction) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(6000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        if (soapAction != null) {
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPAction", "\"" + soapAction + "\"");
        }
        byte[] b = (cuerpo == null ? "" : cuerpo).getBytes("UTF-8");
        c.setFixedLengthStreamingMode(b.length);
        try (OutputStream o = c.getOutputStream()) {
            o.write(b);
        }
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    /** Roku: abre Tele Libre (app instalada en modo desarrollador) con el canal. 404 = no está instalada. */
    public static int enviarRoku(String base, String url) throws Exception {
        return post(base + "launch/dev?contentId=" + URLEncoder.encode(url, "UTF-8") + "&mediaType=live", "", null);
    }

    public static int teclaRoku(String base, String tecla) throws Exception {
        return post(base + "keypress/" + tecla, "", null);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static int soap(String control, String accion, String args) throws Exception {
        String svc = "urn:schemas-upnp-org:service:AVTransport:1";
        String cuerpo = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + accion + " xmlns:u=\"" + svc + "\"><InstanceID>0</InstanceID>" + args
                + "</u:" + accion + "></s:Body></s:Envelope>";
        return post(control, cuerpo, svc + "#" + accion);
    }

    /** Smart TV (DLNA): le manda la señal y la pone a reproducir. */
    public static int enviarDlna(String control, String url, String titulo) throws Exception {
        String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + esc(titulo) + "</dc:title><upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"http-get:*:application/vnd.apple.mpegurl:*\">" + esc(url) + "</res></item></DIDL-Lite>";
        try { soap(control, "Stop", ""); } catch (Exception e) { /* puede no estar reproduciendo */ }
        int r = soap(control, "SetAVTransportURI", "<CurrentURI>" + esc(url) + "</CurrentURI><CurrentURIMetaData>" + esc(didl) + "</CurrentURIMetaData>");
        if (r >= 300) return r;
        return soap(control, "Play", "<Speed>1</Speed>");
    }

    public static int detenerDlna(String control) throws Exception {
        return soap(control, "Stop", "");
    }
}
