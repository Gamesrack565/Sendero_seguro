# Sendero Seguro — Integración del Módulo 1: Identidad, Contactos, Configuración y Cancelación

## 1. Descripción general

El Módulo 1 de **Sendero Seguro** implementa todo lo relacionado con la identidad de la persona y con las reglas personales de seguridad: el aviso de privacidad, el registro, el inicio de sesión por perfil, la red de apoyo (contactos de confianza y círculo escolar), la configuración de activación rápida y energía, y la cancelación segura de una alerta con PIN o huella.

Corresponde a los procesos **P1, P2, P3 y P8** del documento de Diseño (E3). Está desarrollado en Kotlin para Android y utiliza Room, Android Keystore, Retrofit, kotlinx.serialization, BiometricPrompt y Kotlin Coroutines.

### Funcionalidades implementadas

| Funcionalidad | Proceso | Clase responsable |
|---|---|---|
| Punto de entrada del módulo | — | `ModuloIdentidad` |
| Aceptación del aviso de privacidad | P1 | `ProcesoAutenticacion` |
| Registro de la cuenta politécnica y PIN | P1 | `ProcesoAutenticacion` |
| Inicio de sesión por perfil y bloqueo tras 5 intentos | P1 | `ProcesoAutenticacion` |
| Sesión guardada en el teléfono (funciona sin internet) | P1 | `ProcesoAutenticacion` |
| Perfil, cambio de PIN y solicitud de baja | P1 | `ProcesoAutenticacion` |
| Contactos de confianza por prioridad | P2 | `ProcesoRedApoyo` |
| Círculo escolar con código de invitación | P2 | `ProcesoRedApoyo` |
| Aviso de vinculación a la red de apoyo | P2 | `ProcesoRedApoyo` |
| Atajo de botón de encendido ×5 y widget | P3 | `ProcesoActivacionEnergia` |
| Cuenta regresiva previa al envío (3, 5 o 10 s) | P3 | `ProcesoActivacionEnergia` |
| Escaneo de la malla según la batería | P3 | `ProcesoActivacionEnergia` |
| Cancelación con PIN o huella y registro del motivo | P8 | `ProcesoCancelacion` |
| Registro de intentos fallidos (posible coacción) | P8 | `ProcesoCancelacion` |
| Validaciones de formularios | — | `Validaciones` |
| Hash del PIN de cancelación | — | `HashPin` |
| Token y PIN cifrados | — | `AlmacenSeguroKeystore` |
| Base de datos local compartida | — | `SenderoDatabase` |
| ViewModels para las pantallas | — | `ViewModelsIdentidad.kt` |

---

## 2. Arquitectura del módulo

La clase principal es `ModuloIdentidad`. Crea las dependencias (base de datos, almacén seguro, cliente HTTP) y expone los cuatro procesos.

La interfaz no habla directamente con Room, Retrofit ni el Keystore. Usa los **ViewModels** o, desde otros módulos, los **procesos**.

```text
                    FRONTEND ANDROID (Compose)
                              |
                    ViewModels de identidad
     AutenticacionViewModel · RedApoyoViewModel
     ActivacionViewModel    · CancelacionViewModel
                              |
                       ModuloIdentidad
                              |
       +-------------+--------+--------+---------------+
       |             |                 |               |
       v             v                 v               v
 ProcesoAutenticacion ProcesoRedApoyo ProcesoActivacion ProcesoCancelacion
      (P1)              (P2)          Energia (P3)        (P8)
       |             |                 |               |
       +-------------+--------+--------+---------------+
                              |
                    Puertos (interfaces)
                              |
     +-----------+------------+-------------+--------------+
     |           |            |             |              |
     v           v            v             v              v
  Room       Keystore     Retrofit      Batería /      BiometricPrompt
config_local  token y    API Ktor      Doze (Android)
contacto_local hash PIN  (Módulo 4)
cola_alerta
```

### Organización por capas

```text
identidad/
|
|-- ModuloIdentidad.kt          Raíz de composición del módulo
|-- dominio/                    Modelos, validaciones, HashPin, PoliticaEnergia (Kotlin puro)
|-- puertos/Puertos.kt          Interfaces que usan los procesos y claves de config_local
|-- procesos/                   P1, P2, P3 y P8
|-- datos/local/                Repositorios Room
|-- datos/seguro/               AlmacenSeguroKeystore
|-- datos/remoto/               IdentidadApi, ServicioRemotoRetrofit, ClienteHttp
|-- plataforma/                 Batería, exclusión de Doze, huella, datos del teléfono
|-- ui/ViewModelsIdentidad.kt   ViewModels para el equipo de Frontend

core/db/SenderoDatabase.kt      Base Room compartida por todos los módulos (ER-06)
SenderoApp.kt                   Application que crea ModuloIdentidad
```

### Por qué los procesos usan interfaces

Los procesos (`procesos/`) solo dependen de las interfaces de `puertos/`. Las implementaciones reales para Android están en `datos/` y `plataforma/`.

Esto permite:

- Probar toda la lógica en la JVM, sin emulador, usando dobles en memoria.
- Cambiar una implementación (por ejemplo, otra librería HTTP) sin tocar los procesos.

---

## 3. Preparación del proyecto

Todos los archivos están dentro del paquete:

