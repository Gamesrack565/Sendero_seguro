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


---

# Sendero Seguro — Integración del Módulo 2: Motor de Emergencias

## 1. Descripción general

El Módulo 2 de **Sendero Seguro** implementa el sistema de detección, gestión, clasificación, persistencia y transmisión de alertas de emergencia.

Su objetivo es permitir que una alerta se active mediante diferentes detonadores y que, una vez activada, se recopile información del dispositivo, se clasifique el incidente localmente, se almacene la información pendiente de transmisión y se transmita a otros nodos cuando sea posible.

El módulo está desarrollado en Kotlin para Android y utiliza sensores del dispositivo, GPS, Bluetooth BLE, reconocimiento de voz, clasificación local mediante IA-1, almacenamiento SQLite y servicios en primer plano.

Para el Entregable 3 (E3), el motor incorpora mejoras en la gestión de alertas, la persistencia offline, la seguridad de los paquetes BLE, el cálculo de rutas seguras sin conexión y la sincronización de información con los módulos Backend y Frontend.

### Funcionalidades implementadas

| Funcionalidad | Clase responsable |
|---|---|
| Gestión centralizada de emergencias | `ModuloEmergencias` |
| Detección de comandos de voz | `GestorVozPanico` |
| Transcripción de voz a texto | `GestorVozPanico` |
| Clasificación local de incidentes mediante IA-1 | `GestorVozPanico` |
| Detección de caídas e impactos | `GestorTelemetria` |
| Obtención de GPS y batería | `GestorTelemetria` |
| Emisión periódica de ubicación GNSS | `GestorTelemetria` |
| Transmisión y recepción mediante BLE | `GestorMalla` |
| Cifrado AES-GCM y firma HMAC-SHA256 de paquetes | `GestorMalla` |
| Retransmisión de alertas entre dispositivos | `GestorMalla` |
| Modo Operador Ad-Hoc sin Internet | `GestorMalla` |
| Emisión de paquetes de acuse ACK | `GestorMalla` |
| Seguimiento de una ruta segura | `GestorCaminataSegura` |
| Cálculo de rutas offline mediante Dijkstra ponderado | `GestorCaminataSegura` |
| Temporizador de check-in | `GestorCaminataSegura` |
| Cálculo geométrico de desviación de ruta | `GestorCaminataSegura` |
| Persistencia local de alertas pendientes | `PersistenciaOfflineModulo2.kt` |
| Control de paquetes BLE previamente recibidos | `PersistenciaOfflineModulo2.kt` |
| Gestión de la cola local SQLite ER-06 | `GestorColaLocalSQLiteER06` |
| Servicio en primer plano | `ServicioEmergencias` |
| Modelos de información y estados | `ModelosEmergencia` |

---

## 2. Arquitectura del módulo

La clase principal es `ModuloEmergencias`. Funciona como coordinador de las demás clases.

No es necesario que la interfaz gráfica gestione individualmente cada sensor o cada proceso. En su lugar, debe comunicarse con esta clase para iniciar funciones, consultar estados y recibir eventos.

La arquitectura actual incorpora una capa de persistencia local para mantener información cuando no existe conexión a Internet, además de mecanismos de seguridad para los paquetes transmitidos por Bluetooth.

```text
                         FRONTEND ANDROID
                                |
                                |
                         ModuloEmergencias
                                |
          +---------------------+---------------------+
          |                     |                     |
          v                     v                     v
    GestorVozPanico       GestorTelemetria     GestorCaminataSegura
          |                     |                     |
          |                     |                     |
          v                     v                     v
     Comandos de voz       GPS, batería,          Ruta Segura
     Transcripción         acelerómetro           Offline
     Clasificación IA-1    GNSS periódico          Check-in
                                |
                                v
                           GestorMalla
                                |
                   +------------+------------+
                   |                         |
                   v                         v
              Bluetooth BLE             Servidor Linux
                   |
                   v
             Otros dispositivos
                   |
                   v
          Modo Operador Ad-Hoc
          Cifrado AES-GCM
          Firma HMAC-SHA256
                   |
                   v
       PersistenciaOfflineModulo2
                   |
                   v
             SQLite ER-06
             cola_alerta
             paquete_visto

                    ServicioEmergencias
                    Mantiene el servicio
                    Android en primer plano
```

### Comunicación entre las clases

`ModuloEmergencias` crea las instancias de los gestores y conecta sus eventos mediante callbacks y flujos reactivos.

Por ejemplo:

- Cuando `GestorVozPanico` reconoce una palabra clave, notifica a `ModuloEmergencias`.
- Cuando `GestorVozPanico` transcribe el relato, IA-1 puede clasificarlo localmente y actualizar la información de la alerta activa.
- Cuando `GestorTelemetria` detecta un impacto, notifica a `ModuloEmergencias`.
- Cuando vence el temporizador de `GestorCaminataSegura`, se solicita una alerta.
- Cuando se activa una alerta, `ModuloEmergencias` obtiene la telemetría inicial, genera el objeto `AlertaEmergencia` y solicita su transmisión mediante `GestorMalla`.
- Cuando no existe conectividad, los datos pendientes pueden conservarse mediante `GestorColaLocalSQLiteER06`.
- Cuando un dispositivo recibe un paquete BLE, `GestorMalla` comprueba su integridad, evita procesar paquetes repetidos y puede retransmitirlo.
- Cuando una unidad operativa recibe una alerta, puede generar un paquete de acuse ACK para informar que atenderá el incidente.

La comunicación entre los módulos debe realizarse mediante interfaces y modelos compartidos, evitando que el Frontend manipule directamente las tablas SQLite o las operaciones internas de Bluetooth.

---

## 3. Preparación del proyecto

Todos los archivos deben estar dentro del paquete:

```kotlin
com.escom.sendero_seguro.emergencias
```

Archivos necesarios:

```text
emergencias/
|
|-- GestorCaminataSegura.kt
|-- GestorMalla.kt
|-- GestorTelemetria.kt
|-- GestorVozPanico.kt
|-- ModelosEmergencia.kt
|-- ModuloEmergencias.kt
|-- ServicioEmergencias.kt
|-- PersistenciaOfflineModulo2.kt
```

### Responsabilidades de cada archivo

| Archivo | Responsabilidad |
|---|---|
| `GestorCaminataSegura.kt` | Seguimiento de caminatas, check-in, detección de desviaciones y cálculo de rutas seguras offline mediante Dijkstra ponderado. |
| `GestorMalla.kt` | Comunicación BLE, retransmisión, cifrado AES-GCM de 128 bits, firma HMAC-SHA256 y Modo Operador Ad-Hoc. |
| `GestorTelemetria.kt` | Ubicación, GNSS, batería, acelerómetro y generación de datos de telemetría. |
| `GestorVozPanico.kt` | Reconocimiento de palabras clave, transcripción de voz y clasificación local de incidentes mediante IA-1. |
| `ModelosEmergencia.kt` | Modelos de alertas, telemetría, paquetes, estados, rutas y eventos. |
| `ModuloEmergencias.kt` | Coordinación general de los gestores y administración del ciclo de vida de las alertas. |
| `ServicioEmergencias.kt` | Servicio Android en primer plano para mantener las funciones de protección autorizadas. |
| `PersistenciaOfflineModulo2.kt` | Administración de la cola de alertas pendientes y registro de paquetes BLE vistos mediante SQLite ER-06. |

### Persistencia local ER-06

La clase `PersistenciaOfflineModulo2.kt` incorpora el gestor:

```kotlin
GestorColaLocalSQLiteER06
```

Este componente administra la persistencia local para los escenarios sin conexión.

Su responsabilidad principal es conservar las alertas y los paquetes que necesitan controlarse aunque el dispositivo no tenga acceso a Internet.

Las tablas principales del diagrama ER-06 son:

| Tabla | Responsabilidad |
|---|---|
| `cola_alerta` | Conserva alertas o elementos pendientes de procesamiento y transmisión, junto con su vigencia. |
| `paquete_visto` | Registra identificadores de paquetes BLE que ya fueron recibidos o procesados para evitar duplicados. |

Funciones principales:

```kotlin
encolarPendienteConVigencia(...)
registrarPaqueteMallaVisto(...)
pendientesCola(...)
```

La persistencia local permite separar la creación de una alerta de su entrega efectiva al servidor. De esta manera, una interrupción de conectividad no debe provocar que la información desaparezca.

### Dependencias

El proyecto necesita las bibliotecas de AndroidX, SQLite y Kotlin Coroutines utilizadas por el código.

Por ejemplo, en el archivo `build.gradle.kts` del módulo:

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:<version>")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:<version>")
}
```

Si la implementación utiliza SQLite mediante `SQLiteOpenHelper`, no es necesario agregar Room únicamente por utilizar SQLite.

Utiliza versiones compatibles con las que ya tiene configuradas el proyecto. No es necesario agregar estas dependencias si ya están instaladas.

---

## 4. Configuración de permisos

Antes de utilizar las funcionalidades, Android debe conocer los permisos que necesita la aplicación.

En `AndroidManifest.xml`, dentro de la etiqueta `manifest`, se deben declarar los permisos correspondientes.

### Permisos generales

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

<uses-permission android:name="android.permission.WAKE_LOCK" />

<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Para utilizar el micrófono desde un servicio en primer plano en Android 14 o superior:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

### Permisos Bluetooth

Para Android 12 o superior:

```xml
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />

<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

La bandera `neverForLocation` indica que el escaneo Bluetooth no se utiliza para derivar la ubicación física del usuario. Debe conservarse únicamente si esa declaración corresponde al uso real que hace la aplicación.

Para Android 13 o superior, si se utiliza Wi-Fi Direct o funcionalidades que requieren este permiso:

```xml
<uses-permission
    android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />
```

Para versiones anteriores:

```xml
<uses-permission
    android:name="android.permission.BLUETOOTH"
    android:maxSdkVersion="30" />

<uses-permission
    android:name="android.permission.BLUETOOTH_ADMIN"
    android:maxSdkVersion="30" />
```

### Permisos de reconocimiento de voz en segundo plano

Si se pretende utilizar el micrófono mediante un servicio en primer plano en Android 14 o superior, también se necesita:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

### Declaración del servicio

Dentro de la etiqueta `application`:

```xml
<service
    android:name=".emergencias.ServicioEmergencias"
    android:enabled="true"
    android:exported="false"
    android:foregroundServiceType="location|connectedDevice|microphone" />
```

**Importante:** `ServicioEmergencias` soporta dinámicamente el tipo `FOREGROUND_SERVICE_TYPE_MICROPHONE` en Android 14 o superior (API 34+) cuando el permiso `RECORD_AUDIO` ha sido concedido.

La declaración del tipo en el Manifest no sustituye la comprobación del permiso. Antes de iniciar el servicio con ese tipo, el código debe comprobar que el usuario concedió el permiso de micrófono y que se cumplen las restricciones de Android para iniciar un servicio de primer plano de tipo micrófono.

Si el permiso no está disponible, el servicio debe iniciar únicamente con los tipos autorizados y no debe intentar utilizar el micrófono.

### Consulta de servicios de reconocimiento

El código de voz utiliza el servicio de reconocimiento de Android. Se debe conservar esta declaración:

```xml
<queries>
    <intent>
        <action android:name="android.speech.RecognitionService" />
    </intent>
</queries>
```

### Permisos en tiempo de ejecución

Declarar los permisos en el Manifest no es suficiente.

La aplicación debe solicitar al usuario los permisos de:

- Micrófono.
- Ubicación precisa o aproximada, según las funciones habilitadas.
- Ubicación en segundo plano, cuando corresponda y de acuerdo con las restricciones de Android.
- Bluetooth Scan, Advertise y Connect en Android 12 o superior.
- Dispositivos Wi-Fi cercanos, cuando se utilice la funcionalidad correspondiente.
- Notificaciones en Android 13 o superior.

No se deben iniciar los gestores que necesitan un permiso hasta que este haya sido concedido.

La ubicación en segundo plano requiere una solicitud y justificación separadas en las versiones de Android que así lo establecen. No debe asumirse que conceder ubicación mientras se utiliza la aplicación autoriza automáticamente su uso en segundo plano.

---

## 5. Inicialización del motor de emergencias

La clase `ModuloEmergencias` es el punto de entrada para utilizar las funcionalidades.

Se recomienda crear una única instancia y conservarla durante la sesión de protección. No se debe crear una instancia nueva cada vez que se actualice una pantalla de Jetpack Compose.

Ejemplo de inicialización desde una Activity:

```kotlin
private lateinit var moduloEmergencias: ModuloEmergencias

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    moduloEmergencias = ModuloEmergencias(
        context = applicationContext,
        usuarioIdActual = "USR_LOCAL_01"
    )
}
```

El identificador `"USR_LOCAL_01"` es un valor de ejemplo. Debe sustituirse por el identificador del usuario obtenido desde el sistema de autenticación de la aplicación.

El identificador debe mantenerse consistente durante la sesión y utilizarse al generar alertas, paquetes BLE y registros locales.

### Iniciar la protección

Una vez concedidos los permisos:

```kotlin
moduloEmergencias.iniciarProteccionSegundoPlano()
```

Este método realiza las siguientes acciones:

1. Inicia `ServicioEmergencias`.
2. Activa la vigilancia del acelerómetro.
3. Solicita que `GestorMalla` inicie el escaneo BLE.
4. Prepara los componentes necesarios para mantener la protección.

### Activar el reconocimiento de voz

El reconocimiento de voz se inicia por separado:

```kotlin
moduloEmergencias.escucharComandoVozMicrofono()
```

Las palabras clave predeterminadas son:

- `ayuda`
- `auxilio`
- `sendero seguro`

Cuando el reconocedor detecta una coincidencia, se ejecuta el callback de `GestorVozPanico` y se solicita una alerta de emergencia.

### Activación mediante atajo físico

El módulo incorpora el indicador:

```kotlin
moduloEmergencias.atajoCincoToquesEncendidoActivo
```

Este valor permite consultar si está habilitado el atajo físico de cinco presiones del botón de encendido.

El receptor:

```kotlin
receptorBotonEncendido5x
```

es el componente asociado a la detección de cinco presiones, cuando el sistema y las condiciones de ejecución permiten recibir el evento.

**Limitación de Android:** una aplicación normal no dispone de acceso universal a todos los eventos físicos del botón de encendido. El comportamiento puede depender del fabricante, de la versión del sistema y de las funciones SOS nativas. Debe validarse en cada dispositivo de prueba.

### Persistencia y operación offline

El gestor `GestorColaLocalSQLiteER06` debe inicializarse con el contexto de aplicación y mantenerse disponible para las operaciones de persistencia.

Su objetivo es conservar las alertas pendientes y controlar los paquetes que ya fueron procesados.

No se debe asumir que almacenar una alerta localmente significa que ya fue recibida por el servidor. El estado de persistencia y el estado de entrega son conceptos diferentes.

**Limitación del reconocimiento de voz:** `iniciarProteccionSegundoPlano()` y `escucharComandoVozMicrofono()` deben administrarse de acuerdo con el ciclo de vida del servicio y las restricciones de Android. La escucha continua no está garantizada en todos los dispositivos. Por eso, el botón SOS y los demás detonadores deben mantenerse disponibles como alternativas.

---

## 6. Activación de una alerta SOS, voz e IA-1 — Proceso P4

Existen diferentes detonadores definidos en `TipoDetonador`:

```kotlin
enum class TipoDetonador {
    BOTON_SOS,
    COMANDO_VOZ,
    CAIDA_IMPACTO,
    VENCIMIENTO_CAMINATA_SEGURA,
    ATAJO_FISICO,
    WIDGET
}
```

### Activación desde un botón

Desde la interfaz:

```kotlin
val alerta = moduloEmergencias.activarAlertaEmergencia(
    detonador = TipoDetonador.BOTON_SOS
)
```

La función realiza las siguientes operaciones:

1. Comprueba si ya existe una alerta activa.
2. Inicia el servicio de emergencias.
3. Obtiene la última telemetría disponible.
4. Genera un objeto `AlertaEmergencia`.
5. Guarda la alerta en `alertaActiva`.
6. Solicita la emisión de un paquete mediante BLE.
7. Inicia la telemetría en tiempo real.
8. Inicia el hilo de actualización periódica.

### Activación mediante cuenta regresiva — Pantalla A-05

La pantalla A-05 incorpora una cuenta regresiva previa al envío de la alerta.

La función de integración es:

```kotlin
moduloEmergencias.iniciarCuentaRegresivaYEnvioBase(
    detonador = TipoDetonador.BOTON_SOS,
    segundosCuenta = 5,
    omitirCuentaEnviarYa = false
)
```

Parámetros:

| Parámetro | Descripción |
|---|---|
| `detonador` | Origen de la activación de la alerta. |
| `segundosCuenta` | Duración de la cuenta regresiva. |
| `omitirCuentaEnviarYa` | Permite omitir la cuenta y solicitar el envío inmediato. |

El flujo de funcionamiento es:

```text
       USUARIO PRESIONA SOS
                |
                v
    iniciarCuentaRegresivaYEnvioBase()
                |
                v
      Estado EN_CUENTA_REGRESIVA
                |
                v
       Mostrar cuenta en A-05
                |
                v
         ¿Cuenta llegó a 0?
                |
                v
        Generar alerta base
                |
                v
       Estado PENDIENTE_ENVIO
                |
                v
        Emitir alerta genérica
                |
                v
       Asignar folio ALR-2026-XXXXXX
                |
                v
       Continuar enriquecimiento
       con telemetría e IA-1
```

Al llegar la cuenta regresiva a cero, se envía la alerta base como **«Genérica»**, con un folio de referencia en el formato:

```text
ALR-2026-XXXXXX
```

El objetivo es que la clasificación y el enriquecimiento de la información no retrasen la solicitud inicial de ayuda.

Cuando se configura:

```kotlin
omitirCuentaEnviarYa = true
```

se solicita omitir la espera y continuar con el envío inmediato, de acuerdo con la lógica implementada por el motor.

La cuenta regresiva no debe convertirse en un requisito para que una alerta de voz, un detonador físico o una detección automática pueda solicitar ayuda cuando el flujo correspondiente requiera envío inmediato.

### Nuevos detonadores

