# Guía futura: integrar P9–P12 en Azure

Esta guía conserva los pasos para una integración futura del feed comunitario, los reportes, las colas offline y los mapas con la infraestructura Azure existente.

**No se ha desplegado ningún cambio en Azure ni se ha modificado la instancia publicada.** No ejecutes estos pasos hasta que se decida y autorice ese despliegue. La aprobación automática es únicamente para el servidor local de pruebas y queda excluida de esta guía.

> Ejecuta primero los pasos en un entorno de pruebas. Los reportes, las alertas y los avisos enviados al entorno productivo pueden generar datos reales o notificaciones.

## Qué se integraría

| Componente | Dónde corre | Integración futura |
|---|---|---|
| P9 — Mapa comunitario | App Android | Consultar `/feed` para reportes publicados; la app conserva el último feed local para usarlo offline. |
| P10 — Reportes | App Android y servidor Ktor | Enviar reportes con `POST /reportes`, evidencias en Blob Storage y cola P11 si no hay conectividad. En Azure se mantiene el flujo de moderación existente. |
| P11 — Cola offline | App Android | WorkManager reintenta reportes/evidencias y telemetría cuando vuelve la conexión. No requiere un servicio Azure nuevo. |
| P12 — Mapas offline | App Android y servidor Ktor | PostgreSQL guarda versiones y grafo; Blob Storage almacena MBTiles. |
| P6 — Telemetría | App Android y servidor Ktor | La app persiste primero en Room y sincroniza las lecturas después de registrar la alerta padre. |
| Módulo 4 — Backend | Azure App Service | JAR Ktor, PostgreSQL administrado, configuración existente y Blob Storage. Flyway aplica migraciones al iniciar. |

## Antes de autorizar un despliegue

1. Confirma con el responsable del entorno que se autoriza actualizar el App Service y revisa cuál es el grupo de recursos y la suscripción. El host documentado es:

   ```text
   sendero-seguro-bse3efh5ddexczcx.mexicocentral-01.azurewebsites.net
   ```

2. Usa la guía del [servidor del Módulo 4](../modulo4/README.md) para confirmar la configuración existente y preparar PostgreSQL de pruebas. No apuntes las pruebas locales a la base productiva.
3. Ejecuta las pruebas del servidor con `.\gradlew.bat :server:test`. Las pruebas de base de datos se omiten si no está definida `SENDERO_TEST_DB_URL`; no consideres aprobada la suite si fueron omitidas.
4. Ejecuta las pruebas Android y comprueba la compilación:

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest
   .\gradlew.bat :app:assembleDebug
   ```

5. Comprueba el respaldo reciente de PostgreSQL antes de desplegar. La migración `V4__version_grafo.sql` se aplica mediante Flyway al iniciar el nuevo servidor; no edites migraciones que ya se aplicaron.

## Publicar una futura versión del servidor

El App Service documentado usa Java 21 y PostgreSQL administrado. Conserva su runtime, comando de inicio, dominio y configuración actuales; no crees otro App Service para esta actualización.

1. Construye el artefacto con el wrapper del repositorio:

   ```powershell
   .\gradlew.bat :server:buildFatJar
   ```

   El archivo esperado es `server/build/libs/sendero-seguro-server.jar`.

2. Inicia sesión en Azure CLI y selecciona la suscripción correcta:

   ```powershell
   az login
   az account set --subscription "<ID_O_NOMBRE_DE_LA_SUSCRIPCION>"
   ```

3. Cuando exista autorización explícita, despliega el JAR al App Service existente, sustituyendo `<GRUPO_DE_RECURSOS>` por su grupo real:

   ```powershell
   az webapp deploy `
     --resource-group "<GRUPO_DE_RECURSOS>" `
     --name "sendero-seguro-bse3efh5ddexczcx" `
     --src-path ".\server\build\libs\sendero-seguro-server.jar" `
     --type jar
   ```

   Si el equipo utiliza otro flujo manual validado, publica el mismo artefacto con ese flujo.

4. Antes de reiniciar, confirma que se preservan las variables existentes del App Service: PostgreSQL, JWT, enlace, autor de reportes anónimos, Blob Storage, Firebase, SMS y WebSockets. No copies secretos en comandos, documentación, capturas ni commits.

   **No habilites publicación automática en Azure.** La opción local `reportes.autoPublicar` solo se aplica cuando `DB_DATOS_PRUEBA=true`; en el servicio publicado debe mantenerse `DB_DATOS_PRUEBA=false` y la moderación existente. Los reportes nuevos de Azure deben conservar el comportamiento configurado actualmente.

5. Una vez autorizado el despliegue, comprueba los logs del App Service y verifica:

   ```powershell
   $api = "https://sendero-seguro-bse3efh5ddexczcx.mexicocentral-01.azurewebsites.net/api/v1"
   Invoke-RestMethod "$api/salud"
   ```

   El servidor debe responder con `estado = OK`. Si no arranca, revisa los logs antes de volver a desplegar.

## Conectar y probar la app Android

La variante **release** ya apunta al host de Azure configurado en `app/build.gradle.kts`. Para dirigir temporalmente una variante debug a ese host:

```powershell
$api = "https://sendero-seguro-bse3efh5ddexczcx.mexicocentral-01.azurewebsites.net/api/v1/"
.\gradlew.bat :app:installDebug "-PsenderoApiUrl=$api"
```

Inicia sesión con una cuenta autorizada y verifica el envío en una ventana controlada. Para probar el flujo de reportes, confirma que un reporte nuevo queda sujeto al flujo de moderación vigente, que los reportes publicados aparecen en `GET /feed` y que al reactivar la conexión WorkManager entrega los reportes/evidencias de P11.

Los registros que ya estaban `PENDIENTE` no se publican automáticamente. Deben revisarse mediante el proceso autorizado de moderación; no los publiques masivamente sin revisar su privacidad y validez.

## Publicar datos de P12

Estas operaciones también requieren autorización del responsable del entorno. Usa un token de Administrador obtenido por el flujo de autenticación autorizado; no existe `/dev/token` en Azure.

```powershell
$api = "https://sendero-seguro-bse3efh5ddexczcx.mexicocentral-01.azurewebsites.net/api/v1"
$token = "<JWT_ADMINISTRADOR>"
$headers = @{ Authorization = "Bearer $token" }
```

### Publicar el mapa base MBTiles

Usa un archivo MBTiles vectorial válido para CDMX. La guía de [prueba local P12](../prueba-p12-local.md) describe el formato, las capas requeridas y la extensión. Sube el archivo al servidor y registra una versión mayor a la vigente:

```powershell
$mbtiles = "C:\mapas\CDMX-v2.mbtiles"
$archivo = Invoke-RestMethod -Method Post -Uri "$api/archivos/mapa" `
  -Headers $headers -ContentType "application/vnd.sqlite3" -InFile $mbtiles

$paquete = @{
  region = "CDMX"
  version = "<NUEVA_VERSION_MAYOR>"
  minLatitud = 19.0487187
  minLongitud = -99.3649242
  maxLatitud = 19.5927573
  maxLongitud = -98.9403028
  clave = $archivo.clave
  tamanoBytes = $archivo.tamanoBytes
  hashSha256 = $archivo.hashSha256
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri "$api/mapas/paquetes" `
  -Headers $headers -ContentType "application/json" -Body $paquete