```kotlin
com.escom.sendero_seguro.identidad
```

La base de datos compartida está en:

```kotlin
com.escom.sendero_seguro.core.db
```

### Dependencias

El módulo agrega lo siguiente a `gradle/libs.versions.toml`:

```toml
[versions]
ksp = "2.3.4"
room = "2.8.4"
retrofit = "2.11.0"
okhttp = "4.12.0"
kotlinxSerialization = "1.9.0"
biometric = "1.1.0"
fragmentKtx = "1.8.5"
lifecycleRuntimeKtx = "2.9.4"
```

En `build.gradle.kts` de la raíz:

```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
```

En `app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    defaultConfig {
        minSdk = 26   // Android 8.0 (RNF14)
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/api/v1/\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
}
```

**Importante:** `minSdk` pasó de 24 a 26 porque el diseño exige Android 8.0 o superior y porque `HashPin` usa `java.util.Base64` y la capa remota usa `java.time`, que existen desde API 26.

### URL del backend

`API_BASE_URL` apunta al backend Ktor del Módulo 4. El valor `10.0.2.2` es la computadora anfitriona vista desde el emulador.

Para probar en un teléfono físico se cambia por la IP de la computadora en la red local, por ejemplo:

```kotlin
buildConfigField("String", "API_BASE_URL", "\"http://192.168.1.50:8080/api/v1/\"")
```

En ese caso también hay que agregar esa IP a `res/xml/network_security_config.xml`.

---

## 4. Configuración del Manifest

### Permisos

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

| Permiso | Para qué se usa |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Comunicación con el backend |
| `USE_BIOMETRIC` | Cancelar una alerta con huella (A-05 y A-08) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Pedir que Android no duerma la app (A-12) |

Ninguno de estos permisos se solicita en tiempo de ejecución: Android los concede al instalar.

### Etiqueta `application`

```xml
<application
    android:name=".SenderoApp"
    android:allowBackup="false"
    android:networkSecurityConfig="@xml/network_security_config"
    ... >
```

- `android:name=".SenderoApp"` crea `ModuloIdentidad` una sola vez para toda la app.
- `allowBackup="false"` evita que el token y el PIN cifrados se copien a otro teléfono, donde la llave del Keystore no existe.
- `network_security_config.xml` solo permite HTTP sin cifrar hacia `10.0.2.2` y `localhost` (desarrollo). En producción todo debe ir por HTTPS (RNF9).

### MainActivity

`MainActivity` ahora hereda de `FragmentActivity` en lugar de `ComponentActivity`:

```kotlin
class MainActivity : FragmentActivity() { ... }
```

Es necesario porque `BiometricPrompt` solo funciona con una `FragmentActivity`. `FragmentActivity` hereda de `ComponentActivity`, así que `setContent { }` y Jetpack Compose siguen funcionando igual.

---

## 5. Inicialización del módulo

`ModuloIdentidad` se crea una sola vez en `SenderoApp`:

```kotlin
class SenderoApp : Application() {
    val identidad: ModuloIdentidad by lazy { ModuloIdentidad(this) }
}
```

Desde cualquier pantalla o módulo se obtiene así:

```kotlin
val identidad = (applicationContext as SenderoApp).identidad
```

En Jetpack Compose:

```kotlin
val identidad = (LocalContext.current.applicationContext as SenderoApp).identidad
```

**No** se debe crear un `ModuloIdentidad(...)` nuevo en cada pantalla: habría varias conexiones a la base de datos y varios clientes HTTP.

### Lo que expone `ModuloIdentidad`

| Propiedad | Tipo | Uso |
|---|---|---|
| `autenticacion` | `ProcesoAutenticacion` | P1 |
| `redApoyo` | `ProcesoRedApoyo` | P2 |
| `activacion` | `ProcesoActivacionEnergia` | P3 |
| `cancelacion` | `ProcesoCancelacion` | P8 |
| `config` | `RepositorioConfigLocal` | Lectura directa de `config_local` |
| `gestorEnergia` | `GestorEnergiaAndroid` | Intent para excluir la app de Doze |
| `sincronizarPendientes()` | `suspend fun` | Subir lo que quedó pendiente sin señal |

### Obtener los ViewModels

```kotlin
val identidad = (LocalContext.current.applicationContext as SenderoApp).identidad
val fabrica = identidad.fabricaViewModels()

val authVm: AutenticacionViewModel = viewModel(factory = fabrica)
val redVm: RedApoyoViewModel = viewModel(factory = fabrica)
val activacionVm: ActivacionViewModel = viewModel(factory = fabrica)
val cancelacionVm: CancelacionViewModel = viewModel(factory = fabrica)
```

Imports:

```kotlin
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.escom.sendero_seguro.SenderoApp
import com.escom.sendero_seguro.identidad.ui.*
```

---

## 6. Resultados y errores

Todas las operaciones que pueden fallar devuelven un `Resultado<T>`:

```kotlin
sealed class Resultado<out T> {
    data class Exito<out T>(val valor: T) : Resultado<T>()
    data class Error(val error: ErrorIdentidad) : Resultado<Nothing>()
}
```

`ErrorIdentidad` indica qué ocurrió y trae un `mensaje` listo para mostrar:

| Error | Cuándo ocurre |
|---|---|
| `Validacion(errores)` | Algún campo no cumple las reglas; `errores` es un mapa campo → mensaje |
| `ConsentimientoRequerido` | No se aceptó el aviso de privacidad |
| `CredencialesInvalidas(intentosRestantes)` | Usuario o contraseña incorrectos |
| `CuentaBloqueada(hastaMs)` | Se alcanzaron los 5 intentos fallidos |
| `CuentaSuspendida` | El Administrador suspendió la cuenta |
| `PerfilNoPermitido(rol)` | El perfil no puede usar la app (Administrador) |
| `SinPermiso` | La acción es solo para el Coordinador |
| `SinSesion` | No hay sesión o el token venció |
| `SinConexion` | No hay internet |
| `PinNoConfigurado` | El teléfono no tiene PIN de cancelación |
| `CodigoInvalido` | El código del círculo no existe o venció |
| `Conflicto`, `NoEncontrado`, `Servidor` | Respuestas 409, 404 y 5xx del backend |

En los ViewModels, los errores llegan dentro de `Retro`:

```kotlin
data class Retro(
    val cargando: Boolean = false,
    val errores: Map<String, String> = emptyMap(),  // errores por campo
    val mensaje: String? = null                      // mensaje general
)
```

Las claves de `errores` son constantes de `Validaciones`: `NOMBRE`, `TELEFONO`, `IDENTIFICADOR`, `INSTITUCION`, `ESCUELA`, `CARRERA`, `CONTRASENA`, `PIN`, `PIN_CONFIRMACION`, `USUARIO`, `PRIORIDAD`, `CODIGO`, `NOMBRE_GRUPO`, `CUENTA_REGRESIVA` y `MOTIVO`.

Ejemplo en Compose:

```kotlin
val estado by authVm.estado.collectAsState()

OutlinedTextField(
    value = telefono,
    onValueChange = { telefono = it },
    isError = Validaciones.TELEFONO in estado.retro.errores,
    supportingText = { estado.retro.errores[Validaciones.TELEFONO]?.let { Text(it) } }
)
```

---

## 7. P1 — Privacidad, registro y autenticación

Pantallas: **A-01** (aviso), **A-02** (registro), **A-03** (inicio de sesión) y **A-11** (perfil).

### 7.1 Aviso de privacidad (A-01)

```kotlin
authVm.cargarAviso()                         // descarga la versión vigente (v3.1)
authVm.responderAviso(
    aceptado = true,
    aceptaEntrenamientoIa = false            // casilla opcional de A-01 / A-11
)
```

- Si `aceptado = false`, no se guarda nada (flujo 2a de CU-01) y `estado.salirDeLaApp` pasa a `true` para que la interfaz cierre la app.
- La aceptación se guarda en `config_local` hasta que se complete el registro y se envía junto con él.

### 7.2 Registro (A-02)

El registro tiene dos pasos. Primero se validan los datos sin llamar al servidor:

```kotlin
authVm.cargarInstituciones()
authVm.seleccionarInstitucion(institucionId)   // llena estado.escuelas

val datos = DatosRegistro(
    nombre = "Mariana López Hernández",
    telefonoMovil = "55 1234 5678",
    identificadorInstitucional = "2023630417",
    tipoComunidad = TipoComunidad.ALUMNO,
    institucionId = 1,
    escuelaId = 1,
    carreraAdscripcion = "Ing. en Inteligencia Artificial",
    contrasena = "segura2026"
)

if (authVm.validarPasoDatos(datos)) {
    // mostrar el paso 2: crear PIN de cancelación
}
```

Después se crea la cuenta con el PIN:

```kotlin
authVm.registrar(datos, pin = "1234", pinConfirmacion = "1234")
```

Reglas de validación (A-02):

| Campo | Regla |
|---|---|
| Nombre | 3–120 caracteres, solo letras y espacios |
| Teléfono | 10 dígitos (se aceptan espacios, guiones y `+52`) |
| Matrícula / n.º de empleado | Alfanumérico de 6 a 15 caracteres |
| Institución y escuela | Obligatorias |
| Carrera | 3–120 caracteres |
| Contraseña | Mínimo 8, con al menos una letra y un número |
| PIN | 4 dígitos y confirmación igual |

Al registrarse correctamente:

1. El servidor crea la cuenta con el consentimiento y el dispositivo.
2. El hash del PIN se guarda cifrado en el teléfono. **El PIN nunca se envía al servidor.**
3. El token se guarda cifrado y se escriben `usuario_id` y `perfil` en `config_local`.

### 7.3 Inicio de sesión (A-03)

```kotlin
authVm.iniciarSesion(usuario = "2023630417", contrasena = "segura2026")
```

`usuario` puede ser el teléfono (10 dígitos) o la matrícula.

| Situación | Resultado |
|---|---|
| Credenciales correctas | `estado.usuario` y `estado.sesion` se llenan |
| Credenciales incorrectas | Mensaje con los intentos restantes |
| 5 intentos fallidos | `CuentaBloqueada`; la app no vuelve a intentar hasta que pase el bloqueo |
| Perfil ADMINISTRADOR | `PerfilNoPermitido`: debe usar la consola web |
| Teléfono sin PIN | `estado.requiereCrearPin = true` |

