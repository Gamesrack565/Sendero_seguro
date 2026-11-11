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
| Paquete BLE de 24 bytes con ids cortos | `FormatoPaqueteMalla` |
| Entrega al servidor de lo que llega por la malla | `GestorMalla` |
| Registro de la alerta y ubicación en vivo en el servidor | `ModuloEmergencias`, `remoto/` |
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
              Bluetooth BLE             Servidor (Módulo 4)
                   |
                   v
             Otros dispositivos
                   |
                   v
          Modo Operador Ad-Hoc
          Paquete de 24 bytes
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
| `GestorMalla.kt` | Comunicación BLE, retransmisión, entrega al servidor (`POST /malla/relevos`) y Modo Operador Ad-Hoc. |
| `FormatoPaqueteMalla.kt` | Bytes del paquete que viaja en el anuncio BLE (24 bytes) y JSON del relevo al servidor. |
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
5. Entrega al servidor de lo que llega por la malla.
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

### Paquete en el anuncio BLE

Un anuncio BLE "legacy" deja 24 bytes para los datos del fabricante. `FormatoPaqueteMalla` arma el paquete así:

| Bytes | Campo |
|---|---|
| 0-1 | `S` `S` (marca de Sendero Seguro) |
| 2-5 | Id corto de la alerta: primeros 8 hex de su UUID (el mismo que lleva el SMS) |
| 6-9 | Latitud (float) |
| 10-13 | Longitud (float) |
| 14 | Batería (%) |
| 15 | TTL (nace en 5); el bit alto marca un acuse del operador |
| 16-19 | Id corto de la persona: últimos 8 hex del UUID de su cuenta |
| 20-23 | Hora de activación (segundos desde 1970) |

Los saltos no viajan en el paquete: son `5 - TTL`. Un paquete de 16 o 17 bytes es del formato anterior y se sigue leyendo.

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
2. Consultar si el paquete ya fue procesado.
3. Registrar el identificador en la persistencia de paquetes vistos.
4. Actualizar `saltosRecorridos` y reducir `ttl`.
5. Si el teléfono tiene sesión e internet, entregar el paquete al servidor, también cuando ya no le quedan saltos (A-01).
6. Retransmitir el paquete si el TTL lo permite.

El registro de paquetes procesados se realiza mediante:

```kotlin
registrarPaqueteMallaVisto(...)
```

Esto permite reducir retransmisiones duplicadas y evitar ciclos innecesarios.

### Entrega al servidor

`urlServidorLinux` apunta por omisión a `API_BASE_URL + "malla/relevos"`, y `tokenServidor` lo conecta `SenderoApp` con la sesión del Módulo 1:

```kotlin
moduloEmergencias.gestorMalla.tokenServidor = { identidad.almacen.leerToken()?.token }
```

`enviarAServidorLinux(paquete)` manda a `POST /api/v1/malla/relevos` los ids cortos, la ubicación, la batería, los saltos y la hora de activación. El servidor reconoce a la persona por su id corto, registra la alerta con `canal_recepcion = MALLA` y la despacha. Si el mismo paquete llega por varios teléfonos, se guarda una sola vez. Sin sesión no se manda nada.

### Conexión con el servidor

| Qué | Cómo |
|---|---|
| Registro de la alerta (P4) | `ModuloEmergencias.activarAlertaEmergencia` la manda a `POST /alertas` en cuanto hay internet, sin detener la activación. Reintenta 3 veces y la alerta toma el folio del servidor. |
| Ubicación en vivo (P6) | Cada lectura del GPS va a `POST /alertas/{id}/ubicaciones`. Sin señal se juntan hasta 500 y se mandan en grupos de 100 al volver internet; si la alerta se activó sin señal, primero se manda la alerta. |
| Alerta por la malla (RF-14) | El teléfono con internet que recibe el paquete lo entrega a `POST /malla/relevos`. |
| Cancelación (P8) | La hace el Módulo 1. `SenderoApp` detiene la alerta con `desactivarAlertaEmergencia(false)` cuando llega `AlertaCancelada`. |
| PIN de coacción (P8) | `SenderoApp` llama a `activarModoCoaccionSilencioso()`: la alerta sigue con GPS, ubicaciones y malla. Si estaba en la cuenta regresiva, se activa en ese momento. |

La base SQLite del Módulo 2 es `sendero_seguro_malla.db`, separada de la base Room del Módulo 1 (`sendero_seguro_local.db`). Las rutas del servidor están en [docs/modulo4/README.md](../modulo4/README.md).

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

`enviarAServidorLinux(paquete)` manda el paquete a `POST /api/v1/malla/relevos` (`urlServidorLinux`, por omisión `API_BASE_URL + "malla/relevos"`) con el token de la sesión del Módulo 1. El cuerpo es el que arma `FormatoPaqueteMalla.relevoJson`:

```json
{
  "idCortoAlerta": "1a2b3c4d",
  "idCortoUsuario": "9f8e7d6c",
  "latitud": 19.5045,
  "longitud": -99.1469,
  "nivelBateria": 78,
  "ttl": 4,
  "saltosMalla": 1,
  "activadaEn": "2026-11-10T22:15:03Z"
}
```

El servidor responde `201` la primera vez y `200` si la alerta ya estaba registrada (por internet, por SMS o por otro teléfono de la malla). El detalle está en la sección "Registro de alertas" de [docs/modulo4/README.md](../modulo4/README.md).

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

### Bluetooth BLE

El paquete de 24 bytes lleva ids cortos, no datos personales: el servidor reconoce a la persona por el id corto de su cuenta. Se descartan paquetes repetidos (`paquete_visto`) y el servidor rechaza paquetes de más de 24 horas. El TTL limita la propagación.

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
      de voz                       y leer el paquete
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
- [ ] Se verificó el paquete de 24 bytes (`RelevoMallaTest`).
- [ ] Se verificó la entrega al servidor (`POST /malla/relevos`).
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
- Paquete BLE de 24 bytes y entrega al servidor de lo que llega por la malla.
- Registro de la alerta y ubicación en vivo en el servidor del Módulo 4.
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
