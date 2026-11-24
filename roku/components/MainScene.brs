' Tele Libre para Roku: canales por categoría, guía en cuadrícula, favoritos, ocultar canales
' y cambio de canal con el control.

sub init()
    m.cats = m.top.FindNode("cats")
    m.lista = m.top.FindNode("lista")
    m.estado = m.top.FindNode("estado")
    m.video = m.top.FindNode("video")
    m.menu = m.top.FindNode("menu")
    m.osd = m.top.FindNode("osd")
    m.osdNombre = m.top.FindNode("osdNombre")
    m.osdProg = m.top.FindNode("osdProg")
    m.osdTimer = m.top.FindNode("osdTimer")
    m.saltoTimer = m.top.FindNode("saltoTimer")
    m.reconTimer = m.top.FindNode("reconTimer")
    m.refresco = m.top.FindNode("refresco")
    m.panel = m.top.FindNode("panel")
    m.panelLista = m.top.FindNode("panelLista")
    m.guiaBox = m.top.FindNode("guiaBox")
    m.guiaHoras = m.top.FindNode("guiaHoras")
    m.guiaLista = m.top.FindNode("guiaLista")
    m.guiaLinea = m.top.FindNode("guiaLinea")
    m.opc = m.top.FindNode("opc")
    m.opcTitulo = m.top.FindNode("opcTitulo")
    m.opcLista = m.top.FindNode("opcLista")
    m.audio = m.top.FindNode("audio")
    m.radioBox = m.top.FindNode("radioBox")
    m.radioLogo = m.top.FindNode("radioLogo")
    m.radioNombre = m.top.FindNode("radioNombre")
    m.radioInfo = m.top.FindNode("radioInfo")
    m.osdDesc = m.top.FindNode("osdDesc")
    m.osdFondo = m.top.FindNode("osdFondo")
    m.reloj = m.top.FindNode("reloj")
    m.relojTimer = m.top.FindNode("relojTimer")

    m.reg = CreateObject("roRegistrySection", "telelibre")
    m.favs = leerMapa("favs")
    m.ocultos = leerMapa("ocultos")
    m.ajustes = {}
    t = m.reg.Read("ajustes")
    if t <> ""
        a = ParseJson(t)
        if a <> invalid then m.ajustes = a
    end if
    m.fallosReg = {}
    t = m.reg.Read("fallos")
    if t <> ""
        f = ParseJson(t)
        if f <> invalid then m.fallosReg = f
    end if

    m.canales = []
    m.vista = []
    m.grupos = []
    m.guiaCanales = []
    m.catActual = "todo"
    m.cur = -1
    m.curUrl = ""
    m.fallos = 0
    m.armando = false
    m.viendo = false
    m.visto = false
    m.reint = 0
    m.radios = []
    m.recientes = []
    t = m.reg.Read("recientes")
    if t <> ""
        r = ParseJson(t)
        if r <> invalid then m.recientes = r
    end if
    m.cc = (m.reg.Read("cc") = "1")
    if m.cc then m.video.globalCaptionMode = "On"

    m.cats.ObserveField("itemFocused", "alCambiarCategoria")
    m.lista.ObserveField("itemSelected", "alElegirCanal")
    m.panelLista.ObserveField("itemSelected", "alElegirEnPanel")
    m.guiaLista.ObserveField("itemSelected", "alElegirEnGuia")
    m.opcLista.ObserveField("itemSelected", "alElegirOpcion")
    m.video.ObserveField("state", "alCambiarEstadoVideo")
    m.osdTimer.ObserveField("fire", "ocultarOsd")
    m.saltoTimer.ObserveField("fire", "saltarAlSiguiente")
    m.reconTimer.ObserveField("fire", "reconectar")
    m.refresco.ObserveField("fire", "actualizarAhora")
    m.relojTimer.ObserveField("fire", "actualizarReloj")
    m.audio.ObserveField("state", "alCambiarEstadoAudio")
    actualizarReloj()
    m.relojTimer.control = "start"

    m.cats.visible = false
    m.lista.visible = false
    ' El reproductor no debe quedarse con las teclas del control: las maneja la app.
    m.video.enableUI = false

    m.tarea = CreateObject("roSGNode", "CargaTask")
    m.tarea.url = "https://raw.githubusercontent.com/__REPO__/main/roku.json"
    m.tarea.ObserveField("resultado", "alCargar")
    m.tarea.ObserveField("error", "alFallarCarga")
    m.tarea.control = "RUN"
