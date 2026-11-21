#!/usr/bin/env python3
"""Genera guia.json: lo que transmite cada canal de la lista (ahora y próximas horas).

Descarga la lista M3U y guías XMLTV gratuitas, cruza los canales y guarda solo
lo necesario para la app. Lo corre GitHub Actions cada pocas horas.
Uso local:  python3 scripts/guia.py [salida.json]
"""
import gzip, io, json, re, sys, time, unicodedata, urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime

M3U = "https://iptv-org.github.io/iptv/languages/spa.m3u"
# Guías XMLTV gratuitas por país (si alguna falla, se salta).
EPGS = [f"https://epgshare01.online/epgshare01/epg_ripper_{p}1.xml.gz"
        for p in ["MX", "ES", "AR", "CO", "CL", "PE", "US_LOCALS", "DO", "UY", "EC", "VE", "CR"]]
HORAS = 18          # cuántas horas hacia adelante guardar
DESC = 110          # largo máximo de la descripción
COREANO = re.compile(r"\b(core[ao]s?|coreanas?|dorama|doramas|k-?drama|k-?dramas|korean|seúl|seul)\b", re.I)
# Respuestas que significan "probablemente bloqueado por país", no "caído":
# GitHub corre en EE. UU., así que esos canales NO se esconden.
GEO = {401, 403, 451}


def norm(s):
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]", "", s)


def clean_name(s):
    s = re.sub(r"\(.*?\)|\[.*?\]", " ", s or "")
    s = re.sub(r"\b(hd|sd|fhd|uhd|4k)\b", " ", s, flags=re.I)
    return norm(s)


def id_base(i):
    return (i or "").split("@")[0]


def country(i):
    m = re.search(r"\.([a-z]{2})$", id_base(i), re.I)
    return m.group(1).lower() if m else ""


def keys_for(tvg_id, name):
    out = []
    if tvg_id:
        out.append("i:" + norm(id_base(tvg_id)))
    n = clean_name(name)
    if n:
        out.append("n:" + n + "|" + country(tvg_id))
    return out


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 TeleLibre"})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    if url.endswith(".gz") or data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    return data


def m3u_keys(text):
    want = set()
    for line in text.splitlines():
        if line.startswith("#EXTINF"):
            attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', line))
            name = line.rsplit(",", 1)[-1].strip()
            want.update(keys_for(attrs.get("tvg-id", ""), name))
    return want


def ts(s):
    s = s.strip()
    fmt = "%Y%m%d%H%M%S %z" if " " in s else "%Y%m%d%H%M%S"
    if " " not in s:
        s = s[:14]
    return int(datetime.strptime(s, fmt).timestamp())


def build(m3u_text, epg_blobs, now=None):
    now = int(now or time.time())
    lo, hi = now - 3 * 3600, now + HORAS * 3600
    want = m3u_keys(m3u_text)
    chans, keymap = [], {}
    for blob in epg_blobs:
        idx_of = {}
        for ev, el in ET.iterparse(io.BytesIO(blob), events=("end",)):
            if el.tag == "channel":
                cid = el.get("id", "")
                names = [d.text or "" for d in el.findall("display-name")]
                ks = ["i:" + norm(id_base(cid))] + ["n:" + clean_name(n) + "|" + country(cid) for n in names]
                hit = [k for k in ks if k in want and k not in keymap]
                if hit:
                    idx_of[cid] = len(chans)
                    chans.append([])
                    for k in hit:
                        keymap[k] = idx_of[cid]
                el.clear()
            elif el.tag == "programme":
                i = idx_of.get(el.get("channel", ""))
                if i is not None:
                    try:
                        a, b = ts(el.get("start", "")), ts(el.get("stop", ""))
                    except ValueError:
                        el.clear(); continue
                    if b > lo and a < hi:
                        t = (el.findtext("title") or "").strip()
                        d = (el.findtext("desc") or "").strip().replace("\n", " ")
                        cats = " ".join(c.text or "" for c in el.findall("category"))
                        k = 1 if COREANO.search(f"{t} {d} {cats}") else 0
                        if len(d) > DESC:
                            d = d[:DESC - 1].rstrip() + "…"
                        chans[i].append([a, b, t, d, k])
                el.clear()
    for p in chans:
        p.sort(key=lambda x: x[0])
    return {"u": now, "k": keymap, "c": chans}


def stream_urls(text):
    return [l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]


def vivo(url):
    """True si la señal responde (o está bloqueada por país); False si está caída."""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 TeleLibre"})
        with urllib.request.urlopen(req, timeout=10) as r:
            head = r.read(2048)
        return r.status < 400 and (b"#EXTM3U" in head or not url.lower().split("?")[0].endswith(".m3u8"))
    except urllib.error.HTTPError as e:
        return e.code in GEO
    except Exception:
        return False


def caidos(urls, hilos=40):
    from concurrent.futures import ThreadPoolExecutor
    urls = list(dict.fromkeys(urls))
    with ThreadPoolExecutor(hilos) as ex:
        res = list(ex.map(vivo, urls))
    return [u for u, ok in zip(urls, res) if not ok]


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "guia.json"
    m3u = get(M3U).decode("utf-8", "replace")
    blobs = []
    for u in EPGS:
        try:
            blobs.append(get(u)); print("ok ", u)
        except Exception as e:
            print("sin", u, e)
    g = build(m3u, blobs)
    urls = stream_urls(m3u)
    g["x"] = caidos(urls)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(g, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(g['c'])} canales con guía, {len(g['k'])} claves, "
          f"{len(g['x'])} de {len(urls)} señales caídas -> {out}")


if __name__ == "__main__":
    main()
