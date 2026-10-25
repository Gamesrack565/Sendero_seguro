# Sendero Seguro · Backend en la nube

Servidor, base de datos y contrato de la API de Sendero Seguro. Lo mantiene el Módulo 4 de backend, que cubre P5 Dispersión, P13 Auditoría, P14 Infraestructura y API, P15 Base de datos en la nube y P16 Pasarelas.

Con esto ya puedes:
- correr el servidor en tu máquina, con la base de datos creada automáticamente;
- ver qué rutas tiene la API y qué JSON reciben y devuelven;
- crear las tablas locales del teléfono en Room.

## Contenido

```
server/                              Servidor Ktor (módulo de Gradle, junto a app/)
  src/main/kotlin/…/Application.kt   Arranque y registro de rutas
  src/main/kotlin/…/plataforma/      Base de datos, errores, JWT, logs, /salud y ayudas de SQL
  src/main/kotlin/…/dispersion/      Canalización, asignación de la unidad más cercana y escalamiento (P5)
  src/main/kotlin/…/tiemporeal/      WebSocket de la consola y del enlace (P16)
  src/main/kotlin/…/avisos/          Envío de los avisos por push y SMS con reintentos (P16)
  src/main/kotlin/…/auditoria/       Bitácora, historial, línea de tiempo y estadísticas (P13)
  src/main/kotlin/…/pasarela/        Webhooks de Twilio: SMS de contingencia SS1 y estado de los SMS (P16)
  src/main/kotlin/…/desarrollo/      Rutas de prueba (solo en local)
  src/main/resources/
    application.conf                 Configuración (puerto, base de datos, JWT)
    db/migration/V1__esquema_inicial.sql   Base de datos del servidor (PostgreSQL 16): 53 tablas, vistas, triggers y datos iniciales
    db/migration/V2__indices_avisos.sql    Índices para el envío de avisos
    db/datos-prueba/R__datos_prueba.sql    Usuarios, unidades y operadores de prueba (solo en local)
db/
  sendero_seguro_local_sqlite.sql    Base de datos local del teléfono (SQLite): 16 tablas
docs/
  README.md                          Esta guía
  api/openapi.yaml                   Contrato de la API (rutas, cuerpos y respuestas)
  api/README.md                      WebSocket, formato del SMS de contingencia y conexión con la plataforma
docker-compose.yml                   PostgreSQL, Redis y almacén S3 (RustFS) para desarrollo
```

## Correr el servidor en local

Necesitas Docker Desktop abierto y Java 21 (el que trae Android Studio sirve). Desde la raíz del repo:

```bash
# 1. Levantar PostgreSQL, Redis y el almacén S3
docker compose up -d

# 2. Arrancar el servidor (en Windows: .\gradlew.bat :server:run)
./gradlew :server:run
```

Al arrancar, el servidor crea todas las tablas con Flyway. Cuando el log diga `Responding at http://…:8080`, abre http://localhost:8080/api/v1/salud. Debe responder:

```json
{"estado":"OK","version":"0.1.0","componentes":{"baseDatos":"OK"}}
```

Para apagar todo: `docker compose down`. Para borrar también los datos y empezar de cero: `docker compose down -v`.

**Almacén de archivos (S3):** API en `localhost:9000` y consola web en http://localhost:9001, con usuario `sendero` y contraseña `sendero-s3`. Es RustFS, compatible con S3; en la nube se usa el S3 del proveedor.

**Conexión a la base de datos** (DBeaver, DataGrip o el plugin de Android Studio): `localhost:5432`, base `sendero_seguro`, usuario `postgres`, contraseña `sendero`.

Los datos iniciales ya traen la escuela, el catálogo de incidentes publicado, las corporaciones, las reglas de canalización, los parámetros del sistema y los modelos de IA.

**Pruebas:** `./gradlew :server:test` (en Windows: `.\gradlew.bat :server:test`). Las pruebas que usan la base de datos solo corren si defines `SENDERO_TEST_DB_URL`; sin ella salen como omitidas. Con Docker levantado, en PowerShell:

