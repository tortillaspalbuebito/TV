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

    m.reg = CreateObject("roRegistrySection", "telelibre")
    m.favs = leerMapa("favs")
    m.ocultos = leerMapa("ocultos")
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

    m.cats.ObserveField("itemFocused", "alCambiarCategoria")
    m.lista.ObserveField("itemSelected", "alElegirCanal")
    m.panelLista.ObserveField("itemSelected", "alElegirEnPanel")
    m.guiaLista.ObserveField("itemSelected", "alElegirEnGuia")
    m.opcLista.ObserveField("itemSelected", "alElegirOpcion")
    m.video.ObserveField("state", "alCambiarEstadoVideo")
    m.osdTimer.ObserveField("fire", "ocultarOsd")
    m.saltoTimer.ObserveField("fire", "saltarAlSiguiente")
    m.refresco.ObserveField("fire", "actualizarAhora")

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

function ahoraSeg() as integer
    return CreateObject("roDateTime").AsSeconds()
end function

function falloReciente(c as object) as boolean
    if not m.fallosReg.DoesExist(c.u) then return false
    return ahoraSeg() - m.fallosReg[c.u] < 86400
end function

' ---------- Carga ----------
sub alFallarCarga()
    m.estado.text = m.tarea.error + " Revisa tu internet y vuelve a abrir la app."
end sub

sub alCargar()
    m.datos = m.tarea.resultado
    if m.datos = invalid or m.datos.c = invalid then return
    m.canales = m.datos.c
    m.estado.visible = false
    m.cats.visible = true
    m.lista.visible = true
    armarCategorias()
    filtrar("todo")

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
end sub

' ---------- Categorías ----------
sub armarCategorias()
    m.grupos = [{ k: "todo", t: "Todos" }, { k: "guia", t: "Guía de programación" }, { k: "fav", t: "Favoritos" }]
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

function textoAhora(c as object) as string
    t = programaAhora(c)
    if t <> "" then return "Ahora: " + t
    if falloReciente(c) then return "No abrió la última vez"
    return ""
end function

sub actualizarAhora()
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
    i = m.guiaLista.itemSelected
    if i < 0 or i >= m.guiaCanales.Count() then return
    ponerVista(m.guiaCanales)
    reproducir(i)
end sub

' ---------- Reproducción ----------
sub alElegirCanal()
    reproducir(m.lista.itemSelected)
end sub

sub reproducir(i as integer)
    if m.vista.Count() = 0 then return
    if i < 0 then i = 0
    if i >= m.vista.Count() then i = m.vista.Count() - 1
    m.cur = i
    c = m.vista[i]
    m.curUrl = c.u
    m.saltoTimer.control = "stop"
    m.video.control = "stop"
    contenido = CreateObject("roSGNode", "ContentNode")
    contenido.url = c.u
    contenido.title = c.t
    contenido.live = true
    if Instr(1, LCase(c.u), ".m3u8") > 0 then contenido.streamFormat = "hls"
    m.video.content = contenido
    m.video.visible = true
    m.menu.visible = false
    m.panel.visible = false
    m.opc.visible = false
    m.top.SetFocus(true)
    m.video.control = "play"
    m.reg.Write("ultima", c.u)
    m.reg.Flush()
    mostrarOsd(c.n + "   " + c.t, programaAhora(c))
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
    s = m.video.state
    if s = "buffering"
        if m.cur >= 0 and m.cur < m.vista.Count() then mostrarOsd(m.vista[m.cur].n + "   " + m.vista[m.cur].t, "Cargando...")
    else if s = "playing"
        m.fallos = 0
        if m.fallosReg.DoesExist(m.curUrl)
            m.fallosReg.Delete(m.curUrl)
            m.reg.Write("fallos", FormatJson(m.fallosReg))
            m.reg.Flush()
        end if
        if m.cur >= 0 and m.cur < m.vista.Count() then mostrarOsd(m.vista[m.cur].n + "   " + m.vista[m.cur].t, programaAhora(m.vista[m.cur]))
    else if s = "error"
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

sub saltarAlSiguiente()
    if m.video.visible then cambiarCanal(1)
end sub

sub mostrarOsd(titulo as string, detalle as string)
    m.osdNombre.text = titulo
    m.osdProg.text = detalle
    m.osd.visible = true
    m.osdTimer.control = "stop"
    m.osdTimer.control = "start"
end sub

sub ocultarOsd()
    m.osd.visible = false
end sub

sub volverALista()
    m.saltoTimer.control = "stop"
    m.video.control = "stop"
    m.video.visible = false
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
    agregarOpcion(cont, "cancelar", "Cancelar")
    m.opcTitulo.text = c.n + "   " + c.t
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
    if m.video.visible
        if m.panel.visible then m.panelLista.SetFocus(true) else m.top.SetFocus(true)
    else if m.catActual = "guia"
        m.guiaLista.SetFocus(true)
    else
        m.lista.SetFocus(true)
    end if
end sub

sub alElegirOpcion()
    i = m.opcLista.itemSelected
    if i < 0 or i >= m.opcAcciones.Count() then return
    accion = m.opcAcciones[i]
    c = m.opcCanal
    m.opc.visible = false
    if accion = "fav"
        alternarFavorito(c)
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
    viendo = m.video.visible and c.u = m.curUrl
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
    if not presionada then return false
    if m.opc.visible
        if tecla = "back" or tecla = "left" or tecla = "right" or tecla = "options"
            cerrarOpciones()
            return true
        end if
        return false
    end if
    if m.video.visible and m.panel.visible
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
    if m.video.visible
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
            if m.cur >= 0 and m.cur < m.vista.Count() then mostrarOsd(m.vista[m.cur].n + "   " + m.vista[m.cur].t, programaAhora(m.vista[m.cur]))
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
