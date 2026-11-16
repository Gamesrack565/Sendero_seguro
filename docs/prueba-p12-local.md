# Prueba local P12: mapa de calor y rutas offline

Esta prueba usa la app **debug**, el servidor Ktor local y PostgreSQL de Docker. No despliega ni modifica recursos de Azure. El teléfono conserva en Room la última capa de riesgo, el grafo vial y el manifiesto de paquetes; el paquete vectorial se guarda en el almacenamiento privado y se verifica con el tamaño y SHA-256 publicados por el servidor.

## Qué se sincroniza

- `GET /mapas/version` compara versiones de la capa de riesgo, del grafo y de los paquetes vigentes.
- Si cambia la capa, se descarga `GET /mapa-calor?periodo=30`.
- Si cambia el grafo, se descarga `GET /grafo` para el área de prueba de ESCOM, indicada en `ModuloMapas.kt`.
- Si cambia un paquete vigente, se descarga su archivo MBTiles y se comprueba su SHA-256 antes de reemplazar la copia local.
- La pantalla **Mapa P12** usa MapLibre para renderizar teselas vectoriales MBTiles, además de dibujar el grafo vial y la capa de calor como vectores. Muestra versiones/fechas, conteo de nodos y tramos y tamaños en MB. El estilo local espera las capas OpenMapTiles `transportation`, `transportation_name` y `water`; los glifos de Open Sans se incluyen en la app para que las etiquetas funcionen sin red.

No se descargan las rutas calculadas de otras personas: una ruta segura depende de un origen y destino concretos. P12 actualiza el grafo peatonal que permite calcular rutas sin conexión.

El botón **P7: Ruta Segura** de la pantalla Diagnóstico lee ese grafo desde Room y calcula la ruta con sus longitudes, riesgos y direccionalidad. Si no hay un grafo cacheado o no existe conexión entre los puntos, la bitácora muestra el motivo en vez de usar silenciosamente el grafo de ejemplo de P7. El contrato del grafo no incluye iluminación, así que esta conexión no inventa ese dato.

## Preparar el servidor local

1. Levanta PostgreSQL con `docker compose up -d` y reinicia el servidor Ktor local para que Flyway aplique la migración `V4__version_grafo.sql`.
2. Confirma que el teléfono puede abrir `http://<IP_LOCAL_DE_TU_PC>:8080/api/v1/salud`.
3. En `local.properties`, configura `senderoApiUrl` con la URL del servidor local terminada en `/api/v1/`. Usa la IP de tu PC accesible desde el teléfono, no `localhost`.
4. Instala/ejecuta la app debug desde Android Studio. La app programa una comprobación al abrirse y al recuperar conectividad.
5. En Inicio, abre **Mapa P12**. Mientras esa pantalla está visible se comprueban versiones cada 30 segundos, pero solo se descargan los datos que tienen una versión nueva.

En segundo plano, WorkManager necesita conexión y revisa periódicamente. Android fija el mínimo de un trabajo periódico en **15 minutos**; la ejecución exacta depende del sistema. Al volver la conexión se encola además un intento inmediato. Si Android fuerza la detención de la app, abre la app otra vez para reanudar los trabajos.

## Publicar una versión para probar

Desde PowerShell, con el servidor local activo:

```powershell
$api = "http://localhost:8080/api/v1"
$admin = Invoke-RestMethod -Method Post -Uri "$api/dev/token" `
  -ContentType "application/json" -Body '{"cuenta":"admin"}'
$headers = @{ Authorization = "Bearer $($admin.token)" }
```

Genera una versión nueva de la capa de calor:

```powershell
Invoke-RestMethod -Method Post -Uri "$api/mapa-calor/generar" `
  -Headers $headers -ContentType "application/json" -Body '{}'
Invoke-RestMethod -Method Get -Uri "$api/mapas/version" -Headers $headers
```

Para ver polígonos, la base local necesita al menos tres reportes publicados que formen una zona. La generación también sirve para probar el cambio de versión aunque todavía no haya zonas.

Para cargar un grafo de prueba dentro del área que consulta la app:

```powershell
$grafo = @{
  nodos = @(
    @{ id = 990000000001; latitud = 19.5045; longitud = -99.1469 },
    @{ id = 990000000002; latitud = 19.5050; longitud = -99.1460 },
    @{ id = 990000000003; latitud = 19.5055; longitud = -99.1450 }
  )
  aristas = @(
    @{ origen = 990000000001; destino = 990000000002; nombreVia = "Tramo de prueba"; bidireccional = $true },
    @{ origen = 990000000002; destino = 990000000003; nombreVia = "Tramo de prueba"; bidireccional = $true }
  )
} | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Post -Uri "$api/grafo" -Headers $headers `
  -ContentType "application/json" -Body $grafo
