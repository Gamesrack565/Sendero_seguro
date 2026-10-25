# Sendero Seguro · Contrato de la API (borrador 0.1)

El contrato completo está en [`openapi.yaml`](openapi.yaml). Se puede abrir en https://editor.swagger.io para verlo con formato.

Este README explica lo que OpenAPI no cubre bien: el WebSocket, el formato del SMS de contingencia y cómo se conecta la plataforma.

## 1. Convenciones 
- Todas las rutas van con el prefijo `/api/v1`. El JSON va en camelCase y las fechas en ISO 8601 UTC.
- Los ids de alertas, reportes, trayectos y contactos son **UUID generados en el teléfono**. Un reenvío con el mismo id responde `200` con lo que ya existía. Nunca duplica.
- Los enumerados se mandan igual que en la base de datos (`EN_ATENCION`, `PUSH`…).
- Los errores tienen siempre la forma `{ "codigo": "VALIDACION", "mensaje": "…", "detalle": {…} }`. Los códigos están en el esquema `Error`.
- La paginación es por cursor: `?limite=50&cursor=…`, y la respuesta trae `siguienteCursor` (o `null`).
- La autenticación es `Authorization: Bearer <JWT>`. El token trae `sub` (usuario), `rol`, `disp` (dispositivo, solo en la app) y `sid` (sesión). La plataforma valida el token y el rol.

## 2. Rutas del contrato
Cada ruta del YAML trae `x-proceso`, que indica el proceso al que pertenece según la división de trabajo.

| Rutas | Proceso |
|---|---|
| `/salud`, `/sync/lote` | P14 |
| `GET /alertas`, `GET /alertas/{id}`, `acuse`, `estado`, `escalar` | P5 |
| `/enlace/{token}`, `/dispositivos/{id}/push`, `/pasarela/sms/*` | P5 y P16 |
| `/historial`, `/usuarios/{id}/historial`, `/alertas/{id}/timeline`, `/bitacora*`, `/estadisticas*` | P13 |
| `POST /alertas`, `POST /alertas/{id}/ubicaciones` | P4 y P6. Vienen como propuesta, para que la consola y la app tengan una forma de referencia. |

**Dispersión.** La plataforma ofrece esta interfaz de Kotlin, que se llama después de guardar una alerta nueva:

```kotlin
interface Dispersion {
    /** Canaliza, asigna, avisa a la red de apoyo y crea el enlace. No bloquea la respuesta. */
    fun despachar(alertaId: UUID)

    /** Después de marcar la alerta CANCELADA: avisa a la unidad y a la red de apoyo, libera unidades y apaga el enlace. */
    fun cancelada(alertaId: UUID)
}
```

La plataforma usa la misma llamada cuando la alerta entra por SMS o por el lote de sincronización. Así la dispersión vive en un solo lugar.

**Sincronización.** `/sync/lote` recibe la cola del teléfono y la ordena por prioridad: `ALERTA`, `ACUSE`, `UBICACION`, `CANCELACION`, `REPORTE`, `TRAYECTO` y `CHECKIN`. Cada tipo se entrega al manejador que se registre en la plataforma para ese tipo.

## 3. WebSocket
Los mensajes son JSON con la forma `{ "tipo": "...", "ts": "<fecha UTC>", "datos": {...} }`. El navegador no puede mandar encabezados en un WebSocket, así que el token va en la URL.

### `WS /api/v1/ws/consola?token=<JWT>`
Solo para OPERADOR y ADMINISTRADOR. Un operador recibe solo lo de las alertas asignadas a su unidad.