end sub

' ---------- Errores: se atrapan para que la app no se cierre, y se anotan ----------
sub anotarError(donde as string, e as object)
    texto = donde
    if e <> invalid and e.message <> invalid then texto = texto + ": " + e.message
    if e <> invalid and e.number <> invalid then texto = texto + " (" + e.number.ToStr() + ")"
    m.reg.Write("ultimoError", texto)
    m.reg.Flush()
    if m.osd <> invalid then mostrarOsd("Se corrigió un error de la app", texto)
end sub

' ---------- Guardado en el Roku ----------
function leerMapa(clave as string) as object
    mapa = {}
    texto = m.reg.Read(clave)
    if texto <> ""
        arr = ParseJson(texto)
        if arr <> invalid
            for each u in arr
                mapa[u] = true
            end for
        end if
    end if
    return mapa
end function

sub guardarMapa(clave as string, mapa as object)
    arr = []
    for each u in mapa
        arr.Push(u)
    end for
    m.reg.Write(clave, FormatJson(arr))
    m.reg.Flush()
end sub

function esRadio(c as object) as boolean
    return c <> invalid and c.DoesExist("radio")
end function

function ahoraSeg() as integer
    return CreateObject("roDateTime").AsSeconds()
end function

function falloReciente(c as object) as boolean
    if not m.fallosReg.DoesExist(c.u) then return false
    return ahoraSeg() - m.fallosReg[c.u] < 86400
end function

' ---------- Carga ----------
sub alFallarCarga()
    try
        alFallarCargaSeguro()
    catch e
        anotarError("alFallarCarga", e)
    end try
end sub

sub alFallarCargaSeguro()
    m.estado.text = m.tarea.error + " Revisa tu internet y vuelve a abrir la app."
end sub

sub alCargar()
    try
        alCargarSeguro()
    catch e
        anotarError("alCargar", e)
    end try
end sub

sub alCargarSeguro()
    m.datos = m.tarea.resultado
    if m.datos = invalid or m.datos.c = invalid then return
    m.canales = m.datos.c
    errorPrevio = m.reg.Read("ultimoError")
    if m.datos.r <> invalid
        n = 0
        for each r in m.datos.r
            n = n + 1
            info = UCase(r.pais)
            if r.g <> invalid and r.g <> "" then info = info + "  ·  " + r.g
            m.radios.Push({ n: "R" + n.ToStr(), t: r.t, u: r.u, l: r.l, c: r.c, g: [], p: -1, radio: true, info: info })
        end for
    end if
    m.estado.visible = false
    m.cats.visible = true
    m.lista.visible = true
    armarCategorias()
    filtrar("todo")

    ' Si la vez pasada un canal no terminó de abrir (por ejemplo, Roku puso su letrero),
    ' no volver a ponerlo y decir cuál fue.
    intento = m.reg.Read("intentando")
    if intento <> ""
        m.reg.Delete("intentando")
        m.reg.Flush()
        sospechoso = invalid
        for each c in m.canales
            if c.u = intento then sospechoso = c
        end for
        for each c in m.radios
            if c.u = intento then sospechoso = c
        end for
        if sospechoso <> invalid
            m.lista.SetFocus(true)
            avisoIntento(sospechoso)
            m.refresco.control = "start"
            return
        end if
    end if

    ' Volver al último canal que se estaba viendo
    ultima = m.reg.Read("ultima")
    i = indiceDe(ultima)
    if i >= 0
        m.lista.jumpToItem = i
        reproducir(i)
    else
        m.lista.SetFocus(true)
    end if
    m.refresco.control = "start"
    if errorPrevio <> ""
        m.reg.Delete("ultimoError")
        m.reg.Flush()
        mostrarOsd("Error anotado la vez pasada", errorPrevio)
        m.osdTimer.duration = 15
    end if
end sub

