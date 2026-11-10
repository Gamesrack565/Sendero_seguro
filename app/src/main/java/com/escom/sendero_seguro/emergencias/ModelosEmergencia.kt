package com.escom.sendero_seguro.emergencias

import java.util.UUID

// E3 Diccionario de Datos + División de Trabajo: Detonadores de emergencia
enum class TipoDetonador {
    BOTON_SOS,          // Equivalente a BOTON en E3
    ATAJO_FISICO,       // 5 presiones del botón de encendido (A-04 / A-12)
    WIDGET,             // Botón rojo en pantalla de inicio (A-04 / A-12)
    COMANDO_VOZ,
    CAIDA_IMPACTO,
    VENCIMIENTO_CAMINATA_SEGURA
}

// E3 Diagrama E-01: Máquina de estados oficial de la alerta
enum class EstadoAlertaE01 {
    CREADA,
    EN_CUENTA_REGRESIVA,
    PENDIENTE_ENVIO,
    EXPIRADA,
    ENVIADA,
    ASIGNADA,
    ESCALADA,
    ACUSADA,
    EN_ATENCION,
    ATENDIDA,
    CERRADA,
    CANCELADA
}

// E3 Diccionario de Datos: Fuente de lectura de ubicación
enum class FuenteUbicacion {
    GNSS,
    WIFI,
    CELULAR,
    ULTIMA_CONOCIDA
}

// E3 Diccionario de Datos: Canal por el que se transmite/recibe la alerta
enum class CanalRecepcion {
    INTERNET,
    MALLA,
    SMS,
    COLA_LOCAL
}

// P4 (IA-1): Desglose extraído del relato de voz (A-06) y clasificación TFLite
data class ResultadoClasificacionIA1(
    val tipoSugeridoId: Int,
    val nombreTipoSugerido: String,
    val categoria: String, // FISICO, SOCIAL, DIGITAL
    val prioridadCatalogo: Int,
    val confianza: Float,  // 0.0 a 1.0 (Umbral oficial: 0.70)
    val superaUmbral: Boolean,
    val que: String,
    val donde: String,
    val quien: String,
    val cuando: String,
    val latenciaMs: Long
)

// P4 y P6: Estado e información completa de la Alerta de Emergencia
data class AlertaEmergencia(
    val idAlerta: String = UUID.randomUUID().toString(),
    val folio: String = "ALR-2026-${(100000..999999).random()}",
    val detonador: TipoDetonador,
    val estadoActual: EstadoAlertaE01 = EstadoAlertaE01.CREADA,
    val tipoIncidenteId: Int = 0, // 0 = Genérica (por defecto en envío base P4)
    val nombreTipoIncidente: String = "Genérica",
    val esGenerica: Boolean = true,
    val descripcionRelato: String = "",
    val clasificacionIA1: ResultadoClasificacionIA1? = null,
    val latitudInicial: Double,
    val longitudInicial: Double,
    val precisionMetros: Float,
    val nivelBateria: Int,
    val canalRecepcion: CanalRecepcion = CanalRecepcion.INTERNET,
    val saltosMalla: Int = 0,
    val timestampInicio: Long = System.currentTimeMillis(),
    val activa: Boolean = true,
    val enModoCoaccionSilencioso: Boolean = false
)

// P6: Registro inmutable para la línea de tiempo (tabla evento_alerta en E3)
data class EventoHistorialAlerta(
    val idEvento: Long = System.currentTimeMillis(),
    val alertaId: String,
    val estadoAnterior: EstadoAlertaE01?,
    val estadoNuevo: EstadoAlertaE01,
    val ocurridoEnMs: Long = System.currentTimeMillis(),
    val detalle: String = ""
)

// P6: Estado sincronizado para la pantalla A-07 (Emisora) y A-13 (Red de apoyo)
data class EstadoSeguimientoAtencion(
    val alertaId: String,
    val folio: String,
    val estadoActual: EstadoAlertaE01,
    val unidadAsignadaClave: String? = null, // Ej. "P-214"
    val corporacionNombre: String? = null,   // Ej. "Seguridad IPN"
    val distanciaUnidadMetros: Double? = null,
    val horaAcuseMs: Long? = null,
    val contactosNotificados: Int = 0,
    val totalContactos: Int = 3,
    val circuloNotificado: String? = null    // Ej. "6IV1"
)

