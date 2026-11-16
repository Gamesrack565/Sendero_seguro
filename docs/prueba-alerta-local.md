# Prueba local de la cascada de alertas

La prueba usa la app Android en modo **debug**, el servidor Ktor local y PostgreSQL levantado por Docker. No requiere ni modifica el backend de Azure. La cascada intenta, en orden, registrar la alerta por internet, emitirla por malla P5.1, enviar SMS `SS1` si se configuró una pasarela y, si no hay confirmación, guardarla en la cola local P11.

## 1. Levantar la base y el servidor local

Desde PowerShell, en la raíz del repositorio:

```powershell
docker compose up -d
.\gradlew.bat :server:run
```

Deja abierta la terminal del servidor. Espera el mensaje de que Ktor está atendiendo en el puerto `8080` y comprueba `http://localhost:8080/api/v1/salud`.

Docker contiene PostgreSQL; el servidor Ktor se ejecuta aparte. `10.0.2.2` funciona solo en emuladores, no en un teléfono físico.

## 2. Conectar un teléfono físico

1. Conecta el teléfono y la PC a la misma red Wi-Fi (no a una red de invitados con aislamiento entre dispositivos).
2. En la PC, ejecuta `ipconfig` y busca la IPv4 del adaptador **Wi-Fi**. No uses la dirección de `vEthernet`, WSL, Docker ni otro adaptador virtual: el teléfono no puede usar esas direcciones para llegar a la PC.
3. En el navegador del teléfono, abre `http://<IP-WIFI-PC>:8080/api/v1/salud`, reemplazando el marcador por la IPv4 Wi-Fi real. Debe mostrar `{"estado":"OK",...}`. Si no abre, permite el puerto TCP `8080` para Java/Ktor en el perfil **Red privada** del Firewall de Windows y vuelve a probar; no expongas el puerto en redes públicas.
4. Para que el botón **Run** de Android Studio use esa misma dirección, agrega al archivo `local.properties` de la raíz del proyecto:

```properties
senderoApiUrl=http://<IP-WIFI-PC>:8080/api/v1/
```

`local.properties` está ignorado por Git y esta opción solo cambia la URL de debug; release conserva Azure. Con Depuración USB activa, conecta el teléfono y selecciónalo como dispositivo de ejecución en Android Studio. Pulsa **Sync Project with Gradle Files** y luego **Run**.

Como alternativa, instala desde la terminal integrada con:

```powershell
.\gradlew.bat :app:installDebug -PsenderoApiUrl=http://<IP-WIFI-PC>:8080/api/v1/
```

El permiso de HTTP sin cifrar para una IP local está limitado a debug. Si cambia la dirección Wi-Fi de la PC, actualiza `senderoApiUrl` en `local.properties`.

No desinstales la app ni borres sus datos para corregir el cierre: la actualización Room migra la base existente y conserva las alertas en cola.

## 3. Abrir y enviar una alerta

En Inicio, toca el botón de pánico para abrir el formulario. Los campos son tipo de incidente, descripción, latitud, longitud, precisión y batería; el valor inicial de tipo `1` es la alerta genérica de los datos locales de prueba. Al enviar, la tarjeta de estado muestra la etapa, el ID, el folio y el último estado consultado en la línea de tiempo del servidor.

El token de la cuenta local `ana` se solicita a `/api/v1/dev/token` solo en debug y solo cuando la URL es HTTP y apunta a loopback o a una IP privada local. La prueba local no reutiliza un token guardado para Azure. Si el servidor local no responde, la app deja continuar la cascada de contingencia.

El botón **Diagnóstico P5.1 / P6 / P7** sigue disponible en Inicio para consultar los estados y conceder permisos de Bluetooth necesarios para probar la malla.

## 4. Probar los canales

- **Internet y base local:** con Ktor y Docker activos, envía el formulario. La app registra `POST /api/v1/alertas` y consulta `GET /api/v1/alertas/{id}/timeline`.
- **Malla:** para una prueba física, usa dos o más teléfonos Android con Bluetooth activado y los permisos solicitados por el diagnóstico. El emisor no recibe acuse BLE; por eso la pantalla indica que el paquete fue emitido, y la app intenta el registro directo otra vez si recupera internet.
- **SMS:** por defecto está desactivado porque no hay número de pasarela. Si cuentas con una pasarela local configurada para enviar sus webhooks al servidor local, compila debug indicando el teléfono destino:

  ```powershell
  .\gradlew.bat :app:installDebug -PsenderoSmsGatewayNumber=+5255XXXXXXXX
  ```

  Sustituye `+5255XXXXXXXX` por el número real de 10 a 15 dígitos de tu pasarela; no copies el marcador literalmente. La app solicita permiso para SMS cuando se configura el número. Para que el servidor asocie el mensaje con Ana, el teléfono de origen debe coincidir con el teléfono registrado de esa cuenta y la pasarela debe entregar el SMS al servidor local. Enviar SMS reales puede generar cargos; Android solo confirma el envío al operador, no la recepción o el registro por la pasarela. Esta propiedad solo afecta debug; release mantiene el número vacío.
- **Cola P11, WorkManager y confirmación de malla:** para probar el guardado local, detén solo el proceso Ktor con `Ctrl+C` (deja Docker activo), no configures una pasarela SMS y envía otra alerta. Al no haber confirmación del servidor ni SMS, el estado debe quedar en **Cola local P11**. Cierra la app desde Recientes (no uses **Forzar detención**), vuelve a iniciar Ktor y deja el teléfono conectado a internet. WorkManager conserva el trabajo aunque el proceso de la app haya terminado, espera la conectividad y reintenta según el backoff persistido en Room. Si otro teléfono ya registró esa alerta por malla, al sincronizar el emisor consulta el canal de recepción del registro, muestra un aviso dentro de la app y publica una notificación Android si se concedió el permiso. Al sincronizarse, aparecerá el registro en PostgreSQL. Si el registro solo está en la cola, todavía no existe una fila en la base PostgreSQL. Android no ejecuta trabajos de una app que se haya forzado a detener; al abrirla de nuevo se volverán a programar.

La malla y el SMS necesitan hardware y configuración externos; un emulador por sí solo no valida su entrega física.

## 5. Consultar `evento_alerta` en PostgreSQL

Conéctate desde DBeaver, DataGrip o el cliente de PostgreSQL a `localhost:5432`, base `sendero_seguro`, usuario `postgres`, contraseña `sendero`.

Para ver los últimos cambios:

```sql
SELECT e.id, e.alerta_id, e.estado_anterior, e.estado_nuevo,
       e.ocurrido_en, e.actor_id, e.detalle
FROM evento_alerta AS e
ORDER BY e.ocurrido_en DESC
LIMIT 50;
```

Para filtrar por el ID que muestra la app:

```sql
SELECT e.id, e.alerta_id, e.estado_anterior, e.estado_nuevo,
       e.ocurrido_en, e.actor_id, e.detalle
FROM evento_alerta AS e
WHERE e.alerta_id = 'UUID-MOSTRADO-EN-LA-APP'::uuid
ORDER BY e.ocurrido_en;
```

Solo los canales que alcanzaron el servidor local aparecen en `evento_alerta`. Una alerta que permanece en Room/SQLite esperando conectividad se verá en el estado de la app, y aparecerá en PostgreSQL después de sincronizarse.
