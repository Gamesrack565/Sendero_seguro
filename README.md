# Sendero Seguro

Sistema de alertas de emergencia para la comunidad del IPN. La persona activa una alerta desde la app Android (botón, voz, caída, atajo o temporizador). La alerta llega al servidor por internet o por la malla Bluetooth entre teléfonos cercanos, y el servidor también la recibe como SMS. El servidor la asigna a la unidad de atención más cercana, avisa a su red de apoyo con un enlace de ubicación en vivo y deja el registro de todo lo que pasó. También recibe los reportes de la comunidad, los modera, arma el mapa de calor y calcula la ruta segura.

Esta rama junta el backend en la nube (Módulo 4) con los módulos de la app Android: identidad (Módulo 1) y emergencias (Módulo 2). El Módulo 1 y el Módulo 2 están conectados con el servidor, y entre ellos en la sesión y la cancelación. Está probado contra el servidor en Azure.

## Dónde está cada cosa

| Archivo o carpeta | Qué tiene |
|---|---|
| `README.md` | Esta página: qué es el proyecto, estado de cada proceso y dónde está la documentación |
| [`docs/prueba-alerta-local.md`](docs/prueba-alerta-local.md) | **Prueba local de alerta.** Formulario, cascada internet → malla → SMS → cola y consulta de `evento_alerta` |
| [`docs/prueba-p12-local.md`](docs/prueba-p12-local.md) | **Prueba local P12.** Descarga versionada del mapa de calor, el grafo y los paquetes offline |
| [`docs/azure/README.md`](docs/azure/README.md) | **Guía futura de integración en Azure.** Pasos documentados para desplegar y verificar P9–P12 cuando se autorice; no implica cambios en la nube |
| [`docs/modulo4/README.md`](docs/modulo4/README.md) | **Backend en la nube (Módulo 4).** Servidor, base de datos, rutas de la API, cómo correrlo en local, el servidor en Azure, avisos, SMS, bitácora y configuración |
| [`docs/api/openapi.yaml`](docs/api/openapi.yaml) | Contrato de la API: cada ruta con su JSON de entrada, respuestas y errores |
| [`docs/api/README.md`](docs/api/README.md) | Mensajes del WebSocket, formato del SMS de contingencia `SS1` y puntos de conexión de la plataforma |
| [`docs/modulo1/README.md`](docs/modulo1/README.md) | **App, Módulo 1 (identidad).** Registro, sesión, contactos, círculo, activación rápida, cancelación y posible coacción |
| [`docs/modulo1/modulo1_identidad.md`](docs/modulo1/modulo1_identidad.md) | Resumen de los paquetes del Módulo 1 |
| [`docs/modulo1/modulo1_contrato_api.md`](docs/modulo1/modulo1_contrato_api.md) | Contrato que se usó al empezar el Módulo 1. El vigente es `docs/api/openapi.yaml` |
| [`docs/modulo2/README.md`](docs/modulo2/README.md) | **App, Módulo 2 (emergencias).** Detonadores, IA-1, telemetría, malla BLE, ruta segura y conexión con el servidor |
| `app/` | App Android (Kotlin). `identidad/` es el Módulo 1 y `emergencias/` el Módulo 2 |
| `server/` | Servidor Ktor (Kotlin) y migraciones de la base PostgreSQL |
| `db/` | Esquema de la base local del teléfono |
| `pruebas-nube/` | Scripts de PowerShell para el servidor en Azure. `probar-todo.ps1` recorre alertas y cuentas, `probar-unidades-y-comunidad.ps1` la administración y la comunidad, y `crear-administrador.ps1` da de alta un Administrador |
| `pruebas-carga/` | Prueba de carga contra la nube (RNF13) |
| `docker-compose.yml` | Base de datos y servicios para correr el servidor en local |

## Estado por proceso