Invoke-RestMethod -Method Get -Uri "$api/mapas/version" -Headers $headers
```

Una carga completa (`POST /grafo`) aumenta `grafo` en `/mapas/version`; en cargas por partes manda `finalizarVersion=false` en las intermedias y omite el campo (o usa `true`) solo en la última. La publicación queda en la bitácora P13. Para probar una nueva descarga, vuelve a cargar un grafo o genera de nuevo la capa mientras **Mapa P12** está abierto. La versión y la fecha local deben actualizarse sin reinstalar la app. Cada teléfono sincroniza por separado al tener conexión; no es una notificación instantánea de servidor a dispositivo.

Los paquetes MBTiles se descargan cuando cambia su versión. No se acepta el paquete raster ZIP anterior. El MBTiles debe ser una base SQLite vectorial con `metadata.format = pbf`, teselas en la tabla `tiles` y capas OpenMapTiles `transportation`, `transportation_name` (con campos `name` o `name:es`) y `water`. El mapa base es de la región CDMX; el grafo descargado sigue limitado al área ESCOM.

Para esta prueba, `mexico-261115.osm.pbf` (extracto de todo México) se convirtió con el perfil Planetiler OpenMapTiles, incluyendo `transportation_name`, `place` y `poi`. La salida se recortó a la frontera administrativa OSM de CDMX (relación 1376330), entre zoom 8 y 16; `build/p12/CDMX-v2.mbtiles` pesa aproximadamente 72.5 MiB e incluye nombres de calles. La aplicación muestra atribución OpenMapTiles y OpenStreetMap. Publica ese archivo con una cuenta Administrador:

```powershell
$mbtiles = "C:\mapas\CDMX-v2.mbtiles"
$archivo = Invoke-RestMethod -Method Post -Uri "$api/archivos/mapa" `
  -Headers $headers -ContentType "application/vnd.sqlite3" -InFile $mbtiles

$paquete = @{
  region = "CDMX"
  version = "2"
  minLatitud = 19.0487187
  minLongitud = -99.3649242
  maxLatitud = 19.5927573
  maxLongitud = -98.9403028
  clave = $archivo.clave
  tamanoBytes = $archivo.tamanoBytes
  hashSha256 = $archivo.hashSha256
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$api/mapas/paquetes" -Headers $headers `
  -ContentType "application/json" -Body $paquete
```

La app selecciona el paquete cuya región es exactamente `CDMX`. La extensión, firma SQLite, formato PBF y capas OpenMapTiles (incluyendo la capa con nombres) se validan antes de guardar. Al publicar la versión 2, cada dispositivo la sincroniza y reemplaza la versión anterior después de verificar SHA-256. Comprueba en **Mapa P12** que se vean las calles etiquetadas, el mapa de calor y el grafo. La app muestra los nodos y tramos del grafo aunque falte el MBTiles, pero en ese caso el fondo vectorial no estará disponible.

## Verificación rápida

- En el teléfono, el mapa conserva la última capa descargada al cerrar la app o perder conexión.
- Con el MBTiles vectorial CDMX publicado y sincronizado, el mapa base, el grafo y la capa local siguen visibles sin red dentro de la cobertura del paquete.
- En **Mapa P12**, comprueba la versión y fecha de descarga; pulsa **Comprobar ahora** para pedir un intento inmediato.
- En **Mapa P12**, comprueba el conteo de nodos y tamaños MB. Abre **Diagnóstico P5.1 / P6 / P7** y pulsa **P7: Ruta Segura**; la bitácora debe identificar las versiones de grafo y capa usadas.
- En PowerShell, `GET /mapas/version` debe mostrar los valores vigentes de `capaRiesgo`, `grafo` y `paquetes`.
- Si un paquete falla en SHA-256 o tamaño, no reemplaza la copia anterior y WorkManager reintenta.
- P13 audita las cargas/publicaciones del servidor; P12 no necesita descargar la bitácora para mostrar el mapa.

El mapa base de **Mapa P12** no se genera en el teléfono ni se obtiene de osmdroid: necesita que el servidor publique un archivo MBTiles vectorial de CDMX y que el teléfono lo descargue al sincronizar. Sin ese archivo, el grafo y el mapa de calor pueden estar en caché, pero el fondo del mapa no estará disponible. La sincronización de la capa de calor usa los datos que P10 genera en el servidor; osmdroid sigue siendo independiente en las otras pantallas que todavía lo usan.