' ---------- Categorías ----------
sub armarCategorias()
    m.grupos = [{ k: "todo", t: "Todos" }]
    if m.recientes.Count() > 0 then m.grupos.Push({ k: "recientes", t: "Recientes" })
    m.grupos.Push({ k: "guia", t: "Guía de programación" })
    m.grupos.Push({ k: "fav", t: "Favoritos" })
    if m.radios.Count() > 0 then m.grupos.Push({ k: "radio", t: "Radio en vivo  (" + m.radios.Count().ToStr() + ")" })
    for each g in m.datos.grupos
        n = 0
        for each c in m.canales
            if not m.ocultos.DoesExist(c.u) and enGrupo(c, g[0]) then n = n + 1
        end for
        if n > 0 then m.grupos.Push({ k: g[0], t: g[1] + "  (" + n.ToStr() + ")" })
    end for
    n = 0
    for each u in m.ocultos
        n = n + 1
    end for
    if n > 0 then m.grupos.Push({ k: "ocultos", t: "Canales ocultos  (" + n.ToStr() + ")" })
    cont = CreateObject("roSGNode", "ContentNode")
    foco = 0
    for i = 0 to m.grupos.Count() - 1
        item = cont.CreateChild("ContentNode")
        item.title = m.grupos[i].t
        if m.grupos[i].k = m.catActual then foco = i
    end for
    m.armando = true
    m.cats.content = cont
    m.cats.jumpToItem = foco
    m.armando = false
end sub

function enGrupo(c as object, k as string) as boolean
    if c.g = invalid then return false
    for each x in c.g
        if x = k then return true
    end for
    return false
end function

function indiceDe(url as string) as integer
    if url = "" then return -1
    for i = 0 to m.vista.Count() - 1
        if m.vista[i].u = url then return i
    end for
    return -1
end function

sub alCambiarCategoria()
    try
        alCambiarCategoriaSeguro()
    catch e
        anotarError("alCambiarCategoria", e)
    end try
end sub

sub alCambiarCategoriaSeguro()
    if m.armando = true then return
    i = m.cats.itemFocused
    if i < 0 or i >= m.grupos.Count() then return
    k = m.grupos[i].k
    if k = "guia"
        m.catActual = "guia"
        mostrarGuia()
    else
        m.guiaBox.visible = false
        m.lista.visible = true
        filtrar(k)
    end if
end sub

sub filtrar(k as string)
    m.catActual = k
    if k = "radio"
        ponerVista(m.radios)
        return
    end if
    if k = "recientes"
        arr = []
        for each u in m.recientes
            for each c in m.canales
                if c.u = u and not m.ocultos.DoesExist(c.u) then arr.Push(c)
            end for
            for each c in m.radios
                if c.u = u then arr.Push(c)
            end for
        end for
        ponerVista(arr)
        return
    end if
    buenos = []
    malos = []
    for each c in m.canales
        oculto = m.ocultos.DoesExist(c.u)
        entra = false
        if k = "ocultos"
            entra = oculto
        else if oculto
            entra = false
        else if k = "todo"
            entra = true
        else if k = "fav"
            entra = m.favs.DoesExist(c.u)
        else
            entra = enGrupo(c, k)
        end if
        if entra
            if falloReciente(c) then malos.Push(c) else buenos.Push(c)
        end if
    end for
    buenos.Append(malos)
    ponerVista(buenos)
end sub

' Arma la lista que se ve (y que usan el panel y Arriba/Abajo)
sub ponerVista(arr as object)
    m.vista = arr
    cont = CreateObject("roSGNode", "ContentNode")
    for each c in m.vista
        item = cont.CreateChild("ContentNode")
        logo = ""
        if c.l <> invalid then logo = c.l
        item.AddFields({ num: c.n, nombre: c.t, ahora: textoAhora(c), fav: m.favs.DoesExist(c.u), logo: logo })
    end for
    m.lista.content = cont
    m.cur = -1
    for i = 0 to m.vista.Count() - 1
        if m.vista[i].u = m.curUrl then m.cur = i
    end for
end sub

' ---------- Programación ----------
function programaAhora(c as object) as string
    if c.p = invalid or c.p < 0 or m.datos.p = invalid then return ""
    progs = m.datos.p[c.p]
    if progs = invalid then return ""
    ahora = ahoraSeg()
    for each pr in progs
        if pr[0] <= ahora and pr[1] > ahora then return pr[2]
    end for
    return ""
end function

