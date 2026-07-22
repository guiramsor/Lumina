const fs = require('fs');
const path = require('path');
const pngToIco = require('png-to-ico').default;

const pngPath = path.join(__dirname, 'assets', 'icon.png');
const icoPath = path.join(__dirname, 'assets', 'icon.ico');

if (!fs.existsSync(pngPath)) {
  console.error('Error: assets/icon.png no existe. Por favor genera o coloca la imagen primero.');
  process.exit(1);
}

console.log('Generando archivo .ico compatible de alta resolución...');

pngToIco(pngPath)
  .then(buf => {
    fs.writeFileSync(icoPath, buf);
    console.log('¡Icono convertido con éxito usando png-to-ico! Guardado en: ' + icoPath);
  })
  .catch(err => {
    console.error('Error al convertir el icono:', err);
    process.exit(1);
  });