```

No reutilices una versión vigente: los teléfonos descargan el paquete cuando detectan una versión nueva y verifican tamaño y SHA-256 antes de reemplazar la copia local.

### Publicar el grafo y generar el mapa de calor

- Publica con `POST /grafo` el grafo peatonal real de la cobertura que se va a probar. No subas el grafo de ejemplo de la guía local como si fuera el grafo real.
- Comprueba `GET /mapas/version` y confirma que la versión de `grafo` avanzó.
- Para generar el mapa de calor, usa `POST /mapa-calor/generar` con autorización de Administrador. El algoritmo actual no es IA: requiere al menos tres reportes publicados cercanos para formar una zona. La app sincroniza la capa de 30 días.
- Comprueba de nuevo `GET /mapas/version` y `GET /mapa-calor?periodo=30`.

## Verificación offline

1. Con internet, abre **Mapa P12** y comprueba versiones, fecha de sincronización, tamaño del paquete, mapa base, grafo y capa de calor.
2. Espera a que termine la descarga y activa modo avión. Dentro de la cobertura descargada deben seguir visibles el MBTiles y los datos cacheados. P9 usa la última respuesta de feed guardada.
3. Prueba P7 contra el grafo que P12 guardó en Room. Si el grafo no cubre el origen/destino, puede no existir una ruta.
4. Prueba P6/P11 en un entorno controlado: genera una alerta, corta la conexión, recupérala y confirma que se sincroniza primero la alerta y luego su telemetría.
5. Las pruebas nube existentes pueden crear y borrar cuentas, unidades y reportes. Ejecútalas primero en un entorno no productivo y revisa sus efectos antes de usarlas.

El mapa de calor no garantiza polígonos si no hay suficientes reportes públicos cercanos. El feed y el mapa no publican automáticamente los registros históricos pendientes.

## Actualizar y distribuir Android

Desplegar el JAR no actualiza la app ya instalada. Después de validar el backend, compila y distribuye el APK con el proceso de firma del proyecto:

```powershell
.\gradlew.bat :app:assembleRelease
```

La variante release usa la URL de Azure configurada en `app/build.gradle.kts`. Si cambia el host, actualiza esa configuración y recompila; no incluyas tokens ni llaves en el APK.

## Reversión

- Para revertir el servidor, vuelve a desplegar el último JAR aprobado. No reviertas ni edites migraciones de Flyway ya aplicadas.
- Si una publicación MBTiles tiene errores, publica una versión corregida mayor y deja que los teléfonos la descarguen y verifiquen antes de retirar la anterior.

La aprobación automática local no forma parte de esta integración futura. La IA de veracidad P10, el pronóstico IA-2 de P9 y la transcripción del audio también quedan fuera hasta que se definan y autoricen por separado.
