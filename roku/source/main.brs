' Tele Libre para Roku
' Acepta canales mandados desde el celular (ECP): al abrir la app (args) o con la app abierta (roInput).
sub Main(args as dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.SetMessagePort(port)
    scene = screen.CreateScene("MainScene")
    screen.Show()
    entrada = CreateObject("roInput")
    entrada.SetMessagePort(port)
    if args <> invalid and args.contentId <> invalid and args.contentId <> "" then scene.lanzar = args.contentId
    while true
        msg = Wait(0, port)
        t = type(msg)
        if t = "roSGScreenEvent"
            if msg.IsScreenClosed() then return
        else if t = "roInputEvent"
            if msg.IsInput()
                info = msg.GetInfo()
                if info <> invalid and info.contentId <> invalid and info.contentId <> "" then scene.lanzar = info.contentId
            end if
        end if
    end while
end sub