```powershell
$env:SENDERO_TEST_DB_URL="jdbc:postgresql://localhost:5432/sendero_seguro"
.\gradlew.bat :server:test --rerun
```

Apaga el servidor antes de correrlas, para que su revisión de plazos no tome las alertas de las pruebas.

**Si Gradle usa un Java incompleto** (por ejemplo, el error `jlink executable ... does not exist`), apunta `JAVA_HOME` al Java de Android Studio. En Windows, desde PowerShell:

```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Android\Android Studio\jbr", "User")
```

Después cierra y vuelve a abrir la terminal (y VS Code), y corre `.\gradlew.bat --stop`.

## Datos y rutas de prueba

En local (`DB_DATOS_PRUEBA` sin definir o en `true`) el servidor carga usuarios, unidades y operadores de prueba. La contraseña de todas las cuentas es `sendero123`.

| Cuenta | Rol |
|---|---|
| Ana (`5510000001`) | Integrante, con contactos y círculo |
| Beto | Coordinador del círculo de Ana |
| Admin | Administrador |
| Operadores de Caseta ESCOM, Patrulla Lindavista, Ambulancia ERUM 12 y PBI Zacatenco | Operador |

También se activan rutas que no existen en la nube:

| Ruta | Qué hace |
|---|---|
| `POST /api/v1/dev/token` | Entrega un JWT de 8 horas de una cuenta de prueba. Cuerpo: `{"cuenta":"caseta"}`. Cuentas: `ana`, `beto`, `admin`, `caseta`, `c5`, `erum`, `pbi`. |
| `POST /api/v1/dev/alertas` | Crea una alerta de Ana y la despacha. La respuesta trae `enlace`, la ruta que vería la red de apoyo. Cuerpo opcional: `{"tipo":"EMERGENCIA_MEDICA","latitud":19.5045,"longitud":-99.1469}`. Sin cuerpo es una alerta genérica frente a la ESCOM. |
| `POST /api/v1/dev/alertas/{id}/vencer` | Vence ya el plazo de acuse de esa alerta y la escala, sin esperar el tiempo de la regla. |
| `POST /api/v1/dev/unidades/liberar` | Regresa a DISPONIBLE las unidades ocupadas, para repetir la prueba. |
| `POST /api/v1/dev/sms/ss1` | Arma el SMS `SS1` que mandaría el teléfono de Ana sin internet, con su número de origen (`de`). Mismo cuerpo opcional que `/dev/alertas`. |
| `POST /api/v1/dev/sms/prueba` | Manda un SMS de prueba con el proveedor configurado. Cuerpo: `{"telefono":"+5255..."}`. Responde si el proveedor lo aceptó y su id, o el error. |

