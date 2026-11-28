"""Prueba a fondo algunas señales: lista, calidad, primer segmento y si permiten abrirse desde la app (CORS)."""
import re, sys, urllib.request, urllib.parse
URLS = sys.argv[1:]
def abrir(u):
    r = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36", "Origin": "http://localhost"})
    h = urllib.request.urlopen(r, timeout=20)
    return h, h.read(200000 if not re.search(r"\.(ts|aac|mp4|m4s)(\?|$)", u) else 3000)
for u in URLS:
    print("==", u)
    try:
        h, b = abrir(u); t = b.decode("utf-8", "replace")
        print("  lista", h.status, "CORS:", h.headers.get("Access-Control-Allow-Origin"), "->", h.geturl()[:120])
        base = h.geturl()
        if "#EXT-X-STREAM-INF" in t:
            v = [l for l in t.splitlines() if l and not l.startswith("#")][0]
            v = urllib.parse.urljoin(base, v); h, b = abrir(v); t = b.decode("utf-8", "replace"); base = h.geturl()
            print("  calidad", h.status, "CORS:", h.headers.get("Access-Control-Allow-Origin"))
        segs = [l for l in t.splitlines() if l and not l.startswith("#")]
        print("  segmentos:", len(segs), "| DRM:", "#EXT-X-KEY" in t and "METHOD=NONE" not in t, "| fMP4:", "#EXT-X-MAP" in t)
        if segs:
            s = urllib.parse.urljoin(base, segs[0]); h, b = abrir(s)
            print("  segmento", h.status, "CORS:", h.headers.get("Access-Control-Allow-Origin"), len(b), "bytes", s[:100])
    except Exception as e:
        print("  ERROR", e)
