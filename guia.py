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
        for p in ["MX", "ES", "AR", "CO", "CL", "PE", "US_LOCALS", "DO", "UY", "EC", "CR"]]
HORAS = 18          # cuántas horas hacia adelante guardar
DESC = 110          # largo máximo de la descripción
COREANO = re.compile(r"\b(core[ao]s?|coreanas?|dorama|doramas|k-?drama|k-?dramas|korean|seúl|seul)\b", re.I)
# Respuestas que significan "probablemente bloqueado por país", no "caído":
# GitHub corre en EE. UU., así que esos canales NO se esconden.
GEO = {401, 403, 451}
DB = "https://raw.githubusercontent.com/iptv-org/database/master/data/"
# Ciudades y estados que, si aparecen en el nombre de un canal mexicano, lo ubican.
LUGARES = {
    "monterrey": "NLE", "nuevo leon": "NLE", "guadalajara": "JAL", "jalisco": "JAL", "tijuana": "BCN",
    "mexicali": "BCN", "ensenada": "BCN", "baja california sur": "BCS", "los cabos": "BCS", "cancun": "ROO",
    "chetumal": "ROO", "quintana roo": "ROO", "merida": "YUC", "yucatan": "YUC", "puebla": "PUE",
    "queretaro": "QUE", "toluca": "MEX", "mexiquense": "MEX", "edomex": "MEX", "acapulco": "GRO",
    "chilpancingo": "GRO", "oaxaca": "OAX", "veracruz": "VER", "xalapa": "VER", "jalapa": "VER",
    "villahermosa": "TAB", "tabasco": "TAB", "tuxtla": "CHP", "chiapas": "CHP", "hermosillo": "SON",
    "sonora": "SON", "culiacan": "SIN", "mazatlan": "SIN", "sinaloa": "SIN", "durango": "DUR",
    "torreon": "COA", "saltillo": "COA", "coahuila": "COA", "chihuahua": "CHH", "cd juarez": "CHH",
    "ciudad juarez": "CHH", "reynosa": "TAM", "nuevo laredo": "TAM", "matamoros": "TAM", "tampico": "TAM",
    "tamaulipas": "TAM", "san luis potosi": "SLP", "zacatecas": "ZAC", "aguascalientes": "AGU",
    "morelia": "MIC", "michoacan": "MIC", "cuernavaca": "MOR", "pachuca": "HID", "tlaxcala": "TLA",
    "tepic": "NAY", "nayarit": "NAY", "colima": "COL", "campeche": "CAM", "guanajuato": "GUA",
    "irapuato": "GUA", "celaya": "GUA", "cdmx": "CMX", "ciudad de mexico": "CMX",
}


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


STOP = {"canal", "tv", "de", "del", "la", "el", "mexico", "hd", "sd", "fhd", "uhd", "4k",
        "1080p", "720p", "480p", "360p", "channel", "television", "nacional"}
SINON = {"azteca": "az", "once": "11"}


def core(s):
    """Nombre reducido a lo esencial: 'Canal.Azteca.Uno.mx' y 'AztecaUno' -> 'azuno'."""
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode()
    s = re.sub(r"\(.*?\)|\[.*?\]", " ", s)
    s = re.sub(r"(?<=[a-z])(?=[A-Z0-9])|(?<=[0-9])(?=[A-Za-z])", " ", s)
    toks = [SINON.get(t, t) for t in re.split(r"[^a-z0-9]+", s.lower()) if t and t not in STOP]
    return "".join(toks)


def id_sin_pais(i):
    return re.sub(r"\.[a-z]{2}$", "", id_base(i), flags=re.I)


def niveles(tvg_id, names):
    """Claves de búsqueda, de la más segura a la más floja."""
    cc = country(tvg_id)
    out = [set(), set(), set(), set()]
    if tvg_id:
        out[0].add("i:" + norm(id_base(tvg_id)))
        c = core(id_sin_pais(tvg_id))
        if c:
            out[2].add("c:" + c + "|" + cc)
    for n in names:
        if clean_name(n):
            out[1].add("n:" + clean_name(n) + "|" + cc)
            out[3].add("n:" + clean_name(n) + "|")
        if core(n):
            out[2].add("c:" + core(n) + "|" + cc)
    return out