' Programa actual con descripción y hora de término
function programaInfo(c as object) as object
    vacio = { t: "", d: "" }
    if esRadio(c) or c.p = invalid or c.p < 0 or m.datos.p = invalid then return vacio
    progs = m.datos.p[c.p]
    if progs = invalid then return vacio
    ahora = ahoraSeg()
    for i = 0 to progs.Count() - 1
        pr = progs[i]
        if pr[0] <= ahora and pr[1] > ahora
            t = pr[2] + "   (hasta las " + horaTexto(pr[1]) + ")"
            d = ""
            if pr.Count() > 3 and pr[3] <> invalid then d = pr[3]
            if i + 1 < progs.Count() then d = d + "   Después: " + progs[i + 1][2]
            return { t: t, d: d }
        end if
    end for
    return vacio
end function

sub actualizarReloj()
    try
        actualizarRelojSeguro()
    catch e
        anotarError("actualizarReloj", e)
    end try
end sub

sub actualizarRelojSeguro()
    m.reloj.text = horaTexto(ahoraSeg())
end sub

function textoAhora(c as object) as string
    if esRadio(c) then return "Radio · " + c.info
    t = programaAhora(c)
    if t <> "" then return "Ahora: " + t
    if falloReciente(c) then return "No abrió la última vez"
    return ""
end function

sub actualizarAhora()
    try
        actualizarAhoraSeguro()
    catch e
        anotarError("actualizarAhora", e)
    end try
end sub

sub actualizarAhoraSeguro()
    cont = m.lista.content
    if cont <> invalid
        for i = 0 to cont.GetChildCount() - 1
            if i < m.vista.Count() then cont.GetChild(i).ahora = textoAhora(m.vista[i])
        end for
    end if
    if m.guiaBox.visible then armarGuia()
end sub

function horaTexto(seg as integer) as string
    d = CreateObject("roDateTime")
    d.FromSeconds(seg)
    d.ToLocalTime()
    h = d.GetHours()
    mi = d.GetMinutes()
    sufijo = "am"
    if h >= 12 then sufijo = "pm"
    h12 = h mod 12
    if h12 = 0 then h12 = 12
    mm = mi.ToStr()
    if mi < 10 then mm = "0" + mm
    return h12.ToStr() + ":" + mm + " " + sufijo
end function

' ---------- Guía en cuadrícula ----------
sub mostrarGuia()
    m.lista.visible = false
    m.guiaBox.visible = true
    armarGuia()
end sub

sub armarGuia()
    if m.datos = invalid or m.datos.p = invalid then return
    ahora = ahoraSeg()
    inicio = ahora - (ahora mod 1800)
    usados = {}
    m.guiaCanales = []
    for each c in m.canales
        if c.p <> invalid and c.p >= 0 and not m.ocultos.DoesExist(c.u)
            clave = c.p.ToStr()
            if not usados.DoesExist(clave)
                usados[clave] = true
                m.guiaCanales.Push(c)
            end if
        end if
    end for
    cont = CreateObject("roSGNode", "ContentNode")
    for each c in m.guiaCanales
        item = cont.CreateChild("ContentNode")
        item.AddFields({ nombre: c.n + "  " + c.t, progs: m.datos.p[c.p], inicio: inicio })
    end for
    foco = m.guiaLista.itemFocused
    m.guiaLista.content = cont
    if foco > 0 and foco < m.guiaCanales.Count() then m.guiaLista.jumpToItem = foco
    ' Horas arriba de la cuadrícula
    m.guiaHoras.RemoveChildrenIndex(m.guiaHoras.GetChildCount(), 0)
    for k = 0 to 5
        l = CreateObject("roSGNode", "Label")
        l.text = horaTexto(inicio + k * 1800)
        l.translation = [260 + k * 165, 0]
        l.font = "font:SmallBoldSystemFont"
        l.color = "0x9AA1AAFF"
        m.guiaHoras.AppendChild(l)
    end for
    m.guiaLinea.translation = [260 + (ahora - inicio) / 60 * 5.5, 0]
end sub

sub alElegirEnGuia()
    try
        alElegirEnGuiaSeguro()
    catch e
        anotarError("alElegirEnGuia", e)
    end try
end sub

sub alElegirEnGuiaSeguro()
    i = m.guiaLista.itemSelected
    if i < 0 or i >= m.guiaCanales.Count() then return
    ponerVista(m.guiaCanales)
    reproducir(i)
end sub

' ---------- Reproducción ----------
sub alElegirCanal()
    try
        alElegirCanalSeguro()
    catch e
        anotarError("alElegirCanal", e)
    end try
end sub

sub alElegirCanalSeguro()
    reproducir(m.lista.itemSelected)
end sub

