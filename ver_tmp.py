import json,sys;sys.path.insert(0,".");import guia
R="https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/"
for f in ["SamsungTVPlus","Plex","PlutoTV"]:
    d=json.loads(guia.get(R+f+"/.channels.json.gz"))
    print("==",f,type(d).__name__,list(d.keys())[:8] if isinstance(d,dict) else len(d))
    s=json.dumps(d,ensure_ascii=False)
    print(s[:1500])
    if f=="SamsungTVPlus":
        es=d.get("regions",{}).get("es",{}); print("ES keys",list(es.keys())[:10]); ch=es.get("channels",{}); print(len(ch)); print(json.dumps(list(ch.items())[:2],ensure_ascii=False)[:900])
    if f=="Plex":
        print([ (k,v.get("name"),v.get("regions"),v.get("language") ) for k,v in list(d.get("channels",d).items())[:3]] if isinstance(d,dict) else "")