| Detonador | Descripción |
|---|---|
| `BOTON_SOS` | Activación manual desde la interfaz. |
| `COMANDO_VOZ` | Detección de una palabra clave. |
| `CAIDA_IMPACTO` | Detección de un impacto mediante acelerómetro. |
| `VENCIMIENTO_CAMINATA_SEGURA` | Vencimiento del tiempo de check-in. |
| `ATAJO_FISICO` | Cinco presiones del botón de encendido detectadas mediante `receptorBotonEncendido5x`. |
| `WIDGET` | Activación desde el widget de la aplicación. |

### Activación por voz

No es necesario que la interfaz active manualmente la alerta cuando se reconoce una palabra clave. El callback está conectado en `ModuloEmergencias`:

```kotlin
onDetonadorVozActivado = { motivo ->
    activarAlertaEmergencia(TipoDetonador.COMANDO_VOZ)
}
```

El reconocimiento de palabras clave permite activar el flujo de emergencia sin depender de que el usuario navegue hasta la pantalla del botón SOS.

### Transcripción de voz a texto — Pantalla A-06

Se incorpora la función:

```kotlin
transcribirVozATextoOffline()
```

Su responsabilidad es transcribir el relato del usuario para obtener información adicional del incidente.

La transcripción alimenta el flujo de clasificación local mediante IA-1.

El reconocimiento contempla un cooldown de 2.5 segundos para administrar la escucha continua y permitir múltiples disparos de voz sin bloquear el micrófono después de la primera alerta.

El objetivo es que el motor pueda seguir recibiendo eventos de voz y actualizar la información de una alerta activa sin depender de una única detección.

La transcripción se expone mediante:

```kotlin
gestorVozPanico.textoTranscritoEnVivo
```

Este flujo puede observarse desde la pantalla A-06 para mostrar el texto reconocido.

**Nota técnica:** la transcripción offline depende del motor de reconocimiento y del modelo de idioma disponible en el dispositivo. La disponibilidad de la función debe comprobarse antes de afirmar que un dispositivo puede procesar voz sin conexión.

### Clasificación local mediante IA-1

La nueva función es:

```kotlin
clasificarIncidenteConIA1Local(
    relato: String
): ResultadoClasificacionIA1
```

Su objetivo es extraer información del relato y estructurarla en cuatro elementos:

| Elemento | Información extraída |
|---|---|
| QUÉ | Tipo de incidente o situación reportada. |
| DÓNDE | Lugar o referencia de ubicación mencionada. |
| QUIÉN | Personas involucradas o afectadas. |
| CUÁNDO | Momento en que ocurrió o está ocurriendo el incidente. |

La clasificación calcula un nivel de confianza y utiliza el umbral:

```text
0.70
```

Cuando el resultado alcanza el umbral establecido, puede utilizarse para actualizar la información de la alerta activa.

Cuando no alcanza el umbral, el resultado debe tratarse como información incierta y no sustituir datos confirmados.

**Importante:** IA-1 trabaja después de la activación inicial. La clasificación no debe retrasar el envío de la alerta genérica.

El resultado se expone mediante:

```kotlin
gestorVozPanico.ultimaClasificacionIA1
```

La interfaz puede utilizar este estado para mostrar los campos identificados y su nivel de confianza.

### Activación por caída o impacto

`GestorTelemetria` detecta cambios en el acelerómetro.

Cuando la magnitud de aceleración supera el umbral configurado, ejecuta:

```kotlin
onCaidaDetectada()
```

`ModuloEmergencias` recibe el evento y activa una alerta con:

```kotlin
TipoDetonador.CAIDA_IMPACTO
```

Se incluye un intervalo de protección para evitar que varias lecturas consecutivas generen múltiples alertas.

### Desactivar una alerta

Para cancelar normalmente una alerta:

```kotlin
moduloEmergencias.desactivarAlertaEmergencia(
    simulacionCoaccion = false
)
```

Esto ejecuta el flujo normal de desactivación de la alerta y detiene los procesos que correspondan.

El parámetro:

```kotlin
moduloEmergencias.desactivarAlertaEmergencia(
    simulacionCoaccion = true
)
```

activa la simulación del modo de coacción P8.

La integración debe distinguir una cancelación normal de una situación de coacción. El modo de coacción no debe mostrarse en la interfaz como una cancelación ordinaria.

---

## 7. Configuración de parámetros de seguridad — Proceso P3

La función `actualizarParametrosSeguridad()` permite conectar los parámetros configurados desde el Módulo 1.

La firma actualizada incorpora un cuarto parámetro para la cuenta regresiva:

```kotlin
moduloEmergencias.actualizarParametrosSeguridad(
    nuevasPalabrasVoz = listOf(
        "ayuda",
        "auxilio",
        "emergencia",
        "sendero seguro"
    ),
    sensibilidadCaidaG = 2.8f,
    intervaloCheckInSegundos = 60L,
    segundosCuentaRegresiva = 5
)
```

Los parámetros modificados son:

| Parámetro | Descripción |
|---|---|
| `nuevasPalabrasVoz` | Palabras que pueden activar el SOS. |
| `sensibilidadCaidaG` | Umbral de aceleración para detectar impactos. |
| `intervaloCheckInSegundos` | Tiempo máximo entre confirmaciones de presencia. |
| `segundosCuentaRegresiva` | Tiempo previo al envío de la alerta, configurable entre 3 y 10 segundos. |

La lista de palabras clave se normaliza para facilitar la comparación del texto reconocido.

### Configuración de la cuenta regresiva

El valor predeterminado es:

```kotlin
segundosCuentaRegresiva = 5
```

El rango de configuración permitido es:

```text
3 a 10 segundos
```

La pantalla A-05 debe consultar el valor configurado y mostrar la cuenta regresiva correspondiente.

La cuenta regresiva no debe utilizarse para retrasar procesos de emergencia que deban enviar una alerta inmediatamente.

### Atajo físico de cinco presiones

El módulo expone:

```kotlin
moduloEmergencias.atajoCincoToquesEncendidoActivo: Boolean
```

Esta propiedad permite consultar si el atajo de cinco presiones del botón de encendido está habilitado.

El detonador asociado es:

```kotlin
TipoDetonador.ATAJO_FISICO
```

La detección se realiza mediante:

```kotlin
receptorBotonEncendido5x
```

La disponibilidad de este evento depende de las restricciones del sistema operativo y del fabricante del dispositivo.

### Ajuste del escaneo BLE según batería

Para reducir el consumo energético, `GestorMalla` incorpora:

```kotlin
gestorMalla.ajustarEscaneoMallaPorBateria(
    nivelBateria: Int
)
```

La frecuencia de escaneo se ajusta según el nivel de batería:

| Nivel de batería | Comportamiento |
|---|---|
| Mayor a 20% | Escaneo cada 2 minutos. |
| Menor o igual a 20% | Modo de ahorro: escaneo cada 10 minutos. |

Este mecanismo busca equilibrar la disponibilidad de la malla BLE y el consumo energético del dispositivo.

La reducción de frecuencia puede incrementar el tiempo necesario para detectar otros dispositivos. Por ello, el modo de ahorro debe considerarse una optimización energética y no una garantía de recepción inmediata.

---

## 8. Ruta Segura Offline y Caminata Segura — Proceso P7

`GestorCaminataSegura` implementa el modo de acompañamiento durante un recorrido y el cálculo de rutas seguras sin conexión.

El gestor se divide en dos partes funcionales:

- **Parte A:** Cálculo de Ruta Segura Offline — Pantalla A-18.
- **Parte B:** Modo Acompañamiento / Caminata Segura.

### Parte A. Cálculo de Ruta Segura Offline — Pantalla A-18

El cálculo de rutas se realiza directamente en el dispositivo mediante un grafo vial almacenado localmente.

La función principal es:

```kotlin
calcularRutasEnDispositivoOffline(
    origen: PuntoRuta,
    destino: PuntoRuta,
    aristasGrafoCache: List<AristaVialOffline>
): ComparativaRutasP7
```

Parámetros:

| Parámetro | Descripción |
|---|---|
| `origen` | Coordenada inicial del recorrido. |
| `destino` | Coordenada final solicitada. |
| `aristasGrafoCache` | Lista de conexiones viales almacenadas en caché local. |

El algoritmo utiliza Dijkstra sobre el grafo local.

Cada arista representa una conexión entre puntos del grafo y puede contener información sobre distancia, riesgo e iluminación.

El cálculo pondera el costo de cada tramo utilizando la fórmula:

\[
peso = distancia \times (1 + \alpha \times riesgo)
\]

Donde:

- `distancia` representa la longitud del tramo.
- `alfa` representa el factor de ponderación del riesgo.
- `riesgo` representa el nivel de riesgo asociado al tramo.

La función:

```kotlin
ponderarRiesgoEIluminacionRuta(...)
```

participa en la evaluación de los tramos para priorizar recorridos con mejores condiciones de seguridad.

El cálculo contempla:

- Evitar polígonos de riesgo alto.
- Priorizar calles iluminadas.
- Penalizar los tramos con mayor riesgo.
- Comparar la distancia total de las alternativas.
- Realizar el cálculo local sin depender de una consulta al servidor durante la generación de la ruta.

El resultado `ComparativaRutasP7` permite presentar dos alternativas:

| Alternativa | Descripción |
|---|---|
| Ruta segura RECOMENDADA | Ruta que pondera el riesgo y la iluminación para favorecer condiciones más seguras. |
| Ruta Más corta | Ruta que prioriza reducir la distancia total del recorrido. |

El resultado se expone mediante:

```kotlin
gestorCaminataSegura.comparativaRutasOffline
```

La pantalla A-18 debe observar este flujo para mostrar las alternativas calculadas.

**Importante:** la calidad de la ruta depende de que el grafo vial, los polígonos de riesgo y los datos de iluminación estén disponibles y actualizados en la caché local. El algoritmo no puede garantizar que una calle sea segura si la información de origen está incompleta o desactualizada.

### Parte B. Modo Acompañamiento / Caminata Segura

Esta parte implementa el seguimiento del recorrido seleccionado.

Sus funciones principales son:

- Iniciar un recorrido con una ruta objetivo.
- Mantener un temporizador de llegada estimada.
- Solicitar confirmaciones periódicas de presencia.
- Detectar desviaciones de la ruta.
- Identificar la llegada al destino.
- Activar una alerta si vence el tiempo de check-in.

### Iniciar una caminata

Se debe proporcionar una lista de coordenadas y el tiempo estimado de llegada en milisegundos.

```kotlin
val ruta = listOf(
    PuntoRuta(19.5045, -99.1469),
    PuntoRuta(19.5050, -99.1475),
    PuntoRuta(19.5060, -99.1480)
)

moduloEmergencias.gestorCaminataSegura.iniciarCaminataSegura(
    rutaObjetivo = ruta,
    tiempoEstimadoLlegadaMs = 30 * 60 * 1000L,
    intervaloCheckInSeg = 60L,
    toleranciaMetros = 100.0
)
```

### Confirmar presencia

Cuando el usuario presione el botón de confirmación de presencia:

```kotlin
moduloEmergencias.gestorCaminataSegura.confirmarPresenciaCheckIn()
```

Esto reinicia la cuenta regresiva del check-in.

### Finalizar caminata

```kotlin
moduloEmergencias.gestorCaminataSegura.finalizarCaminataSegura()
```

### Eventos automáticos

La clase está conectada con `ModuloEmergencias`.

Cuando vence el check-in:

```kotlin
TipoDetonador.VENCIMIENTO_CAMINATA_SEGURA
```

se solicita una alerta automáticamente.

Cuando el usuario llega a menos de 20 metros del último punto de la ruta, el gestor finaliza la caminata y ejecuta el callback de llegada exitosa.

### Mejora del cálculo de desviación

El cálculo de desviación se mejoró para utilizar la distancia geométrica perpendicular entre la ubicación actual y los segmentos de la polilínea de la ruta.

Anteriormente, el cálculo se basaba en la distancia hacia los vértices individuales de la ruta. Esto podía generar mediciones poco precisas cuando los puntos estaban separados entre sí.

Ahora se utiliza la distancia punto-a-segmento, lo que permite evaluar la distancia mínima entre la ubicación actual y cada tramo de la polilínea.

De esta forma, la detección de desviaciones representa mejor la distancia real respecto al recorrido definido.

---

## 9. Integración de telemetría y estados — Proceso P6

`GestorTelemetria` obtiene información del dispositivo y mantiene un flujo actualizado de datos.

La estructura `DatosTelemetria` contiene:

| Campo | Descripción |
|---|---|
| `latitud` | Latitud de la ubicación obtenida. |
| `longitud` | Longitud de la ubicación obtenida. |
| `precision` | Precisión de la ubicación. |
| `velocidadMps` | Velocidad en metros por segundo. |
| `rumboGrados` | Dirección de desplazamiento. |
| `aceleracionG` | Magnitud de aceleración expresada en G. |
| `nivelBateria` | Porcentaje de batería. |
| `timestamp` | Momento de la lectura. |
| `fuente` | Fuente utilizada para obtener la ubicación. |

El campo:

```kotlin
fuente: FuenteUbicacion
```

permite identificar el origen de la coordenada.

Los valores contemplados son:

```kotlin
enum class FuenteUbicacion {
    GNSS,
    WIFI,
    CELULAR,
    ULTIMA_CONOCIDA
}
```

| Fuente | Descripción |
|---|---|
| `GNSS` | Ubicación obtenida mediante sistemas satelitales. |
| `WIFI` | Ubicación estimada mediante información de redes Wi-Fi. |
| `CELULAR` | Ubicación estimada mediante información de redes celulares. |
| `ULTIMA_CONOCIDA` | Última ubicación disponible cuando no se obtiene una lectura nueva. |

La fuente debe conservarse junto con la coordenada para que los módulos consumidores puedan interpretar correctamente su precisión y antigüedad.

### Iniciar telemetría

```kotlin
moduloEmergencias.gestorTelemetria.iniciarTelemetriaTiempoReal(
    intervaloMs = 3000L
)
```

### Emisión periódica de ubicación GNSS

Durante una alerta activa se utiliza la función:

```kotlin
emitirUbicacionGNSSPeriodica()
```

El intervalo oficial de actualización es:

```kotlin
10_000L
```

Es decir, una emisión cada 10,000 milisegundos.

El objetivo es mantener actualizada la ubicación del incidente mientras la alerta permanezca activa.

La función debe respetar los permisos de ubicación, el estado del proveedor GNSS y las restricciones de ejecución de Android.

### Detener telemetría

```kotlin
moduloEmergencias.gestorTelemetria.detenerTelemetriaTiempoReal()
```

### Obtener una lectura instantánea

```kotlin
val datos = moduloEmergencias.gestorTelemetria.emitirInstantaneaTelemetria()

Log.d("TELEMETRIA", "Latitud: ${datos.latitud}")
Log.d("TELEMETRIA", "Longitud: ${datos.longitud}")
Log.d("TELEMETRIA", "Batería: ${datos.nivelBateria}%")
Log.d("TELEMETRIA", "Fuente: ${datos.fuente}")
```

### Simular una ubicación para pruebas

```kotlin
moduloEmergencias.gestorTelemetria.simularUbicacionParaPruebas(
    latitud = 19.5045,
    longitud = -99.1469
)
```

Esta función permite probar la lógica de ubicación sin desplazarse físicamente.

**Advertencia:** una coordenada de prueba nunca debe mostrarse como si fuera la ubicación real del usuario. Se debe comprobar la fuente, la precisión y la antigüedad de la ubicación antes de incluirla en una alerta.

### Máquina de estados oficial E01

El ciclo de vida de una alerta se representa mediante:

```kotlin
EstadoAlertaE01
```

Los estados oficiales son:

| Estado | Descripción |
|---|---|
| `CREADA` | La alerta ha sido generada. |
| `EN_CUENTA_REGRESIVA` | La alerta se encuentra en la cuenta regresiva previa al envío. |
| `PENDIENTE_ENVIO` | La alerta está pendiente de ser transmitida o confirmada. |
| `EXPIRADA` | La alerta superó su vigencia definida. |
| `ENVIADA` | La alerta fue transmitida. |
| `ASIGNADA` | Una unidad operativa fue asignada al incidente. |
| `ESCALADA` | La alerta fue escalada a otro nivel de atención. |
| `ACUSADA` | Se recibió un acuse de atención. |
| `EN_ATENCION` | El incidente está siendo atendido. |
| `ATENDIDA` | La atención del incidente fue realizada. |
| `CERRADA` | El ciclo de atención fue cerrado. |
| `CANCELADA` | La alerta fue cancelada. |

La máquina de estados permite que el Frontend y los módulos Backend conozcan el estado actual de la alerta sin depender únicamente de una variable local de pantalla.

### Historial de eventos de alerta

La función:

```kotlin
actualizarHistorialEventoAlerta(...)
```

permite incorporar eventos al historial de una alerta.

Los eventos alimentan:

```kotlin
historialEventosAlerta
```

Este flujo contiene una lista de:

```kotlin
List<EventoHistorialAlerta>
```

La finalidad es mostrar una línea de tiempo con los cambios relevantes del incidente.

Por ejemplo:

```text
ALERTA CREADA
     |
     v
ALERTA ENVIADA
     |
     v
UNIDAD ASIGNADA
     |
     v
ACUSE RECIBIDO
     |
     v
UNIDAD EN ATENCIÓN
     |
     v
ALERTA ATENDIDA
     |
     v
ALERTA CERRADA
```

Los eventos deben conservar su marca de tiempo y la información necesaria para reconstruir el historial.

### Sincronización con la red de apoyo y emisora

La función:

```kotlin
sincronizarVistaRedApoyoYEmisora(...)
```

actualiza la información de seguimiento de la unidad asignada.

El estado se expone mediante:

```kotlin
vistaSincronizadaAtencion
```

Su tipo es:

```kotlin
StateFlow<EstadoSeguimientoAtencion?>
```

La información puede incluir:

- Identificador de la unidad asignada.
- Distancia respecto al incidente.
- Hora en que se recibió el acuse.
- Estado actual del seguimiento.

Ejemplo de información que puede mostrarse en la interfaz:

```text
Unidad asignada: P-214
Distancia al incidente: 180 m
Estado: ACUSADA
Hora de acuse: 18:42
```

La información de la unidad debe proceder de una actualización recibida o de un cálculo realizado con coordenadas válidas. No debe inventarse una distancia ni mostrarse una asignación que no haya sido confirmada.