sub reproducir(i as integer)
    if m.vista.Count() = 0 then return
    if i < 0 then i = 0
    if i >= m.vista.Count() then i = m.vista.Count() - 1
    m.cur = i
    c = m.vista[i]
    m.curUrl = c.u
    m.visto = false
    m.reint = 0
    m.reconTimer.control = "stop"
    m.saltoTimer.control = "stop"
    m.video.control = "stop"
    m.audio.control = "stop"
    contenido = CreateObject("roSGNode", "ContentNode")
    contenido.url = c.u
    contenido.title = c.t
    contenido.live = true
    m.viendo = true
    if esRadio(c)
        contenido.streamFormat = c.c
        m.audio.content = contenido
        m.video.visible = false
        m.radioBox.visible = true
        m.radioLogo.uri = c.l
        m.radioNombre.text = c.t
        m.radioInfo.text = c.info
        m.audio.control = "play"
    else
        if Instr(1, LCase(c.u), ".m3u8") > 0 then contenido.streamFormat = "hls"
        aplicarAjuste(ajusteDe(c.u))
        m.video.content = contenido
        m.radioBox.visible = false
        m.video.visible = true
    end if
    ' Recientes: lo último que viste, primero
    nuevos = [c.u]
    for each u in m.recientes
        if u <> c.u and nuevos.Count() < 15 then nuevos.Push(u)
    end for
    m.recientes = nuevos
    m.reg.Write("recientes", FormatJson(m.recientes))
    m.menu.visible = false
    m.panel.visible = false
    m.opc.visible = false
    m.top.SetFocus(true)
    if not esRadio(c) then m.video.control = "play"
    m.reg.Write("ultima", c.u)
    ' Se borra cuando el canal empieza a verse; si Roku interrumpe, queda anotado
    m.reg.Write("intentando", c.u)
    m.reg.Flush()
    osdCanal(c, "")
end sub

sub cambiarCanal(d as integer)
    n = m.vista.Count()
    if n = 0 then return
    if m.cur < 0 or m.cur >= n
        m.cur = 0
        for i = 0 to n - 1
            if m.vista[i].u = m.curUrl then m.cur = i
        end for
    end if
    reproducir((m.cur + d + n) mod n)
end sub

sub alCambiarEstadoVideo()
    try
        alCambiarEstadoVideoSeguro()
    catch e
        anotarError("alCambiarEstadoVideo", e)
    end try
end sub

sub alCambiarEstadoVideoSeguro()
    s = m.video.state
    if s = "buffering"
        if m.cur >= 0 and m.cur < m.vista.Count() then osdCanal(m.vista[m.cur], "Cargando...")
    else if s = "playing"
        if m.reg.Exists("intentando")
            m.reg.Delete("intentando")
            m.reg.Flush()
        end if
        m.fallos = 0
        m.visto = true
        m.reint = 0
        if m.fallosReg.DoesExist(m.curUrl)
            m.fallosReg.Delete(m.curUrl)
            m.reg.Write("fallos", FormatJson(m.fallosReg))
            m.reg.Flush()
        end if
        if m.cur >= 0 and m.cur < m.vista.Count() then osdCanal(m.vista[m.cur], "")
    else if s = "error" and m.visto
        ' Ya se estaba viendo: reconectar el mismo canal, nunca saltar
        if m.reint < 5
            m.reint = m.reint + 1
            if m.cur >= 0 and m.cur < m.vista.Count() then osdCanal(m.vista[m.cur], "Reconectando...")
            m.reconTimer.control = "start"
        else
            mostrarOsd("Se perdió la señal", "Usa Arriba / Abajo para otro canal, o Regresar")
        end if
    else if s = "error"
        if m.reg.Exists("intentando") then m.reg.Delete("intentando")
        ' Recordar que no abrió, para mandarlo al final de la lista
        m.fallosReg[m.curUrl] = ahoraSeg()
        m.reg.Write("fallos", FormatJson(m.fallosReg))
        m.reg.Flush()
        m.fallos = m.fallos + 1
        if m.fallos <= 5 and m.vista.Count() > 1
            mostrarOsd("Este canal no está disponible", "Pasando al siguiente...")
            m.saltoTimer.control = "start"
        else
            m.fallos = 0
            mostrarOsd("Ningún canal respondió", "Usa Arriba / Abajo para probar otro, o Regresar")
        end if
    end if
end sub

