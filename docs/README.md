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
  src/main/kotlin/…/plataforma/      Base de datos, errores, JWT, logs y /salud
  src/main/resources/
    application.conf                 Configuración (puerto, base de datos, JWT)
    db/migration/V1__esquema_inicial.sql   Base de datos del servidor (PostgreSQL 16): 53 tablas, vistas, triggers y datos iniciales
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

**Pruebas:** `./gradlew :server:test` (en Windows: `.\gradlew.bat :server:test`).

**Si Gradle usa un Java incompleto** (por ejemplo, el error `jlink executable ... does not exist`), apunta `JAVA_HOME` al Java de Android Studio. En Windows, desde PowerShell:

```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Android\Android Studio\jbr", "User")
```

Después cierra y vuelve a abrir la terminal (y VS Code), y corre `.\gradlew.bat --stop`.

## Configuración

Los valores de `application.conf` sirven para desarrollo. En la nube se reemplazan con variables de entorno:

| Variable | Para qué | Valor en local |
|---|---|---|
| `PORT` | Puerto del servidor | `8080` |
| `DB_URL` | Conexión JDBC a PostgreSQL | `jdbc:postgresql://localhost:5432/sendero_seguro` |
| `DB_USUARIO` / `DB_PASSWORD` | Credenciales de la base | `postgres` / `sendero` |
| `DB_POOL_MAX` | Conexiones máximas | `10` |
| `JWT_SECRETO` | Llave para firmar y validar los JWT | Valor de desarrollo. En la nube es obligatorio cambiarla. |

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
| Dispersión: `Dispersion.despachar(alertaId)` | En desarrollo |
| Sincronización: `POST /sync/lote` | En desarrollo |
| Ayuda para escribir en la bitácora | En desarrollo |
| Avisos: push (FCM), SMS y WhatsApp opcional | En desarrollo |
| Tiempo real: WebSocket de la consola y del enlace | En desarrollo |

El detalle de cada punto está en `docs/api/README.md`.