| Proceso | Módulo | Estado |
|---|---|---|
| P1 Registro y autenticación | 1 | Listo en la app y en el servidor |
| P2 Contactos de confianza y círculo | 1 | Listo en la app y en el servidor. El contacto recibe SMS reales |
| P3 Parámetros de seguridad | 1 y 4 | Listo en la app (atajo, widget, cuenta regresiva y energía, en A-12). El catálogo de incidentes, sus reglas y el escalamiento se editan, validan y publican en el servidor |
| P4 Activación de la alerta | 2 | Listo. La app incluye un formulario de prueba local para recorrer internet → malla → SMS → cola |
| P5 Dispersión | 4 | Listo en Azure: asignación de la unidad más cercana, escalamiento y avisos a la red de apoyo. Corporaciones, unidades, operadores y turnos se administran con la API |
| P5.1 Retransmisión por malla | 2 | Listo. Paquete BLE de 24 bytes; el teléfono con internet lo entrega al servidor |
| P6 Telemetría en tiempo real | 2 | Listo. La ubicación en vivo llega al servidor y al enlace de la red de apoyo |
| P7 Caminata segura | 2 | Funciona en el teléfono (ruta segura offline y check-in) |
| P11 Trayectos | 4 | Listo en el servidor: guarda trayectos y check-ins, por internet o por la cola sin señal, y avisa a la red de apoyo si se comparte |
| P8 Cancelación | 1 | Listo. Cancelar detiene la alerta en el teléfono y en el servidor. Un PIN que no coincide deja la alerta activa, con mensaje neutro, y el servidor lo marca como posible coacción (flujo 3a de CU-21) |
| P9 Mapa comunitario | 3 | Pantalla provisional: muestra reportes públicos recientes con filtros y conserva el último feed para consulta sin conexión |
| P10 Reportes comunitarios | 3 | Formulario provisional con fotos o grabación de audio y cola P11; aprobación automática únicamente en el servidor local |
| P12 Ruta segura y mapas offline | 3 | Pantalla provisional para probar la descarga versionada de capa, grafo y paquetes y su caché local |
| P13 Auditoría e historial | 4 | Listo en Azure: bitácora encadenada, línea de tiempo, historial y estadísticas |
| P14 Infraestructura y API | 4 | Listo en Azure (App Service con Java 21) |
| P15 Base de datos en la nube | 4 | Listo en Azure (PostgreSQL 16), con archivos en Azure Blob Storage |
| P16 Pasarelas y tiempo real | 4 | Listo en Azure: WebSocket, push con Firebase y SMS con el celular pasarela |

### Lo que sigue

