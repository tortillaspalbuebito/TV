import json,sys,urllib.request;sys.path.insert(0,".");import guia
R="https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/"
def prueba(u):
    try:
        r=urllib.request.Request(u,headers={"User-Agent":"Mozilla/5.0"})
        with urllib.request.urlopen(r,timeout=15) as h: b=h.read(300); return f"{h.status} {h.geturl()[:80]} {b[:40]!r}"
    except Exception as e: return "ERR "+str(e)[:80]
s=json.loads(guia.get(R+"SamsungTVPlus/.channels.json.gz"))
es=s["regions"]["es"]["channels"]
libres=[k for k,v in es.items() if not v.get("license_url")]
print("Samsung ES", len(es), "sin DRM", len(libres), "grupos", sorted({v.get("group") for v in es.values()}))
for k in libres[:3]:
    for pat in ["https://jmp2.uk/stvp-%s","https://jmp2.uk/stvp-%s.m3u8","https://jmp2.uk/sam-%s.m3u8"]:
        print(pat%k, prueba(pat%k))
us=s["regions"]["us"]["channels"]
print("Samsung US grupos", sorted({v.get("group") for v in us.values()}))
print("US español:", [v["name"] for v in us.values() if "spa" in (v.get("group") or "").lower() or "españ" in (v.get("group") or "").lower() or "latin" in (v.get("group") or "").lower()][:80])
p=json.loads(guia.get(R+"PlutoTV/.channels.json.gz"))
for reg in ["mx","ar","cl","es","us"]:
    ch=p["regions"][reg]["channels"]; print("Pluto",reg,len(ch), sorted({v.get("group") for v in ch.values()})[:40])
mx=set(p["regions"]["mx"]["channels"])
for reg in ["ar","cl","es"]:
    nuevos=[v["name"] for k,v in p["regions"][reg]["channels"].items() if k not in mx]; print("Pluto",reg,"no en mx:",len(nuevos),nuevos[:60])
usesp=[v["name"] for k,v in p["regions"]["us"]["channels"].items() if k not in mx and re.search("spanish|español|latino",(v.get("group") or "")+v["name"],re.I)] if (re:=__import__("re")) else 0
print("Pluto US en español no en mx:",len(usesp),usesp[:60])
pl=json.loads(guia.get(R+"Plex/.channels.json.gz"))
mxp=[(k,v["name"]) for k,v in pl["channels"].items() if "mx" in v.get("regions",[])]
print("Plex mx",len(mxp),[n for _,n in mxp][:80]); print("Plex keys", list(list(pl["channels"].values())[0].keys()))
for k,_ in mxp[:2]:
    for pat in ["https://jmp2.uk/plex-%s.m3u8"]: print(pat%k, prueba(pat%k))
