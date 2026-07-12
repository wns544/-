const { app, BrowserWindow, ipcMain, clipboard } = require("electron");
const http = require("http");
const fs = require("fs");
const path = require("path");

let server;
let baseUrl;
const appIconPath = path.join(__dirname, "..", "assets", "icon.ico");

function contentType(filePath) {
  if (filePath.endsWith(".html")) return "text/html; charset=utf-8";
  if (filePath.endsWith(".css")) return "text/css; charset=utf-8";
  if (filePath.endsWith(".js")) return "application/javascript; charset=utf-8";
  return "text/plain; charset=utf-8";
}

function startServer() {
  const root = __dirname;
  server = http.createServer((request, response) => {
    const url = new URL(request.url, "http://127.0.0.1");
    const pathname = url.pathname === "/" ? "/index.html" : url.pathname;
    const filePath = path.join(root, pathname.replace(/^\/+/, ""));
    if (!filePath.startsWith(root)) {
      response.writeHead(403);
      response.end("Forbidden");
      return;
    }
    fs.readFile(filePath, (error, data) => {
      if (error) {
        response.writeHead(404);
        response.end("Not found");
        return;
      }
      response.writeHead(200, { "Content-Type": contentType(filePath) });
      response.end(data);
    });
  });
  return new Promise((resolve) => {
    server.listen(0, "localhost", () => {
      const { port } = server.address();
      baseUrl = `http://localhost:${port}`;
      resolve(baseUrl);
    });
  });
}

function createWindow(widget = false) {
  const window = new BrowserWindow({
    width: widget ? 360 : 720,
    height: widget ? 460 : 720,
    minWidth: widget ? 280 : 520,
    minHeight: widget ? 260 : 520,
    frame: !widget,
    alwaysOnTop: widget,
    transparent: widget,
    icon: appIconPath,
    title: widget ? "잠깐 할 일 위젯" : "잠깐 할 일 PC",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  window.loadURL(`${baseUrl}/index.html${widget ? "?widget=1" : ""}`);
  return window;
}

app.whenReady().then(async () => {
  await startServer();
  createWindow(false);
});

ipcMain.handle("clipboard:write", (_event, text) => {
  clipboard.writeText(text || "");
});

ipcMain.handle("window:close", (event) => {
  BrowserWindow.fromWebContents(event.sender)?.close();
});

ipcMain.handle("window:minimize", (event) => {
  BrowserWindow.fromWebContents(event.sender)?.minimize();
});

app.on("window-all-closed", () => {
  if (server) {
    server.close();
  }
  app.quit();
});