sub alCambiarEstadoAudio()
    try
        alCambiarEstadoAudioSeguro()
    catch e
        anotarError("alCambiarEstadoAudio", e)
    end try
end sub

sub alCambiarEstadoAudioSeguro()
    s = m.audio.state
    if s = "buffering"
        m.radioInfo.text = "Conectando..."
    else if s = "playing"
        if m.reg.Exists("intentando") then m.reg.Delete("intentando")
        if m.cur >= 0 and m.cur < m.vista.Count() then m.radioInfo.text = m.vista[m.cur].info
    else if s = "error"
        m.radioInfo.text = "Esta estación no responde. Prueba otra con Arriba / Abajo."
    end if
end sub

sub reconectar()
    try
        reconectarSeguro()
    catch e
        anotarError("reconectar", e)
    end try
end sub

sub reconectarSeguro()
    if not m.viendo or m.video.content = invalid then return
    contenido = m.video.content
    m.video.control = "stop"
    m.video.content = contenido
    m.video.control = "play"
end sub

sub saltarAlSiguiente()
    try
        saltarAlSiguienteSeguro()
    catch e
        anotarError("saltarAlSiguiente", e)
    end try
end sub

sub saltarAlSiguienteSeguro()
    if m.viendo and m.video.visible then cambiarCanal(1)
end sub

sub osdCanal(c as object, estado as string)
    info = programaInfo(c)
    detalle = info.t
    if estado <> "" then detalle = estado
    if esRadio(c) then detalle = "Radio en vivo"
    mostrarOsd(c.n + "   " + c.t, detalle)
    if estado = "" and info.d <> ""
        m.osdDesc.text = info.d
        m.osdFondo.height = 290
    end if
end sub

sub mostrarOsd(titulo as string, detalle as string)
    m.osdNombre.text = titulo
    m.osdProg.text = detalle
    m.osdDesc.text = ""
    m.osdFondo.height = 170
    m.osd.visible = true
    m.osdTimer.control = "stop"
    m.osdTimer.control = "start"
end sub

sub ocultarOsd()
    try
        ocultarOsdSeguro()
    catch e
        anotarError("ocultarOsd", e)
    end try
end sub

sub ocultarOsdSeguro()
    m.osd.visible = false
    m.osdTimer.duration = 4
end sub

'' ---------- Ajuste de imagen por canal ----------
function ajusteDe(u as string) as string
    if m.ajustes.DoesExist(u) then return m.ajustes[u]
    return "normal"
end function

function nombreAjuste(modo as string) as string
    if modo = "ampliar" then return "Ampliar (recorta arriba y abajo)"
    if modo = "estirar" then return "Estirar (llena deformando)"
    return "Normal"
end function

sub aplicarAjuste(modo as string)
    m.video.scale = [1.0, 1.0]
    if modo = "ampliar"
        m.video.width = 2560
        m.video.height = 1440
        m.video.translation = [-320, -180]
    else
        m.video.width = 1920
        m.video.height = 1080
        m.video.translation = [0, 0]
        if modo = "estirar"
            m.video.scaleRotateCenter = [960, 540]
            m.video.scale = [1.3333, 1.0]
        end if
    end if
end sub

sub cambiarAjuste()
    if m.cur < 0 or m.cur >= m.vista.Count() or esRadio(m.vista[m.cur]) then return
    c = m.vista[m.cur]
    modo = ajusteDe(c.u)
    if modo = "normal"
        modo = "ampliar"
    else if modo = "ampliar"
        modo = "estirar"
    else
        modo = "normal"
    end if
    if modo = "normal" then m.ajustes.Delete(c.u) else m.ajustes[c.u] = modo
    m.reg.Write("ajustes", FormatJson(m.ajustes))
    m.reg.Flush()
    aplicarAjuste(modo)
    mostrarOsd("Imagen: " + nombreAjuste(modo), "Botón ↺ para cambiarla. Se guarda para este canal.")
end sub

sub volverALista()
    m.saltoTimer.control = "stop"
    m.video.control = "stop"
    m.audio.control = "stop"
    m.viendo = false
    m.video.visible = false
    m.radioBox.visible = false
    m.osd.visible = false
    m.panel.visible = false
    m.menu.visible = true
    if m.catActual = "guia"
        m.guiaLista.SetFocus(true)
    else
        filtrar(m.catActual)
        if m.cur >= 0 and m.cur < m.vista.Count() then m.lista.jumpToItem = m.cur
        m.lista.SetFocus(true)
    end if
