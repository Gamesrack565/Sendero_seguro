# Sendero Seguro

Sistema de alertas de emergencia para la comunidad del IPN. La persona activa una alerta desde la app Android (botón, voz, caída, atajo o temporizador). La alerta llega al servidor por internet o por la malla Bluetooth entre teléfonos cercanos, y el servidor también la recibe como SMS. El servidor la asigna a la unidad de atención más cercana, avisa a su red de apoyo con un enlace de ubicación en vivo y deja el registro de todo lo que pasó. También recibe los reportes de la comunidad, los modera, arma el mapa de calor y calcula la ruta segura.

Esta rama junta el backend en la nube (Módulo 4) con los módulos de la app Android: identidad (Módulo 1) y emergencias (Módulo 2). El Módulo 1 y el Módulo 2 están conectados con el servidor, y entre ellos en la sesión y la cancelación. Está probado contra el servidor en Azure.

## Dónde está cada cosa

| Archivo o carpeta | Qué tiene |
|---|---|
| `README.md` | Esta página: qué es el proyecto, estado de cada proceso y dónde está la documentación |
| [`docs/modulo4/README.md`](docs/modulo4/README.md) | **Backend en la nube (Módulo 4).** Servidor, base de datos, rutas de la API, cómo correrlo en local, el servidor en Azure, avisos, SMS, bitácora y configuración |
| [`docs/api/openapi.yaml`](docs/api/openapi.yaml) | Contrato de la API: cada ruta con su JSON de entrada, respuestas y errores |
| [`docs/api/README.md`](docs/api/README.md) | Mensajes del WebSocket, formato del SMS de contingencia `SS1` y puntos de conexión de la plataforma |
| [`docs/modulo1/README.md`](docs/modulo1/README.md) | **App, Módulo 1 (identidad).** Registro, sesión, contactos, círculo, activación rápida, cancelación y PIN de coacción |
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
| P3 Parámetros de seguridad | 1 y 4 | Listo en la app (atajo, widget, cuenta regresiva, energía y PIN de coacción). El catálogo de incidentes, sus reglas y el escalamiento se editan, validan y publican en el servidor |
| P4 Activación de la alerta | 2 | Listo. La alerta llega al servidor por internet, por la malla o por la cola sin señal, sin duplicarse |
| P5 Dispersión | 4 | Listo en Azure: asignación de la unidad más cercana, escalamiento y avisos a la red de apoyo. Corporaciones, unidades, operadores y turnos se administran con la API |
| P5.1 Retransmisión por malla | 2 | Listo. Paquete BLE de 24 bytes; el teléfono con internet lo entrega al servidor |
| P6 Telemetría en tiempo real | 2 | Listo. La ubicación en vivo llega al servidor y al enlace de la red de apoyo |
| P7 Caminata segura | 2 | Funciona en el teléfono (ruta segura offline y check-in) |
| P11 Trayectos | 4 | Listo en el servidor: guarda trayectos y check-ins, por internet o por la cola sin señal, y avisa a la red de apoyo si se comparte |
| P8 Cancelación | 1 | Listo. Cancelar detiene la alerta en el teléfono y en el servidor. El PIN de coacción la muestra cancelada y la deja activa |
| P9 Reportes y moderación | 3 | Listo en el servidor: reportes con evidencia, anónimos con el autor cifrado, moderación e identidad protegida con justificación |
| P10 Feed y mapa de calor | 3 | Listo en el servidor: feed de reportes publicados y zonas de riesgo con DBSCAN, por 7 y 30 días |
| P12 Ruta segura y mapas offline | 3 | Listo en el servidor: ruta segura con A* sobre el grafo peatonal, grafo por zona para el teléfono, paquetes de mapa, puntos de interés y zonas |
| P13 Auditoría e historial | 4 | Listo en Azure: bitácora encadenada, línea de tiempo, historial y estadísticas |
| P14 Infraestructura y API | 4 | Listo en Azure (App Service con Java 21) |
| P15 Base de datos en la nube | 4 | Listo en Azure (PostgreSQL 16), con archivos en Azure Blob Storage |
| P16 Pasarelas y tiempo real | 4 | Listo en Azure: WebSocket, push con Firebase y SMS con el celular pasarela |

### Lo que sigue

