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
PAISES_LATAM = ["mx", "ar", "co", "cl", "pe", "ve", "ec", "gt", "cu", "bo", "do", "hn", "py", "sv", "ni", "cr", "pa", "uy", "pr"]


PLUTO_MX = "https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/PlutoTV/mx.xml.gz"


def pluto_mx(texto_lista):
    """Canales de Pluto TV México (gratis y oficiales) que todavía no están en la lista."""
    try:
        blob = get(PLUTO_MX)
    except Exception as e:
        print("sin Pluto TV", e)
        return "", None
    ya = set(re.findall(r"plu-([0-9a-f]{24})", texto_lista))
    extra, n = [], 0
    for ev, el in ET.iterparse(io.BytesIO(blob), events=("end",)):
        if el.tag == "channel":
            cid = el.get("id", "")
            nombre = (el.findtext("display-name") or "").strip()
            icono = el.find("icon")
            logo = icono.get("src", "") if icono is not None else ""
            if re.fullmatch(r"[0-9a-f]{24}", cid) and cid not in ya and nombre:
                extra.append(f'#EXTINF:-1 tvg-id="Pluto{cid}.mx" tvg-logo="{logo}" group-title="Undefined",{nombre}')
                extra.append(f"https://jmp2.uk/plu-{cid}.m3u8")
                n += 1
            el.clear()
        elif el.tag == "programme":
            el.clear()
    print(f"Pluto TV: {n} canales de México agregados")
    return "\n".join(extra), blob


def prioridad(pais):
    """Orden de la lista: México, Latinoamérica, EE. UU. (en español), otros, España."""
    if pais == "mx": return 0
    if pais in PAISES_LATAM: return 1
    if pais == "us": return 2
    if pais == "es": return 4
    return 3


def lista_completa(spa_text):
    """La lista en español + los canales latinos que no tienen idioma registrado."""
    import csv
    try:
        idioma = {}
        for f in csv.DictReader(io.StringIO(get(DB + "feeds.csv").decode())):
            idioma[f["channel"] + "@" + f["id"]] = f.get("languages", "")
            if f.get("is_main") == "TRUE":
                idioma.setdefault(f["channel"], f.get("languages", ""))
    except Exception as e:
        print("sin idiomas", e)
        return spa_text
    vistos = {l.strip() for l in spa_text.splitlines() if l.strip() and not l.startswith("#")}
    extra, n = [], 0
    for c in PAISES_LATAM:
        try:
            txt = get(f"https://iptv-org.github.io/iptv/countries/{c}.m3u").decode("utf-8", "replace").splitlines()
        except Exception as e:
            print("sin país", c, e); continue
        for i, l in enumerate(txt):
            if not l.startswith("#EXTINF") or i + 1 >= len(txt): continue
            url = txt[i + 1].strip()
            if not url or url.startswith("#") or url in vistos: continue
            tid = (re.search(r'tvg-id="([^"]*)"', l) or [None, ""])[1]
            langs = idioma.get(tid) or idioma.get(tid.split("@")[0], "")
            if langs and "spa" not in langs.split(";"): continue
            vistos.add(url); extra += [l, url]; n += 1
    print(f"lista: {n} canales latinos agregados a la lista en español")
    return spa_text.rstrip() + "\n" + "\n".join(extra) + "\n"

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


def estado_senal(url):
    """'ok', 'dudoso' (no respondió desde EE. UU.: puede verse en México) o 'caido' (ya no existe)."""
    import socket
    for intento in range(2):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Linux; Android) TeleLibre"})
            with urllib.request.urlopen(req, timeout=15) as r:
                head = r.read(4096)
            if b"#EXTM3U" in head or not url.lower().split("?")[0].endswith(".m3u8"):
                return "ok"
            return "caido" if b"<html" in head.lower() else "dudoso"
        except urllib.error.HTTPError as e:
            if e.code in (404, 410):
                return "caido"
            return "dudoso"
        except urllib.error.URLError as e:
            if isinstance(e.reason, socket.gaierror):
                return "caido"          # el dominio ya no existe
            if intento:
                return "dudoso"
        except Exception:
            if intento:
                return "dudoso"
    return "dudoso"