end sub

' ---------- Panel lateral ----------
sub abrirPanel()
    if m.lista.content = invalid then return
    m.panelLista.content = m.lista.content
    m.panel.visible = true
    m.osd.visible = false
    if m.cur >= 0 then m.panelLista.jumpToItem = m.cur
    m.panelLista.SetFocus(true)
end sub

sub cerrarPanel()
    m.panel.visible = false
    m.top.SetFocus(true)
end sub

sub alElegirEnPanel()
    try
        alElegirEnPanelSeguro()
    catch e
        anotarError("alElegirEnPanel", e)
    end try
end sub

sub alElegirEnPanelSeguro()
    reproducir(m.panelLista.itemSelected)
end sub

' ---------- Menú de opciones (botón *) ----------
sub abrirOpciones(c as object)
    if c = invalid then return
    m.opcCanal = c
    m.opcAcciones = []
    cont = CreateObject("roSGNode", "ContentNode")
    if m.favs.DoesExist(c.u)
        agregarOpcion(cont, "fav", "Quitar de favoritos")
    else
        agregarOpcion(cont, "fav", "Agregar a favoritos")
    end if
    if m.ocultos.DoesExist(c.u)
        agregarOpcion(cont, "mostrar", "Mostrar de nuevo este canal")
    else
        agregarOpcion(cont, "ocultar", "Ocultar este canal")
    end if
    if m.viendo and not esRadio(c) and c.u = m.curUrl
        agregarOpcion(cont, "ajuste", "Ajustar imagen (ahora: " + nombreAjuste(ajusteDe(c.u)) + ")")
        if m.cc
            agregarOpcion(cont, "cc", "Desactivar subtítulos")
        else
            agregarOpcion(cont, "cc", "Activar subtítulos (si el canal los trae)")
        end if
    end if
    agregarOpcion(cont, "cancelar", "Cancelar")
    m.opcTitulo.text = c.n + "   " + c.t
    m.opcLista.content = cont
    m.opcLista.jumpToItem = 0
    m.opc.visible = true
    m.opcLista.SetFocus(true)
end sub

sub avisoIntento(c as object)
    m.opcCanal = c
    m.opcAcciones = []
    cont = CreateObject("roSGNode", "ContentNode")
    agregarOpcion(cont, "ocultar", "Ocultar ese canal")
    agregarOpcion(cont, "cancelar", "Dejarlo")
    m.opcTitulo.text = "No terminó de abrir: " + c.n + "  " + c.t
    m.opcLista.content = cont
    m.opcLista.jumpToItem = 0
    m.opc.visible = true
    m.opcLista.SetFocus(true)
end sub

sub agregarOpcion(cont as object, accion as string, texto as string)
    item = cont.CreateChild("ContentNode")
    item.title = texto
    m.opcAcciones.Push(accion)
end sub

sub cerrarOpciones()
    m.opc.visible = false
    if m.viendo
        if m.panel.visible then m.panelLista.SetFocus(true) else m.top.SetFocus(true)
    else if m.catActual = "guia"
        m.guiaLista.SetFocus(true)
    else
        m.lista.SetFocus(true)
    end if
end sub

sub alElegirOpcion()
    try
        alElegirOpcionSeguro()
    catch e
        anotarError("alElegirOpcion", e)
    end try
end sub

sub alElegirOpcionSeguro()
    i = m.opcLista.itemSelected
    if i < 0 or i >= m.opcAcciones.Count() then return
    accion = m.opcAcciones[i]
    c = m.opcCanal
    m.opc.visible = false
    if accion = "fav"
        alternarFavorito(c)
        cerrarOpciones()
    else if accion = "ajuste"
        cerrarOpciones()
        cambiarAjuste()
    else if accion = "cc"
        m.cc = not m.cc
        if m.cc
            m.video.globalCaptionMode = "On"
            m.reg.Write("cc", "1")
            mostrarOsd("Subtítulos activados", "Se ven solo si el canal los transmite")
        else
            m.video.globalCaptionMode = "Off"
            m.reg.Write("cc", "0")
            mostrarOsd("Subtítulos desactivados", "")
        end if
        m.reg.Flush()
        cerrarOpciones()
    else if accion = "ocultar"
        ocultarCanal(c)
    else if accion = "mostrar"
        m.ocultos.Delete(c.u)
        guardarMapa("ocultos", m.ocultos)
        armarCategorias()
        filtrar(m.catActual)
        mostrarOsd("Canal visible de nuevo", c.n + "   " + c.t)
        cerrarOpciones()
    else
        cerrarOpciones()
    end if