Perfiles que pueden usar la app:

| Perfil | ¿Entra a la app? |
|---|---|
| INTEGRANTE | Sí |
| COORDINADOR | Sí (además administra su círculo) |
| OPERADOR | Sí (modo operador A-14) |
| ADMINISTRADOR | No, solo consola web (W-01) |

Si inicia sesión en un teléfono nuevo, hay que pedir el PIN:

```kotlin
if (estado.requiereCrearPin) {
    authVm.crearPin("1234", "1234")
}
```

### 7.4 Sesión sin internet

```kotlin
val sesion: SesionLocal? = identidad.autenticacion.sesionLocal()
```

```kotlin
data class SesionLocal(
    val usuarioId: String,
    val rol: UsuarioRol,
    val tokenVigente: Boolean
)
```

La sesión se considera válida aunque no haya señal o el token haya vencido. Así el botón de pánico siempre sabe quién emite la alerta. `tokenVigente = false` solo indica que hay que volver a iniciar sesión cuando regrese internet.

Si `sesionLocal()` devuelve `null`, la persona no ha iniciado sesión y se debe mostrar A-03.

### 7.5 Perfil (A-11)

```kotlin
authVm.cargarPerfil()
authVm.actualizarPerfil(telefono = "5512345678", matricula = null, carrera = null, aceptaIa = true)
authVm.cambiarPin(actual = "1234", nuevo = "5678", confirmacion = "5678")
authVm.solicitarBaja(contrasena = "segura2026")   // usuario.estado = BAJA_SOLICITADA
authVm.cerrarSesion()
```

Los parámetros en `null` no se modifican. Cambiar el PIN es una operación local: verifica el PIN actual contra el hash guardado.

Al cerrar sesión o solicitar la baja se borran del teléfono el token, el hash del PIN, los contactos locales y los datos de sesión de `config_local`.

---

## 8. P2 — Red de apoyo

Pantallas: **A-09** (contactos de confianza) y **A-10** (círculo escolar).

### 8.1 Contactos de confianza (A-09)

La lista se observa directamente desde Room, por lo que se ve aunque no haya conexión:

```kotlin
val contactos by redVm.contactos.collectAsState()
```

```kotlin
data class Contacto(
    val id: String,                     // UUID generado en el teléfono
    val nombre: String,
    val telefono: String,
    val prioridad: Int,                 // 1 = se avisa primero
    val canalPreferido: NotificacionCanal,
    val tieneApp: Boolean,              // true → PUSH, false → SOLO SMS
    val pendienteSincronizar: Boolean   // aún no llega al servidor
)
```

Operaciones:

```kotlin
redVm.agregarContacto("Laura Hernández (mamá)", "55 8812 4410", prioridad = null) // al final
redVm.agregarContacto("Sofía Pérez", "5567451283", prioridad = 1)                 // primero
redVm.editarContacto(id, "Laura H.", "5588124410")
redVm.eliminarContacto(id)
redVm.reordenar(listOf(idSofia, idLaura, idDiego))   // al arrastrar en la lista
redVm.sincronizar()
```

Reglas:

| Regla | Detalle |
|---|---|
| Nombre | 2–80 caracteres |
| Teléfono | 10 dígitos, sin repetir en la lista |
| Prioridad | Siempre 1..n sin huecos ni repetidos; al insertar o borrar se renumera |
| Reordenar | Debe incluir todos los contactos |
| Canal | Lo decide el servidor: si el número tiene cuenta, `PUSH`; si no, `SMS` |

#### Funcionamiento sin conexión

Los contactos se guardan **primero en el teléfono** y después se envían al servidor:

1. Se genera un UUID en el teléfono y se guarda en `contacto_local`.
2. Se intenta enviar con `PUT /contactos/{id}`.
3. Si no hay señal, el contacto queda con `pendienteSincronizar = true` y su id se anota en `config_local` (`contactos_pendientes`).
4. Al recuperar señal, `sincronizarPendientes()` lo vuelve a enviar con el **mismo UUID**, así que el servidor no lo duplica.

Esto es importante porque el Módulo 2 usa `contacto_local` para mandar el SMS a los contactos cuando no hay internet.

### 8.2 Círculo escolar (A-10)

```kotlin
redVm.cargarCirculo()            // llena estado.circulo y estado.miembros
val estado by redVm.estado.collectAsState()
```

**Pestaña «Unirme con código» (cualquier perfil):**

```kotlin
redVm.unirse("K7Q-42M")   // también acepta "k7q42m"
redVm.retirarme()         // miembro_circulo.estado = RETIRADA
```

**Pestaña «Coordinar» (solo COORDINADOR):**

```kotlin
redVm.crearCirculo("Grupo 6IV1")
redVm.regenerarCodigo()          // el código anterior deja de ser válido
redVm.darDeBaja(usuarioId)       // miembro_circulo.estado = DADA_DE_BAJA
```

| Regla | Detalle |
|---|---|
| Código | Formato `AAA-999` (letras o dígitos), vigente |
| Nombre del grupo | 2–40 caracteres |
| Crear, regenerar, dar de baja | Solo COORDINADOR; otro perfil recibe `SinPermiso` |
| Darse de baja a sí mismo | No permitido (`Conflicto`) |

