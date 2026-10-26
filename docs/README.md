# Sendero Seguro · Backend en la nube

Servidor, base de datos y contrato de la API de Sendero Seguro. Lo mantiene el Módulo 4 de backend, que cubre P5 Dispersión, P13 Auditoría, P14 Infraestructura y API, P15 Base de datos en la nube y P16 Pasarelas.

Con esto ya puedes:
- usar el servidor que ya está en la nube (Azure);
- correr el servidor en tu máquina, con la base de datos creada automáticamente;
- ver qué rutas tiene la API y qué JSON reciben y devuelven;
- ver qué ofrece la plataforma (dispersión, avisos, bitácora, SMS) y cómo se usa;
- crear las tablas locales del teléfono en Room.

## Cómo funciona, de punta a punta

Lo que hace el servidor con una alerta, paso por paso. Todo esto ya está implementado y corriendo en la nube.

1. **Llega la alerta.** Con internet entra por el registro de alertas. Sin internet llega por la malla, por la cola offline del teléfono (`/sync/lote`) o como SMS `SS1` al celular pasarela. Si la misma alerta llega por dos caminos, el servidor la reconoce (persona + id corto + hora ±60 s) y no la duplica.
2. **Se despacha.** El servidor busca la regla del tipo de incidente, elige la unidad disponible más cercana de la corporación que toca, se la asigna y arranca el plazo de acuse.
3. **Se avisa.** El operador de esa unidad recibe un push prioritario y los demás operadores de la corporación uno informativo. Al mismo tiempo, la red de apoyo de la persona (contactos de confianza y círculo) recibe un push o un SMS con el enlace de ubicación.
4. **La consola lo ve en vivo.** Por WebSocket llegan la alerta nueva, su ubicación y cada cambio. La red de apoyo sigue la ubicación con su enlace, sin cuenta.
5. **Acuse o escalamiento.** Si el operador acusa a tiempo, se detiene el plazo. Si no, cada 5 segundos el servidor revisa los plazos vencidos y manda la alerta al siguiente nivel de su regla (otra corporación, otro operador o el coordinador del círculo), y avisa a los Administradores.
6. **Atención y cierre.** El operador la pasa a `EN_ATENCION`, `ATENDIDA` y `CERRADA`. La persona recibe un aviso en cada paso; al cerrar se liberan las unidades, se apaga el enlace y la red de apoyo recibe el aviso de cierre. Si la persona cancela, pasa lo mismo, salvo con el PIN de coacción, donde la alerta sigue activa en silencio.
7. **Queda el registro.** Cada paso queda en la historia de la alerta y en la bitácora encadenada. De ahí salen la línea de tiempo, el historial de cada persona y las estadísticas del tablero.

Si un aviso no se puede entregar, el servidor lo reintenta con esperas cada vez más largas. Si aun así falla, avisa a los Administradores y la consola lo muestra.

## Contenido

```
server/                              Servidor Ktor (módulo de Gradle, junto a app/)
  src/main/kotlin/…/Application.kt   Arranque y registro de rutas
  src/main/kotlin/…/plataforma/      Base de datos, errores, JWT, logs, /salud y ayudas de SQL
  src/main/kotlin/…/dispersion/      Canalización, asignación de la unidad más cercana y escalamiento (P5)
  src/main/kotlin/…/tiemporeal/      WebSocket de la consola y del enlace (P16)
  src/main/kotlin/…/avisos/          Envío de los avisos por push y SMS con reintentos (P16)
  src/main/kotlin/…/auditoria/       Bitácora, historial, línea de tiempo y estadísticas (P13)
  src/main/kotlin/…/pasarela/        Webhooks de Twilio y del celular pasarela: SMS de contingencia SS1 y estado de los SMS (P16)
  src/main/kotlin/…/sincronizacion/  Recepción del lote offline del teléfono, /sync/lote (P14)
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

## Servidor en la nube

El servidor ya está en Azure (App Service con Java 21 y PostgreSQL 16 administrado, en México Central):

```
https://sendero-seguro-bse3efh5ddexczcx.mexicocentral-01.azurewebsites.net/api/v1
```

- **Para ver si está vivo:** abre `/api/v1/salud`. Debe decir `"estado":"OK"`.
- **Ya está todo configurado:** base de datos, llaves, push con Firebase y SMS con el celular pasarela. El deploy ya está hecho y se actualiza a mano, solo después de que pasan las pruebas (`:server:test`).
- **Es igual que en local, con estas diferencias:** no hay rutas `/dev` ni cuentas de prueba (no hay `POST /dev/token`); los avisos salen de verdad (push por Firebase y SMS por el celular pasarela); y el WebSocket va con `wss://` en vez de `ws://`.
- **Las pruebas del día a día se hacen en local**, no en la nube: en la nube cada alerta manda SMS y push reales.
- **Las llaves de la nube no están en el repo** (base de datos, JWT, enlaces, Firebase, SMS). Viven en las variables de entorno de Azure. Un `.json` de Firebase, una contraseña o un token que se suba al repo queda expuesto en el historial de Git.
- **La base de la nube es solo del servidor en la nube.** Para probar en local se usa la base de Docker.

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