def caidos(urls, hilos=40):
    """Solo se esconden las señales que de plano ya no existen."""
    from collections import Counter
    from concurrent.futures import ThreadPoolExecutor
    urls = list(dict.fromkeys(urls))
    with ThreadPoolExecutor(hilos) as ex:
        res = list(ex.map(estado_senal, urls))
    print("revisión de señales:", dict(Counter(res)))
    return [u for u, r in zip(urls, res) if r == "caido"]


# ---------------------------------------------------------------------------
# Datos para la app de Roku: la misma lista que ve el celular (mismos filtros,
# números y categorías, tomados de index.html para no tener dos versiones).
# ---------------------------------------------------------------------------
def reglas_de_la_app(html):
    def lista(nombre):
        m = re.search(r"const " + nombre + r"=(\[.*?\]);", html, re.S)
        return json.loads(m.group(1).replace("'", '"'))
    def regex(nombre):
        m = re.search(r"const " + nombre + r"=/(.*?)/(i?);", html)
        return re.compile(m.group(1), re.I if m.group(2) else 0)
    num = dict(re.findall(r"(\w+):'([\d.]+)'", re.search(r"const NUM_MX=\{(.*?)\};", html).group(1)))
    grupos = json.loads(re.search(r"const GRUPOS=(\[.*?\]\]);", html, re.S).group(1).replace("'", '"'))
    pistas = [(k, re.compile(p.replace("\\/", "/"))) for k, p in
              re.findall(r"\['(\w+)',/(.*?)/\]", re.search(r"const PISTAS=\[(.*?)\];\n", html, re.S).group(1))]
    return dict(pago=lista("PAGO"), corto=regex("PAGO_CORTO"), gratis=regex("GRATIS"),
                redes=regex("REDES"), num=num, grupos=grupos, pistas=pistas)


def sin_acentos(s):
    return unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode().lower()


def para_roku(m3u_text, g, html, now=None):
    R = reglas_de_la_app(html)
    now = int(now or time.time())
    canales, meta = [], None
    for line in m3u_text.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF"):
            a = dict(re.findall(r'([\w-]+)="([^"]*)"', line))
            meta = dict(name=line.rsplit(",", 1)[-1].strip() or a.get("tvg-name", "Canal"),
                        id=a.get("tvg-id", ""), grp=a.get("group-title", "Undefined"), logo=a.get("tvg-logo", ""))
        elif line and not line.startswith("#") and meta:
            meta["url"], meta["pais"] = line, country(meta["id"])
            canales.append(meta); meta = None
    def sin_par(s): return re.sub(r"\(.*?\)|\[.*?\]", " ", s)
    def palabras(x):
        base = re.sub(r"\.[a-z]{2}$", "", id_base(x["id"]), flags=re.I)
        base = re.sub(r"(?<=[a-z])(?=[A-Z0-9])", " ", base)
        return [w for w in re.split(r"[^a-z0-9]+", sin_acentos(sin_par(x["name"]) + " " + base)) if w]
    def es_pago(x):
        if R["gratis"].search(x["url"]) or re.search(r" by ", x["name"], re.I): return False
        if any(R["corto"].match(w) for w in palabras(x)): return True
        s = norm(sin_par(x["name"])) + " " + norm(id_base(x["id"]))
        return any(p in s for p in R["pago"])
    def es_retrans(x):
        return (x["pais"] == "mx" and R["redes"].match(norm(re.sub(r"\.mx$", "", id_base(x["id"]), flags=re.I)) or "")
                and re.match(r"^https?://\d+\.\d+\.\d+\.\d+", x["url"]))
    canales = [x for x in canales if not re.search(r"xxx|adult", x["grp"] + x["name"], re.I)
               and not es_pago(x) and not es_retrans(x)]
    canales.sort(key=lambda x: (prioridad(x["pais"]), sin_acentos(x["name"])))
    # números: los abiertos con su número de siempre, el resto del 100 en adelante
    ident = lambda x: norm(id_base(x["id"])) if x["id"] else clean_name(x["name"]) + "|" + x["pais"]
    nums, n = {}, 100
    for x in canales:
        k = x["pais"] == "mx" and R["num"].get(norm(re.sub(r"\.mx$", "", id_base(x["id"]), flags=re.I)))
        if k: x["num"] = k
    for x in canales:
        if "num" not in x:
            i = ident(x)
            if i not in nums: nums[i] = str(n); n += 1
            x["num"] = nums[i]
    muertos, progs, salida, idx = set(g.get("x", [])), [], [], {}
    for x in canales:
        if x["url"] in muertos: continue
        cats = {c.strip().lower() for c in x["grp"].split(";") if c.strip()} - {"general", "undefined"}
        s = " " + sin_acentos(x["name"]) + " "
        cats |= {k for k, rx in R["pistas"] if rx.search(s)}
        cats = cats or {"general"}
        grupos = [k for k, _, ks in R["grupos"] if cats & set(ks)]
        p = -1
        ref = g["k"].get(app_key(x["id"], x["name"]))
        if ref is not None:
            if ref not in idx:
                pr = [[a, b, t, (r[0] if r else "")] for a, b, t, *r in g["c"][ref] if b > now - 7200 and a < now + 12 * 3600]
                idx[ref] = len(progs) if pr else -1
                if pr: progs.append(pr)
            p = idx[ref]
        salida.append({"n": x["num"], "t": x["name"], "u": x["url"], "l": x["logo"], "g": grupos, "p": p, "e": g.get("e", {}).get(app_key(x["id"], x["name"]), "")})
    return {"u": now, "grupos": [[k, t] for k, t, _ in R["grupos"]], "c": salida, "p": progs}


