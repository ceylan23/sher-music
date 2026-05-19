const { app, BrowserWindow, Menu, shell } = require('electron');
const path = require('path');

let mainWindow;
const PORT = 13000;

async function startApiServer() {
  process.env.PORT = String(PORT);
  process.env.HOST = '127.0.0.1';
  const { startService } = require('../server');
  await startService();
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 800,
    minHeight: 600,
    title: 'Sher',
    backgroundColor: '#000000',
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
    },
    ...(process.platform === 'darwin' ? {
      titleBarStyle: 'hiddenInset',
      trafficLightPosition: { x: 16, y: 16 }
    } : {}),
  });

  mainWindow.loadURL(`http://127.0.0.1:${PORT}`);

  mainWindow.on('closed', () => { mainWindow = null; });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http')) shell.openExternal(url);
    return { action: 'deny' };
  });
}

const menu = Menu.buildFromTemplate([
  { label: 'Sher', submenu: [
    { role: 'about' },
    { type: 'separator' },
    { role: 'quit' },
  ]},
  { label: 'Edit', submenu: [
    { role: 'undo' }, { role: 'redo' }, { type: 'separator' },
    { role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { role: 'selectAll' },
  ]},
  { label: 'View', submenu: [
    { role: 'reload' }, { role: 'toggleDevTools' }, { type: 'separator' },
    { role: 'zoomIn' }, { role: 'zoomOut' }, { role: 'resetZoom' },
  ]},
]);

app.whenReady().then(async () => {
  Menu.setApplicationMenu(menu);
  try {
    await startApiServer();
    console.log('API server started on port ' + PORT);
  } catch (e) {
    console.error('Failed to start server:', e);
  }
  createWindow();
});

app.on('window-all-closed', () => app.quit());
app.on('activate', () => { if (!mainWindow) createWindow(); });
