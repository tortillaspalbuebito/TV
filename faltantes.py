#!/usr/bin/env python3
"""Informe: canales gratis y legales en español que existen en fuentes oficiales
(Pluto TV, Samsung TV Plus, Plex, Free-TV, TDTChannels) y que NO están en lista.m3u.
Solo imprime el informe; no cambia la lista."""
import json, re, sys, concurrent.futures as cf
sys.path.insert(0, ".")
import guia

FUENTES = []
for reg in ["mx", "ar", "cl", "co", "pe", "es", "us", "br", "latam", "all"]:
    FUENTES.append((f"Pluto {reg}", f"https://i.mjh.nz/PlutoTV/{reg}.m3u8"))
for reg in ["mx", "es", "us", "br", "all"]:
    FUENTES.append((f"Samsung {reg}", f"https://i.mjh.nz/SamsungTVPlus/{reg}.m3u8"))
for reg in ["mx", "es", "us", "all"]:
    FUENTES.append((f"Plex {reg}", f"https://i.mjh.nz/Plex/{reg}.m3u8"))
FUENTES.append(("Roku", "https://i.mjh.nz/Roku/all.m3u8"))
FUENTES.append(("Free-TV", "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"))
FUENTES.append(("TDTChannels", "https://www.tdtchannels.com/lists/tv.m3u8"))

ESP = re.compile(r"\b(es|spa|spanish|español|espanol|latino|latin|en español|mexico|méxico|argentina|chile|colombia|peru|perú|venezuela|ecuador|uruguay|paraguay|bolivia|guatemala|honduras|el salvador|nicaragua|costa rica|panama|panamá|cuba|dominicana|puerto rico|spain|españa)\b", re.I)

def clave(s):
    s = guia.sin_acentos(re.sub(r"\(.*?\)|\[.*?\]", " ", s or ""))
    s = re.sub(r"\b(tv|hd|fhd|sd|canal|channel|en vivo|live|by pluto|pluto|plex|samsung|tv\+)\b", " ", s)
    return re.sub(r"[^a-z0-9]", "", s)

def parse(txt):
    out, meta = [], None
    for l in txt.splitlines():
        l = l.strip()
        if l.startswith("#EXTINF"):
            a = dict(re.findall(r'([\w-]+)="([^"]*)"', l))
            meta = dict(name=l.rsplit(",", 1)[-1].strip(), id=a.get("tvg-id", "") or a.get("channel-id", ""),
                        grp=a.get("group-title", ""), lang=a.get("tvg-language", "") + " " + a.get("tvg-country", ""))
        elif l and not l.startswith("#") and meta:
            meta["url"] = l; out.append(meta); meta = None
    return out

def vivo(url):
    try:
        import urllib.request
        r = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(r, timeout=12) as h:
            return b"#EXTM3U" in h.read(4096)
    except Exception:
        return False

actual = guia.get("https://raw.githubusercontent.com/tortillaspalbuebito/TV/main/lista.m3u").decode("utf-8", "replace")
tengo = {clave(c["name"]) for c in parse(actual)} | {clave(c["id"]) for c in parse(actual)}
tengo_url = {c["url"] for c in parse(actual)}
R = guia.reglas_de_la_app(open("index.html", encoding="utf-8").read())
def es_pago(n):
    s = guia.norm(n)
    return any(p in s for p in R["pago"]) or any(R["corto"].match(w) for w in re.split(r"[^a-z0-9]+", guia.sin_acentos(n)) if w)

print(f"Ya en la lista: {len(parse(actual))} canales")
faltan = {}
for nombre, url in FUENTES:
    try:
        cs = parse(guia.get(url).decode("utf-8", "replace"))
    except Exception as e:
        print(f"[{nombre}] no disponible ({e})"); continue
    esp = [c for c in cs if nombre.startswith(("Pluto mx", "Pluto ar", "Pluto cl", "Pluto co", "Pluto pe", "Pluto es", "Pluto latam", "Samsung mx", "Samsung es", "Plex mx", "Plex es", "TDT"))
           or ESP.search(c["grp"] + " " + c["lang"] + " " + c["name"])]
    nuevos = [c for c in esp if clave(c["name"]) not in tengo and c["url"] not in tengo_url and clave(c["name"])]
    print(f"[{nombre}] {len(cs)} canales, {len(esp)} en español, {len(nuevos)} no están")
    for c in nuevos:
        k = clave(c["name"])
        if k not in faltan:
            c["fuente"] = nombre; c["pago"] = es_pago(c["name"]); faltan[k] = c

with cf.ThreadPoolExecutor(40) as ex:
    for c, ok in zip(faltan.values(), ex.map(lambda c: vivo(c["url"]), faltan.values())):
        c["vivo"] = ok
print(f"\nTOTAL que faltan: {len(faltan)} · funcionando desde GitHub: {sum(c['vivo'] for c in faltan.values())}")
for c in sorted(faltan.values(), key=lambda c: (c["fuente"], c["name"])):
    print(f"FALTA|{c['fuente']}|{'OK' if c['vivo'] else '--'}|{'PAGO?' if c['pago'] else ''}|{c['grp']}|{c['name']}|{c['url']}")