---

## 10. Integración de la malla Bluetooth BLE y Modo Operador Ad-Hoc — Proceso P5.1

`GestorMalla` permite transmitir y recibir paquetes de emergencia utilizando Bluetooth Low Energy.

Su funcionamiento se divide en:

1. Escaneo de anuncios BLE.
2. Transmisión de paquetes de emergencia.
3. Recepción y validación de paquetes.
4. Retransmisión de paquetes recibidos.
5. Protección criptográfica de la información.
6. Operación Ad-Hoc para unidades de campo sin Internet.
7. Emisión de paquetes de acuse ACK.

### Activar el escaneo

Normalmente se activa desde:

```kotlin
moduloEmergencias.iniciarProteccionSegundoPlano()
```

También se puede solicitar directamente:

```kotlin
moduloEmergencias.gestorMalla.asegurarEscaneoMallaActivo()
```

### Emitir una alerta

Cuando se activa una alerta, `ModuloEmergencias` ejecuta:

```kotlin
gestorMalla.emitirAlertaEnMalla(
    alerta = nuevaAlerta,
    emisorId = usuarioIdActual
)
```

Se genera un `PaqueteMalla` con la información necesaria para propagar el incidente.

### Estructura actualizada de PaqueteMalla

El modelo incorpora los siguientes elementos:

| Campo | Descripción |
|---|---|
| Identificador del paquete | Identifica de manera única el paquete. |
| Identificador del emisor | Identifica el dispositivo o usuario que originó el mensaje. |
| Coordenadas | Ubicación asociada a la alerta. |
| Nivel de batería | Porcentaje de batería del dispositivo emisor. |
| `ttl` | Cantidad de retransmisiones o saltos permitidos restantes. |
| `saltosRecorridos` | Número de saltos que ha recorrido el paquete. |
| `cargaCifradaBase64` | Contenido protegido mediante AES-GCM y codificado en Base64. |
| `firmaHmacHex` | Firma de integridad calculada mediante HMAC-SHA256. |

### Control de saltos y TTL

El paquete incorpora dos valores complementarios:

- `saltosRecorridos` aumenta en una unidad cuando el paquete se retransmite.
- `ttl` disminuye en una unidad cuando el paquete avanza por la malla.

Ejemplo conceptual:

```text
PAQUETE ORIGINAL
ttl = 5
saltosRecorridos = 0
       |
       v
PRIMER DISPOSITIVO
ttl = 4
saltosRecorridos = 1
       |
       v
SEGUNDO DISPOSITIVO
ttl = 3
saltosRecorridos = 2
       |
       v
TERCER DISPOSITIVO
ttl = 2
saltosRecorridos = 3
```

Cuando el TTL llega al límite establecido, el paquete deja de retransmitirse.

Este mecanismo evita la propagación indefinida de un mismo paquete.

### Seguridad criptográfica de los paquetes

La implementación incorpora dos mecanismos:

**AES-GCM de 128 bits**

La carga útil se cifra mediante AES-GCM con una clave de 128 bits.

El resultado se codifica en Base64 y se almacena en:

```kotlin
cargaCifradaBase64
```

AES-GCM proporciona confidencialidad y autenticación criptográfica del contenido cifrado mediante su etiqueta de autenticación.

**HMAC-SHA256**

La integridad del paquete se verifica mediante una firma HMAC-SHA256 almacenada en:

```kotlin
firmaHmacHex
```

Este mecanismo permite comprobar que el contenido firmado no fue modificado y que corresponde a quien conoce la clave compartida.

La verificación debe realizarse antes de procesar o retransmitir el contenido como una alerta válida.

**Nota técnica:** AES-GCM y HMAC requieren una administración segura de claves. Las claves no deben incluirse directamente en el código fuente ni enviarse dentro del mismo paquete que protegen. También se debe evitar la reutilización de nonces con una misma clave AES-GCM.

### Compatibilidad con versiones anteriores de Android

Se incorpora soporte retrocompatible para dispositivos con Android API inferior a 26 mediante:

```kotlin
emitirModoLegacyRespaldo(...)
```

Esta función permite utilizar el mecanismo de respaldo definido para versiones antiguas.

La compatibilidad debe comprobarse en dispositivos reales, ya que las capacidades BLE disponibles dependen tanto de la versión de Android como del hardware.

### Retransmisión de paquetes

Cuando un dispositivo recibe un paquete BLE válido, ejecuta:

```kotlin
interceptarYRetransmitir(paqueteEntrante)
```

La función debe:

1. Validar la estructura del paquete.
2. Comprobar la integridad criptográfica.
3. Consultar si el paquete ya fue procesado.
4. Registrar el identificador en la persistencia de paquetes vistos.
5. Verificar que el TTL permita continuar la propagación.
6. Actualizar `saltosRecorridos`.
7. Reducir `ttl`.
8. Retransmitir el paquete cuando corresponda.

El registro de paquetes procesados se realiza mediante:

```kotlin
registrarPaqueteMallaVisto(...)
```

Esto permite reducir retransmisiones duplicadas y evitar ciclos innecesarios.

### Configurar el servidor Linux

La dirección del servidor se establece en:

```kotlin
moduloEmergencias.gestorMalla.urlServidorLinux =
    "http://DIRECCION_IP:8080/alerta"
```

El servidor debe estar accesible desde la red del dispositivo y aceptar solicitudes HTTP POST con contenido JSON.

La función:

```kotlin
enviarAServidorLinux(...)
```

permite enviar la información del paquete al servidor.

La emisión inicial de una alerta por BLE y su envío HTTP son operaciones diferentes. Debe verificarse el punto del flujo que invoca la transmisión HTTP para garantizar que también se atiendan los paquetes originales cuando así lo requiera la integración.

### Modo Operador Ad-Hoc — Pantalla A-14

El Modo Operador Ad-Hoc permite que una unidad de campo consulte alertas recibidas mediante la malla BLE sin depender de una conexión a Internet.

La función:

```kotlin
mostrarAlertaAdHocModoOperador(
    paquete: PaqueteMalla,
    latitudOperador: Double,
    longitudOperador: Double
): AlertaAdHocOperadorVista
```

genera una vista de la alerta a partir del paquete recibido y de la ubicación actual del operador.

La función calcula:

- Distancia entre el operador y el incidente.
- Rumbo cardinal hacia la ubicación de la alerta.
- Información necesaria para mostrar la alerta en la pantalla A-14.

Ejemplo de presentación:

```text
ALERTA RECIBIDA

Distancia: 180 m
Dirección: noreste

Referencia:
180 m al noreste
```

La distancia y el rumbo deben calcularse a partir de coordenadas válidas. Si no se cuenta con una ubicación confiable, la interfaz debe indicarlo en lugar de mostrar una dirección estimada como si fuera precisa.

### Acuse de atención offline por malla

La función:

```kotlin
acusarYAcudirOfflinePorMalla(
    paqueteOriginal: PaqueteMalla,
    unidadOperativaId: String = "P-214"
): PaqueteMalla
```

permite que una unidad operativa genere un paquete de acuse sin depender de Internet.

El identificador predeterminado de unidad es:

```text
P-214
```

La función genera un paquete de respuesta con un identificador de acuse en el formato:

```text
ACK-XXXX
```

Este paquete se emite por BLE para comunicar que la unidad recibió y acusó la alerta.

El acuse debe conservar una referencia al paquete original para permitir relacionar la respuesta con el incidente correspondiente.

**Importante:** emitir un ACK por BLE no significa que el servidor central ya haya recibido el acuse. La confirmación local y la sincronización con el servidor deben manejarse como estados diferentes.

---

## 11. Conectar la interfaz mediante StateFlow y SharedFlow

Los gestores exponen flujos de datos para que la interfaz pueda observar los cambios sin consultar manualmente cada clase.

Los principales flujos disponibles desde `ModuloEmergencias` y sus gestores son:

| Flujo | Tipo | Información |
|---|---|---|
| `alertaActiva` | StateFlow | Alerta actual. |
| `flujoTelemetria` | StateFlow | Telemetría actual. |
| `estadoCaminata` | StateFlow | Estado de la caminata. |
| `estadoRedYMalla` | StateFlow | Conectividad y estado BLE. |
| `flujoPaquetesMalla` | SharedFlow | Paquetes emitidos o retransmitidos. |
| `colaTelemetriaOfflineP11` | SharedFlow | Lecturas para almacenamiento offline. |
| `segundosRestantesPreEnvio` | StateFlow | Segundos restantes de la cuenta regresiva previa al envío. |
| `historialEventosAlerta` | StateFlow | Línea de tiempo de eventos de la alerta. |
| `vistaSincronizadaAtencion` | StateFlow | Estado de seguimiento de la unidad asignada. |
| `gestorVozPanico.textoTranscritoEnVivo` | StateFlow | Texto reconocido durante la transcripción de voz. |
| `gestorVozPanico.ultimaClasificacionIA1` | StateFlow | Último resultado de clasificación local IA-1. |
| `gestorMalla.alertaModoOperadorAdHoc` | StateFlow | Vista de alerta para el operador de campo. |
| `gestorCaminataSegura.comparativaRutasOffline` | StateFlow | Comparativa de rutas calculadas localmente. |

