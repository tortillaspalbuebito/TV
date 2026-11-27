// Tele Libre para PC (Windows): abre la misma app del celular en su propia ventana.
const { app, BrowserWindow, Menu, shell } = require('electron');
const path = require('path');

// Muchas señales de TV son http o no permiten abrirse desde otras páginas;
// como esto es un programa (no una página web), se le permite abrirlas.
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

function crear() {
  const w = new BrowserWindow({
    width: 1280, height: 800, minWidth: 380, minHeight: 500,
    backgroundColor: '#0d0f14', title: 'Tele Libre', autoHideMenuBar: true,
    icon: path.join(__dirname, 'app', 'icon-512.png'),
    webPreferences: { webSecurity: false, allowRunningInsecureContent: true, contextIsolation: true }
  });
  Menu.setApplicationMenu(null);
  // Algunas señales piden que la petición parezca de un navegador normal
  w.webContents.session.webRequest.onBeforeSendHeaders((d, cb) => {
    const h = d.requestHeaders;
    h['User-Agent'] = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';
    delete h['Origin'];
    cb({ requestHeaders: h });
  });
  // Enlaces externos (Google Calendar, GitHub…) se abren en el navegador
  w.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' }; });
  // F11 = pantalla completa, F5 = recargar
  w.webContents.on('before-input-event', (e, i) => {
    if (i.type !== 'keyDown') return;
    if (i.key === 'F11') { w.setFullScreen(!w.isFullScreen()); e.preventDefault(); }
    if (i.key === 'F5') { w.reload(); e.preventDefault(); }
    if (i.key === 'Escape' && w.isFullScreen()) w.setFullScreen(false);
  });
  w.loadFile(path.join(__dirname, 'app', 'index.html'));
}

app.whenReady().then(crear);
app.on('window-all-closed', () => app.quit());
