const { app, BrowserWindow, Menu, Tray, nativeImage, shell } = require('electron');
const path = require('path');

let mainWindow = null;
let tray = null;
const PORT = 37248;

async function startServer() {
  process.env.PORT = String(PORT);
  process.env.HOST = '127.0.0.1';
  const { startService } = require('../server');
  await startService();
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1100,
    height: 720,
    minWidth: 700,
    minHeight: 500,
    title: 'Sher',
    backgroundColor: '#000000',
    autoHideMenuBar: true,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
    },
  });

  mainWindow.loadURL(`http://127.0.0.1:${PORT}`);

  mainWindow.on('close', (e) => {
    if (tray) { e.preventDefault(); mainWindow.hide(); }
  });

  mainWindow.on('closed', () => { mainWindow = null; });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http')) shell.openExternal(url);
    return { action: 'deny' };
  });
}

function createTray() {
  try {
    const icon = nativeImage.createFromDataURL('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAIklEQVQ4T2P8z8BQz0BAwMhAAJgYRg0YNWDUgFEDBpoHEwA7IgEJGbVfTQAAAABJRU5ErkJggg==');
    tray = new Tray(icon.resize({ width: 16, height: 16 }));
    const ctxMenu = Menu.buildFromTemplate([
      { label: '显示 Sher', click: () => { if (mainWindow) mainWindow.show(); else createWindow(); }},
      { type: 'separator' },
      { label: '退出', click: () => { tray.destroy(); app.quit(); }},
    ]);
    tray.setToolTip('Sher Music');
    tray.setContextMenu(ctxMenu);
    tray.on('click', () => { if (mainWindow) mainWindow.show(); });
  } catch(e) { /* tray not supported on some platforms */ }
}

const menu = Menu.buildFromTemplate([
  { label: 'Sher', submenu: [
    { role: 'about' }, { type: 'separator' }, { role: 'quit' },
  ]},
  { label: '编辑', submenu: [
    { role: 'undo' }, { role: 'redo' }, { type: 'separator' },
    { role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { role: 'selectAll' },
  ]},
  { label: '视图', submenu: [
    { role: 'reload' }, { role: 'toggleDevTools' }, { type: 'separator' },
    { role: 'zoomIn' }, { role: 'zoomOut' }, { role: 'resetZoom' },
  ]},
]);

app.whenReady().then(async () => {
  Menu.setApplicationMenu(menu);
  try { await startServer(); } catch (e) { console.error('Server start failed:', e); }
  createWindow();
  createTray();
});

app.on('window-all-closed', () => { if (!tray) app.quit(); });
app.on('activate', () => { if (!mainWindow) createWindow(); });