Ejemplo en PowerShell:

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/v1/dev/alertas
Invoke-RestMethod -Method Post http://localhost:8080/api/v1/dev/alertas -ContentType application/json -Body '{"tipo":"EMERGENCIA_MEDICA"}'
```

## Dispersión

Quien registra una alerta nueva llama a `dispersion.despachar(alertaId)` justo después de guardarla. La llamada regresa de inmediato y la dispersión corre aparte:

1. Busca la regla de `regla_canalizacion` para el tipo de la alerta. Si el tipo no tiene regla, usa la de la alerta genérica.
2. Elige la unidad `DISPONIBLE` más cercana de la corporación destino, según la última ubicación de la alerta.
3. Crea la `asignacion`, pasa la unidad y la alerta a `ASIGNADA` y fija `acuse_limite_en` con el tiempo de la regla.

Al asignar, el operador de la unidad recibe un aviso `ALERTA_PRIORITARIA` y los demás operadores de la corporación uno `ALERTA_INFORMATIVA`. Si la corporación no tiene unidades disponibles, la alerta se queda en `ENVIADA`, el motivo queda en `evento_alerta`, se avisa a todo el canal y a los Administradores, y el plazo se marca como vencido para que se escale. Despachar dos veces la misma alerta no la asigna dos veces. Si una alerta se queda en `ENVIADA` sin despachar (por ejemplo, porque el servidor se reinició justo después de registrarla), la revisión periódica la despacha.

**Red de apoyo (RF-9).** Al despachar la alerta, al mismo tiempo que se busca la unidad, se crea un enlace de ubicación y se deja un aviso `RED_APOYO` para:
- los contactos de confianza activos y confirmados, por su canal preferido (si el canal es push, a su cuenta);
- los integrantes y el coordinador de los círculos de la persona, por push.

Cada persona recibe un solo aviso. Los contactos `SOLO_AVISO` no reciben el enlace. Al cerrar la alerta, el enlace se apaga y la red de apoyo recibe un aviso `CIERRE`. Quien cancela una alerta (cancelación, Módulo 1) la marca como `CANCELADA` y llama a `dispersion.cancelada(alertaId)`: se avisa a los operadores asignados y a la red de apoyo, se liberan las unidades y se apaga el enlace. Con el PIN de coacción no se llama, porque la alerta sigue en silencio.

**Enlace de ubicación.** `GET /api/v1/enlace/{token}` no pide sesión. Muestra el primer nombre, el estado en palabras y la última ubicación (con velocidad, rumbo y batería), sin datos del perfil. Si la alerta ya terminó responde `410`. La base solo guarda el SHA-256 del token; el token se calcula con `ENLACE_SECRETO`, así que una copia de la base no basta para abrir enlaces.

**Escalamiento.** Cada 5 segundos el servidor busca las alertas sin acuse cuyo `acuse_limite_en` ya pasó y las manda al siguiente paso de `paso_escalamiento` de su regla:

| Destino del paso | Qué hace |
|---|---|
| `CORPORACION` | Asigna la unidad disponible más cercana de esa corporación. La unidad anterior se libera. |
| `OPERADOR` | Asigna la unidad de ese operador. |
| `COORDINADOR_CIRCULO` | Deja un aviso para el Coordinador del círculo de quien envió la alerta. |

Si el destino no puede recibirla (no tiene unidades o no hay coordinador), se pasa al siguiente paso en la misma revisión. En cada escalamiento la alerta queda `ESCALADA`, se reinicia el plazo con el tiempo de la regla, se registra en `escalamiento` y `evento_alerta` y se avisa a los Administradores. Cuando ya no quedan pasos, la alerta queda con `sin_acuse = true` y se avisa a los Administradores.

El plazo vive en la base de datos, así que reiniciar el servidor no pierde ningún temporizador. Los avisos se guardan en `notificacion` como `PENDIENTE`; el servicio de avisos es el que los envía.

## Rutas de la consola

Necesitan `Authorization: Bearer <JWT>`. En local el token sale de `POST /api/v1/dev/token`.

| Ruta | Quién | Qué hace |
|---|---|---|
| `GET /alertas` | Operador, Administrador | Lista con `estado` (por ejemplo `ACTIVAS` o `ASIGNADA,ESCALADA`), `desde`, `hasta`, `limite` y `cursor`. El operador solo ve las alertas que tuvieron asignada a su unidad. |
| `GET /alertas/{id}` | Operador, Administrador y el emisor | Detalle. El emisor no recibe los datos de la consola y, si canceló con el PIN de coacción, la ve como `CANCELADA`. |
| `POST /alertas/{id}/acuse` | Operador de la unidad asignada | Acuse: la alerta pasa a `ACUSADA` y se detiene el plazo. Si llega cuando la alerta ya se escaló a otra unidad, se registra y responde `409 VENCIDO`. |
| `PATCH /alertas/{id}/estado` | Operador de la unidad asignada, Administrador | `ACUSADA → EN_ATENCION → ATENDIDA → CERRADA`. Para cerrar se pide `resultadoCierre`. Al cerrar se liberan las unidades. |
| `POST /alertas/{id}/escalar` | Operador de la unidad asignada, Administrador | Escala a mano al siguiente nivel. Sin acuse, igual que el automático. Con la alerta acusada o en atención, la unidad que atiende se queda y el siguiente nivel llega de apoyo. |

Al emisor se le avisa del acuse, de cada cambio de estado y del cierre, salvo que haya cancelado con el PIN de coacción.

## Tiempo real

Hay dos WebSocket. Sus mensajes tienen la forma `{ "tipo", "ts", "datos" }` y están en `api/README.md`.

| Ruta | Quién | Qué recibe |
|---|---|---|
| `WS /api/v1/ws/consola?token=<JWT>` | Operador, Administrador | `alerta.nueva`, `alerta.estado`, `alerta.escalada`, `alerta.ubicacion` y `alerta.sin_acuse`. El Administrador recibe todas las alertas; el operador, las que se asignaron a su unidad. |
| `WS /api/v1/ws/enlace/{token}` | Público (red de apoyo) | Al conectarse, el estado y la última ubicación. Después, cada ubicación nueva y cada cambio de estado en palabras. Al terminar, `fin` con `CERRADA`, `CANCELADA` o `VENCIDA`, y se cierra la conexión. |

- El JWT va en la URL porque el navegador no puede mandar encabezados en un WebSocket. Si falta, no es válido o el rol no es de la consola, la conexión se cierra con el código `1008` y el motivo (`NO_AUTENTICADO` o `SIN_PERMISO`). Un enlace que no existe o ya no está vigente se cierra igual, con `ENLACE_NO_DISPONIBLE`.
- Cada 25 s el servidor manda `ping` y el cliente contesta `{"tipo":"pong"}`. Si pasan dos pings sin nada del cliente, se cierra con `1001`. Si vence el JWT, se cierra con `1008 TOKEN_VENCIDO` y la consola vuelve a conectarse con un token nuevo.
- El servidor lee cada segundo las filas nuevas de `alerta`, `asignacion`, `escalamiento`, `evento_alerta` y `ubicacion_alerta`. Así llegan también las ubicaciones que guarda el registro de alertas y lo que pasa en otro servidor, sin Redis. Una vez conectado, la consola carga lo anterior con `GET /alertas`.
- `GET /enlace/{token}` trae en `websocket` la dirección para conectarse. Se arma con `ENLACE_URL_WEBSOCKET`.

## Avisos

Todo aviso es una fila de `notificacion`: se crea `PENDIENTE` y el servidor la envía en unos 2 segundos. Sigue el diagrama E-03:

| Estado | Qué pasa |
|---|---|
| `PENDIENTE` | Se elige el canal. Push si la cuenta tiene un teléfono con token FCM; si no, SMS a su número. Los contactos sin app reciben SMS (WhatsApp también sale por SMS mientras no haya proveedor de WhatsApp). Una alerta informativa sin push se descarta, porque el operador ya la ve en la consola. |
| `ENVIADA` | El proveedor aceptó el mensaje. Si el proveedor confirma entregas (Twilio con `PASARELA_URL_PUBLICA`) y en 10 minutos no avisa ni que lo pasó a la compañía telefónica, pasa a `FALLIDA`. |
| `ENTREGADA` | El proveedor confirmó la entrega. |
| `FALLIDA` | Se cuenta el reintento y se espera 15 s, 30 s, 1 min, 2 min… antes de volver a intentar. Si el proveedor responde que está saturado (429), no cuenta como reintento: se espera lo que pide (`Retry-After`) y mientras no se le manda nada más. |
| `DESCARTADA` | Se llegó a 5 fallas, o el error no tiene caso reintentarlo (token o número inválido). Se avisa a los Administradores: queda en la historia de la alerta (`ENTREGA_DESCARTADA`) y la consola recibe `alerta.entrega_fallida`. |

- Los SMS van sin acentos ni eñes para caber en 160 caracteres. A la red de apoyo con acceso de seguimiento le llega el enlace de ubicación.
- No se envían los avisos para atender una alerta que ya terminó (alerta asignada o informativa, escalamiento, sin acuse), ni los que tienen más de 24 horas (por ejemplo, si el servidor estuvo apagado). Se marcan `DESCARTADA` sin avisar.
- En local el proveedor es `simulado`: no manda nada y escribe cada mensaje en el log del servidor con `[SIMULADO]`. Los proveedores reales (FCM y Twilio) se eligen con `AVISOS_PROVEEDOR_PUSH` y `AVISOS_PROVEEDOR_SMS`.
- **Push con FCM.** Con `AVISOS_PROVEEDOR_PUSH=fcm` y `FCM_CREDENCIALES` (ruta del `.json` de la cuenta de servicio de Firebase, que nunca se sube al repo), el servidor envía por la API HTTP v1 de FCM con prioridad alta. FCM no confirma la entrega, así que el push queda `ENVIADA`. Si FCM responde que el token ya no sirve, se borra del dispositivo y el aviso sale por SMS en el siguiente intento.
- **SMS con Twilio.** Con `AVISOS_PROVEEDOR_SMS=twilio`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` y `TWILIO_NUMERO`, el servidor envía cada SMS por la API de Twilio, al número en formato internacional (`5512345678` sale como `+525512345678`). Con `PASARELA_URL_PUBLICA`, Twilio avisa el estado de cada SMS en `/pasarela/sms/estado`: `sent` (lo entregó a la compañía), `delivered` (`ENTREGADA`) o `undelivered`/`failed` (`FALLIDA` y se reintenta; si el número no existe o es fijo, `DESCARTADA`). Algunas compañías nunca mandan `delivered`; esos SMS se quedan `ENVIADA` y no se repiten. Un número que Twilio no acepta (inválido, no es celular, pidió no recibir SMS, o no está verificado en una cuenta de prueba) se descarta. La cuenta de prueba "Limited trial" de Twilio solo manda sus plantillas, no un texto propio (error 572006); con ella los avisos se descartan y hay que usar el proveedor simulado o una cuenta "Full access".
- **Token del teléfono.** La app lo registra con `PUT /api/v1/dispositivos/{id}/push` `{"fcmToken": "..."}` al iniciar sesión y cada vez que FCM lo rota. Solo el dueño del dispositivo; si otro dispositivo tenía ese token, se le quita.
- Varios servidores pueden enviar a la vez sin repetir avisos.
- Las pruebas automáticas descartan al terminar los avisos que crearon, para que el servidor no los mande al arrancar.
- En el día a día conviene dejar `AVISOS_PROVEEDOR_SMS=simulado`. Con Twilio, cada alerta de prueba también intenta mandar SMS a los números de los datos de prueba. La cuenta de prueba de Twilio los rechaza porque no están verificados, pero una cuenta de pago sí los mandaría.

