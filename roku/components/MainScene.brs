' Tele Libre para Roku: lista de canales por categoría, guía y cambio de canal con el control.

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

    m.reg = CreateObject("roRegistrySection", "telelibre")
    m.favs = {}
    texto = m.reg.Read("favs")
    if texto <> ""
        arr = ParseJson(texto)
        if arr <> invalid
            for each u in arr
                m.favs[u] = true
            end for
        end if
    end if

    m.canales = []
    m.vista = []
    m.grupos = []
    m.cur = -1
    m.fallos = 0

    m.cats.ObserveField("itemFocused", "alCambiarCategoria")
    m.lista.ObserveField("itemSelected", "alElegirCanal")
    m.panelLista.ObserveField("itemSelected", "alElegirEnPanel")
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

sub alFallarCarga()
    m.estado.text = m.tarea.error + " Revisa tu internet y vuelve a abrir la app."
end sub

sub alCargar()
    m.datos = m.tarea.resultado
    if m.datos = invalid or m.datos.c = invalid then return
    m.canales = m.datos.c
    m.estado.visible = false

    ' Categorías: Todos, Favoritos y las que tengan canales
    m.grupos = [{ k: "todo", t: "Todos" }, { k: "fav", t: "Favoritos" }]
    for each g in m.datos.grupos
        n = 0
        for each c in m.canales
            if enGrupo(c, g[0]) then n = n + 1
        end for
        if n > 0 then m.grupos.Push({ k: g[0], t: g[1] + "  (" + n.ToStr() + ")" })
    end for
    cont = CreateObject("roSGNode", "ContentNode")
    for each g in m.grupos
        item = cont.CreateChild("ContentNode")
        item.title = g.t
    end for
    m.cats.content = cont
    m.cats.visible = true
    m.lista.visible = true
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
    i = m.cats.itemFocused
    if i < 0 or i >= m.grupos.Count() then return
    filtrar(m.grupos[i].k)
end sub

sub filtrar(k as string)
    m.vista = []
    for each c in m.canales
        if k = "todo"
            m.vista.Push(c)
        else if k = "fav"
            if m.favs.DoesExist(c.u) then m.vista.Push(c)
        else if enGrupo(c, k)
            m.vista.Push(c)
        end if
    end for
    cont = CreateObject("roSGNode", "ContentNode")
    for each c in m.vista
        item = cont.CreateChild("ContentNode")
        item.AddFields({ num: c.n, nombre: c.t, ahora: textoAhora(c), fav: m.favs.DoesExist(c.u) })
    end for
    m.lista.content = cont
    m.cur = -1
end sub

' Programa que va en este momento
function programaAhora(c as object) as string
    if c.p = invalid or c.p < 0 or m.datos.p = invalid then return ""
    progs = m.datos.p[c.p]
    if progs = invalid then return ""
    ahora = CreateObject("roDateTime").AsSeconds()
    for each pr in progs
        if pr[0] <= ahora and pr[1] > ahora then return pr[2]
    end for
    return ""
end function

function textoAhora(c as object) as string
    t = programaAhora(c)
    if t = "" then return ""
    return "Ahora: " + t
end function

sub actualizarAhora()
    cont = m.lista.content
    if cont = invalid then return
    for i = 0 to cont.GetChildCount() - 1
        if i < m.vista.Count() then cont.GetChild(i).ahora = textoAhora(m.vista[i])
    end for
end sub

sub alElegirCanal()
    reproducir(m.lista.itemSelected)
end sub

sub reproducir(i as integer)
    if m.vista.Count() = 0 then return
    if i < 0 then i = 0
    if i >= m.vista.Count() then i = m.vista.Count() - 1
    m.cur = i
    c = m.vista[i]
    m.saltoTimer.control = "stop"
    contenido = CreateObject("roSGNode", "ContentNode")
    contenido.url = c.u
    contenido.title = c.t
    contenido.live = true
    if Instr(1, LCase(c.u), ".m3u8") > 0 then contenido.streamFormat = "hls"
    m.video.content = contenido
    m.video.visible = true
    m.menu.visible = false
    m.panel.visible = false
    m.top.SetFocus(true)
    m.video.control = "play"
    m.reg.Write("ultima", c.u)
    m.reg.Flush()
    mostrarOsd(c.n + "   " + c.t, programaAhora(c))
end sub

' Panel de canales a un lado, sin quitar el video
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

sub cambiarCanal(d as integer)
    n = m.vista.Count()
    if n = 0 then return
    reproducir((m.cur + d + n) mod n)
end sub

sub alCambiarEstadoVideo()
    s = m.video.state
    if s = "playing"
        m.fallos = 0
    else if s = "error"
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
    if m.cur >= 0 and m.cur < m.vista.Count() then m.lista.jumpToItem = m.cur
    m.lista.SetFocus(true)
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
    arr = []
    for each u in m.favs
        arr.Push(u)
    end for
    m.reg.Write("favs", FormatJson(arr))
    m.reg.Flush()
    i = -1
    for j = 0 to m.vista.Count() - 1
        if m.vista[j].u = c.u then i = j
    end for
    if i >= 0 and m.lista.content <> invalid then m.lista.content.GetChild(i).fav = m.favs.DoesExist(c.u)
    mostrarOsd(aviso, c.n + "   " + c.t)
end sub

function onKeyEvent(tecla as string, presionada as boolean) as boolean
    if not presionada then return false
    if m.video.visible and m.panel.visible
        if tecla = "back" or tecla = "right"
            cerrarPanel()
            return true
        end if
        return true
    end if
    if m.video.visible
        if tecla = "left"
            abrirPanel()
            return true
        else if tecla = "up"
            cambiarCanal(1)
            return true
        else if tecla = "down"
            cambiarCanal(-1)
            return true
        else if tecla = "back"
            volverALista()
            return true
        else if tecla = "options"
            if m.cur >= 0 then alternarFavorito(m.vista[m.cur])
            return true
        else if tecla = "OK" or tecla = "right"
            if m.cur >= 0 then mostrarOsd(m.vista[m.cur].n + "   " + m.vista[m.cur].t, programaAhora(m.vista[m.cur]))
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
        m.lista.SetFocus(true)
        return true
    else if tecla = "left" and m.lista.HasFocus()
        m.cats.SetFocus(true)
        return true
    else if tecla = "options" and m.lista.HasFocus()
        i = m.lista.itemFocused
        if i >= 0 and i < m.vista.Count() then alternarFavorito(m.vista[i])
        return true
    end if
    return false
end function