### Ejemplo de observación en una Activity

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {

        launch {
            moduloEmergencias.alertaActiva.collect { alerta ->
                if (alerta != null) {
                    Log.d("SOS", "Alerta activa: ${alerta.idAlerta}")
                }
            }
        }

        launch {
            moduloEmergencias.flujoTelemetria.collect { datos ->
                datos?.let {
                    Log.d("GPS", "${it.latitud}, ${it.longitud}")
                }
            }
        }

        launch {
            moduloEmergencias.estadoRedYMalla.collect { estado ->
                Log.d(
                    "MALLA",
                    "BLE activo: ${estado.escaneoBleActivo}"
                )
            }
        }

        launch {
            moduloEmergencias.segundosRestantesPreEnvio.collect { segundos ->
                Log.d("SOS", "Cuenta regresiva: $segundos")
            }
        }

        launch {
            moduloEmergencias.historialEventosAlerta.collect { eventos ->
                Log.d("SOS", "Eventos registrados: ${eventos.size}")
            }
        }
    }
}
```

Para este ejemplo se necesitan los imports:

```kotlin
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
```

### Ejemplo de observación en Jetpack Compose

Si la interfaz utiliza Jetpack Compose, los flujos `StateFlow` pueden observarse con `collectAsState()`:

```kotlin
val alerta by moduloEmergencias.alertaActiva.collectAsState()

val telemetria by moduloEmergencias.flujoTelemetria.collectAsState()

val estadoMalla by moduloEmergencias.estadoRedYMalla.collectAsState()

val segundosRestantes by moduloEmergencias.segundosRestantesPreEnvio.collectAsState()

val historial by moduloEmergencias.historialEventosAlerta.collectAsState()

val seguimiento by moduloEmergencias.vistaSincronizadaAtencion.collectAsState()

val textoVoz by moduloEmergencias.gestorVozPanico.textoTranscritoEnVivo.collectAsState()

val clasificacion by moduloEmergencias.gestorVozPanico.ultimaClasificacionIA1.collectAsState()

val alertaOperador by moduloEmergencias.gestorMalla.alertaModoOperadorAdHoc.collectAsState()

val rutasOffline by moduloEmergencias.gestorCaminataSegura.comparativaRutasOffline.collectAsState()
```

Imports:

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
```

La interfaz puede utilizar estos valores para actualizar indicadores, mostrar la ubicación, informar si existe una alerta, presentar la clasificación del incidente y mostrar el estado de la malla BLE.

### 11.1. Contrato de Integración con los demás Módulos

Esta sección define las funciones y los flujos que deben utilizar los demás equipos para integrar el Módulo 2.

El objetivo es que los equipos consuman las interfaces públicas del motor y no repliquen la lógica de emergencia, persistencia, telemetría o comunicación BLE.

#### A. Módulo Backend 1 — P1, P2, P3 y P8

Este módulo es responsable de la información del usuario, configuración de seguridad y parámetros asociados al funcionamiento del sistema.

**1. Inyectar el usuario actual**

Al crear el motor, debe proporcionarse el identificador real del usuario autenticado:

```kotlin
val moduloEmergencias = ModuloEmergencias(
    context = applicationContext,
    usuarioIdActual = usuarioIdActual
)
```

`usuarioIdActual` debe provenir del sistema de autenticación del proyecto.

No se debe utilizar un identificador fijo de prueba en una versión integrada.

**2. Actualizar parámetros de seguridad**

Cuando el usuario modifique sus preferencias, el Módulo Backend 1 debe invocar:

```kotlin
moduloEmergencias.actualizarParametrosSeguridad(
    nuevasPalabrasVoz = palabrasClaveConfiguradas,
    sensibilidadCaidaG = sensibilidadCaida,
    intervaloCheckInSegundos = intervaloCheckIn,
    segundosCuentaRegresiva = segundosCuenta
)
```

Los valores deben respetar las restricciones de configuración:

- Cuenta regresiva: de 3 a 10 segundos.
- Sensibilidad de caída: de acuerdo con la calibración definida.
- Intervalo de check-in: según la configuración del usuario.
- Palabras de voz: lista de palabras admitidas por el motor.

**3. Desactivación normal o simulación de coacción**

Para cancelar una alerta mediante el flujo normal:

```kotlin
moduloEmergencias.desactivarAlertaEmergencia(
    simulacionCoaccion = false
)
```

Para solicitar el flujo de simulación de coacción:

```kotlin
moduloEmergencias.desactivarAlertaEmergencia(
    simulacionCoaccion = true
)
```

El Backend 1 debe distinguir ambos casos y evitar tratar una simulación de coacción como una cancelación normal.

La lógica de P8 debe conservar la semántica del estado de alerta y coordinarse con el Backend correspondiente.

#### B. Módulo Backend 3 — P9, P10, P11 y P12

Este módulo consume la persistencia local, la cola offline, los datos de telemetría y la información vial.

**1. Utilizar la persistencia local ER-06**

La clase responsable es:

```kotlin
GestorColaLocalSQLiteER06
```

Funciones principales:

```kotlin
encolarPendienteConVigencia(...)
```

Agrega un elemento pendiente a la cola local con su vigencia correspondiente.

```kotlin
registrarPaqueteMallaVisto(...)
```

Registra un paquete BLE que ya fue procesado para evitar duplicados.

```kotlin
pendientesCola
```

Permite consultar los elementos que permanecen pendientes de procesamiento o sincronización.

El Backend 3 debe utilizar estas funciones para coordinar el almacenamiento offline y la posterior recuperación de los elementos pendientes.

No debe insertar directamente en las tablas si existe una función pública del gestor que ya realiza esa operación.

**2. Escuchar la telemetría pendiente P11**

El flujo:

```kotlin
moduloEmergencias.colaTelemetriaOfflineP11
```

permite recibir las lecturas que deben conservarse o procesarse cuando no existe conectividad.

Ejemplo:

```kotlin
lifecycleScope.launch {
    moduloEmergencias.colaTelemetriaOfflineP11.collect { datos ->
        // Procesar o entregar la lectura al mecanismo de persistencia P11.
    }
}
```

El Backend 3 debe coordinar la persistencia y la sincronización de estas lecturas con el servidor cuando vuelva a existir conexión.

**3. Proporcionar la caché vial para rutas offline**

El cálculo de rutas necesita una lista de aristas del grafo local:

```kotlin
val comparativa = moduloEmergencias.gestorCaminataSegura
    .calcularRutasEnDispositivoOffline(
        origen = origen,
        destino = destino,
        aristasGrafoCache = aristasViales
    )
```

Donde:

```kotlin
aristasViales: List<AristaVialOffline>
```

La lista debe proceder de la caché local administrada por el módulo responsable de los datos geográficos.

El Backend 3 es responsable de entregar la información al motor de rutas. El cálculo Dijkstra se realiza en el dispositivo.

La estructura de cada arista debe corresponder al modelo `AristaVialOffline` compartido por el proyecto.

#### C. Módulo Backend 4 — Nube / Servidor

El Backend 4 recibe las alertas transmitidas desde los dispositivos y coordina la sincronización con el servidor.

**1. Recepción de paquetes mediante HTTP**

El punto de integración es:

```kotlin
enviarAServidorLinux(...)
```

La URL se configura mediante:

```kotlin
moduloEmergencias.gestorMalla.urlServidorLinux =
    "http://DIRECCION_IP:8080/alerta"
```

El envío se realiza mediante HTTP POST con contenido JSON.

El contrato de transporte debe contemplar los datos necesarios para identificar y procesar el paquete:

| Dato | Descripción |
|---|---|
| Identificador del paquete | Identificador único del mensaje. |
| Identificador del emisor | Usuario o dispositivo que origina el paquete. |
| Identificador de alerta | Folio del incidente asociado. |
| Coordenadas | Ubicación asociada al incidente. |
| Marca de tiempo | Momento de generación o transmisión. |
| TTL | Saltos restantes. |
| Saltos recorridos | Número de retransmisiones realizadas. |
| Carga cifrada | Contenido protegido en Base64. |
| Firma HMAC | Valor de integridad en hexadecimal. |

Ejemplo conceptual del contenido:

```json
{
  "idPaquete": "PKT-XXXX",
  "emisorId": "USR_LOCAL_01",
  "idAlerta": "ALR-2026-XXXXXX",
  "latitud": 19.5045,
  "longitud": -99.1469,
  "timestamp": 1790000000000,
  "ttl": 4,
  "saltosRecorridos": 1,
  "cargaCifradaBase64": "<contenido_cifrado>",
  "firmaHmacHex": "<firma_hmac>"
}
```

Este ejemplo representa los campos del contrato lógico, no sustituye el serializador real del proyecto. Los nombres, tipos, formato de timestamp y estructura definitiva deben coincidir exactamente con el DTO que genera `enviarAServidorLinux()` y con el endpoint del Backend 4.

El servidor debe validar la estructura y la integridad antes de procesar el contenido. La verificación criptográfica debe utilizar las claves administradas por el sistema y no claves recibidas dentro del mismo paquete.

**2. Sincronizar estados de atención**

Cuando el servidor actualice el estado de una alerta, la integración debe reflejarlo mediante:

```kotlin
sincronizarVistaRedApoyoYEmisora(...)
```

La información debe alimentar:

```kotlin
vistaSincronizadaAtencion
```