def app_key(tvg_id, name):
    """La clave que busca la app (index.html) para este canal."""
    return "i:" + norm(id_base(tvg_id)) if tvg_id else "n:" + clean_name(name) + "|" + country(tvg_id)


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 TeleLibre"})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    if url.endswith(".gz") or data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    return data


def m3u_canales(text):
    out = []
    for line in text.splitlines():
        if line.startswith("#EXTINF"):
            attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', line))
            out.append((attrs.get("tvg-id", ""), line.rsplit(",", 1)[-1].strip()))
    return list(dict.fromkeys(out))


def ts(s):
    s = s.strip()
    fmt = "%Y%m%d%H%M%S %z" if " " in s else "%Y%m%d%H%M%S"
    if " " not in s:
        s = s[:14]
    return int(datetime.strptime(s, fmt).timestamp())


def build(m3u_text, epg_blobs, now=None):
    now = int(now or time.time())
    lo, hi = now - 3 * 3600, now + HORAS * 3600
    canales = m3u_canales(m3u_text)
    # 1) Canales de las guías: sus claves por nivel (gana el primero que aparece).
    epg = [{}, {}, {}, {}]
    for bi, blob in enumerate(epg_blobs):
        for ev, el in ET.iterparse(io.BytesIO(blob), events=("end",)):
            if el.tag == "channel":
                cid = el.get("id", "")
                names = [d.text or "" for d in el.findall("display-name")] + [id_sin_pais(cid).replace(".", " ")]
                for lv, ks in enumerate(niveles(cid, names)):
                    if lv == 0:
                        ks = {"i:" + norm(id_base(cid))}
                    for k in ks:
                        epg[lv].setdefault(k, (bi, cid))
                el.clear()
            elif el.tag == "programme":
                el.clear()
    # 2) Cada canal de la lista se empareja con la clave más segura que coincida.
    asign = {}
    for tid, name in canales:
        ak = app_key(tid, name)
        if ak in asign:
            continue
        for lv, ks in enumerate(niveles(tid, [name])):
            hit = next((epg[lv][k] for k in sorted(ks) if k in epg[lv]), None)
            if hit:
                asign[ak] = hit
                break
    # 3) Programación solo de los canales emparejados.
    idx, chans = {}, []
    for ref in asign.values():
        if ref not in idx:
            idx[ref] = len(chans)
            chans.append([])
    keymap = {k: idx[r] for k, r in asign.items()}
    for bi, blob in enumerate(epg_blobs):
        for ev, el in ET.iterparse(io.BytesIO(blob), events=("end",)):
            if el.tag == "programme":
                i = idx.get((bi, el.get("channel", "")))
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
            elif el.tag == "channel":
                el.clear()
    for p in chans:
        p.sort(key=lambda x: x[0])
    return {"u": now, "k": keymap, "c": chans}


def palabras(s):
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode().lower()
    return " " + " ".join(re.split(r"[^a-z0-9]+", s)).strip() + " "


def estados(m3u_text):
    """Estado de México de cada canal local: {clave de la app: 'JAL'}."""
    import csv
    area = {}
    try:
        cities = {c["code"]: c["subdivision"] for c in csv.DictReader(io.StringIO(get(DB + "cities.csv").decode()))}
        for f in csv.DictReader(io.StringIO(get(DB + "feeds.csv").decode())):
            if not f["channel"].lower().endswith(".mx"):
                continue
            for a in f["broadcast_area"].split(";"):
                sub = a[2:] if a.startswith("s/") else cities.get(a[3:]) if a.startswith("ct/") else None
                if sub and sub.startswith("MX-"):
                    area[f["channel"] + "@" + f["id"]] = sub[3:]
                    break
    except Exception as e:
        print("sin base de canales", e)
    out = {}
    for tid, name in m3u_canales(m3u_text):
        if country(tid) != "mx":
            continue
        code = area.get(tid)
        if not code:
            w = palabras(name + " " + id_sin_pais(tid).replace(".", " "))
            code = next((c for l, c in LUGARES.items() if f" {l} " in w), None)
        if code:
            out[app_key(tid, name)] = code
    return out


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
    g["e"] = estados(m3u)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(g, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(g['c'])} canales con guía, {len(g['k'])} claves, "
          f"{len(g['x'])} de {len(urls)} señales caídas, {len(g['e'])} canales locales -> {out}")


if __name__ == "__main__":
    main()
