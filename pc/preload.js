// Puente seguro entre la app y el programa: solo expone la revisión de canales.
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('TLPC', {
  probar: (opts) => ipcRenderer.invoke('probar', (opts && opts.urls) || [])
});