end sub

sub ocultarCanal(c as object)
    m.ocultos[c.u] = true
    guardarMapa("ocultos", m.ocultos)
    viendo = m.viendo and c.u = m.curUrl
    idx = m.cur
    if m.catActual = "guia" then armarGuia()
    armarCategorias()
    if m.catActual <> "guia" then filtrar(m.catActual)
    mostrarOsd("Canal oculto", "Para verlo de nuevo: categoría Canales ocultos")
    if viendo
        ' Quitarlo de la lista que se usa con Arriba/Abajo y pasar al siguiente
        resto = []
        for each x in m.vista
            if x.u <> c.u then resto.Push(x)
        end for
        ponerVista(resto)
        if idx >= resto.Count() then idx = resto.Count() - 1
        if idx >= 0
            reproducir(idx)
        else
            volverALista()
        end if
    else
        cerrarOpciones()
    end if
end sub

sub alternarFavorito(c as object)
    if c = invalid then return
    if m.favs.DoesExist(c.u)
        m.favs.Delete(c.u)
        aviso = "Quitado de favoritos"
    else
        m.favs[c.u] = true
        aviso = "Agregado a favoritos"
    end if
    guardarMapa("favs", m.favs)
    for j = 0 to m.vista.Count() - 1
        if m.vista[j].u = c.u and m.lista.content <> invalid then m.lista.content.GetChild(j).fav = m.favs.DoesExist(c.u)
    end for
    mostrarOsd(aviso, c.n + "   " + c.t)
end sub

' ---------- Control remoto ----------
function onKeyEvent(tecla as string, presionada as boolean) as boolean
    r = true
    try
        r = teclaSegura(tecla, presionada)
    catch e
        anotarError("tecla " + tecla, e)
    end try
    return r
end function

function teclaSegura(tecla as string, presionada as boolean) as boolean
    if not presionada then return false
    if m.opc.visible
        if tecla = "back" or tecla = "left" or tecla = "right" or tecla = "options"
            cerrarOpciones()
            return true
        end if
        return false
    end if
    if m.viendo and m.panel.visible
        if tecla = "back" or tecla = "right"
            cerrarPanel()
            return true
        else if tecla = "options"
            i = m.panelLista.itemFocused
            if i >= 0 and i < m.vista.Count() then abrirOpciones(m.vista[i])
            return true
        end if
        return true
    end if
    if m.viendo
        if tecla = "left"
            abrirPanel()
            return true
        else if tecla = "up" or tecla = "fastforward"
            cambiarCanal(1)
            return true
        else if tecla = "down" or tecla = "rewind"
            cambiarCanal(-1)
            return true
        else if tecla = "back"
            volverALista()
            return true
        else if tecla = "options"
            if m.cur >= 0 and m.cur < m.vista.Count() then abrirOpciones(m.vista[m.cur])
            return true
        else if tecla = "OK" or tecla = "right"
            if m.cur >= 0 and m.cur < m.vista.Count()
                m.osdTimer.duration = 8
                osdCanal(m.vista[m.cur], "")
            end if
            return true
        else if tecla = "replay"
            cambiarAjuste()
            return true
        else if tecla = "play"
            if m.video.state = "paused"
                m.video.control = "resume"
            else
                m.video.control = "pause"
            end if
            return true
        end if
        return true
    end if
    if tecla = "right" and m.cats.HasFocus()
        if m.catActual = "guia" then m.guiaLista.SetFocus(true) else m.lista.SetFocus(true)
        return true
    else if tecla = "left" and (m.lista.HasFocus() or m.guiaLista.HasFocus())
        m.cats.SetFocus(true)
        return true
    else if tecla = "options" and m.lista.HasFocus()
        i = m.lista.itemFocused
        if i >= 0 and i < m.vista.Count() then abrirOpciones(m.vista[i])
        return true
    else if tecla = "options" and m.guiaLista.HasFocus()
        i = m.guiaLista.itemFocused
        if i >= 0 and i < m.guiaCanales.Count() then abrirOpciones(m.guiaCanales[i])
        return true
    end if
    return false
end function
