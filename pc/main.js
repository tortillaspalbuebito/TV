// Tele Libre para PC (Windows): abre la misma app del celular en su propia ventana.
const { app, BrowserWindow, Menu, shell, ipcMain } = require('electron');
const path = require('path');

// Muchas señales de TV son http o no permiten abrirse desde otras páginas;
// como esto es un programa (no una página web), se le permite abrirlas.
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

function crear() {
  const w = new BrowserWindow({
    width: 1280, height: 800, minWidth: 380, minHeight: 500,
    backgroundColor: '#0d0f14', title: 'Tele Libre', autoHideMenuBar: true,
    icon: path.join(__dirname, 'app', 'icon-512.png'),
    webPreferences: { webSecurity: false, allowRunningInsecureContent: true, contextIsolation: true, preload: path.join(__dirname, 'preload.js') }
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

// ---------- Revisar canales desde el internet de esta PC ----------
// Un canal "funciona" solo si de verdad llega video: lista -> calidad -> primer pedazo de video.
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';
async function bajar(url, rango) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), 12000);
  try {
    const h = { 'User-Agent': UA };
    if (rango) h.Range = 'bytes=0-2047';
    const r = await fetch(url, { headers: h, redirect: 'follow', signal: ctl.signal });
    if (!r.ok) return null;
    if (rango) { const b = await r.arrayBuffer(); return b.byteLength > 0 ? { url: r.url, texto: '' } : null; }
    return { url: r.url, texto: (await r.text()).slice(0, 300000) };
  } catch (e) { return null; } finally { clearTimeout(t); }
}
function primera(base, texto) {
  for (let l of texto.split(/\r?\n/)) { l = l.trim(); if (l && !l.startsWith('#')) return new URL(l, base).toString(); }
  return null;
}
async function responde(u) {
  try {
    let r = await bajar(u);
    if (!r) return false;
    if (!r.texto.includes('#EXTM3U')) return !/\.m3u8(\?|$)/i.test(u) && !/jmp2\.uk/.test(u);
    if (r.texto.includes('#EXT-X-STREAM-INF')) {
      const v = primera(r.url, r.texto.slice(r.texto.indexOf('#EXT-X-STREAM-INF')));
      if (!v) return false;
      r = await bajar(v);
      if (!r || !r.texto.includes('#EXTM3U')) return false;
    }
    const seg = primera(r.url, r.texto);
    return !!seg && !!(await bajar(seg, true));
  } catch (e) { return false; }
}
ipcMain.handle('probar', async (_e, urls) => {
  const ok = [], mal = [];
  let i = 0;
  async function trabajador() {
    while (i < urls.length) { const u = urls[i++]; ((await responde(u)) ? ok : mal).push(u); }
  }
  await Promise.all(Array.from({ length: 12 }, trabajador));
  return { ok, mal };
});

app.whenReady().then(crear);
app.on('window-all-closed', () => app.quit());