## SMS de contingencia

Cuando el teléfono no tiene internet ni malla, la app manda la alerta como un SMS `SS1|…` de 46 caracteres al número de Twilio (formato en `docs/api/README.md`). Twilio llama a `POST /api/v1/pasarela/sms/entrante` y el servidor:

1. Valida la firma `X-Twilio-Signature` con `TWILIO_AUTH_TOKEN`. Si no coincide, responde 401 y no guarda nada.
2. Guarda el mensaje en `mensaje_sms`, válido o no. Es válido si es un `SS1` con la verificación correcta, de las últimas 24 horas, y si el número de origen es el `telefono_movil` de una cuenta activa (con o sin `+52`).
3. Si es de una alerta que ya existe (misma persona, mismo id corto y hora ±60 s), lo liga a ella. Así, si el teléfono reintenta el SMS, la alerta no se duplica.
4. Si no existe, la registra con `canal_recepcion = SMS` y la despacha igual que una alerta por internet. El registro es del Módulo 2: implementa `RegistroAlertasSms` y se conecta en `Application.kt`. Mientras no exista, en local se usa un registro de prueba y en la nube los mensajes válidos quedan pendientes (`procesado_en` nulo) hasta que se conecte.

Siempre responde 200 con TwiML vacío, así que a la persona no le llega ninguna respuesta por SMS. Para que Twilio llame al servidor, en la Console de Twilio el número debe tener como webhook de "A message comes in" la dirección `https://<dominio>/api/v1/pasarela/sms/entrante` (HTTP POST), y `PASARELA_URL_PUBLICA` debe ser `https://<dominio>/api/v1`.