**En la app**
- **Pantallas.** Las 19 pantallas del E3 (A-01 a A-19) están en `app/.../ui/pantallas`, por bloques (acceso, alerta, red, comunidad, operación), y `NavegacionApp` sigue el mapa de navegación G-02. Las herramientas de prueba del equipo (prueba local de alerta, diagnóstico P5.1/P6/P7, prototipos P10 y P12, galería) quedan en Perfil › Herramientas de prueba, solo en debug.
- **Tipo y relato después de enviar (A-06).** El servidor no tiene una ruta para cambiar el tipo o la descripción de una alerta ya registrada; el tipo elegido en A-05 sí sale con la alerta, pero lo de A-06 solo se guarda en el teléfono.
- **Token de push.** Después del login, la app tiene que pedir su token de Firebase y mandarlo a `PUT /dispositivos/{id}/push`. El servidor ya manda push y ya recibe el token; los pasos están en [docs/modulo4/README.md](docs/modulo4/README.md#cuentas-red-de-apoyo-y-cancelación). Mientras no lo mande, los avisos a esa persona le llegan por SMS.
- **Configuración de activación (P3) hacia el Módulo 2.** La cuenta regresiva de A-12 ya la usa el Módulo 2. El atajo del botón de encendido, el widget y el escaneo según la batería se guardan en el Módulo 1, pero el Módulo 2 todavía usa sus propios valores.
- **SMS de contingencia.** El envío `SS1` desde la app está habilitado únicamente en debug y solo con número de pasarela configurado; no se habilita en release.
- **Modo operador (A-14).** El servidor ya recibe el acuse por la cola sin señal (`ACUSE` en `/sync/lote`) y el turno de la unidad (`/unidades/mia`); falta la pantalla del operador en la app que los mande. Mientras tanto el operador acusa con internet.
- **Trayectos (P7 con P11).** El servidor ya guarda trayectos y check-ins (`/trayectos`, y `TRAYECTO` y `CHECKIN` en `/sync/lote`); falta que la app los mande. Mientras tanto se quedan en el teléfono.
- **Comunidad (Módulo 3, P9/P10/P12).** El formulario, el feed y el mapa actuales son provisionales. La aprobación automática está limitada al servidor local; faltan las pantallas finales y el panel de administración/moderación.

**En la web**
- **Consola del operador y página del enlace.** El servidor ya da las rutas y el WebSocket de la consola y del enlace de ubicación. El enlace hoy responde con los datos en JSON; falta la página que los muestra en un mapa.
- **Pantallas de administración y moderación.** El servidor ya tiene las rutas de usuarios, corporaciones, unidades, catálogo y moderación; faltan las pantallas de la consola. Mientras tanto se usan con la API.

**En la nube**
- **Unidades y operadores reales.** Ya se dan de alta con `/unidades` y `/usuarios/{id}/perfil`, y `crear-administrador.ps1` crea el primer Administrador. Falta dar de alta las unidades reales en Azure; mientras tanto las alertas quedan "Buscando unidad" y escalan.
- **Grafo peatonal.** Falta cargar el grafo de calles de la zona con `POST /grafo` (datos de OpenStreetMap); mientras tanto `/rutas/segura` responde `FUERA_DE_AREA` y la app calcula la ruta en el teléfono.

**Otros módulos y pruebas**
- **IA pendiente.** P10 todavía no tiene evaluación automática de veracidad; P9 todavía no tiene pronóstico de riesgo IA-2. El mapa actual es descriptivo y agrupa reportes publicados con DBSCAN. La transcripción de audio también está pospuesta.
- **Teléfonos físicos.** Probar la malla BLE y el GPS entre dos o más teléfonos reales, y que un push llegue a un teléfono.

## Cómo se conectan los módulos

| Parte | Cómo funciona |
|---|---|
| Cuentas y contactos (P1, P2) | El cliente de red del Módulo 1 usa las rutas y el JSON del servidor (`docs/api/openapi.yaml`). La versión debug usa `10.0.2.2:8080` en emulador; en teléfono físico lee `senderoApiUrl` de `local.properties` (o acepta `-PsenderoApiUrl=...`). La release usa Azure. |
| Módulo 1 con Módulo 2 | En `SenderoApp`. El Módulo 2 usa la sesión y el id de la cuenta del Módulo 1 (también contra el servidor local; el usuario de prueba solo se usa sin sesión). Los eventos de cancelación del Módulo 1 detienen la alerta del Módulo 2. |
| Alerta por internet (P4) | `ModuloEmergencias` manda la alerta a `POST /alertas` en cuanto hay internet y toma el folio del servidor. Hay un solo `ModuloEmergencias` para toda la app, en `SenderoApp`. Los detonadores de la app se traducen a los del servidor (`BOTON_SOS` → `BOTON`, etc.). |
| Malla (P5.1, RF-14) | El paquete BLE es de 24 bytes, con ids cortos de la alerta y de la persona. El teléfono con internet que lo recibe lo entrega a `POST /malla/relevos`, también en el último salto. El servidor reconoce a la persona por su id corto y no duplica la alerta si llega por varios teléfonos. |
| Ubicación en vivo (P6) | Cada lectura del GPS se persiste en la cola P11 antes de enviarse a `POST /alertas/{id}/ubicaciones`. WorkManager reintenta los fallos al recuperar la red; primero sincroniza la alerta y después sus lecturas, con la vigencia de 2 horas de la cola de alertas. |
| Cancelación (P8) | Las cancelaciones guardadas sin señal se mandan al abrir la app y al volver internet. El servidor encuentra la alerta con el id del teléfono aunque haya llegado antes por la malla o por SMS. |
| Posible coacción (P8) | Un PIN que no coincide no cancela: la pantalla muestra un mensaje neutro, la alerta sigue activa y el servidor recibe el intento con `exitoso = false` y lo marca como posible coacción. Solo la consola ve la marca. |
| Bases del teléfono | El Módulo 1 usa `sendero_seguro_local.db` (Room) y el Módulo 2, `sendero_seguro_malla.db` (SQLite). |

## Cómo se probó

- **Servidor:** `.\gradlew :server:test` (pruebas contra PostgreSQL en Docker).
- **App:** `.\gradlew :app:testDebugUnitTest` con el servidor local corriendo (`.\gradlew :server:run`). Prueban el código real de la app contra el servidor: registro y cancelación de alertas, ubicación en vivo, relevo de la malla, cola sin señal y posible coacción. Para ejecutar la nueva prueba manual, sigue [`docs/prueba-alerta-local.md`](docs/prueba-alerta-local.md).
- **Nube:** `powershell -ExecutionPolicy Bypass -File pruebas-nube\probar-todo.ps1`. Recorre cuentas, alertas, malla, cola sin señal, cancelación, posible coacción, consola, tiempo real, historial, archivos y bitácora contra Azure, y al final limpia lo que creó. `probar-unidades-y-comunidad.ps1` recorre usuarios, unidades, turno, acuse y atención, la cola sin señal del operador, el catálogo, trayectos, reportes, moderación, feed, mapa de calor y mapas, y también limpia. El detalle está en [docs/modulo4/README.md](docs/modulo4/README.md#servidor-en-la-nube).

## Cambios recientes: pantallas provisionales de comunidad, mapas y colas

Esta sección resume los prototipos actuales de P9, P10, P11 y P12. **No son las pantallas finales ni incluyen el panel de administración/moderación.** La aprobación automática está limitada al servidor local; no se debe desplegar ni habilitar en Azure.

| Proceso | Cambios |
|---|---|
| **P9 — Mapa comunitario** | La pantalla provisional descarga el feed público, presenta reportes como puntos seleccionables, ofrece filtros locales y conserva el último feed para consultarlo sin conexión. |
| **P10 — Reportes comunitarios** | El formulario provisional admite fotos y grabación de audio; los reportes y evidencias pueden esperar en la cola P11. En el servidor local, los reportes nuevos se aprueban automáticamente con datos personales ocultos y ubicación aproximada. Los reportes pendientes anteriores no se cambian. |
| **P11 — Cola offline** | WorkManager reintenta reportes, evidencias y lecturas de telemetría pendientes. El audio se adjunta como evidencia; no se transcribe. |
| **P12 — Mapas offline** | La pantalla provisional sincroniza versiones de la capa de calor, el grafo y los paquetes MBTiles, y conserva los datos descargados localmente. P12 proporciona el grafo cacheado que P7 puede usar para calcular rutas offline. |
| **P6 — Telemetría** | Las ubicaciones se guardan en Room antes del envío y se reintentan por la cola, incluso si el primer intento falla con conexión disponible. |

### Módulos involucrados

| Módulo | Responsabilidad e integración actual |
|---|---|
| **Módulo 1 — Identidad** | Registro y sesión de usuario. Proporciona la identidad y sesión que usan los envíos autenticados. |
| **Módulo 2 — Emergencias** | Alertas, malla, GPS y P6. La telemetría pendiente se persiste en la cola local y se envía después de su alerta asociada. |
| **Módulo 3 — Inteligencia comunitaria** | Sus pantallas actuales son prototipos: P9 muestra el feed; P10 captura reportes; P12 sincroniza mapa de calor, grafo vial y MBTiles. La aprobación automática aplica solo al servidor local; Azure conserva el flujo existente. |
| **Módulo 4 — Backend y nube** | API Ktor, PostgreSQL, Blob Storage y procesos de servidor. Recibe reportes/sincronizaciones y publica los datos consumidos por P9 y P12. |

## Siguiente etapa de IA (pendiente)

Las siguientes funciones no están implementadas. El mapa de calor actual no es IA ni predicción: agrupa reportes publicados con DBSCAN. Antes de empezar cualquiera de estas funciones hay que acordar fuentes de datos, privacidad, ejecución local o en nube y criterios de evaluación.

1. **P10 — Veracidad y clasificación de reportes.** Definir qué señales puede sugerir el modelo y cómo medir falsos positivos. Integrarlo como apoyo a una decisión humana, no como rechazo automático. La publicación automática local es solo para pruebas y no debe llegar a Azure; antes del flujo final habrá que implementar las pantallas de administración y moderación.
2. **P9 — Pronóstico de riesgo (IA-2).** Diseñar un modelo espacio-temporal separado del mapa descriptivo: horizonte de predicción, cobertura, confianza, validación con datos históricos y límites de uso. Publicarlo con una versión y marca de tiempo; mantener el mapa de calor actual como alternativa si el modelo no está disponible.
3. **Transcripción opcional de audio.** Aún no se transcribe. Antes de implementarla, elegir entre reconocimiento en el dispositivo o un servicio de nube, pedir autorización para procesar el audio y definir cuánto tiempo se conserva. No enviar grabaciones a un servicio externo sin aprobación.

Cada etapa de IA requiere pruebas con datos representativos, revisión de privacidad y sesgos, auditoría de resultados, despliegue gradual y una forma de desactivarla sin interrumpir los reportes ni el mapa actual.