La interfaz podrá consultar la unidad asignada, su distancia y la hora de acuse.

**3. Actualizar el historial**

Los cambios relevantes del ciclo de vida deben incorporarse mediante:

```kotlin
actualizarHistorialEventoAlerta(...)
```

El resultado se refleja en:

```kotlin
historialEventosAlerta
```

El Backend 4 debe conservar la correspondencia entre el folio de alerta, los eventos y los estados E01.

#### D. Frontend 1 y Frontend 2

Los equipos de interfaz deben observar los flujos del Módulo 2 para actualizar las pantallas sin duplicar la lógica de los gestores.

| Pantalla | Flujos y datos que debe observar |
|---|---|
| A-04 | `alertaActiva`, `flujoTelemetria`, estado general de protección y estado de la alerta. |
| A-05 | `segundosRestantesPreEnvio`, `alertaActiva` y estado E01 para mostrar la cuenta regresiva y el resultado del envío. |
| A-06 | `gestorVozPanico.textoTranscritoEnVivo`, `gestorVozPanico.ultimaClasificacionIA1` y `alertaActiva`. |
| A-07 | `estadoCaminata`, `flujoTelemetria` y estado del check-in. |
| A-08 | `historialEventosAlerta` y `vistaSincronizadaAtencion` para presentar el seguimiento del incidente. |
| A-12 | `estadoRedYMalla`, `flujoPaquetesMalla` y estado de sincronización. |
| A-14 | `gestorMalla.alertaModoOperadorAdHoc` y paquetes recibidos mediante BLE. |
| A-18 | `gestorCaminataSegura.comparativaRutasOffline` para mostrar la ruta segura recomendada y la ruta más corta. |
| A-19 | `historialEventosAlerta`, `vistaSincronizadaAtencion` y estado E01 para presentar el seguimiento y cierre de la atención. |

**Reglas de integración del Frontend:**

1. No generar alertas directamente desde las pantallas manipulando los gestores internos.
2. Utilizar las funciones públicas de `ModuloEmergencias`.
3. Observar los `StateFlow` para mantener la interfaz sincronizada.
4. No asumir que una alerta fue enviada únicamente porque existe en `alertaActiva`.
5. Diferenciar los estados locales de los estados confirmados por el servidor.
6. Mostrar cuando una ubicación procede de `ULTIMA_CONOCIDA`.
7. Mostrar la clasificación IA-1 como información estimada, acompañada de su confianza.
8. No presentar una unidad operativa como asignada si no existe una confirmación correspondiente.
9. Mantener disponible el botón SOS como alternativa a los detonadores automáticos.

### Responsabilidad de cada módulo

```text
       FRONTEND 1 / FRONTEND 2
                 |
                 v
        Presentación y eventos UI
                 |
                 v
          ModuloEmergencias
                 |
      +----------+-----------+
      |          |           |
      v          v           v
  BACKEND 1  BACKEND 3   BACKEND 4
  Usuarios   Offline     Nube/Servidor
  Seguridad  SQLite      Sincronización
  Parámetros Rutas       Estados E01
```

El Módulo 2 debe actuar como motor de coordinación. Los demás módulos consumen sus funciones y estados sin replicar internamente sus responsabilidades.

---

## 12. Servicio en primer plano

`ServicioEmergencias` mantiene un servicio Android en primer plano mediante una notificación persistente.

Se inicia con:

```kotlin
ServicioEmergencias.iniciar(context)
```

Y se detiene con:

```kotlin
ServicioEmergencias.detener(context)
```

Sus responsabilidades son:

- Crear el canal de notificaciones.
- Mostrar una notificación de protección activa.
- Adquirir un `PARTIAL_WAKE_LOCK`.
- Solicitar que Android mantenga el servicio en primer plano.
- Declarar los tipos de servicio que corresponden a las funciones activas.
- Liberar el WakeLock cuando se destruye el servicio.

El servicio utiliza `START_STICKY`, que permite a Android intentar recrearlo si el proceso es terminado por el sistema. Esto no garantiza que permanezca activo permanentemente.

### Tipos de servicio

Los tipos deben corresponder a las funciones que realmente se ejecutan.

| Tipo | Uso |
|---|---|
| `LOCATION` | Actualización de ubicación durante la protección. |
| `CONNECTED_DEVICE` | Funciones de comunicación con dispositivos cercanos. |
| `MICROPHONE` | Reconocimiento de voz mediante el micrófono. |

En Android 14 o superior, el tipo `MICROPHONE` requiere su permiso correspondiente y que se cumplan las restricciones de inicio del servicio.

La implementación actual soporta agregar dinámicamente:

```kotlin
ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
```

cuando se ha concedido `RECORD_AUDIO`.

### WakeLock

El servicio utiliza un:

```kotlin
PARTIAL_WAKE_LOCK
```

para mantener la CPU activa durante las operaciones autorizadas.

Debe comprobarse que el WakeLock se libere correctamente y que se maneje su expiración, ya que mantenerlo durante periodos prolongados puede incrementar el consumo de batería.

### Consideración de arquitectura

El servicio debe coordinar el ciclo de vida de las funciones de protección.

Si el reconocimiento de voz se administra desde el servicio, debe evitarse crear una segunda instancia independiente de `GestorVozPanico` desde una Activity.

La interfaz debe observar los flujos compartidos y comunicarse con el servicio o con la instancia central del motor.

El servicio debe detener o liberar los recursos correspondientes cuando finalice el ciclo de protección.

---

## 13. Consideraciones importantes antes de utilizar el módulo

El módulo incorpora las funcionalidades necesarias para el Entregable 3, pero todavía existen aspectos que deben revisarse y validarse antes de considerarlo un sistema de protección confiable.

### Reconocimiento de voz

`SpeechRecognizer` depende del servicio de reconocimiento instalado en el dispositivo.

En las pruebas realizadas se observó que algunos dispositivos con servicios de Google reconocen correctamente las palabras clave, mientras que otros presentan errores de idioma o de red.

El error de reconocimiento 2 corresponde a un problema de red. El error 12 indica que el idioma solicitado no es compatible con el reconocedor seleccionado.

La transcripción offline depende de la disponibilidad de un motor y modelo de idioma compatible. No se debe asumir que todos los dispositivos Android pueden procesar voz sin conexión.

La escucha continua tampoco está garantizada por Android, especialmente con la pantalla apagada o cuando el sistema restringe el acceso al micrófono.

### Clasificación IA-1

La clasificación local permite estructurar la información del relato en QUÉ, DÓNDE, QUIÉN y CUÁNDO.

El umbral de confianza de 0.70 debe utilizarse como referencia para decidir si un resultado puede incorporarse a la alerta.

Un resultado con confianza insuficiente no debe sustituir información confirmada ni utilizarse como una afirmación definitiva sobre el incidente.

La clasificación no debe retrasar el envío de la alerta inicial.

### Servicio en primer plano

Para utilizar ubicación y micrófono en segundo plano se deben declarar los tipos de servicio adecuados y cumplir las restricciones de Android.

Un WakeLock no garantiza que Android mantenga todos los procesos activos ni evita que el fabricante aplique restricciones adicionales de batería.

El servicio debe solicitar únicamente los tipos y permisos necesarios para las funciones que realmente ejecuta.

### Telemetría y detección de caídas

El umbral de aceleración debe calibrarse con pruebas controladas. Un impacto no necesariamente significa que ocurrió una caída.

También es recomendable agregar mecanismos para evitar múltiples detecciones consecutivas.

### Ubicación

No se debe enviar una ubicación de prueba como si fuera una ubicación GPS real.

Se debe comprobar:

- La fuente de ubicación.
- La precisión.
- La antigüedad de la lectura.
- La disponibilidad de permisos.
- La validez de las coordenadas.

El valor `ULTIMA_CONOCIDA` debe distinguirse de una ubicación GNSS recién obtenida.

### Persistencia offline ER-06

La base SQLite permite conservar alertas pendientes y registrar paquetes ya vistos.

Sin embargo, la persistencia local no representa una confirmación de recepción por parte del servidor.

Se debe controlar:

- La vigencia de los elementos en `cola_alerta`.
- La eliminación o actualización de pendientes ya sincronizados.
- La prevención de duplicados mediante `paquete_visto`.
- La recuperación de registros después de reiniciar la aplicación.
- La consistencia entre los estados locales y los confirmados por el servidor.

### Bluetooth BLE y seguridad

La implementación ahora incorpora cifrado AES-GCM de 128 bits y firma HMAC-SHA256.

Por lo tanto, la información transmitida cuenta con mecanismos criptográficos para proteger la confidencialidad y comprobar la integridad de los paquetes.

Esto no elimina todos los riesgos de comunicación.

Se deben revisar:

- Administración y almacenamiento de claves.
- Generación y unicidad de nonces AES-GCM.
- Verificación de la etiqueta de autenticación.
- Verificación HMAC antes de procesar el paquete.
- Prevención de repetición de mensajes.
- Vigencia de los paquetes.
- Compatibilidad entre versiones del formato.
- Validación de identificadores y campos recibidos.

El TTL y `saltosRecorridos` controlan la propagación, pero no sustituyen los mecanismos criptográficos.

El alcance de BLE depende del hardware, del entorno y de las condiciones de radio. La recepción de un anuncio no garantiza que el paquete haya llegado a un servidor o a un servicio de emergencia.