## Bitácora

`bitacora` guarda las acciones que hay que poder auditar (RNF16). La base calcula un hash SHA-256 que encadena cada registro con el anterior y no deja cambiar ni borrar registros. Cualquier módulo escribe con `Bitacora.registrar`, dentro de la misma transacción que la acción:

```kotlin
dataSource.transaccion { conn ->
    // ... la acción ...
    Bitacora.registrar(conn, "CONSULTAR_HISTORIAL_AJENO", usuario.usuarioId,
        entidad = "usuario", entidadId = id.toString(),
        detalle = buildJsonObject { put("justificacion", justificacion) }, ip = call.ipCliente())
}
```

Rutas, solo para el Administrador:
- `GET /api/v1/bitacora`: registros del más nuevo al más viejo. Filtros `accion`, `entidad`, `entidadId`, `usuarioId`, `desde`, `hasta`, `limite` (1 a 200) y `cursor`.
- `GET /api/v1/bitacora/verificar`: revisa toda la cadena con `fn_verificar_bitacora()`. `integra` es `false` si algún registro se alteró, y `primerIdAlterado` dice cuál.

Las pruebas automáticas escriben registros `PRUEBA_…` y `CONSULTAR_HISTORIAL_AJENO` en la bitácora de la base local; por diseño no se pueden borrar.