## Puntos de conexión de la plataforma

Lo que la plataforma ofrece para usarse desde otras partes del sistema, qué hace cada cosa y cómo se llama.

**Sesión (JWT)**
- `EmisorTokens.emitir(usuario, duracion)` (`plataforma/Seguridad.kt`) firma un JWT con `JWT_SECRETO`. El token lleva `sub` (usuario), `rol`, `disp` (dispositivo) y `sid` (sesión).
- Las rutas con sesión ya validan el token y el rol (`authenticate(AUTH_JWT)` y `exigirRol`).

**Dispersión**
- `dispersion.despachar(alertaId)`: para una alerta ya guardada. Canaliza, asigna la unidad más cercana, avisa a la red de apoyo y crea el enlace de ubicación. Regresa de inmediato y corre aparte (detalle en [Dispersión](#dispersión)). Si una alerta se queda `ENVIADA` sin despachar, la revisión periódica la despacha.
- `dispersion.cancelada(alertaId)`: para una alerta que ya quedó `CANCELADA`. Avisa a los operadores asignados y a la red de apoyo, libera las unidades y apaga el enlace. Con el PIN de coacción no aplica, porque la alerta sigue activa en silencio.

**Alertas por SMS**
- `RegistroAlertasSms` (`pasarela/RecepcionSms.kt`) es la interfaz por la que la pasarela entrega una alerta que llegó por SMS y todavía no existe. La implementación recibe la conexión de la transacción, inserta la alerta con `canal_recepcion = SMS` y un id que empieza con el id corto del SMS, y no despacha (la pasarela despacha después). Se conecta en `Application.kt`.
- Mientras no hay implementación, en local se usa un registro de prueba y en la nube los SMS válidos quedan guardados como pendientes. Cuando se conecta, se procesan solos los de las últimas 24 horas.
- Una misma alerta que llega por dos caminos (SMS y después internet o lote) se reconoce por persona + id corto + hora ±60 s y no se duplica.

**Lote offline (`/sync/lote`)**
- Recibe lo que el teléfono guardó sin internet y lo entrega, tipo por tipo, a un manejador registrado en `Application.kt` (`manejadoresSync[TipoSync.X]`). Detalle en [Sincronización](#sincronización).
- Un tipo sin manejador responde `RECHAZADO` temporal, así que el teléfono lo conserva y lo reintenta.

**Avisos push**
- El servidor manda push con Firebase (FCM) al token que el teléfono registra con `PUT /api/v1/dispositivos/{id}/push`, al iniciar sesión y cada vez que FCM lo cambia. La app usa el `google-services.json` del proyecto de Firebase de Sendero Seguro; la clave de la cuenta de servicio solo la usa el servidor.
- Si el teléfono no tiene token, el aviso sale por SMS.

**SMS de contingencia**
- El formato `SS1` está en `docs/api/README.md`. Se manda al número del celular pasarela, que no va en el repo.

**Tiempo real**
- `/ws/consola?token=<JWT>` manda a la consola cada alerta nueva, cambio de estado, escalamiento y ubicación. El cliente contesta `{"tipo":"pong"}` a cada `ping`, y si se cierra con `1008 TOKEN_VENCIDO` se reconecta con un token nuevo.
- `GET /api/v1/enlace/{token}` y `/ws/enlace/{token}` dan el estado y la ubicación de una alerta sin sesión, para la red de apoyo. Hoy el enlace que llega por SMS abre ese JSON; `AVISOS_URL_ENLACE` permite apuntarlo a una página web que lo muestre en un mapa.

**Bitácora y errores**
- `Bitacora.registrar(...)`, dentro de la misma transacción que la acción, deja un registro auditable (ver [Bitácora](#bitácora)).
- `ApiException` responde con el formato de error del contrato (ver [Cómo se agrega una ruta](#cómo-se-agrega-una-ruta)).

**Dirección del servidor y base del teléfono**
- En la nube, la de [Servidor en la nube](#servidor-en-la-nube). En local, `http://localhost:8080/api/v1`; desde el emulador de Android, `http://10.0.2.2:8080/api/v1`.
- La base local del teléfono (Room) sigue `db/sendero_seguro_local_sqlite.sql` (ver [Base de datos del teléfono](#base-de-datos-del-teléfono)).

## Dispersión

Quien registra una alerta nueva llama a `dispersion.despachar(alertaId)` justo después de guardarla. La llamada regresa de inmediato y la dispersión corre aparte:

1. Busca la regla de `regla_canalizacion` para el tipo de la alerta. Si el tipo no tiene regla, usa la de la alerta genérica.
2. Elige la unidad `DISPONIBLE` más cercana de la corporación destino, según la última ubicación de la alerta.
3. Crea la `asignacion`, pasa la unidad y la alerta a `ASIGNADA` y fija `acuse_limite_en` con el tiempo de la regla.

Al asignar, el operador de la unidad recibe un aviso `ALERTA_PRIORITARIA` y los demás operadores de la corporación uno `ALERTA_INFORMATIVA`. Si la corporación no tiene unidades disponibles, la alerta se queda en `ENVIADA`, el motivo queda en `evento_alerta`, se avisa a todo el canal y a los Administradores, y el plazo se marca como vencido para que se escale. Despachar dos veces la misma alerta no la asigna dos veces. Si una alerta se queda en `ENVIADA` sin despachar (por ejemplo, porque el servidor se reinició justo después de registrarla), la revisión periódica la despacha.

**Red de apoyo (RF-9).** Al despachar la alerta, al mismo tiempo que se busca la unidad, se crea un enlace de ubicación y se deja un aviso `RED_APOYO` para:
- los contactos de confianza activos y confirmados, por su canal preferido (si el canal es push, a su cuenta);
- los integrantes y el coordinador de los círculos de la persona, por push.

Cada persona recibe un solo aviso. Los contactos `SOLO_AVISO` no reciben el enlace. Al cerrar la alerta, el enlace se apaga y la red de apoyo recibe un aviso `CIERRE`. Cuando una alerta se marca como `CANCELADA` y se llama a `dispersion.cancelada(alertaId)`, se avisa a los operadores asignados y a la red de apoyo, se liberan las unidades y se apaga el enlace. Con el PIN de coacción no se llama, porque la alerta sigue en silencio.

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
| `PENDIENTE` | Se elige el canal. Push si la cuenta tiene un teléfono con token FCM; si no, SMS a su número. Los contactos sin app reciben SMS. Una alerta informativa sin push se descarta, porque el operador ya la ve en la consola. |
| `ENVIADA` | El proveedor aceptó el mensaje. Si el proveedor confirma entregas (Twilio con `PASARELA_URL_PUBLICA`, o el celular pasarela con `SMSGATE_LLAVE_FIRMA`) y en 10 minutos no avisa ni que lo pasó a la compañía telefónica, pasa a `FALLIDA`. |
| `ENTREGADA` | El proveedor confirmó la entrega. |
| `FALLIDA` | Se cuenta el reintento y se espera 15 s, 30 s, 1 min, 2 min… antes de volver a intentar. Si el proveedor responde que está saturado (429), no cuenta como reintento: se espera lo que pide (`Retry-After`) y mientras no se le manda nada más. |
| `DESCARTADA` | Se llegó a 5 fallas, o el error no tiene caso reintentarlo (token o número inválido). Se avisa a los Administradores: queda en la historia de la alerta (`ENTREGA_DESCARTADA`) y la consola recibe `alerta.entrega_fallida`. |

- Los SMS van sin acentos ni eñes para caber en 160 caracteres. A la red de apoyo con acceso de seguimiento le llega el enlace de ubicación.
- No se envían los avisos para atender una alerta que ya terminó (alerta asignada o informativa, escalamiento, sin acuse), ni los que tienen más de 24 horas (por ejemplo, si el servidor estuvo apagado). Se marcan `DESCARTADA` sin avisar.
- En local el proveedor es `simulado`: no manda nada y escribe cada mensaje en el log del servidor con `[SIMULADO]`. Los proveedores reales (FCM, Twilio o el celular pasarela) se eligen con `AVISOS_PROVEEDOR_PUSH` y `AVISOS_PROVEEDOR_SMS`.
- **Push con FCM.** Con `AVISOS_PROVEEDOR_PUSH=fcm` y `FCM_CREDENCIALES` (ruta del `.json` de la cuenta de servicio de Firebase, que nunca se sube al repo), el servidor envía por la API HTTP v1 de FCM con prioridad alta. FCM no confirma la entrega, así que el push queda `ENVIADA`. Si FCM responde que el token ya no sirve, se borra del dispositivo y el aviso sale por SMS en el siguiente intento.
- **SMS con Twilio.** Con `AVISOS_PROVEEDOR_SMS=twilio`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN` y `TWILIO_NUMERO`, el servidor envía cada SMS por la API de Twilio, al número en formato internacional (`5512345678` sale como `+525512345678`). Con `PASARELA_URL_PUBLICA`, Twilio avisa el estado de cada SMS en `/pasarela/sms/estado`: `sent` (lo entregó a la compañía), `delivered` (`ENTREGADA`) o `undelivered`/`failed` (`FALLIDA` y se reintenta; si el número no existe o es fijo, `DESCARTADA`). Algunas compañías nunca mandan `delivered`; esos SMS se quedan `ENVIADA` y no se repiten. Un número que Twilio no acepta (inválido, no es celular, pidió no recibir SMS, o no está verificado en una cuenta de prueba) se descarta. La cuenta de prueba "Limited trial" de Twilio solo manda sus plantillas, no un texto propio (error 572006); con ella los avisos se descartan y hay que usar el proveedor simulado o una cuenta "Full access".
- **Token del teléfono.** La app lo registra con `PUT /api/v1/dispositivos/{id}/push` `{"fcmToken": "..."}` al iniciar sesión y cada vez que FCM lo rota. Solo el dueño del dispositivo; si otro dispositivo tenía ese token, se le quita.
- Varios servidores pueden enviar a la vez sin repetir avisos.
- Las pruebas automáticas descartan al terminar los avisos que crearon, para que el servidor no los mande al arrancar.
- En local los dos proveedores vienen en `simulado`. Así las alertas de prueba no mandan SMS ni push reales a los números de los datos de prueba.
- En la nube ya está configurado: el SMS sale por el celular pasarela (`smsgate`) y el push por Firebase (`fcm`). Twilio queda en el código como alternativa.

## Sincronización

`POST /api/v1/sync/lote` recibe la cola que el teléfono guardó sin internet: `{ dispositivoId, elementos: [{ tipo, id, creadoEn, datos }] }`, con 200 elementos y 1 MB como máximo (si pasa, responde 413). El dispositivo tiene que ser de la persona del token.

- Procesa en este orden, sin importar cómo lleguen: `ALERTA`, `ACUSE`, `UBICACION`, `CANCELACION`, `REPORTE`, `TRAYECTO` y `CHECKIN`; dentro de cada tipo, del más viejo al más nuevo. Responde un resultado por elemento, en el orden en que llegaron.
- `VENCIDO`: un acuse con más de 2 horas, un reporte con más de 6 días y lo demás con más de 72 horas. Un `creadoEn` más de 10 minutos en el futuro sale `RECHAZADO`.
- Cada elemento va en su propia transacción: si uno falla, lo que escribió se deshace y los demás siguen. En un `RECHAZADO`, `error.detalle.temporal` dice si el teléfono debe conservarlo y reintentar (`true`) o borrarlo (`false`).
- Una `ALERTA` que ya llegó por internet, por SMS o en un lote anterior sale `DUPLICADO` con su folio, sin llamar al manejador. Si se acepta una alerta nueva, la plataforma la despacha.

Cada tipo lo guarda un manejador que se registra en `Application.kt`. Tiene que ser idempotente, porque el teléfono puede reenviar:

```kotlin
manejadoresSync[TipoSync.REPORTE] = ManejadorSync { conn, contexto, elemento ->
    // contexto: usuarioId y dispositivoId. elemento: tipo, id, creadoEn y datos (el JSON del teléfono)
    ResultadoManejo.Aceptado(referencia = folio)   // o Duplicado(folio), o Rechazado(codigo, mensaje, temporal)
}
```

Un tipo sin manejador sale `RECHAZADO` con `temporal: true`, así que el teléfono lo guarda hasta que exista. En local, las `ALERTA` usan un manejador de prueba.

## SMS de contingencia

Cuando el teléfono no tiene internet ni malla, la app manda la alerta como un SMS `SS1|…` de 46 caracteres (formato en `docs/api/README.md`) al **número del celular pasarela** (no va en el repo). El celular le pasa el SMS al servidor por `POST /api/v1/pasarela/smsgate` (ver [Celular Android como pasarela](#celular-android-como-pasarela)). Con Twilio, el SMS llega a `POST /api/v1/pasarela/sms/entrante`. En los dos casos el servidor:

1. Valida la firma del proveedor (`X-Signature` del celular o `X-Twilio-Signature` de Twilio). Si no coincide, responde 401 y no guarda nada.
2. Guarda el mensaje en `mensaje_sms`, válido o no. Es válido si es un `SS1` con la verificación correcta, de las últimas 24 horas, y si el número de origen es el `telefono_movil` de una cuenta activa (con o sin `+52`).
3. Si es de una alerta que ya existe (misma persona, mismo id corto y hora ±60 s), lo liga a ella. Así, si el teléfono reintenta el SMS, la alerta no se duplica.
4. Si no existe, la registra con `canal_recepcion = SMS` y la despacha igual que una alerta por internet. El registro llega por la interfaz `RegistroAlertasSms`, que se conecta en `Application.kt` (ver [Puntos de conexión de la plataforma](#puntos-de-conexión-de-la-plataforma)). Mientras no hay implementación, en local se usa un registro de prueba y en la nube los mensajes válidos quedan pendientes (`procesado_en` nulo) hasta que se conecte.

A la persona no le llega ninguna respuesta por SMS. Con Twilio, la ruta siempre responde 200 con TwiML vacío, y para que Twilio llame al servidor, en la Console de Twilio el número debe tener como webhook de "A message comes in" la dirección `https://<dominio>/api/v1/pasarela/sms/entrante` (HTTP POST), y `PASARELA_URL_PUBLICA` debe ser `https://<dominio>/api/v1`.

### Celular Android como pasarela

Es lo que se usa en la nube, porque es gratis: un celular Android con chip manda y recibe los SMS con la app [SMS Gateway for Android](https://docs.sms-gate.app) en modo nube. Se activa con `AVISOS_PROVEEDOR_SMS=smsgate`; el código de Twilio sigue ahí para cuando se necesite.

- **Para mandar**, el servidor deja cada SMS en `api.sms-gate.app` con el usuario y la contraseña de la app (`SMSGATE_USUARIO`, `SMSGATE_PASSWORD`), y el celular lo manda con su chip. Cada intento lleva su propio id y vence en la nube un minuto antes de que el servidor lo reintente, así que si el celular estuvo sin internet el SMS no sale dos veces.
- **Para recibir**, la app llama a `POST /api/v1/pasarela/smsgate` con la firma `X-Signature` (HMAC-SHA256 con `SMSGATE_LLAVE_FIRMA`). Un `sms:received` pasa por la misma recepción que el de Twilio (pasos 2 a 4 de arriba). `sms:sent`, `sms:delivered` y `sms:failed` actualizan el aviso igual que el estado de Twilio.
- **Si el celular pierde internet**, los SMS que le llegan se quedan en el chip y la app reintenta avisar al servidor hasta por 2 días. Los que manda el servidor esperan en la nube hasta que vencen, y entonces el servidor los reintenta.
- **Ya está implementada y funcionando.** La app corre en un celular Android dedicado, conectado al servidor de Azure, con los eventos `sms:received`, `sms:sent`, `sms:delivered` y `sms:failed` registrados hacia `/api/v1/pasarela/smsgate` y la llave de firma configurada. Se probó de ida y vuelta: el servidor manda SMS por el celular y los SMS que llegan al celular entran al servidor. Para usarla no hay que instalar nada: la app de Sendero Seguro solo manda el SMS `SS1` al número de ese celular.
- La ruta rechaza con 401 cualquier aviso sin la firma correcta, así que nadie más puede meter SMS falsos al servidor.

**Ruta de diagnóstico.** El servidor trae `POST /api/v1/pasarela/prueba-sms`, que manda un SMS de prueba con el proveedor y las credenciales configuradas, sin crear alertas. Está apagada: solo existe mientras la variable `PRUEBA_SMS_LLAVE` tiene valor, y pide esa llave en `X-Llave-Prueba`. Con ella se comprobó el envío desde Azure.

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

## Retención de trayectos

Un trayecto terminado (`FINALIZADO` o `CANCELADO`) hace más de 30 días y que no tuvo alerta se borra, junto con sus check-ins, sus enlaces y sus avisos. Los que tuvieron alerta se quedan, porque son parte del historial de esa alerta, y los `EN_CURSO` no se tocan. El servidor lo revisa al arrancar y cada 6 horas, por tandas de 500, y deja en la bitácora un registro `PURGAR_TRAYECTOS` con la cantidad borrada.

## Estadísticas

`GET /api/v1/estadisticas/alertas?desde=2026-10-01&hasta=2026-10-31` da los indicadores del tablero a partir de `v_alerta_tiempos`. Solo la ve el Administrador y no trae datos de personas. Filtros opcionales `tipoIncidenteId` y `zonaId`.
- El periodo va por días completos en la hora de la Ciudad de México, de `desde` a `hasta` incluidos, y no puede pasar de 366 días.
- La zona es la caja de `zona_geografica` que contiene la primera ubicación de la alerta.
- `medianaAcuseSeg`: mediana del tiempo entre que se activó y se acusó.
- `pctCumpleMetaAcuse`: de las alertas que se acusaron o a las que se les pasó la meta (`meta_acuse_seg`, 120 s) sin acuse, cuántas se acusaron a tiempo. Las canceladas antes de la meta y las que todavía van dentro de ella no cuentan.
- `pctEscaladas`: las que escalaron al menos una vez. `porSemana` trae todas las semanas ISO del periodo, también las que no tuvieron alertas.

`GET /api/v1/estadisticas/exportar?formato=csv` (o `pdf`) descarga los mismos números, con los mismos filtros. Solo el Administrador, y cada exportación queda en la bitácora como `EXPORTAR_ESTADISTICAS` con el formato y los filtros; si no se puede registrar, no se entrega el archivo.
- **CSV:** una fila por dato (`seccion,concepto,valor,mediana_acuse_seg`), en UTF-8 con BOM para que Excel lea los acentos. Un texto que empieza con `=`, `+`, `-` o `@` se escribe con un apóstrofo delante para que Excel no lo ejecute como fórmula.
- **PDF:** tamaño carta, con el resumen, los repartos y una barra por semana. Usa Apache PDFBox y las fuentes estándar del PDF; un carácter que no traen (un emoji) sale como `?`.

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
| `AVISOS_PROVEEDOR_PUSH` / `AVISOS_PROVEEDOR_SMS` | Proveedor de cada canal: `simulado`, `fcm` (push), `twilio` o `smsgate` (SMS) | `simulado` |
| `FCM_CREDENCIALES` | Ruta del `.json` de la cuenta de servicio de Firebase (con `AVISOS_PROVEEDOR_PUSH=fcm`). En la nube puede traer el contenido del `.json` en vez de la ruta. | vacío |
| `TWILIO_ACCOUNT_SID` / `TWILIO_NUMERO` | Cuenta de Twilio y número que manda y recibe los SMS (`+1...`) | vacío |
| `TWILIO_AUTH_TOKEN` | Secreto de la cuenta de Twilio. Valida los webhooks y autentica los envíos. Sin él, los webhooks solo funcionan en local y sin firma. | vacío |
| `SMSGATE_USUARIO` / `SMSGATE_PASSWORD` | Usuario y contraseña de la app del celular pasarela (Cloud Server), con `AVISOS_PROVEEDOR_SMS=smsgate`. La contraseña es secreta. | vacío |
| `SMSGATE_LLAVE_FIRMA` | Llave de firma de los webhooks del celular pasarela. Sin ella, en la nube no se aceptan sus avisos. | vacío |
| `SMSGATE_URL` | Nube de la app | `https://api.sms-gate.app` |
| `PRUEBA_SMS_LLAVE` | Solo para probar el envío de SMS en la nube: activa `POST /pasarela/prueba-sms`. Se borra al terminar. | vacío |
| `PASARELA_URL_PUBLICA` | Dirección pública del servidor hasta `/api/v1`. Con ella se validan las firmas detrás de un proxy y Twilio avisa el estado de cada SMS. | vacío |
| `AVISOS_MAX_REINTENTOS` | Fallas antes de descartar un aviso | `5` |
| `AVISOS_ESPERA_BASE_SEG` | Espera antes del primer reintento; se duplica en cada falla | `15` |
| `AVISOS_REVISION_MS` | Cada cuántos milisegundos se buscan avisos por enviar | `2000` |
| `AVISOS_URL_ENLACE` | Página de seguimiento que se manda a la red de apoyo, sin el token | `http://localhost:8080/api/v1/enlace` |
| `TIEMPO_REAL_REVISION_MS` | Cada cuántos milisegundos se buscan cambios para los WebSocket | `1000` |
| `DB_DATOS_PRUEBA` | Carga los datos y las rutas de prueba | `true`. En la nube, `false`. |
| `JWT_SECRETO` | Llave para firmar y validar los JWT | Valor de desarrollo. En la nube es obligatorio cambiarla. |
| `DETRAS_DE_PROXY` | El servidor está detrás del balanceador de la nube: la IP de la bitácora se toma del final de `X-Forwarded-For` | `false`. En la nube, `true`. |
| `RETENCION_TRAYECTO_DIAS` / `RETENCION_REVISION_HORAS` | Días que se guarda un trayecto terminado sin alerta, y cada cuántas horas se revisa | `30` / `6` |

Para la nube se arma un solo archivo con `.\gradlew.bat :server:buildFatJar` (en Linux o Mac, `./gradlew`). Queda en `server/build/libs/sendero-seguro-server.jar` y se corre con `java -jar`.

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
| Sincronización: `POST /sync/lote`, con los manejadores de cada tipo por conectar | Listo |
| Bitácora: `Bitacora.registrar`, consulta y verificación de la cadena | Listo |
| Línea de tiempo de una alerta e historial de cada persona | Listo |
| Retención: borrar trayectos de más de 30 días sin alerta | Listo |
| Estadísticas de alertas para el tablero y exportación en CSV y PDF | Listo |
| Avisos: envío con reintentos (E-03) y proveedor simulado | Listo |
| Avisos: push con FCM y registro del token (`PUT /dispositivos/{id}/push`) | Listo |
| Avisos: SMS con el celular Android como pasarela (`/pasarela/smsgate`), de ida y vuelta | Listo, en uso en la nube |
| Avisos: SMS con Twilio y estado de entrega (`/pasarela/sms/estado`) | Listo, como alternativa (hoy sin número) |
| SMS de contingencia `SS1`, con `RegistroAlertasSms` por conectar | Listo |
| Servidor y base de datos en la nube (Azure) | Listo |
| Tiempo real: WebSocket de la consola y del enlace | Listo |

El detalle de cada punto está en `docs/api/README.md`.

## Errores comunes

| Lo que ves | Qué pasa y qué hacer |
|---|---|
| `Connection refused` a `localhost:5432` al arrancar | Docker Desktop no está abierto o falta `docker compose up -d`. |
| Docker dice que el puerto `5432` está ocupado | Tienes PostgreSQL instalado en tu compu. Detén su servicio (en Windows: Servicios → postgresql → Detener) y vuelve a levantar Docker. |
| `Address already in use` en el puerto 8080 | Ya hay un servidor corriendo. Ciérralo (Ctrl+C) o corre `.\gradlew.bat --stop`. |
| `jlink executable ... does not exist` | Gradle usa un Java incompleto. Ver la nota de `JAVA_HOME` en [Correr el servidor en local](#correr-el-servidor-en-local). |
| Las pruebas salen como omitidas (skipped) | Falta `SENDERO_TEST_DB_URL` en esa terminal. |
| Pruebas de alertas fallan sin razón | El servidor local estaba corriendo a la vez y tomó las alertas de las pruebas. Apágalo y vuelve a correrlas. |
| `Validate failed` o `checksum mismatch` de Flyway | Se editó una migración que ya se había aplicado. Regresa el archivo como estaba y pon el cambio en una migración nueva (`V3__…`). En local también puedes empezar de cero con `docker compose down -v`. |
| `401 NO_AUTENTICADO` | Falta `Authorization: Bearer <JWT>` o el token ya venció. En local pide otro con `POST /dev/token`. |
| `403 SIN_PERMISO` | Tu rol no puede usar esa ruta. La tabla de cada sección dice quién puede. |
| `404` en `/dev/...` en la nube | Es normal: las rutas de prueba solo existen en local. |
| El WebSocket se cierra con `1001` | El cliente no contestó `{"tipo":"pong"}` a dos `ping` seguidos. |
| El WebSocket se cierra con `1008` | Token faltante o vencido (`NO_AUTENTICADO`, `TOKEN_VENCIDO`), rol sin permiso (`SIN_PERMISO`) o enlace que ya no sirve (`ENLACE_NO_DISPONIBLE`). |
| En la nube un SMS tarda en llegar | Los SMS se reintentan solos: si la pasarela estuvo un momento sin conexión, el SMS sale cuando vuelve. |
| En local no llega ningún SMS ni push | Es lo correcto: en local el proveedor es `simulado` y el mensaje sale en el log con `[SIMULADO]`. |

## Palabras que se usan

| Palabra | Qué es |
|---|---|
| Alerta | Lo que manda una persona cuando está en peligro. Tiene un folio (`ALR-2026-000001`) y un estado. |
| Canalización | Elegir a qué corporación va una alerta según su tipo (`regla_canalizacion`). |
| Unidad / Operador | La unidad es quien atiende (una patrulla, una caseta, una ambulancia); el operador es la persona que la maneja desde la consola. |
| Acuse | El operador confirma que vio la alerta. Si no lo hace a tiempo, la alerta se escala. |
| Escalamiento | Mandar la alerta al siguiente nivel de su regla (`paso_escalamiento`) cuando nadie acusa. |
| Red de apoyo | Los contactos de confianza y el círculo de la persona. Reciben un aviso y, si tienen acceso de seguimiento, el enlace de ubicación. |
| Enlace de ubicación | Liga pública con token para seguir la alerta sin cuenta. Se apaga cuando la alerta termina. |
| Aviso | Un push o SMS que hay que entregar. Es una fila de `notificacion` con reintentos. |
| Proveedor | Quién entrega el aviso: `simulado`, `fcm` (push), `smsgate` (celular pasarela) o `twilio` (SMS). |
| Pasarela | Lo que conecta el servidor con los SMS: el celular Android o Twilio. |
| `SS1` / id corto | El SMS de contingencia de 46 caracteres, y los primeros 8 caracteres del UUID de la alerta que lleva adentro. |
| PIN de coacción | PIN que parece cancelar la alerta pero la deja activa en silencio. |
| Bitácora | Registro de auditoría encadenado con hash; no se puede cambiar ni borrar. |
| Lote offline | Lo que el teléfono guardó sin internet y manda junto a `/sync/lote`. |