### Modo Operador Ad-Hoc

El Modo Operador permite consultar y acusar alertas mediante BLE sin Internet.

Un paquete ACK emitido por BLE confirma una acción local de acuse, pero no garantiza que el servidor central haya recibido esa información.

La sincronización posterior debe conservar el identificador del paquete original y el identificador del acuse para evitar asociaciones incorrectas.

### Rutas seguras offline

El algoritmo Dijkstra calcula rutas utilizando el grafo local disponible.

La ruta recomendada depende de la vigencia y calidad de los datos de:

- Conectividad vial.
- Polígonos de riesgo.
- Iluminación.
- Distancias entre nodos.

La aplicación debe informar cuando no exista una ruta válida o cuando la caché local esté incompleta.

Una ruta recomendada por el algoritmo no constituye una garantía de seguridad física.

### Atajo físico de cinco presiones

La detección de cinco presiones del botón de encendido puede entrar en conflicto con el SOS nativo del sistema operativo.

Para realizar pruebas controladas, se debe revisar la configuración del teléfono y desactivar temporalmente el **SOS de emergencia nativo de Android**, si el dispositivo permite hacerlo, para evitar que las cinco presiones abran el flujo de llamada al 911 del sistema.

Esta modificación debe limitarse al entorno de pruebas. En un dispositivo de uso cotidiano no se recomienda desactivar las protecciones de emergencia del sistema sin valorar sus consecuencias.

Además, la recepción del evento depende de las restricciones del fabricante y de Android. No debe considerarse un detonador universalmente disponible.

### Modo de coacción

La implementación de `simulacionCoaccion` debe distinguirse de una cancelación normal.

Debe completarse y probarse cuidadosamente antes de presentarse como una funcionalidad operativa.

---

## 14. Flujo general de funcionamiento

El flujo esperado del módulo es el siguiente:

```text
INICIO DE LA APLICACIÓN
          |
          v
Solicitar permisos Android
          |
          v
Crear ModuloEmergencias
          |
          v
Inicializar persistencia ER-06
          |
          v
Iniciar protección
          |
          +---------------------------+
          |                           |
          v                           v
    Vigilancia de                 Escaneo BLE
     acelerómetro                      |
          |                            |
          v                            v
 Activar reconocimiento           Recibir paquetes
      de voz                       y validar firma
          |                            |
          v                            v
 Transcribir relato             Consultar paquete_visto
          |                            |
          v                            v
 Clasificar mediante IA-1       ¿Paquete nuevo?
          |                            |
          |                       +----+----+
          |                       |         |
          |                      NO        SÍ
          |                       |         |
          |                       v         v
          |                    Ignorar   Procesar
          |                              y retransmitir
          |
          v
¿Se detectó un detonador?
          |
     +----+----+
     |         |
    NO        SÍ
     |         |
     v         v
Continuar   Iniciar flujo SOS
monitoreo       |
                v
       ¿Requiere cuenta regresiva?
                |
                v
        Estado EN_CUENTA_REGRESIVA
                |
                v
          Generar alerta base
                |
                v
        Estado PENDIENTE_ENVIO
                |
                v
          Asignar folio
                |
                v
        Obtener telemetría
                |
                v
        Emitir paquete BLE
                |
                v
       Persistir si es necesario
                |
                v
       Sincronizar con servidor
                |
                v
       Actualizar estados E01
                |
                v
       Actualizar historial
                |
                v
       ¿Se desactivó alerta?
                |
          +-----+-----+
          |           |
         NO           SÍ
          |           |
          v           v
      Continuar    Detener procesos
      monitoreo    correspondientes
```

---

## 15. Lista de comprobación de integración

Antes de considerar conectado el Módulo 2 con el frontend y los demás módulos, verificar los siguientes puntos:

### Configuración y permisos

- [ ] Todos los archivos Kotlin están dentro del paquete correcto.
- [ ] Se agregó `PersistenciaOfflineModulo2.kt`.
- [ ] Se agregaron las dependencias necesarias.
- [ ] Se declararon los permisos en `AndroidManifest.xml`.
- [ ] Se declaró `ACCESS_BACKGROUND_LOCATION` cuando corresponda.
- [ ] Se declaró `NEARBY_WIFI_DEVICES` cuando se utilice Wi-Fi Direct.
- [ ] Se configuró `BLUETOOTH_SCAN` con `neverForLocation` cuando corresponda.
- [ ] Se implementó la solicitud de permisos en tiempo de ejecución.
- [ ] Se verificó el permiso de micrófono antes de iniciar el tipo de servicio `MICROPHONE`.

### Motor de emergencias

- [ ] Se creó una instancia única de `ModuloEmergencias`.
- [ ] Se inyectó el identificador real del usuario.
- [ ] Se conectó el botón SOS.
- [ ] Se conectó la cuenta regresiva de la pantalla A-05.
- [ ] Se verificó la generación del folio de alerta.
- [ ] Se conectó el reconocimiento de voz.
- [ ] Se verificó la transcripción de voz.
- [ ] Se verificó la clasificación local IA-1.
- [ ] Se conectó la detección de movimiento brusco.
- [ ] Se conectó el atajo físico de cinco presiones cuando el dispositivo lo permita.
- [ ] Se conectó el widget SOS.

### Caminata Segura y telemetría

- [ ] Se conectó el modo Caminata Segura.
- [ ] Se verificó el check-in.
- [ ] Se verificó el cálculo de desviación punto-a-segmento.
- [ ] Se conectó la caché vial local.
- [ ] Se probó el cálculo Dijkstra offline.
- [ ] Se verificó la ruta segura recomendada.
- [ ] Se verificó la ruta más corta.
- [ ] Se verificó el intervalo GNSS de 10,000 ms durante una alerta activa.
- [ ] Se verificó la fuente de ubicación `FuenteUbicacion`.

### Persistencia y malla

- [ ] Se verificaron las tablas `cola_alerta` y `paquete_visto`.
- [ ] Se probó `encolarPendienteConVigencia(...)`.
- [ ] Se probó `registrarPaqueteMallaVisto(...)`.
- [ ] Se probó la recuperación de `pendientesCola`.
- [ ] Se probó la transmisión y recepción de paquetes BLE.
- [ ] Se verificó el cifrado AES-GCM.
- [ ] Se verificó la firma HMAC-SHA256.
- [ ] Se verificó el control de TTL y `saltosRecorridos`.
- [ ] Se probó la compatibilidad legacy.
- [ ] Se probó el Modo Operador Ad-Hoc.
- [ ] Se probó la emisión de paquetes ACK.

### Integración con Backend y Frontend

- [ ] Se conectaron los flujos StateFlow y SharedFlow.
- [ ] Se conectó el historial de eventos de alerta.
- [ ] Se conectó la sincronización de la unidad asignada.
- [ ] Se verificó el contrato JSON con el Backend 4.
- [ ] Se verificó la sincronización de estados E01.
- [ ] Se conectaron las pantallas A-04, A-05, A-06, A-07, A-08, A-12, A-14, A-18 y A-19.
- [ ] Se verificó el comportamiento sin conexión a Internet.
- [ ] Se comprobó que la cancelación detenga los procesos correspondientes.
- [ ] Se revisó el comportamiento con la pantalla apagada.
- [ ] Se realizaron pruebas en diferentes dispositivos Android.

---

## 16. Conclusión

El Módulo 2 de Sendero Seguro centraliza las funciones de emergencia en `ModuloEmergencias`, permitiendo que los distintos gestores trabajen de manera coordinada.

La arquitectura facilita la integración con los demás módulos del proyecto, especialmente el frontend, la configuración de seguridad, la persistencia local, la comunicación con el servidor y el almacenamiento offline de telemetría.

Para el Entregable 3, el motor incorpora:

- Activación de alertas mediante diferentes detonadores.
- Cuenta regresiva previa al envío de la alerta.
- Transcripción de voz y clasificación local mediante IA-1.
- Persistencia offline mediante SQLite ER-06.
- Cifrado AES-GCM y firma HMAC-SHA256 en la malla BLE.
- Modo Operador Ad-Hoc para unidades de campo sin Internet.
- Emisión de paquetes ACK.
- Cálculo de rutas seguras offline mediante Dijkstra ponderado.
- Cálculo geométrico de desviación mediante distancia punto-a-segmento.
- Telemetría con identificación de la fuente de ubicación.
- Actualización periódica GNSS durante una alerta activa.
- Máquina de estados E01 e historial de eventos.
- Sincronización de la unidad asignada y seguimiento de atención.
- Contratos de integración para Backend 1, Backend 3, Backend 4 y los equipos Frontend.

La interfaz únicamente necesita comunicarse con las funciones públicas del motor, modificar parámetros y observar los flujos de información.

**Nota final:** esta implementación constituye una base de integración para el desarrollo y las pruebas del Entregable 3. Antes de utilizarla como sistema real de emergencia, es necesario validar el comportamiento en diferentes dispositivos, comprobar las restricciones de Android, verificar los mecanismos criptográficos, asegurar la persistencia y sincronización de datos y probar los flujos de atención de extremo a extremo. La disponibilidad de BLE, GNSS, reconocimiento de voz y servicios en segundo plano depende del dispositivo, del sistema operativo y de los permisos concedidos.
