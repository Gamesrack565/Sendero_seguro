# Sendero Seguro — Integración del Módulo 2: Emergencias

## 1. Descripción general

El Módulo 2 de **Sendero Seguro** implementa el sistema de detección, gestión y transmisión de alertas de emergencia.

Su objetivo es permitir que una alerta se active mediante diferentes detonadores y que, una vez activada, se recopile información del dispositivo y se transmita a otros nodos cuando sea posible.

El módulo está desarrollado en Kotlin para Android y utiliza sensores del dispositivo, GPS, Bluetooth BLE, reconocimiento de voz y servicios en primer plano.

### Funcionalidades implementadas

| Funcionalidad | Clase responsable |
|---|---|
| Gestión centralizada de emergencias | `ModuloEmergencias` |
| Detección de comandos de voz | `GestorVozPanico` |
| Detección de caídas e impactos | `GestorTelemetria` |
| Obtención de GPS y batería | `GestorTelemetria` |
| Transmisión y recepción mediante BLE | `GestorMalla` |
| Retransmisión de alertas entre dispositivos | `GestorMalla` |
| Seguimiento de una ruta segura | `GestorCaminataSegura` |
| Temporizador de check-in | `GestorCaminataSegura` |
| Servicio en primer plano | `ServicioEmergencias` |
| Modelos de información | `ModelosEmergencia` |

---

## 2. Arquitectura del módulo

La clase principal es `ModuloEmergencias`. Funciona como coordinador de las demás clases.

No es necesario que la interfaz gráfica gestione individualmente cada sensor o cada proceso. En su lugar, debe comunicarse con esta clase para iniciar funciones, consultar estados y recibir eventos.

```text
                    FRONTEND ANDROID
                           |
                           |
                    ModuloEmergencias
                           |
          +----------------+----------------+
          |                |                |
          v                v                v
    GestorVozPanico  GestorTelemetria  GestorCaminataSegura
          |                |                |
          |                |                |
          v                v                v
     Comandos de       GPS, batería,      Ruta y
        voz            acelerómetro      Check-in
                           |
                           v
                      GestorMalla
                           |
                    Bluetooth BLE
                           |
                    Otros dispositivos
                           |
                     Servidor Linux

                 ServicioEmergencias
                 Mantiene el servicio
                 Android en primer plano
```

### Comunicación entre las clases

`ModuloEmergencias` crea las instancias de los gestores y conecta sus eventos mediante callbacks.

Por ejemplo:

- Cuando `GestorVozPanico` reconoce una palabra clave, notifica a `ModuloEmergencias`.
- Cuando `GestorTelemetria` detecta un impacto, notifica a `ModuloEmergencias`.
- Cuando vence el temporizador de `GestorCaminataSegura`, se solicita una alerta.
- Cuando se activa una alerta, `ModuloEmergencias` obtiene la telemetría inicial, genera el objeto `AlertaEmergencia` y solicita su transmisión mediante `GestorMalla`.

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
```

Se deben conservar los modelos de datos utilizados por los gestores, ya que las clases intercambian información mediante objetos como `AlertaEmergencia`, `DatosTelemetria`, `PaqueteMalla` y `EstadoCaminataSegura`.

### Dependencias

El proyecto necesita las bibliotecas de AndroidX y Kotlin Coroutines utilizadas por el código.

Por ejemplo, en el archivo `build.gradle.kts` del módulo:

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:<version>")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:<version>")
}
```

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

<uses-permission android:name="android.permission.WAKE_LOCK" />

<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### Permisos Bluetooth

Para Android 12 o superior:

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
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

### Permiso adicional para reconocimiento de voz en segundo plano

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

**Importante:** el código actual de `ServicioEmergencias` declara los tipos `LOCATION` y `CONNECTED_DEVICE` al ejecutar `startForeground()`. Si también se va a mantener el micrófono mediante ese servicio, se debe agregar `FOREGROUND_SERVICE_TYPE_MICROPHONE` en Android 14 o superior y solicitar los permisos necesarios.

El permiso de micrófono debe concederse antes de iniciar el reconocimiento. Android también impone restricciones para iniciar servicios de micrófono desde segundo plano; normalmente debe iniciarse mientras la aplicación está visible.

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
- Bluetooth Scan, Advertise y Connect en Android 12 o superior.
- Notificaciones en Android 13 o superior.

No se deben iniciar los gestores que necesitan un permiso hasta que este haya sido concedido.

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

### Iniciar la protección

Una vez concedidos los permisos:

```kotlin
moduloEmergencias.iniciarProteccionSegundoPlano()
```

Este método realiza las siguientes acciones:

1. Inicia `ServicioEmergencias`.
2. Activa la vigilancia del acelerómetro.
3. Solicita que `GestorMalla` inicie el escaneo BLE.

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

**Limitación actual:** `iniciarProteccionSegundoPlano()` no llama a `escucharComandoVozMicrofono()`. Por lo tanto, iniciar el servicio no activa automáticamente el micrófono. Además, el reconocedor está alojado actualmente en `ModuloEmergencias`, no dentro de `ServicioEmergencias`. Para que la escucha pueda administrarse de forma consistente después de cerrar la Activity, se recomienda trasladar la propiedad del motor de voz a un componente de larga duración, como el servicio en primer plano, y evitar crear dos instancias independientes de `ModuloEmergencias`.

El reconocimiento de Android depende del servicio de voz instalado y de sus idiomas disponibles. En algunos dispositivos puede no funcionar, incluso si el micrófono tiene permiso. La escucha continua tampoco está garantizada por Android. Por eso, el botón SOS debe mantenerse disponible como alternativa.

---

## 6. Activación de una alerta SOS

Existen diferentes detonadores definidos en `TipoDetonador`:

```kotlin
enum class TipoDetonador {
    BOTON_SOS,
    COMANDO_VOZ,
    CAIDA_IMPACTO,
    VENCIMIENTO_CAMINATA_SEGURA
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

### Activación por voz

No es necesario que la interfaz active manualmente la alerta cuando se reconoce una palabra clave. El callback ya está conectado en `ModuloEmergencias`:

```kotlin
onDetonadorVozActivado = { motivo ->
    activarAlertaEmergencia(TipoDetonador.COMANDO_VOZ)
}
```

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

Se incluye un intervalo de protección de dos segundos para evitar que varias lecturas consecutivas generen múltiples alertas.

### Desactivar una alerta

Para cancelar normalmente una alerta:

```kotlin
moduloEmergencias.desactivarAlertaEmergencia()
```

Esto detiene el hilo de telemetría, las actualizaciones GPS y la transmisión BLE, y elimina el estado de alerta activa.

El parámetro:

```kotlin
moduloEmergencias.desactivarAlertaEmergencia(
    simulacionCoaccion = true
)
```

activa la simulación del modo de coacción P8. En la implementación actual, conserva el estado de la alerta y registra el modo silencioso, pero no implementa por sí solo toda la lógica de un sistema de coacción completo.

---

## 7. Configuración de parámetros de seguridad

La función `actualizarParametrosSeguridad()` permite conectar los parámetros configurados desde el Módulo 1.

Ejemplo:

```kotlin
moduloEmergencias.actualizarParametrosSeguridad(
    nuevasPalabrasVoz = listOf(
        "ayuda",
        "auxilio",
        "emergencia",
        "sendero seguro"
    ),
    sensibilidadCaidaG = 2.8f,
    intervaloCheckInSegundos = 60L
)
```

Los parámetros modificados son:

| Parámetro | Descripción |
|---|---|
| `nuevasPalabrasVoz` | Palabras que pueden activar el SOS |
| `sensibilidadCaidaG` | Umbral de aceleración para detectar impactos |
| `intervaloCheckInSegundos` | Tiempo máximo entre confirmaciones de presencia |

La lista de palabras clave se normaliza para facilitar la comparación del texto reconocido.

---

## 8. Integración de Caminata Segura

`GestorCaminataSegura` implementa el modo de acompañamiento durante un recorrido.

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

La clase ya está conectada con `ModuloEmergencias`.

Cuando vence el check-in:

```kotlin
TipoDetonador.VENCIMIENTO_CAMINATA_SEGURA
```

se solicita una alerta automáticamente.

Cuando el usuario llega a menos de 20 metros del último punto de la ruta, el gestor finaliza la caminata y ejecuta el callback de llegada exitosa.

La desviación se calcula mediante la distancia entre la ubicación actual y los puntos de la ruta. Para recorridos largos conviene mejorar el cálculo utilizando segmentos de ruta y no únicamente los puntos individuales.

---

## 9. Integración de telemetría

`GestorTelemetria` obtiene información del dispositivo y mantiene un flujo actualizado de datos.

La estructura `DatosTelemetria` contiene:

| Campo | Descripción |
|---|---|
| `latitud` | Latitud GPS |
| `longitud` | Longitud GPS |
| `precision` | Precisión de la ubicación |
| `velocidadMps` | Velocidad en metros por segundo |
| `rumboGrados` | Dirección de desplazamiento |
| `aceleracionG` | Magnitud de aceleración expresada en G |
| `nivelBateria` | Porcentaje de batería |
| `timestamp` | Momento de la lectura |

### Iniciar telemetría

```kotlin
moduloEmergencias.gestorTelemetria.iniciarTelemetriaTiempoReal(
    intervaloMs = 3000L
)
```

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
```