// P5.1 (X-02): Paquete P2P de la red malla con cabecera en claro, carga cifrada y firma
data class PaqueteMalla(
    val versionFormato: Int = 1,
    val packetId: String = UUID.randomUUID().toString(),
    val alertaId: String = UUID.randomUUID().toString(),
    val folioCorto: String = "ALR-004817",
    val emisorId: String,
    val nombreEmisor: String = "Integrante IPN",
    val matriculaEmisor: String = "2023630417",
    val telefonoEmisor: String = "5512345678",
    val tipoIncidenteNombre: String = "Genérica",
    val descripcionCorta: String = "Alerta SOS en curso",
    val prioridad: Int = 1,
    val latitud: Double,
    val longitud: Double,
    val precisionMetros: Float = 8.0f,
    val nivelBateria: Int,
    val ttl: Int = 5,            // Nace en 5, cada nodo resta 1
    val saltosRecorridos: Int = 0, // Nace en 0, cada nodo suma 1
    val esAcuseOperador: Boolean = false,
    val unidadAcuseId: String? = null,
    val cargaCifradaBase64: String = "",
    val firmaHmacHex: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

// P5.1 (A-14): Modelo para la vista Modo Operador Ad-Hoc sin internet
data class AlertaAdHocOperadorVista(
    val paquete: PaqueteMalla,
    val distanciaMetros: Double,
    val rumboGrados: Double,
    val rumboCardinal: String, // Ej. "al noreste"
    val textoDistanciaYRumbo: String, // Ej. "180 m al noreste (±8 m)"
    val acusadaLocalmente: Boolean = false
)

// P5.1 y Frontend 2 (A-19): Estado de conectividad y cola de retransmisión
data class EstadoRedYMalla(
    val conectadoAInternet: Boolean = false,
    val escaneoBleActivo: Boolean = false,
    val modoEscaneoBateria: String = "NORMAL (cada 2 min)",
    val cantidadPaquetesRetransmitidos: Int = 0,
    val historialRecienteMalla: List<PaqueteMalla> = emptyList()
)

// P6: Flujo continuo de Monitoreo y Telemetría en Tiempo Real (tabla ubicacion_alerta)
data class DatosTelemetria(
    val latitud: Double,
    val longitud: Double,
    val precision: Float,
    val velocidadMps: Float,
    val rumboGrados: Float,
    val aceleracionG: Float,
    val nivelBateria: Int,
    val fuente: FuenteUbicacion = FuenteUbicacion.GNSS,
    val timestamp: Long = System.currentTimeMillis()
)

// P7: Coordenada y nodo para Caminata Segura y Grafo Peatonal Offline
data class PuntoRuta(
    val latitud: Double,
    val longitud: Double,
    val idNodoOsm: Long = 0L
)

// P7 (E3 A-18): Arista del grafo vial local con riesgo normalizado (riesgo_tramo)
data class AristaVialOffline(
    val idArista: Long,
    val nodoOrigen: PuntoRuta,
    val nodoDestino: PuntoRuta,
    val nombreVia: String,
    val longitudMetros: Double,
    val riesgoNormalizado: Double, // 0.0 a 1.0 (según capa IA-2 v42)
    val bienIluminada: Boolean = true,
    val zonaRiesgoNombre: String? = null
)

// P7 (E3 A-18): Resultado de cálculo de ruta peatonal
data class OpcionRutaCalculada(
    val nombreRuta: String, // "Ruta segura RECOMENDADA" vs "Más corta"
    val esRecomendada: Boolean,
    val nodos: List<PuntoRuta>,
    val distanciaTotalKm: Double,
    val tiempoEstimadoMin: Int,
    val puntajeRiesgoPromedio: Double,
    val advertenciasZonasRiesgo: List<String>, // Ej. "Cruza Salida metro Politécnico (riesgo ALTO, 21-24 h)"
    val calculadaOffline: Boolean = true
)

data class ComparativaRutasP7(
    val rutaSeguraRecomendada: OpcionRutaCalculada,
    val rutaMasCorta: OpcionRutaCalculada,
    val versionCapaRiesgo: Int = 42
)

// P7 (División de trabajo): Estado del Modo Acompañamiento ("Caminata Segura")
data class EstadoCaminataSegura(
    val activa: Boolean = false,
    val rutaObjetivo: List<PuntoRuta> = emptyList(),
    val tiempoEstimadoLlegadaMs: Long = 0L,
    val segundosRestantesEta: Long = 0L,
    val segundosRestantesCheckIn: Long = 0L,
    val desviacionActualMetros: Double = 0.0,
    val distanciaAlDestinoMetros: Double = 0.0,
    val fueraDeRuta: Boolean = false,
    val destinoAlcanzado: Boolean = false
)