El círculo requiere conexión: el código lo genera y valida el servidor.

### 8.3 Aviso de vinculación (X-01)

No es necesario llamarlo desde la interfaz. Se ejecuta solo:

- Al agregar un contacto → aviso `CONTACTO_AGREGADO` (le llega un SMS o push indicando que fue agregado).
- Al unirse a un círculo → aviso `NUEVO_INTEGRANTE` al Coordinador.

Si no hay señal, el alta no se bloquea.

---

## 9. P3 — Activación rápida, energía y red malla

Pantalla: **A-12**. Toda la configuración vive en `config_local`, por lo que funciona sin internet.

### 9.1 Configuración

```kotlin
val config by activacionVm.config.collectAsState()
```

```kotlin
data class ConfigActivacion(
    val atajoActivo: Boolean = true,        // botón de encendido ×5
    val widgetActivo: Boolean = true,       // widget rojo de un toque
    val cuentaRegresivaSeg: Int = 5,        // 3, 5 o 10
    val retransmitirMalla: Boolean = true,  // reenviar alertas de otros
    val ahorroBateria: Boolean = true,      // escaneo según batería
    val mapasSoloWifi: Boolean = false
)
```

Los valores entre paréntesis son los predeterminados cuando la persona aún no ha cambiado nada.

```kotlin
activacionVm.atajoBotonEncendido(true)
activacionVm.widget(true)
activacionVm.cuentaRegresiva(5)          // solo 3, 5 o 10
activacionVm.retransmitirMalla(true)
activacionVm.escaneoSegunBateria(true)
activacionVm.mapasSoloWifi(false)
```

Si se pasa un valor no permitido a `cuentaRegresiva`, `activacionVm.error` muestra el mensaje.

**Importante:** el Módulo 1 solo **guarda** estas preferencias. Detectar las 5 presiones del botón de encendido, mostrar el widget y ejecutar la cuenta regresiva le corresponde al Módulo 2, que debe leer estos valores (ver sección 11).

### 9.2 Escaneo de la malla según la batería

```kotlin
val politica by activacionVm.politica.collectAsState()
// politica?.descripcion → "Batería 64 %: escaneo cada 2 min"
```

| Condición | Intervalo de escaneo |
|---|---|
| Batería ≥ 20 % | Cada 2 min |
| Batería < 20 % y «Escaneo según batería» activo | Cada 10 min |
| Cargando | Cada 2 min |
| «Escaneo según batería» desactivado | Cada 2 min |

```kotlin
data class PoliticaEscaneo(
    val intervaloMs: Long,
    val retransmitirOtros: Boolean,
    val descripcion: String
)
```

El flujo se actualiza solo cuando cambia la batería o la configuración.

### 9.3 Exclusión del ahorro de energía de Android

Si Android aplica Doze, puede dormir la app y perder alertas de la malla. A-12 debe mostrar el aviso y pedir la exclusión:

```kotlin
val identidad = (context.applicationContext as SenderoApp).identidad

if (activacionVm.requiereExclusionAhorroEnergia()) {
    context.startActivity(identidad.gestorEnergia.intentSolicitarExclusion())
}
```

Android muestra un cuadro de diálogo propio; la persona decide.

---

## 10. P8 — Cancelación segura de una alerta

Pantallas: **A-08** (cancelar alerta) y **A-05** (cancelar durante la cuenta regresiva).

### 10.1 Cómo funciona

1. La persona elige el **motivo** (obligatorio).
2. Escribe su **PIN** de 4 dígitos o usa la **huella**.
3. El PIN se verifica **en el teléfono**, contra el hash cifrado en el Keystore. No necesita internet.
4. Si es correcto:
   - Se avisa al backend, que pasa la alerta a `CANCELADA`, avisa a la unidad y a la red de apoyo y revoca el enlace de ubicación.
   - Se emite el evento `AlertaCancelada` para que el Módulo 2 detenga GPS, telemetría y malla.
5. Si es incorrecto:
   - **La alerta sigue activa.**
   - Se registra el intento fallido (`cancelacion.exitoso = false`) para que el backend lo trate como **posible coacción**.
   - La pantalla muestra solo un mensaje neutro: *"No fue posible cancelar en este momento. La alerta sigue activa."* No dice que el PIN estuvo mal, para no alertar a un posible agresor (flujo 3a de CU-21).

Motivos disponibles (`CancelacionMotivo`):

| Valor | Texto en pantalla |
|---|---|
| `YA_ESTOY_A_SALVO` | Ya estoy a salvo |
| `FALSA_ALARMA` | Falsa alarma |
| `ACTIVACION_ACCIDENTAL` | La activé por accidente |

### 10.2 Cancelar con PIN (A-08)

```kotlin
cancelacionVm.motivos.forEach { /* mostrar it.etiqueta como opción */ }

cancelacionVm.elegirMotivo(CancelacionMotivo.YA_ESTOY_A_SALVO)
cancelacionVm.confirmarConPin(alertaId = "…", pin = "1234")

val estado by cancelacionVm.estado.collectAsState()
when (val r = estado.resultado) {
    is ResultadoCancelacion.Cancelada   -> { /* volver a A-04 */ }
    is ResultadoCancelacion.NoCancelada -> { /* mostrar r.mensajeNeutro */ }
    null -> {}
}
```