### Simular una ubicación para pruebas

```kotlin
moduloEmergencias.gestorTelemetria.simularUbicacionParaPruebas(
    latitud = 19.5045,
    longitud = -99.1469
)
```

Esta función permite probar la lógica de ubicación sin desplazarse físicamente.

**Advertencia:** actualmente, si no se obtiene una ubicación GPS o de red, el código utiliza las coordenadas de ESCOM como valor predeterminado. Esto es útil para algunas pruebas, pero debe sustituirse antes de utilizar la aplicación en situaciones reales. Una coordenada de prueba nunca debe mostrarse como si fuera la ubicación real del usuario.

---

## 10. Integración de la malla Bluetooth BLE

`GestorMalla` permite transmitir y recibir paquetes de emergencia utilizando Bluetooth Low Energy.

Su funcionamiento se divide en tres partes:

1. Escaneo de anuncios BLE.
2. Transmisión de paquetes de emergencia.
3. Retransmisión de paquetes recibidos.

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

Se genera un `PaqueteMalla` que contiene:

- Identificador del paquete.
- Identificador del emisor.
- Coordenadas.
- Nivel de batería.
- TTL o tiempo de vida del paquete.

### Retransmisión de paquetes

Cuando un dispositivo recibe un paquete BLE válido, ejecuta:

```kotlin
interceptarYRetransmitir(paqueteEntrante)
```

La función comprueba si el paquete ya fue procesado y reduce el TTL antes de retransmitirlo.

Esto permite propagar alertas entre dispositivos cercanos sin retransmitir indefinidamente el mismo paquete.

### Configurar el servidor Linux

La dirección del servidor se establece en:

```kotlin
moduloEmergencias.gestorMalla.urlServidorLinux =
    "http://DIRECCION_IP:8080/alerta"
```

El servidor debe estar accesible desde la red del dispositivo y aceptar solicitudes HTTP POST con contenido JSON.

La función `enviarAServidorLinux()` se ejecuta durante la retransmisión de un paquete recibido. En la implementación actual, la emisión inicial de una alerta no realiza automáticamente ese envío HTTP.

---

## 11. Conectar la interfaz mediante StateFlow y SharedFlow

Los gestores exponen flujos de datos para que la interfaz pueda observar los cambios sin consultar manualmente cada clase.

Los principales flujos disponibles desde `ModuloEmergencias` son:

| Flujo | Tipo | Información |
|---|---|---|
| `alertaActiva` | StateFlow | Alerta actual |
| `flujoTelemetria` | StateFlow | Telemetría actual |
| `estadoCaminata` | StateFlow | Estado de la caminata |
| `estadoRedYMalla` | StateFlow | Conectividad y estado BLE |
| `flujoPaquetesMalla` | SharedFlow | Paquetes emitidos o retransmitidos |
| `colaTelemetriaOfflineP11` | SharedFlow | Lecturas para almacenamiento offline |

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
```

Imports:

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
```

La interfaz puede utilizar estos valores para actualizar indicadores, mostrar la ubicación, informar si existe una alerta y mostrar el estado de la malla BLE.

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

Sus responsabilidades actuales son:

- Crear el canal de notificaciones.
- Mostrar una notificación de protección activa.
- Adquirir un `PARTIAL_WAKE_LOCK`.
- Solicitar que Android mantenga el servicio en primer plano.
- Liberar el WakeLock cuando se destruye el servicio.

El servicio utiliza `START_STICKY`, que permite a Android intentar recrearlo si el proceso es terminado por el sistema. Esto no garantiza que permanezca activo permanentemente.

**Importante:** el servicio actual no contiene una instancia de `ModuloEmergencias` ni inicia directamente `GestorVozPanico`. Para tener una arquitectura robusta, se recomienda que el servicio sea responsable del ciclo de vida del motor de protección y que la interfaz se comunique con él, en lugar de mantener los gestores únicamente dentro de una Activity.

También debe comprobarse que el WakeLock se libere correctamente y que se maneje su expiración, ya que actualmente se adquiere con una duración máxima de una hora.

---

## 13. Consideraciones importantes antes de utilizar el módulo