**En la app**
- **Pantallas.** Hoy la app abre la pantalla de diagnóstico del Módulo 2. Faltan las de registro e inicio de sesión, contactos y círculo, configuración (incluido crear el PIN de cancelación y el de coacción), cancelación (A-08) y cuenta regresiva (A-05). Los ViewModels del Módulo 1 ya están listos para conectarlas.
- **Token de push.** Después del login, la app tiene que pedir su token de Firebase y mandarlo a `PUT /dispositivos/{id}/push`. El servidor ya manda push y ya recibe el token; los pasos están en [docs/modulo4/README.md](docs/modulo4/README.md#cuentas-red-de-apoyo-y-cancelación). Mientras no lo mande, los avisos a esa persona le llegan por SMS.
- **Configuración de activación (P3) hacia el Módulo 2.** La cuenta regresiva elegida, el atajo del botón de encendido, el widget y el escaneo según la batería se guardan en el Módulo 1, pero el Módulo 2 todavía usa sus propios valores.
- **SMS de contingencia.** El servidor y el celular pasarela ya reciben el SMS `SS1` y registran la alerta; la app todavía no lo manda.
- **Modo operador (A-14).** El servidor ya recibe el acuse por la cola sin señal (`ACUSE` en `/sync/lote`) y el turno de la unidad (`/unidades/mia`); falta la pantalla del operador en la app que los mande. Mientras tanto el operador acusa con internet.
- **Trayectos (P7 con P11).** El servidor ya guarda trayectos y check-ins (`/trayectos`, y `TRAYECTO` y `CHECKIN` en `/sync/lote`); falta que la app los mande. Mientras tanto se quedan en el teléfono.
- **Reportes, feed y mapa de calor (Módulo 3).** El servidor ya tiene las rutas; faltan las pantallas de la app. Mientras tanto se prueban con `probar-unidades-y-comunidad.ps1`.

**En la web**
- **Consola del operador y página del enlace.** El servidor ya da las rutas y el WebSocket de la consola y del enlace de ubicación. El enlace hoy responde con los datos en JSON; falta la página que los muestra en un mapa.
- **Pantallas de administración y moderación.** El servidor ya tiene las rutas de usuarios, corporaciones, unidades, catálogo y moderación; faltan las pantallas de la consola. Mientras tanto se usan con la API.

**En la nube**
- **Unidades y operadores reales.** Ya se dan de alta con `/unidades` y `/usuarios/{id}/perfil`, y `crear-administrador.ps1` crea el primer Administrador. Falta dar de alta las unidades reales en Azure; mientras tanto las alertas quedan "Buscando unidad" y escalan.
- **Grafo peatonal.** Falta cargar el grafo de calles de la zona con `POST /grafo` (datos de OpenStreetMap); mientras tanto `/rutas/segura` responde `FUERA_DE_AREA` y la app calcula la ruta en el teléfono.

**Otros módulos y pruebas**
- **Pronóstico de riesgo (IA-2).** Falta el modelo de IA; mientras tanto el mapa de calor agrupa los reportes publicados con DBSCAN.
- **Teléfonos físicos.** Probar la malla BLE y el GPS entre dos o más teléfonos reales, y que un push llegue a un teléfono.

## Cómo se conectan los módulos

| Parte | Cómo funciona |
|---|---|
| Cuentas y contactos (P1, P2) | El cliente de red del Módulo 1 usa las rutas y el JSON del servidor (`docs/api/openapi.yaml`). La versión debug de la app usa el servidor de la PC (`10.0.2.2:8080`) y la release, el de Azure. |
| Módulo 1 con Módulo 2 | En `SenderoApp`. El Módulo 2 usa la sesión y el id de la cuenta del Módulo 1. Los eventos de cancelación del Módulo 1 detienen la alerta del Módulo 2, o la dejan en modo silencioso con el PIN de coacción. |
| Alerta por internet (P4) | `ModuloEmergencias` manda la alerta a `POST /alertas` en cuanto hay internet y toma el folio del servidor. Hay un solo `ModuloEmergencias` para toda la app, en `SenderoApp`. Los detonadores de la app se traducen a los del servidor (`BOTON_SOS` → `BOTON`, etc.). |
| Malla (P5.1, RF-14) | El paquete BLE es de 24 bytes, con ids cortos de la alerta y de la persona. El teléfono con internet que lo recibe lo entrega a `POST /malla/relevos`, también en el último salto. El servidor reconoce a la persona por su id corto y no duplica la alerta si llega por varios teléfonos. |
| Ubicación en vivo (P6) | Cada lectura del GPS va a `POST /alertas/{id}/ubicaciones`. Sin señal se juntan y se mandan al volver internet; si la alerta se activó sin señal, primero se manda la alerta. La cola sin señal (`/sync/lote`) también acepta ubicaciones. |
| Cancelación (P8) | Las cancelaciones guardadas sin señal se mandan al abrir la app y al volver internet. El servidor encuentra la alerta con el id del teléfono aunque haya llegado antes por la malla o por SMS. |
| PIN de coacción (P8) | Segundo PIN, distinto al de cancelación. Al usarlo la pantalla muestra la alerta cancelada, el servidor recibe el intento como coacción y la alerta sigue activa en silencio. |
| Bases del teléfono | El Módulo 1 usa `sendero_seguro_local.db` (Room) y el Módulo 2, `sendero_seguro_malla.db` (SQLite). |

## Cómo se probó

- **Servidor:** `.\gradlew :server:test` (pruebas contra PostgreSQL en Docker).
- **App:** `.\gradlew :app:testDebugUnitTest` con el servidor local corriendo (`.\gradlew :server:run`). Prueban el código real de la app contra el servidor: registro y cancelación de alertas, ubicación en vivo, relevo de la malla, cola sin señal y PIN de coacción.
- **Nube:** `powershell -ExecutionPolicy Bypass -File pruebas-nube\probar-todo.ps1`. Recorre cuentas, alertas, malla, cola sin señal, cancelación, coacción, consola, tiempo real, historial, archivos y bitácora contra Azure, y al final limpia lo que creó. `probar-unidades-y-comunidad.ps1` recorre usuarios, unidades, turno, acuse y atención, la cola sin señal del operador, el catálogo, trayectos, reportes, moderación, feed, mapa de calor y mapas, y también limpia. El detalle está en [docs/modulo4/README.md](docs/modulo4/README.md#servidor-en-la-nube).
