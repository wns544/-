const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("nudge", {
  copy: (text) => ipcRenderer.invoke("clipboard:write", text),
  close: () => ipcRenderer.invoke("window:close"),
  minimize: () => ipcRenderer.invoke("window:minimize")
});
