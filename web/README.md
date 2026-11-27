# Sendero Seguro · Consola Web (Operador de atención y Administrador)

Aplicación web SPA desarrollada en **React + Vite + TypeScript** fiel a las vistas de diseño (**W-01 a W-10**) y a las especificaciones del documento técnico *Proyecto: Sendero Seguro — Diseño* (págs. 3–59).

## Ejecución local

```bash
npm install
npm run dev
```

Abre en tu navegador: `http://localhost:5173`

Para compilación de producción y verificación de tipos:

```bash
npm run typecheck
npm run build
```

## Cuentas de prueba (contraseña: `Password123`)

| Usuario / Matrícula | Rol | Comportamiento esperado (W-01) |
|---|---|---|
| `E-48211` (Ricardo Méndez Soto) | `OPERADOR` | Entra a Consola Operador (**W-02, W-03, W-04**), Unidad `P-214 · Seguridad IPN` |
| `E-30117` (Sofía Aguilar Peña) | `ADMINISTRADOR` | Entra a Consola Administración (**W-02 a W-10** y **Bitácora**) |
| `2024630902` (Ana Fuentes Rivas) | `INTEGRANTE` | Rechazo de acceso con mensaje para usar app móvil (registrado en bitácora) |
| `2023630417` (Mariana López) | `COORDINADOR` | Rechazo de acceso con mensaje para usar app móvil (registrado en bitácora) |
| `2022630155` (Luis Ibarra Ramos) | `INTEGRANTE` | Cuenta `BLOQUEADA_TEMPORAL` tras 5 intentos fallidos |

## Vistas y salidas implementadas

- **W-01. Inicio de sesión web**: Validación de credenciales, límite de 5 intentos (`BLOQUEADA_TEMPORAL`), rechazo a perfiles `INTEGRANTE`/`COORDINADOR` y registro en bitácora.
- **W-02. Dashboard de alertas**: Cola ordenada por prioridad **IA-4** con motivos visibles, respaldo automático por prioridad de catálogo y antigüedad si IA-4 supera 200 ms, indicadores del turno, simulador de alerta Prioridad 1 (aviso global en cualquier vista del operador) y mapa dual (**Operativo estilizado v42** + **Leaflet/OpenStreetMap real**).
- **W-03. Detalle y atención de alerta**: Mapa de trayectoria GNSS cada 10 s, datos del emisor, historial inmutable `evento_alerta`, panel de atención con validación de unidad asignada (`ACUSAR RECIBO` detiene escalamiento y habilita transiciones válidas E-01, catálogo de resultados y observaciones ≤ 1000 caracteres), cadena de escalamiento con cronómetro en vivo y avisos a la red de apoyo.
- **W-04. Tablero y exportación**: Filtros por periodo (≤ 12 meses), tipo, estado y zona sin datos personales, KPIs, gráfica diaria interactiva, barra por canal de recepción y tipos más frecuentes.
  - **X-02 (Exportar CSV)**: Descarga UTF-8 con las columnas exactas sin identidad personal (`folio,activada_en,tipo,categoria,estado_final,canal_recepcion,seg_a_acuse,nivel_escalamiento,zona,corporacion,unidad`).
  - **X-03 (Exportar PDF)**: Vista previa e impresión tamaño carta del *Reporte Estadístico* con filtros aplicados, alertas por semana, tabla por tipo y pie con responsable.
- **W-05. Moderación de reportes (IA-3)**: Cola `PENDIENTE`, resaltado de datos personales detectados, puntajes IA-3 (`moderacion-v1.2`), enlace al posible duplicado (`REP-2026-000388`), acciones de publicación/rechazo conectadas a `retroalimentacion_ia` y consulta de identidad protegida (**CU-31**) con justificación obligatoria ≥ 20 caracteres registrada en bitácora.
- **W-06. Catálogo de incidentes**: Edición en borrador `v8 · BORRADOR`, validación **A-08** (impide publicar si un tipo activo de alerta no tiene regla de canalización), enlace directo a W-07 (`Asignar regla`) y publicación de versión.
- **W-07. Canalización y escalamiento**: Reglas por tipo, canal destinatario activo, tiempo máximo de acuse (`30–600 s`) y cadena ordenada sin destinos repetidos.
- **W-08. Corporaciones y unidades**: Pestañas `Corporaciones` y `Unidades`, cambio de estado operativo y alta de nuevas unidades y corporaciones.
- **W-09. Usuarios y perfiles**: Búsqueda desde 3 caracteres, asignación de perfil/unidad con motivo obligatorio (≥ 10 caracteres), cierre de sesiones activas, desbloqueo y suspensión.
- **W-10. Supervisión de modelos IA**: Tarjetas de **IA-1** a **IA-4**, ajuste de umbral de confianza (`0.50–0.95`) y mínimo de reportes por celda, activación de `prioridad-v0.9`, tabla viva de `retroalimentacion_ia` y botón de reentrenamiento con confirmación.
- **X-01. Enlace público de seguimiento** (`/seguimiento/:token`): Vista sin matrícula ni perfil que muestra «Esta alerta ya fue atendida» al cerrarse la alerta.
- **Bitácora de auditoría** (`/bitacora`): Registro de solo inserción con verificación de cadena **SHA-256** (RNF16).

## Aplicación Web Progresiva (PWA)

La consola está configurada como **PWA instalable con soporte sin conexión** mediante `vite-plugin-pwa` (Workbox):

- **Manifiesto web** (`manifest.webmanifest`) con accesos directos a *Alertas activas* (`/alertas`) y *Tablero* (`/tablero`), modo `standalone` e iconos dedicados (`192×192`, `512×512`, `maskable` y `apple-touch-icon`).
- **Service Worker + Caché offline**: precarga automática de la interfaz, caché local de fuentes y caché de teselas de OpenStreetMap (`CacheFirst`) para consultar mapas visitados sin internet.
- **Botón «Instalar app»**: integrado en la pantalla de inicio de sesión (**W-01**) y en la barra superior de la consola.
- **Indicador de modo sin conexión y actualización**: barra superior de modo offline (`navigator.onLine`), persistencia local (`localStorage`) y notificación automática cuando hay una nueva versión lista para actualizar.

Para probar la PWA compilada en modo producción (recomendado para validar el Service Worker completo en Chrome/Edge DevTools → *Application*):

```bash
npm run build
npm run preview
```

## Integración con Backend Kotlin (Ktor)

Configura `.env` tomando como base `.env.example` con `VITE_USE_MOCK=false`, `VITE_API_BASE_URL` y `VITE_WS_URL` para conectar `src/services/apiClient.ts` al servidor Ktor (REST + WebSocket).
