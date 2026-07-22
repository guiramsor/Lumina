import { app, BrowserWindow, Menu, screen } from 'electron';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

let mainWindow;

const stateFile = () => path.join(app.getPath('userData'), 'window-state.json');

function loadWindowState() {
  try {
    const state = JSON.parse(fs.readFileSync(stateFile(), 'utf8'));
    // Solo restaurar si la posición guardada sigue siendo visible en algún monitor
    const visible = screen.getAllDisplays().some((d) => {
      const a = d.workArea;
      return (
        state.x >= a.x - 100 &&
        state.y >= a.y - 50 &&
        state.x < a.x + a.width - 100 &&
        state.y < a.y + a.height - 100
      );
    });
    if (!visible) return { maximized: state.maximized };
    return state;
  } catch {
    return {};
  }
}

function saveWindowState() {
  if (!mainWindow) return;
  try {
    const bounds = mainWindow.isMaximized() ? mainWindow.getNormalBounds() : mainWindow.getBounds();
    fs.writeFileSync(
      stateFile(),
      JSON.stringify({ ...bounds, maximized: mainWindow.isMaximized() })
    );
  } catch {
    /* ignorar: perder el estado de la ventana no es crítico */
  }
}

function createWindow() {
  const state = loadWindowState();
  mainWindow = new BrowserWindow({
    width: state.width || 1200,
    height: state.height || 800,
    x: state.x,
    y: state.y,
    minWidth: 950,
    minHeight: 650,
    show: false, // Evita mostrar la ventana antes de que esté lista, previniendo destellos blancos
    backgroundColor: '#0b0a14', // Color de fondo oscuro a juego con el tema de Lumina
    icon: path.join(__dirname, 'assets', 'icon.ico'),
    title: 'Lumina · Audiolibros',
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true
    },
  });

  // Ocultar el menú de navegación clásico (Archivo, Editar, etc.) para una estética premium e integrada
  Menu.setApplicationMenu(null);

  // Cargar la aplicación dependiendo del entorno
  const isDev = process.env.NODE_ENV === 'development';
  if (isDev) {
    mainWindow.loadURL('http://localhost:5173');
    mainWindow.webContents.openDevTools();
  } else {
    mainWindow.loadFile(path.join(__dirname, 'dist', 'index.html'));
  }

  // Mostrar la ventana suavemente una vez que todo el contenido visual esté listo
  mainWindow.once('ready-to-show', () => {
    if (state.maximized) mainWindow.maximize();
    mainWindow.show();
    mainWindow.focus();
  });

  // Recordar tamaño y posición entre sesiones
  mainWindow.on('close', saveWindowState);

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

// Inicializar la aplicación cuando esté lista
app.whenReady().then(() => {
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

// Salir cuando todas las ventanas estén cerradas, excepto en macOS
app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