| `tipo` | Cuándo | `datos` |
|---|---|---|
| `alerta.nueva` | Se asignó una alerta a la unidad. Al Administrador, cada alerta que se registra. | `AlertaResumen` |
| `alerta.estado` | Cambió el estado | `{ alertaId, estadoAnterior, estadoNuevo }` |
| `alerta.escalada` | Escaló por falta de acuse o a mano | `{ alertaId, escalamiento: Escalamiento }` |
| `alerta.ubicacion` | Llegó una lectura nueva | `{ alertaId, ubicacion: Ubicacion }` |
| `alerta.sin_acuse` | Se agotó la cadena de escalamiento | `{ alertaId }` |
| `alerta.entrega_fallida` | Solo al Administrador: un aviso de la alerta se descartó después de los reintentos | `{ alertaId, notificacionId, tipo, canal, error }` |
| `ping` | Cada 25 s | `{}`. El cliente contesta `{ "tipo": "pong" }`. |

### `WS /api/v1/ws/enlace/{token}`
Público, de solo lectura, para la red de apoyo.

| `tipo` | `datos` |
|---|---|
| `ubicacion` | `Ubicacion` |
| `estado` | `{ estado }`, en texto legible |
| `fin` | `{ motivo: "CERRADA" \| "CANCELADA" \| "VENCIDA" }`. Después el servidor cierra la conexión. |

El servidor saca estos mensajes de la base de datos cada segundo, así que funciona igual con uno o con varios servidores y recibe también lo que guardan otros módulos (por ejemplo las ubicaciones).

Si el token no sirve, el servidor cierra la conexión con el código `1008` y el motivo: `NO_AUTENTICADO`, `SIN_PERMISO`, `ENLACE_NO_DISPONIBLE` o `TOKEN_VENCIDO`. Si el cliente no contesta dos pings seguidos, cierra con `1001`.

## 4. SMS de contingencia `SS1` (46 caracteres)
Lo arma la app cuando no hay internet ni malla, y el servidor lo decodifica en el webhook. Después de `SS1|` todos los campos son de ancho fijo y sin separadores:

| Posición | Largo | Campo | Formato |
|---|---|---|---|
| 0–3 | 4 | Versión | `SS1\|` |
| 4–11 | 8 | Id corto | Primeros 8 caracteres hex del UUID de la alerta |
| 12–13 | 2 | Tipo | `tipo_incidente_id` en base 36 |
| 14–21 | 8 | Latitud | Signo y 7 dígitos, en grados × 100 000 |
| 22–30 | 9 | Longitud | Signo y 8 dígitos, en grados × 100 000 |
| 31–33 | 3 | Precisión | Metros, de 000 a 999 |
| 34–39 | 6 | Hora | Segundos Unix en base 36, en mayúsculas |
| 40–45 | 6 | Verificación | Primeros 6 caracteres hex de SHA-256 sobre los 40 caracteres anteriores |

Ejemplo: `SS1|3f9a2c7101+1950431-09914632012TNJXWPe08c9d` es tipo 1, 19.50431, -99.14632, ±12 m.

El servidor acepta el mensaje solo si la verificación coincide y el número de origen es el `telefono_movil` de una cuenta activa. Todo mensaje se guarda en `mensaje_sms`, sea válido o no.

## 5. Decisiones del contrato
Se sigue lo que dicen los documentos. Donde los documentos no dan el detalle, se tomó la opción más simple.
1. **Registro y dispersión.** Según el Diseño de Datos, P4 escribe `alerta` y P5 la despacha.
2. **El SMS lleva el "id corto"** (X-01), con los primeros 8 caracteres del UUID. Si después llega la misma alerta por internet o sincronización, el servidor la reconoce porque coinciden la persona, los 8 caracteres y la hora (±60 s). Responde con el id que ya tiene y no la duplica.
3. **Verificación del SMS.** Se usa una suma de comprobación y se exige que el número de origen sea el de la cuenta, sin cambiar la base de datos. No es una firma criptográfica; se documenta como límite conocido.
4. **El JWT dura 15 min** como propuesta.
5. **Proveedor de SMS: sigue abierto**, porque ningún documento lo fija. Solo cambia el encabezado de la firma y el formato del webhook.