## Línea de tiempo de una alerta

`GET /api/v1/alertas/{id}/timeline` junta en orden los cambios de estado, las asignaciones, los escalamientos, los avisos y los intentos de cancelación. La ven las mismas personas que `GET /alertas/{id}`:
- **El Administrador y el operador cuya unidad tuvo la alerta** ven todo, con nombres y a quién se mandó cada aviso.
- **Quien emitió la alerta** ve su versión. Incluye sus estados, la unidad asignada, sus intentos de cancelación y los avisos a su red de apoyo. No ve nombres de operadores, ni escalamientos ni datos internos. Si canceló con el PIN de coacción, su línea termina en `CANCELADA` en ese momento, aunque la alerta siga activa en silencio.


## Historial

Lee `v_historial_usuario`: alertas, reportes con nombre y trayectos, del más nuevo al más viejo. Los reportes anónimos no aparecen. Filtros `tipo` (`ALERTA`, `REPORTE` o `TRAYECTO`), `desde`, `hasta`, `limite` (1 a 200) y `cursor`.
- `GET /api/v1/historial`: el de la persona del token. Una alerta cancelada con el PIN de coacción sale como `CANCELADA`, igual que en el detalle y la línea de tiempo.
- `GET /api/v1/usuarios/{id}/historial`: el de otra persona, solo para el Administrador. Pide el encabezado `X-Justificacion` (10 a 500 caracteres) y cada consulta queda en la bitácora como `CONSULTAR_HISTORIAL_AJENO`, con la justificación y la IP. Muestra el estado real de cada alerta.

## Estadísticas

`GET /api/v1/estadisticas/alertas?desde=2026-10-01&hasta=2026-10-31` da los indicadores del tablero a partir de `v_alerta_tiempos`. Solo la ve el Administrador y no trae datos de personas. Filtros opcionales `tipoIncidenteId` y `zonaId`.
- El periodo va por días completos en la hora de la Ciudad de México, de `desde` a `hasta` incluidos, y no puede pasar de 366 días.
- La zona es la caja de `zona_geografica` que contiene la primera ubicación de la alerta.
- `medianaAcuseSeg`: mediana del tiempo entre que se activó y se acusó.
- `pctCumpleMetaAcuse`: de las alertas que se acusaron o a las que se les pasó la meta (`meta_acuse_seg`, 120 s) sin acuse, cuántas se acusaron a tiempo. Las canceladas antes de la meta y las que todavía van dentro de ella no cuentan.
- `pctEscaladas`: las que escalaron al menos una vez. `porSemana` trae todas las semanas ISO del periodo, también las que no tuvieron alertas.