El módulo cuenta con varias funcionalidades implementadas, pero todavía existen aspectos que deben revisarse antes de considerarlo un sistema de protección confiable.

### Reconocimiento de voz

`SpeechRecognizer` depende del servicio de reconocimiento instalado en el dispositivo.

En las pruebas realizadas se observó que algunos dispositivos con servicios de Google reconocen correctamente las palabras clave, mientras que otros presentan errores de idioma o de red.

El error de reconocimiento 2 corresponde a un problema de red. El error 12 indica que el idioma solicitado no es compatible con el reconocedor seleccionado.

No se debe asumir que todos los dispositivos Android pueden mantener una escucha continua, especialmente con la pantalla apagada.

### Servicio en primer plano

Para utilizar ubicación y micrófono en segundo plano se deben declarar los tipos de servicio adecuados y cumplir las restricciones de Android.

Un WakeLock no garantiza que Android mantenga todos los procesos activos ni evita que el fabricante aplique restricciones adicionales de batería.

### Telemetría y detección de caídas

El umbral de aceleración debe calibrarse con pruebas controladas. Un impacto no necesariamente significa que ocurrió una caída.

También es recomendable agregar mecanismos para evitar múltiples detecciones consecutivas.

### Ubicación

No se debe enviar una ubicación de prueba como si fuera una ubicación GPS real. Se debe comprobar la precisión y disponibilidad de la coordenada antes de utilizarla en una alerta.

### Bluetooth BLE

El alcance de BLE depende del hardware, del entorno y de las condiciones de radio. La recepción de un anuncio no garantiza que el paquete haya llegado a un servidor o a un servicio de emergencia.

La implementación actual tampoco incluye autenticación ni cifrado de los paquetes BLE. Antes de utilizarla en producción, deben incorporarse mecanismos de integridad, autenticación y protección de datos.

### Modo de coacción

La implementación de `simulacionCoaccion` es parcial. Debe completarse y probarse cuidadosamente antes de presentarse como una funcionalidad operativa.

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
Iniciar protección
          |
          +----------------------+
          |                      |
          v                      v
    Vigilancia de          Escaneo BLE
     acelerómetro
          |
          v
 Activar reconocimiento
      de voz
          |
          v
¿Se detectó un detonador?
          |
     +----+----+
     |         |
    NO        SÍ
     |         |
     v         v
Continuar   Activar alerta SOS
monitoreo       |
                v
        Obtener telemetría
                |
                v
          Crear alerta
                |
                v
        Emitir paquete BLE
                |
                v
        Iniciar telemetría
                |
                v
       Actualizar información
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

Antes de considerar conectado el Módulo 2 con el frontend, verificar los siguientes puntos:

- [ ] Todos los archivos Kotlin están dentro del paquete correcto.
- [ ] Se agregaron las dependencias necesarias.
- [ ] Se declararon los permisos en `AndroidManifest.xml`.
- [ ] Se implementó la solicitud de permisos en tiempo de ejecución.
- [ ] Se creó una instancia única de `ModuloEmergencias`.
- [ ] Se conectó el botón SOS.
- [ ] Se inició la vigilancia del acelerómetro.
- [ ] Se conectó el reconocimiento de voz.
- [ ] Se conectó el servicio en primer plano.
- [ ] Se habilitó el escaneo BLE.
- [ ] Se conectó la pantalla de telemetría mediante StateFlow.
- [ ] Se conectó el modo Caminata Segura.
- [ ] Se probó la transmisión y recepción de paquetes BLE.
- [ ] Se verificó el comportamiento sin conexión a Internet.
- [ ] Se revisó el comportamiento con la pantalla apagada.
- [ ] Se comprobó que la cancelación detenga los procesos correspondientes.

---

## 16. Conclusión

El Módulo 2 de Sendero Seguro centraliza las funciones de emergencia en `ModuloEmergencias`, permitiendo que los distintos gestores trabajen de manera coordinada.

La interfaz únicamente necesita comunicarse con esta clase para activar las funciones, modificar parámetros y observar los flujos de información.

La arquitectura facilita la integración con los demás módulos del proyecto, especialmente el frontend, la configuración de seguridad y el almacenamiento local de telemetría.

**Nota:** esta implementación constituye una base funcional para pruebas y desarrollo. Antes de utilizarla como sistema real de emergencia, es necesario completar la integración del micrófono con el servicio en primer plano, validar permisos y restricciones de Android, asegurar las comunicaciones y probar el comportamiento en diferentes dispositivos.
