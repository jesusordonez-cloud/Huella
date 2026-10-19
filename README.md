# Huella

**Deja huella de cada evidencia.** Aplicación web para capturar fotografías de evidencia (talleres, capacitaciones, asambleas, reuniones de trabajo…) con un pie de página que incluye el logo de la asociación, el título, la clasificación, la fecha, las coordenadas GPS, el código Plus y la dirección del lugar. Funciona sin internet.

## Funciones

- Cámara en vivo (trasera y frontal) o selección de fotos del dispositivo.
- Mejora de imagen: automática, brillo, contraste, saturación, nitidez, temperatura y rotación.
- Pie de página profesional con logo, título, clasificación, asociación, fecha y hora, coordenadas, código Plus y dirección.
- Clasificaciones personalizables.
- Carpeta de destino elegida por la persona usuaria (Chrome/Edge de escritorio y Android), con subcarpeta por clasificación y ficha JSON opcional. Donde no es posible elegir carpeta, la foto se descarga.
- Ubicación sin internet: el GPS entrega coordenadas; la dirección se completa a partir de los **lugares frecuentes** registrados en Ajustes (nombre, dirección, coordenadas y radio).
- Instalable como app (PWA) y disponible sin conexión gracias al *service worker*.
- Modo claro y oscuro, diseño de cristal.

## Publicar en GitHub Pages

1. Crea un repositorio en GitHub y sube el contenido de esta carpeta.
2. En **Settings → Pages**, elige *Source: GitHub Actions* (el flujo `.github/workflows/pages.yml` ya está incluido) o bien *Deploy from a branch* con la rama `main` y carpeta `/ (root)`.
3. Abre la URL que te entregue GitHub Pages (`https://TU-USUARIO.github.io/huella/`). En el teléfono, usa *Agregar a pantalla de inicio* para instalarla.

> La cámara y la ubicación requieren HTTPS; GitHub Pages lo ofrece de forma predeterminada.

## Uso local

Abre `index.html` directamente en el navegador, o sirve la carpeta con cualquier servidor estático:

```bash
npx serve .
```

## Estructura

```
index.html      Aplicación completa (HTML, CSS y JavaScript en un solo archivo)
manifest.json   Manifiesto PWA
sw.js           Service worker para uso sin conexión
icons/          Íconos de la app
```

## Privacidad

Todo se procesa en el dispositivo. No se envía ninguna fotografía, coordenada ni dato a servidores externos. Los ajustes se guardan en el navegador (localStorage e IndexedDB).

## Licencia

MIT