### 10.3 Cancelar con huella

```kotlin
val actividad = LocalContext.current as FragmentActivity
val scope = rememberCoroutineScope()

if (AutenticadorBiometrico.disponible(actividad)) {
    scope.launch {
        val ok = AutenticadorBiometrico.autenticar(actividad)
        cancelacionVm.confirmarConBiometria(alertaId, autenticada = ok)
    }
}
```

`autenticar()` devuelve `false` si la persona cancela el diálogo o falla la huella; en ese caso también se registra como intento fallido.

### 10.4 Cancelar durante la cuenta regresiva (A-05)

Si la alerta **aún no salió del teléfono**, se pasa `alertaYaEnviada = false`:

```kotlin
cancelacionVm.confirmarConPin(alertaId, pin, alertaYaEnviada = false)
```

En ese caso no se avisa al servidor (la alerta nunca llegó), pero sí se emite el evento `AlertaCancelada` para detener la cuenta regresiva.

### 10.5 Cancelación sin conexión

| Caso | Qué pasa |
|---|---|
| Cancelación correcta sin internet | Se guarda en `cola_alerta` con `tipo_elemento = CANCELACION`; la envía el despachador multicanal del Módulo 2/3 |
| La alerta seguía en la cola y nunca salió por ningún canal | Se borra de la cola; no hay nada que avisar |
| Intento fallido sin internet | Se guarda en `config_local` y se sube con `sincronizarPendientes()` |

---

## 11. Integración con los demás módulos

### 11.1 Módulo 2 — Emergencias

**Identificador del usuario.** `ModuloEmergencias` recibe un `usuarioIdActual` de ejemplo (`"USR_LOCAL_01"`). Debe sustituirse por el de la sesión:

```kotlin
val identidad = (applicationContext as SenderoApp).identidad

lifecycleScope.launch {
    val sesion = identidad.autenticacion.sesionLocal() ?: return@launch // sin sesión → A-03
    moduloEmergencias = ModuloEmergencias(
        context = applicationContext,
        usuarioIdActual = sesion.usuarioId
    )
}
```

**Detener la alerta cuando se cancela.** El Módulo 2 debe escuchar los eventos de P8 en lugar de exponer su propia cancelación sin PIN:

```kotlin
lifecycleScope.launch {
    identidad.cancelacion.eventos.collect { evento ->
        when (evento) {
            is EventoCancelacion.AlertaCancelada ->
                moduloEmergencias.desactivarAlertaEmergencia()
            is EventoCancelacion.IntentoFallido -> {
                // La alerta sigue activa. No se detiene nada.
            }
        }
    }
}
```

El PIN **no** se verifica en el Módulo 2. `desactivarAlertaEmergencia()` solo debe llamarse después de un evento `AlertaCancelada`.

**Configuración de activación.**

```kotlin
lifecycleScope.launch {
    identidad.activacion.observarConfig().collect { c ->
        // c.atajoActivo        → activar o no la detección del botón de encendido ×5
        // c.widgetActivo       → habilitar o no el widget
        // c.cuentaRegresivaSeg → duración de la cuenta regresiva antes de enviar
        // c.retransmitirMalla  → si GestorMalla reenvía paquetes de otros
    }
}

lifecycleScope.launch {
    identidad.activacion.ajustarEscaneoMallaPorBateria().collect { p ->
        // p.intervaloMs → intervalo de escaneo BLE para GestorMalla
    }
}
```

**Contactos para el SMS sin internet.** Están en `contacto_local` y se leen con:

```kotlin
identidad.redApoyo.observarContactos().first()
```

**Diferencias con el README del Módulo 2.**

| Tema del Módulo 2 | Situación en el Módulo 1 |
|---|---|
| `actualizarParametrosSeguridad(palabrasVoz, sensibilidadCaidaG, intervaloCheckIn)` | El diseño E3 (A-12) no incluye palabras de voz, sensibilidad de caída ni check-in, por lo que el Módulo 1 no los guarda. Si el equipo decide agregarlos, van como claves nuevas en `config_local` dentro de P3 |
| `desactivarAlertaEmergencia(simulacionCoaccion = true)` | El E3 define la coacción como **PIN incorrecto registrado**, no como un segundo PIN. El Módulo 1 ya lo cubre con `IntentoFallido`; no hace falta simulación en el Módulo 2 |

### 11.2 Módulo 3 — Offline y caché

- Su Worker de sincronización (P11) debe llamar a `identidad.sincronizarPendientes()` al recuperar internet. Sube los contactos pendientes y los intentos de cancelación fallidos.
- La fila `CANCELACION` en `cola_alerta` debe enviarse con la misma prioridad que una alerta. El paquete está en JSON; el despachador debe cifrarlo y firmarlo antes de mandarlo por malla o SMS (RNF9).
- `mapasSoloWifi` de `ConfigActivacion` indica si se pueden descargar mapas con datos móviles.

### 11.3 Módulo 4 — Nube

El contrato completo de endpoints está en `docs/modulo1_contrato_api.md`: rutas, cuerpos JSON, códigos de error y reglas que debe cumplir el servidor (conteo de intentos, idempotencia de contactos y cancelaciones, etc.).

Resumen:

| Proceso | Endpoints |
|---|---|
| P1 | `GET avisos-privacidad/vigente`, `GET instituciones`, `GET instituciones/{id}/escuelas`, `POST auth/registro`, `POST auth/login`, `POST auth/logout`, `GET/PATCH usuarios/me`, `POST usuarios/me/baja` |
| P2 | `GET contactos`, `PUT contactos/{id}`, `DELETE contactos/{id}`, `PUT contactos/orden`, `GET circulos/mio`, `POST circulos`, `POST circulos/unirse`, `POST circulos/{id}/codigo`, `GET circulos/{id}/miembros`, `DELETE circulos/{id}/miembros/{usuarioId}`, `POST circulos/{id}/retirarme`, `POST red-apoyo/avisos` |
| P8 | `POST alertas/{id}/cancelaciones` |

---

## 12. Base de datos local y seguridad

### 12.1 `SenderoDatabase` (Room)

Base compartida por todos los módulos, según el diagrama ER-06:

| Tabla | Qué guarda | Quién la usa |
|---|---|---|
| `config_local` | Sesión, configuración de P3 y listas de pendientes | Módulos 1, 2 y 3 |
| `contacto_local` | Contactos para el aviso sin internet | Módulos 1 y 2 |
| `cola_alerta` | Alertas y cancelaciones por enviar | Módulo 1 (solo cancelaciones), Módulos 2 y 3 |

**Para agregar tablas de otro módulo:** añadir la entidad a `@Database(entities = [...])`, su DAO, y subir `version` con una migración. No crear una segunda base de datos.

### 12.2 Claves de `config_local` del Módulo 1

| Clave | Contenido |
|---|---|
| `usuario_id`, `perfil` | Sesión |
| `dispositivo_id` | UUID del teléfono, generado una vez |
| `cuenta_regresiva_seg`, `atajo_activo`, `widget_activo`, `retransmitir_malla`, `ahorro_bateria`, `mapas_solo_wifi` | P3 |
| `consentimiento_aviso_id`, `consentimiento_aviso_version`, `consentimiento_aceptado_en`, `acepta_entrenamiento_ia` | Aceptación del aviso antes del registro |
| `bloqueada_hasta` | Fin del bloqueo temporal |
| `circulo_id` | Círculo actual |
| `contactos_pendientes`, `contactos_eliminados_pendientes` | Cambios por subir |
| `cancelaciones_fallidas_pendientes` | Intentos fallidos por subir |

Las constantes están en `ClavesConfig` (`puertos/Puertos.kt`).

### 12.3 Token y PIN

| Dato | Dónde se guarda | Cómo |
|---|---|---|
| Token de sesión | `SharedPreferences` (`sendero_identidad_seguro`) | Cifrado con AES-256-GCM; la llave está en el Android Keystore y no se puede extraer |
| Hash del PIN | Mismo archivo | PBKDF2-HMAC-SHA256 con sal aleatoria, y además cifrado con la llave del Keystore |
| Contraseña | En ningún lado del teléfono | Solo viaja al servidor por HTTPS |
| PIN | En ningún lado en texto | Nunca sale del teléfono |

El cálculo del hash del PIN se ejecuta en `Dispatchers.Default` para no congelar la interfaz.

---

## 13. Pruebas

Las pruebas están en:

```text
app/src/test/java/com/escom/sendero_seguro/identidad/
|
|-- DominioTest.kt     Validaciones, HashPin y política de batería
|-- ProcesosTest.kt    P1, P2, P3 y P8 con dobles en memoria
|-- Fakes.kt           Backend falso, Room en memoria, almacén falso
```

Se ejecutan sin emulador:

```bash
./gradlew :app:testDebugUnitTest
```

O desde Android Studio: clic derecho sobre la carpeta `identidad` en `test` → **Run 'Tests in identidad'**.

Son 29 pruebas. Algunas de las situaciones que cubren:

- Sin aceptar el aviso no se guarda nada ni se puede registrar.
- El Administrador no puede entrar a la app.
- El bloqueo temporal impide nuevos intentos hasta que vence.
- La sesión funciona sin internet y con el token vencido.
- Un contacto creado sin señal se sincroniza después con el mismo UUID.
- Solo el Coordinador crea el círculo y da de baja integrantes.
- Un PIN incorrecto mantiene la alerta activa y registra el intento.
- Sin señal, la cancelación va a la cola y los intentos fallidos se suben al reconectar.

---

## 14. Consideraciones importantes

### Backend

El módulo depende del backend del Módulo 4 para registrarse, iniciar sesión y gestionar el círculo. Mientras no exista, esas operaciones devolverán `SinConexion`. Las pruebas unitarias no lo necesitan.

### Primer inicio de sesión

El PIN se guarda solo en el teléfono. Si la persona inicia sesión en un teléfono nuevo, debe crear su PIN otra vez (`requiereCrearPin`). Si no lo hace, P8 devuelve `PinNoConfigurado` y la alerta no se podrá cancelar con PIN.

### Bloqueo de intentos

El servidor es quien cuenta los intentos fallidos (`usuario.intentos_fallidos`). El teléfono solo recuerda `bloqueada_hasta` para no hacer peticiones inútiles mientras dura el bloqueo.

### Llave de `cola_alerta`

