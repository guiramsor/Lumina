const { contextBridge, ipcRenderer, webUtils } = require('electron');

/**
 * Puente mínimo entre la interfaz y el sistema de archivos.
 *
 * Existe por una sola razón: guardar la RUTA de los audiolibros en vez de una
 * copia de sus bytes. Antes cada libro importado se duplicaba dentro de
 * IndexedDB, así que una biblioteca de 3,4 GB ocupaba casi 7.
 *
 * Se expone lo mínimo imprescindible y nada más: no hay acceso general al
 * disco, solo resolver la ruta de un archivo que el propio usuario acaba de
 * elegir y preguntar si sigue existiendo.
 */
contextBridge.exposeInMainWorld('lumina', {
  /**
   * Ruta real de un `File` que el usuario ha soltado o seleccionado.
   * Desde Electron 32 `File.path` no existe y hay que pasar por webUtils.
   */
  rutaDeArchivo: (archivo) => {
    try {
      return webUtils.getPathForFile(archivo) || null;
    } catch {
      return null;
    }
  },

  /** ¿Sigue estando el archivo donde lo dejamos? */
  existe: (ruta) => ipcRenderer.invoke('lumina:existe', ruta),

  /** URL con la que el reproductor puede leer un archivo local. */
  urlDeAudio: (ruta) => `lumina://audio/?p=${encodeURIComponent(ruta)}`,
});
