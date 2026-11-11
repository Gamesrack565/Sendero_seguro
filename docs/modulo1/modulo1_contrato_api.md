# Módulo 1 · Contrato de API con el backend (Módulo 4)

Base: `{API_BASE_URL}` (desarrollo: `http://10.0.2.2:8080/api/v1/`). JSON en `snake_case` con los mismos
nombres que las columnas de `sendero_seguro_postgres.sql`. Fechas ISO-8601 (`timestamptz`). Autenticación con
`Authorization: Bearer <JWT>` salvo en `avisos-privacidad`, `instituciones` y `auth/*`.

## Errores

Toda respuesta 4xx lleva este cuerpo; la app lo traduce en `ServicioRemotoRetrofit.mapearHttp`:

```json
{ "codigo": "CREDENCIALES_INVALIDAS", "mensaje": "…", "errores": { "telefono": "…" },
  "intentos_restantes": 3, "bloqueada_hasta": "2026-11-07T21:40:00Z", "rol": "ADMINISTRADOR" }
```

| HTTP | `codigo` | Cuándo |
|---|---|---|
| 400/422 | `VALIDACION` | `errores` por campo (mismas claves que `Validaciones.kt`) |
| 401 | `CREDENCIALES_INVALIDAS` | login fallido; incluye `intentos_restantes` |
| 401 | `TOKEN_INVALIDO` | token vencido o revocado |
| 423 | `CUENTA_BLOQUEADA` | 5.º intento fallido o cuenta en `BLOQUEADA_TEMPORAL`; incluye `bloqueada_hasta` |
| 403 | `CUENTA_SUSPENDIDA` / `PERFIL_NO_PERMITIDO` | estado `SUSPENDIDA` / rol sin acceso |
| 404, 410 | `CODIGO_INVALIDO`, `CODIGO_VENCIDO` | código de círculo inexistente o `VENCIDO` |
| 409 | `DUPLICADO` | teléfono o matrícula ya registrados, contacto repetido |

## P1 · Privacidad, registro y autenticación

| Método | Ruta | Cuerpo → Respuesta |
|---|---|---|
| GET | `avisos-privacidad/vigente` | → `{id, version, contenido_hash}` |
| GET | `instituciones` | → `[{id, nombre, siglas}]` |
| GET | `instituciones/{id}/escuelas` | → `[{id, institucion_id, nombre}]` |
| POST | `auth/registro` | `{nombre, telefono_movil, identificador_institucional, tipo_comunidad, escuela_id, carrera_adscripcion, contrasena, acepta_entrenamiento_ia, consentimiento:{aviso_id, aceptado_en}, dispositivo:{id, modelo, android_api, soporta_wifi_aware}}` → `Sesion` |
| POST | `auth/login` | `{usuario, contrasena, dispositivo}` → `Sesion`. `usuario` es teléfono (10 dígitos) o matrícula |
| POST | `auth/logout` | revoca la sesión (`sesion.revocada_en`) |
| GET | `usuarios/me` | → `Usuario` |
| PATCH | `usuarios/me` | `{telefono_movil?, identificador_institucional?, carrera_adscripcion?, acepta_entrenamiento_ia?}` → `Usuario` |
| POST | `usuarios/me/baja` | `{contrasena}` → `usuario.estado = BAJA_SOLICITADA` |

`Sesion = {token, expira_en, usuario: Usuario}`
`Usuario = {id, nombre, telefono_movil, identificador_institucional, escuela_id, carrera_adscripcion, rol, estado, acepta_entrenamiento_ia}`

Reglas del servidor: contraseña con Argon2id/bcrypt; `intentos_fallidos` y `bloqueada_hasta` en `usuario`;
guardar `sesion.token_hash` (SHA-256), nunca el token; registrar en `consentimiento` y `dispositivo`;
`ACCESO`/`BLOQUEO` en `bitacora`. El PIN de cancelación **no** viaja: vive solo en el teléfono.

## P2 · Red de apoyo

| Método | Ruta | Cuerpo → Respuesta |
|---|---|---|
| GET | `contactos` | → `[Contacto]` |
| PUT | `contactos/{id}` | `Contacto` → `Contacto` (idempotente: el `id` lo genera el teléfono) |
| DELETE | `contactos/{id}` | — (idempotente: 204 aunque ya no exista) |
| PUT | `contactos/orden` | `{ids:[…]}` → `[Contacto]` con prioridad 1..n |
| GET | `circulos/mio` | → `{circulo: Circulo \| null}` |
| POST | `circulos` | `{id, nombre_grupo}` → `Circulo` (solo COORDINADOR; el servidor genera código y vigencia) |
| POST | `circulos/unirse` | `{codigo}` → `Circulo` |
| POST | `circulos/{id}/codigo` | → `Circulo` con código nuevo (solo su coordinador) |
| GET | `circulos/{id}/miembros` | → `[{usuario_id, nombre, estado}]` |
| DELETE | `circulos/{id}/miembros/{usuarioId}` | → `DADA_DE_BAJA` (solo su coordinador) |
| POST | `circulos/{id}/retirarme` | → `RETIRADA` |
| POST | `red-apoyo/avisos` | `{tipo: CONTACTO_AGREGADO \| NUEVO_INTEGRANTE, contacto_id?, circulo_id?}` → crea `notificacion` |

`Contacto = {id, nombre, telefono, prioridad, canal_preferido, usuario_vinculado_id, activo}`. El servidor
llena `usuario_vinculado_id` si el teléfono pertenece a una cuenta y entonces puede poner `canal_preferido = PUSH`.

`Circulo = {id, nombre_grupo, escuela_id, coordinador_id, codigo_invitacion, estado_codigo, codigo_vence_en}`

## P8 · Cancelación

| Método | Ruta | Cuerpo |
|---|---|---|
| POST | `alertas/{id}/cancelaciones` | `{dispositivo_id, motivo, metodo: PIN \| BIOMETRIA, exitoso, intentado_en}` |

- `exitoso = true`: insertar en `cancelacion`, `alerta.estado = CANCELADA`, `cancelada_en`, `motivo_cancelacion`,
  `evento_alerta`, revocar `enlace_ubicacion`, liberar `asignacion` y crear `notificacion` tipo `CANCELACION`
  para la unidad y la red de apoyo.
- `exitoso = false`: solo insertar en `cancelacion` y marcar posible coacción (la alerta **no** cambia).
- Idempotente por `(alerta_id, intentado_en)`: el mismo paquete puede llegar por internet y luego por malla o SMS.