## Configuración

Los valores de `application.conf` sirven para desarrollo. En la nube se reemplazan con variables de entorno:

| Variable | Para qué | Valor en local |
|---|---|---|
| `PORT` | Puerto del servidor | `8080` |
| `DB_URL` | Conexión JDBC a PostgreSQL | `jdbc:postgresql://localhost:5432/sendero_seguro` |
| `DB_USUARIO` / `DB_PASSWORD` | Credenciales de la base | `postgres` / `sendero` |
| `DB_POOL_MAX` | Conexiones máximas | `10` |
| `ENLACE_SECRETO` | Llave para calcular los tokens de los enlaces de ubicación | Valor de desarrollo. En la nube es obligatorio cambiarla. |
| `ENLACE_HORAS_MAX` | Vigencia máxima de un enlace | `24` |
| `DISPERSION_REVISION_SEG` | Cada cuántos segundos se revisan los plazos de acuse | `5` |
| `ENLACE_URL_WEBSOCKET` | Dirección pública de `/ws/enlace`, sin el token | `ws://localhost:8080/api/v1/ws/enlace`. En la nube, `wss://<dominio>/api/v1/ws/enlace`. |
| `AVISOS_PROVEEDOR_PUSH` / `AVISOS_PROVEEDOR_SMS` | Proveedor de cada canal: `simulado`, `fcm` (push) o `twilio` (SMS) | `simulado` |
| `FCM_CREDENCIALES` | Ruta del `.json` de la cuenta de servicio de Firebase (con `AVISOS_PROVEEDOR_PUSH=fcm`) | vacío |
| `TWILIO_ACCOUNT_SID` / `TWILIO_NUMERO` | Cuenta de Twilio y número que manda y recibe los SMS (`+1...`) | vacío |
| `TWILIO_AUTH_TOKEN` | Secreto de la cuenta de Twilio. Valida los webhooks y autentica los envíos. Sin él, los webhooks solo funcionan en local y sin firma. | vacío |
| `PASARELA_URL_PUBLICA` | Dirección pública del servidor hasta `/api/v1`. Con ella se validan las firmas detrás de un proxy y Twilio avisa el estado de cada SMS. | vacío |
| `AVISOS_MAX_REINTENTOS` | Fallas antes de descartar un aviso | `5` |
| `AVISOS_ESPERA_BASE_SEG` | Espera antes del primer reintento; se duplica en cada falla | `15` |
| `AVISOS_REVISION_MS` | Cada cuántos milisegundos se buscan avisos por enviar | `2000` |
| `AVISOS_URL_ENLACE` | Página de seguimiento que se manda a la red de apoyo, sin el token | `http://localhost:8080/api/v1/enlace` |
| `TIEMPO_REAL_REVISION_MS` | Cada cuántos milisegundos se buscan cambios para los WebSocket | `1000` |
| `DB_DATOS_PRUEBA` | Carga los datos y las rutas de prueba | `true`. En la nube, `false`. |
| `JWT_SECRETO` | Llave para firmar y validar los JWT | Valor de desarrollo. En la nube es obligatorio cambiarla. |

Con `DB_DATOS_PRUEBA=false`, el servidor no arranca si `JWT_SECRETO` o `ENLACE_SECRETO` tienen el valor de desarrollo o menos de 32 caracteres, y el log dice cuál falta. Se puede generar una llave con `openssl rand -hex 32`. Con `DB_DATOS_PRUEBA=true`, el log avisa al arrancar que las rutas `/dev` están activas.

## Cambios a la base de datos

Las migraciones viven en `server/src/main/resources/db/migration/` y Flyway las aplica en orden al arrancar. Un cambio nuevo va en un archivo nuevo con el siguiente número (`V2__descripcion.sql`, `V3__…`). Una migración que ya se aplicó no se edita, porque Flyway detecta el cambio y el servidor no arranca.

## Cómo se agrega una ruta

Las rutas van dentro de `route("/api/v1")` en `Application.kt`. Para rutas con sesión se usa `authenticate(AUTH_JWT)`, y para limitar por rol, `call.exigirRol(...)`:

```kotlin
authenticate(AUTH_JWT) {
    get("/alertas/{id}") {
        val usuario = call.exigirRol(Rol.OPERADOR, Rol.ADMINISTRADOR)
        // ...
    }
}
```

Para responder con un error del contrato basta con lanzar `ApiException`:

```kotlin
throw ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "La alerta no existe")
```

## Ver el contrato de la API

Abre https://editor.swagger.io y pega el contenido de `docs/api/openapi.yaml`. Ahí ves cada ruta con sus parámetros, el JSON de entrada, las respuestas y los errores. Cada ruta indica a qué proceso pertenece (`x-proceso`).

Reglas generales de la API:
- Prefijo `/api/v1`, JSON en camelCase y fechas ISO 8601 en UTC.
- Autenticación con `Authorization: Bearer <JWT>`.
- Los errores siempre tienen la forma `{ "codigo", "mensaje", "detalle" }`.

## Base de datos del teléfono

`db/sendero_seguro_local_sqlite.sql` es la referencia para las entidades de Room: nombres de tablas, columnas y restricciones. Para probarlo suelto: `sqlite3 prueba.db < db/sendero_seguro_local_sqlite.sql`.

Las fechas se guardan como milisegundos Unix, los booleanos como 0/1 y los UUID como texto. Los secretos (token, PIN y llaves) no van en SQLite, sino en el Android Keystore.

## Cómo funciona la base de datos del servidor

- **Ids:** las alertas, los reportes, los trayectos y los contactos usan un UUID generado en el teléfono. Si el mismo envío llega dos veces, no se duplica.
- **Folios:** `ALR-2026-000001` y `REP-2026-000001` los asigna un trigger al insertar.
- **Bitácora:** `bitacora` es de solo inserción, y el trigger calcula un hash SHA-256 que encadena cada registro. `select * from fn_verificar_bitacora();` revisa que nadie la haya alterado.
- **Historial que no se borra:** `evento_alerta`, `intento_cancelacion` y `decision_moderacion` no aceptan UPDATE, DELETE ni TRUNCATE.
- **Vistas útiles:** `v_historial_usuario`, `v_alerta_tiempos`, `v_ubicacion_actual_alerta`, `v_catalogo_errores` y `v_ia_metricas`.

La lista de qué tablas escribe y lee cada proceso está en la sección 5 del documento "Diseño de Datos · Sendero Seguro".

## Qué ofrece la plataforma

| Pieza | Estado |
|---|---|
| Arranque, base de datos con Flyway, errores del contrato, logs y `GET /salud` | Listo |
| Validación del JWT y de los roles (`authenticate`, `exigirRol`) | Listo |
| Dispersión: `Dispersion.despachar(alertaId)`, canalización, asignación y escalamiento automático | Listo |
| Consola: lista y detalle de alertas, acuse, cambios de estado y escalamiento manual | Listo |
| `EmisorTokens` para firmar los JWT que entrega el login | Listo |
| Dispersión: aviso a la red de apoyo y enlace de ubicación (`GET /enlace/{token}`) | Listo |
| Sincronización: `POST /sync/lote` | En desarrollo |
| Bitácora: `Bitacora.registrar`, consulta y verificación de la cadena | Listo |
| Línea de tiempo de una alerta e historial de cada persona | Listo |
| Estadísticas de alertas para el tablero | Listo |
| Avisos: envío con reintentos (E-03) y proveedor simulado | Listo |
| Avisos: push con FCM y registro del token (`PUT /dispositivos/{id}/push`) | Listo |
| Avisos: SMS con Twilio y estado de entrega (`/pasarela/sms/estado`) | Listo |
| SMS de contingencia `SS1` (`/pasarela/sms/entrante`), con el registro del Módulo 2 por conectar | Listo |
| Avisos: WhatsApp opcional | En desarrollo |
| Tiempo real: WebSocket de la consola y del enlace | Listo |

El detalle de cada punto está en `docs/api/README.md`.