`cola_alerta` usa `alerta_id` como llave primaria, así que solo cabe una fila por alerta. Por eso los intentos fallidos sin señal se guardan en `config_local` y no en la cola. Se sugiere al Módulo 3 cambiar la llave a `(alerta_id, tipo_elemento)`.

### Cifrado de la base local

Room no está cifrado. El token y el PIN no están en Room, pero el diseño menciona "SQLite / Room cifrado". Si el equipo lo requiere, se puede agregar SQLCipher en `SenderoDatabase` sin cambiar los procesos.

### HTTP en desarrollo

`network_security_config.xml` permite HTTP solo hacia `10.0.2.2` y `localhost`. Antes de entregar, el backend debe usar HTTPS y esa excepción debe eliminarse.

### Archivos compartidos modificados

El Módulo 1 cambió archivos que también tocan otros equipos. Hay que revisarlos al unir ramas:

- `gradle/libs.versions.toml`
- `build.gradle.kts` y `app/build.gradle.kts`
- `AndroidManifest.xml`
- `MainActivity.kt`

---

## 15. Flujo general de funcionamiento

```text
INICIO DE LA APLICACIÓN
          |
          v
   ¿Hay sesión local?
          |
     +----+----+
     |         |
    NO        SÍ
     |         |
     v         |
  ¿Tiene       |
  cuenta?      |
     |         |
  +--+--+      |
  |     |      |
 NO    SÍ      |
  |     |      |
  v     v      |
A-01  A-03     |
Aviso Inicio   |
  |   sesión   |
  v     |      |
A-02    |      |
Registro|      |
+ PIN   |      |
  |     |      |
  +--+--+      |
     |         |
     v         |
¿Requiere PIN? |
     |         |
  SÍ → crear   |
     |         |
     +----+----+
          |
          v
    A-04 Inicio (botón de pánico)
          |
   +------+-------+---------------+
   |              |               |
   v              v               v
A-09 / A-10     A-12          ALERTA ACTIVA
Red de apoyo  Activación      (Módulo 2)
   |          y energía           |
   v              |               v
contacto_local    v         ¿Cancelar? (A-08)
y servidor   config_local         |
                  |               v
                  v         Motivo + PIN/huella
           Módulo 2 lee           |
           la configuración  ¿PIN correcto?
                                  |
                            +-----+-----+
                            |           |
                           NO          SÍ
                            |           |
                            v           v
                   Alerta sigue    Avisar al servidor
                   activa +        (o encolar sin señal)
                   intento               |
                   registrado            v
                   (coacción)     Evento AlertaCancelada
                                         |
                                         v
                                  Módulo 2 detiene
                                  GPS, malla y telemetría
```

---

## 16. Lista de comprobación de integración

Antes de considerar conectado el Módulo 1:

- [ ] Los archivos están en `com.escom.sendero_seguro.identidad` y `core.db`.
- [ ] Se agregaron las dependencias y plugins (Room, KSP, Retrofit, serialización, biometric).
- [ ] `minSdk` es 26.
- [ ] El Manifest declara `SenderoApp`, los permisos y `network_security_config`.
- [ ] `MainActivity` hereda de `FragmentActivity`.
- [ ] `API_BASE_URL` apunta al backend correcto.
- [ ] Las 29 pruebas unitarias pasan (`./gradlew :app:testDebugUnitTest`).
- [ ] La pantalla de inicio decide entre A-01/A-03 y A-04 con `sesionLocal()`.
- [ ] A-01, A-02 y A-03 están conectadas a `AutenticacionViewModel`.
- [ ] Se pide crear el PIN cuando `requiereCrearPin = true`.
- [ ] A-09 y A-10 están conectadas a `RedApoyoViewModel`.
- [ ] A-12 está conectada a `ActivacionViewModel` y pide la exclusión de Doze.
- [ ] A-08 está conectada a `CancelacionViewModel` (PIN y huella).
- [ ] El Módulo 2 usa `sesionLocal().usuarioId` en lugar de `"USR_LOCAL_01"`.
- [ ] El Módulo 2 escucha `cancelacion.eventos` para detener la alerta.
- [ ] El Módulo 2 lee `observarConfig()` y `ajustarEscaneoMallaPorBateria()`.
- [ ] El Worker del Módulo 3 llama a `sincronizarPendientes()`.
- [ ] Se probó cancelar con PIN correcto, PIN incorrecto y sin internet.
- [ ] Se probó agregar contactos sin internet y sincronizarlos después.

---

## 17. Conclusión

El Módulo 1 de Sendero Seguro concentra la identidad de la persona y sus reglas de seguridad en `ModuloIdentidad`, organizado en cuatro procesos independientes (P1, P2, P3 y P8).

La interfaz solo necesita los ViewModels, y los demás módulos solo necesitan tres cosas: la sesión local para saber quién emite la alerta, la configuración de activación y los eventos de cancelación.

Las partes críticas funcionan sin internet: la sesión, el PIN, los contactos y la configuración viven en el teléfono, y lo que no pudo enviarse se sincroniza al recuperar la conexión.

**Nota:** antes de usarlo como sistema real, falta conectar el backend del Módulo 4, cambiar a HTTPS, decidir si se cifra la base Room y probar el flujo completo de cancelación con el Módulo 2 en teléfonos físicos.