RADIO_API = "https://de1.api.radio-browser.info/json/stations/search"


def radios():
    """Estaciones de radio en vivo (sus propias señales), primero México y luego Latinoamérica."""
    from urllib.parse import urlencode
    out, vistos = [], set()
    for cc in ["mx"] + [p for p in PAISES_LATAM if p != "mx"]:
        q = urlencode(dict(countrycode=cc.upper(), hidebroken="true", order="clickcount", reverse="true",
                           limit=150 if cc == "mx" else 25))
        try:
            datos = json.loads(get(RADIO_API + "?" + q))
        except Exception as e:
            print("sin radios", cc, e); continue
        for r in datos:
            u = (r.get("url_resolved") or r.get("url") or "").strip()
            codec = (r.get("codec") or "").upper()
            if not u or u in vistos or codec not in ("MP3", "AAC", "AAC+") and ".m3u8" not in u:
                continue
            vistos.add(u)
            out.append({"t": r.get("name", "").strip()[:60], "u": u, "l": r.get("favicon", ""),
                        "pais": cc, "c": "hls" if ".m3u8" in u else ("mp3" if codec == "MP3" else "aac"),
                        "g": ", ".join((r.get("tags") or "").split(",")[:2])})
    print(f"radios: {len(out)} estaciones")
    return out


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "guia.json"
    m3u = lista_completa(get(M3U).decode("utf-8", "replace"))
    extra_pluto, guia_pluto = pluto_mx(m3u)
    if extra_pluto:
        m3u = m3u.rstrip() + "\n" + extra_pluto + "\n"
    with open("lista.m3u", "w", encoding="utf-8") as f:
        f.write(m3u)
    blobs = []
    for u in EPGS:
        try:
            blobs.append(get(u)); print("ok ", u)
        except Exception as e:
            print("sin", u, e)
    if guia_pluto:
        blobs.append(gzip.decompress(guia_pluto) if guia_pluto[:2] == b"\x1f\x8b" else guia_pluto)
    g = build(m3u, blobs)
    urls = stream_urls(m3u)
    g["x"] = caidos(urls)
    g["e"] = estados(m3u)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(g, f, ensure_ascii=False, separators=(",", ":"))
    print(f"{len(g['c'])} canales con guía, {len(g['k'])} claves, "
          f"{len(g['x'])} de {len(urls)} señales caídas, {len(g['e'])} canales locales -> {out}")
    rad = radios()
    with open("radios.json", "w", encoding="utf-8") as f:
        json.dump(rad, f, ensure_ascii=False, separators=(",", ":"))
    try:
        r = para_roku(m3u, g, open("index.html", encoding="utf-8").read())
        r["r"] = rad
        with open("roku.json", "w", encoding="utf-8") as f:
            json.dump(r, f, ensure_ascii=False, separators=(",", ":"))
        print(f"roku.json: {len(r['c'])} canales, {len(r['p'])} con programación")
    except Exception as e:
        print("no se pudo generar roku.json:", e)


if __name__ == "__main__":
    main()
