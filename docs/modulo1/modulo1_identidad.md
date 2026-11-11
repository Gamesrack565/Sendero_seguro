# Módulo 1 · Identidad, Contactos, Configuración y Control de Coacción

Paquete `com.escom.sendero_seguro.identidad`. Procesos del E3: **P1, P2, P3 y P8**.

```
identidad/
├── ModuloIdentidad.kt          raíz de composición (acceso: (application as SenderoApp).identidad)
├── dominio/                    modelos, validaciones, HashPin, PoliticaEnergia (Kotlin puro)
├── puertos/Puertos.kt          interfaces que usan los procesos + claves de config_local
├── procesos/                   P1 ProcesoAutenticacion · P2 ProcesoRedApoyo
│                               P3 ProcesoActivacionEnergia · P8 ProcesoCancelacion
├── datos/local/                Room (config_local, contacto_local, cola_alerta)
├── datos/seguro/               token y hash del PIN cifrados con Android Keystore
├── datos/remoto/               Retrofit + kotlinx.serialization (contrato en modulo1_contrato_api.md)
├── plataforma/                 batería, exclusión de Doze, BiometricPrompt, datos del teléfono
└── ui/ViewModelsIdentidad.kt   ViewModels para el equipo de Frontend
core/db/SenderoDatabase.kt      base Room compartida (ER-06)
```

## Funciones del E3 → código

| Proceso | Función del diseño | Dónde | Pantallas |
|---|---|---|---|
| P1 | `registrarConsentimientoPrivacidad()` | `ProcesoAutenticacion` | A-01, A-11 |
| P1 | `registrarCuentaPolitecnica()` | `ProcesoAutenticacion` | A-02 |
| P1 | `autenticarPorRolYControlarIntentos()` | `ProcesoAutenticacion` | A-03 |
| P2 | `gestionarContactosPorPrioridad()` | `ProcesoRedApoyo` | A-09 |
| P2 | `gestionarCirculoEscolar()` | `ProcesoRedApoyo` | A-10 |
| P2 | `notificarVinculacionRedApoyo()` | `ProcesoRedApoyo` | A-09, A-10, X-01 |
| P3 | `configurarAtajosSinDesbloquear()` | `ProcesoActivacionEnergia` | A-12, A-04 |
| P3 | `definirCuentaRegresivaPreEnvio()` | `ProcesoActivacionEnergia` | A-12, A-05 |
| P3 | `ajustarEscaneoMallaPorBateria()` | `ProcesoActivacionEnergia` | A-12, A-19 |
| P8 | `capturarMotivoCancelacion()` | `ProcesoCancelacion` | A-08 |
| P8 | `verificarPinCancelacion()` | `ProcesoCancelacion` | A-08, A-05 |
| P8 | `cerrarAlertaYNotificarCierre()` | `ProcesoCancelacion` | A-07, W-03, X-01 |

## Lo que otros módulos usan de aquí

- **Módulo 2 (emergencias)**: `cancelacion.eventos` (detener cuenta regresiva/GNSS al cancelar),
  `activacion.observarConfig()` (atajo ×5, widget, segundos de cuenta regresiva),
  `activacion.ajustarEscaneoMallaPorBateria()` (intervalo de escaneo), `autenticacion.sesionLocal()` (emisor sin internet),
  contactos en `contacto_local` para el SMS sin conexión.
- **Módulo 3 (offline)**: la fila `CANCELACION` en `cola_alerta`; llamar `identidad.sincronizarPendientes()` desde su Worker.
- **Módulo 4 (nube)**: implementar `docs/modulo1_contrato_api.md`.

## Pruebas

`app/src/test/.../identidad`: 29 pruebas JVM (validaciones, hash del PIN, energía y los 4 procesos con dobles en memoria).
Ejecutar con `./gradlew :app:testDebugUnitTest`.
