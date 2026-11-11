package com.senderoseguro.server.trayectos

import com.senderoseguro.server.cuentas.errorValidacion
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.dobleONulo
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import com.senderoseguro.server.sincronizacion.ManejadorSync
import com.senderoseguro.server.sincronizacion.ResultadoManejo
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** Inicio de un trayecto (Caminata segura, P7). El `id` lo genera el teléfono. */
@Serializable
data class TrayectoEntrada(
    val id: String,
    val tipoRuta: String? = null,
    val origenLatitud: Double,
    val origenLongitud: Double,
    val destinoLatitud: Double,
    val destinoLongitud: Double,
    val distanciaM: Int? = null,
    val duracionEstMin: Int? = null,
    val toleranciaDesviacionM: Int? = null,
    val intervaloCheckinMin: Int? = null,
    /** GeoJSON (LineString o Feature) de la ruta que eligió. */
    val rutaPlanificada: JsonObject? = null,
    val iniciadoEn: String,
    /** Avisa a su red de apoyo con app que puede seguir el trayecto. */
    val compartir: Boolean = false,
    // Solo en la cola sin señal: el trayecto puede llegar ya terminado.
    val estado: String? = null,
    val finalizadoEn: String? = null,
    val ultimaLatitud: Double? = null,
    val ultimaLongitud: Double? = null,
    val ultimaUbicacionEn: String? = null,
)

/** Avance o fin del trayecto. */
@Serializable
data class TrayectoCambio(
    val estado: String? = null,
    val latitud: Double? = null,
    val longitud: Double? = null,
    val ubicacionEn: String? = null,
    val finalizadoEn: String? = null,
)

@Serializable
data class CheckinEntrada(val esperadoEn: String, val confirmadoEn: String? = null)

@Serializable
data class CheckinSync(val trayectoId: String, val esperadoEn: String, val confirmadoEn: String? = null)

@Serializable
data class Checkin(val esperadoEn: String, val confirmadoEn: String? = null)

@Serializable
data class Trayecto(
    val id: String,
    val usuarioId: String,
    val tipoRuta: String,
    val estado: String,
    val origenLatitud: Double,
    val origenLongitud: Double,
    val destinoLatitud: Double,
    val destinoLongitud: Double,
    val distanciaM: Int? = null,
    val duracionEstMin: Int? = null,
    val toleranciaDesviacionM: Int? = null,
    val intervaloCheckinMin: Int? = null,
    val rutaPlanificada: JsonObject? = null,
    val ultimaLatitud: Double? = null,
    val ultimaLongitud: Double? = null,
    val ultimaUbicacionEn: String? = null,
    val iniciadoEn: String,
    val finalizadoEn: String? = null,
    val checkins: List<Checkin> = emptyList(),
)

private val ESTADOS_FIN = setOf("FINALIZADO", "CANCELADO")

/**
 * Caminata segura en el servidor (P7, RF-23): el teléfono calcula la ruta y vigila el check-in; el servidor guarda el
 * trayecto, su última ubicación y los check-ins, avisa a la red de apoyo con app si la persona lo comparte, y lo deja
 * en el historial (P13). Si el temporizador del trayecto dispara una alerta, la alerta llega con `trayectoId`.
 *
 * Con internet: `POST /trayectos`, `PATCH /trayectos/{id}`, `POST /trayectos/{id}/checkins`, `GET /trayectos/{id}`.
 * Sin señal: tipos TRAYECTO y CHECKIN de `/sync/lote`, con los mismos datos.
 */
class TrayectosServicio(private val dataSource: DataSource) {

    private val json = Json { ignoreUnknownKeys = true }

    fun iniciar(usuarioId: UUID, e: TrayectoEntrada, ip: String?): Pair<Trayecto, Boolean> = dataSource.transaccion { conn ->
        val (id, nuevo) = guardar(conn, usuarioId, e, ip)
        leer(conn, id)!! to nuevo
    }

    fun cambiar(usuarioId: UUID, idTexto: String, c: TrayectoCambio): Trayecto = dataSource.transaccion { conn ->
        val id = propio(conn, usuarioId, idTexto)
        aplicarCambio(conn, id, c)
        leer(conn, id)!!
    }

    fun checkin(usuarioId: UUID, idTexto: String, c: CheckinEntrada): Trayecto = dataSource.transaccion { conn ->
        val id = propio(conn, usuarioId, idTexto)
        guardarCheckin(conn, id, c.esperadoEn, c.confirmadoEn)
        leer(conn, id)!!
    }

    /** Lo ve su dueño y quien está en su red de apoyo con cuenta (contacto vinculado o integrante de su círculo). */
    fun consultar(usuarioId: UUID, idTexto: String): Trayecto = dataSource.connection.use { conn ->
        val id = runCatching { UUID.fromString(idTexto) }.getOrNull() ?: throw noEncontrado()
        val dueno = conn.consultarUno("SELECT usuario_id FROM trayecto WHERE id = ?", id) { it.uuid(COL_USUARIO) } ?: throw noEncontrado()
        if (dueno != usuarioId && !enRedDeApoyo(conn, dueno, usuarioId)) throw noEncontrado()
        leer(conn, id)!!
    }

    val manejadorTrayecto = ManejadorSync { conn, contexto, elemento ->
        val e = runCatching { json.decodeFromJsonElement<TrayectoEntrada>(elemento.datos) }.getOrNull()
            ?: return@ManejadorSync ResultadoManejo.Rechazado("VALIDACION", "Faltan datos del trayecto (id, origen, destino, iniciadoEn)")
        try {
            val (id, nuevo) = guardar(conn, contexto.usuarioId, e, null)
            // Lo que el teléfono juntó sin señal: la última ubicación y, si ya terminó, el fin.
            aplicarCambio(conn, id, TrayectoCambio(e.estado, e.ultimaLatitud, e.ultimaLongitud, e.ultimaUbicacionEn, e.finalizadoEn), estricto = false)
            if (nuevo) ResultadoManejo.Aceptado(id.toString()) else ResultadoManejo.Duplicado(id.toString())
        } catch (ex: ApiException) {
            ResultadoManejo.Rechazado(ex.codigo, ex.detalle?.toString() ?: ex.message ?: ex.codigo)
        }
    }

    val manejadorCheckin = ManejadorSync { conn, contexto, elemento ->
        val c = runCatching { json.decodeFromJsonElement<CheckinSync>(elemento.datos) }.getOrNull()
            ?: return@ManejadorSync ResultadoManejo.Rechazado("VALIDACION", "Faltan trayectoId o esperadoEn")
        val id = runCatching { UUID.fromString(c.trayectoId) }.getOrNull()
            ?: return@ManejadorSync ResultadoManejo.Rechazado("VALIDACION", "trayectoId no es un UUID")
        val dueno = conn.consultarUno("SELECT usuario_id FROM trayecto WHERE id = ?", id) { it.uuid(COL_USUARIO) }
            ?: return@ManejadorSync ResultadoManejo.Rechazado("NO_ENCONTRADO", "El trayecto todavía no llega", temporal = true)
        if (dueno != contexto.usuarioId) return@ManejadorSync ResultadoManejo.Rechazado("NO_ENCONTRADO", "El trayecto no es tuyo")
        try {
            if (guardarCheckin(conn, id, c.esperadoEn, c.confirmadoEn)) ResultadoManejo.Aceptado(id.toString())
            else ResultadoManejo.Duplicado(id.toString())
        } catch (ex: ApiException) {
            ResultadoManejo.Rechazado(ex.codigo, ex.detalle?.toString() ?: ex.message ?: ex.codigo)
        }
    }

    /** Crea el trayecto, o regresa el que ya existe con ese id (el teléfono reintenta). */
    private fun guardar(conn: Connection, usuarioId: UUID, e: TrayectoEntrada, ip: String?): Pair<UUID, Boolean> {
        val id = runCatching { UUID.fromString(e.id) }.getOrNull()
        val iniciadoEn = hora(e.iniciadoEn)
        val tipo = e.tipoRuta?.trim()?.uppercase() ?: "SEGURA"
        val errores = buildMap {
            if (id == null) put("id", "Debe ser un UUID")
            if (iniciadoEn == null) put("iniciadoEn", "Debe ser una fecha ISO 8601")
            if (tipo !in setOf("SEGURA", "MAS_CORTA")) put("tipoRuta", "Debe ser SEGURA o MAS_CORTA")
            if (e.origenLatitud !in -90.0..90.0 || e.destinoLatitud !in -90.0..90.0) put("latitud", "Debe estar entre -90 y 90")
            if (e.origenLongitud !in -180.0..180.0 || e.destinoLongitud !in -180.0..180.0) put("longitud", "Debe estar entre -180 y 180")
            if (e.distanciaM != null && e.distanciaM <= 0) put("distanciaM", "Debe ser mayor que 0")
            if (e.duracionEstMin != null && e.duracionEstMin !in 1..32767) put("duracionEstMin", "Debe ser mayor que 0")
            if (e.toleranciaDesviacionM != null && e.toleranciaDesviacionM !in 1..32767) put("toleranciaDesviacionM", "Debe ser mayor que 0")
            if (e.intervaloCheckinMin != null && e.intervaloCheckinMin !in 1..32767) put("intervaloCheckinMin", "Debe ser mayor que 0")
            if ((e.rutaPlanificada?.toString()?.length ?: 0) > 500_000) put("rutaPlanificada", "La ruta es demasiado grande")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        val dueno = conn.consultarUno("SELECT usuario_id FROM trayecto WHERE id = ?", id) { it.uuid(COL_USUARIO) }
        if (dueno != null) {
            if (dueno != usuarioId) throw ApiException(HttpStatusCode.Conflict, "CONFLICTO", "Ese id de trayecto ya existe")
            return id!! to false
        }
        conn.ejecutar(
            """INSERT INTO trayecto (id, usuario_id, tipo_ruta, origen_latitud, origen_longitud, destino_latitud, destino_longitud,
                                     distancia_m, duracion_est_min, tolerancia_desviacion_m, intervalo_checkin_min, ruta_planificada,
                                     iniciado_en, capa_id)
               VALUES (?, ?, ?::ruta_tipo, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, (SELECT id FROM capa_riesgo WHERE es_vigente))""",
            id, usuarioId, tipo, e.origenLatitud, e.origenLongitud, e.destinoLatitud, e.destinoLongitud,
            e.distanciaM, e.duracionEstMin?.toShort(), e.toleranciaDesviacionM?.toShort(), e.intervaloCheckinMin?.toShort(),
            e.rutaPlanificada?.toString(), minOf(iniciadoEn!!, Instant.now()),
        )
        var avisados = 0
        if (e.compartir) {
            avisados = conn.ejecutar(
                """INSERT INTO notificacion (trayecto_id, usuario_id, tipo, canal)
                   SELECT ?, d.id, 'TRAYECTO_COMPARTIDO', 'PUSH' FROM ($RED_CON_CUENTA) d""",
                id, usuarioId, usuarioId, usuarioId, usuarioId,
            )
        }
        Bitacora.registrar(conn, "INICIAR_TRAYECTO", usuarioId, "trayecto", id.toString(),
            buildJsonObject { put("compartido", e.compartir); put("avisados", avisados) }, ip)
        return id!! to true
    }

    private fun aplicarCambio(conn: Connection, id: UUID, c: TrayectoCambio, estricto: Boolean = true) {
        val estado = c.estado?.trim()?.uppercase()
        val errores = buildMap {
            if (estado != null && estado !in ESTADOS_FIN + "EN_CURSO") put("estado", "Debe ser EN_CURSO, FINALIZADO o CANCELADO")
            if ((c.latitud == null) != (c.longitud == null)) put("latitud", "Manda latitud y longitud juntas")
            if (c.latitud != null && c.latitud !in -90.0..90.0) put("latitud", "Debe estar entre -90 y 90")
            if (c.longitud != null && c.longitud !in -180.0..180.0) put("longitud", "Debe estar entre -180 y 180")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        val actual = conn.consultarUno("SELECT estado::text FROM trayecto WHERE id = ? FOR UPDATE", id) { it.getString(1) }!!
        if (c.latitud != null) {
            val en = minOf(hora(c.ubicacionEn) ?: Instant.now(), Instant.now())
            // Solo avanza: una lectura vieja que llega tarde no pisa una más nueva.
            conn.ejecutar(
                """UPDATE trayecto SET ultima_latitud = ?, ultima_longitud = ?, ultima_ubicacion_en = ?
                   WHERE id = ? AND (ultima_ubicacion_en IS NULL OR ultima_ubicacion_en < ?)""",
                c.latitud, c.longitud, en, id, en,
            )
        }
        when {
            estado == null || estado == actual -> {}
            estado in ESTADOS_FIN && actual == "EN_CURSO" -> {
                val inicio = conn.consultarUno("SELECT iniciado_en FROM trayecto WHERE id = ?", id) { it.instante("iniciado_en") }!!
                val fin = maxOf(minOf(hora(c.finalizadoEn) ?: Instant.now(), Instant.now()), inicio)
                conn.ejecutar("UPDATE trayecto SET estado = ?::trayecto_estado, finalizado_en = ? WHERE id = ?", estado, fin, id)
            }
            // Un trayecto terminado no se reabre ni cambia de final. La cola sin señal puede traer una foto vieja: se ignora.
            estricto -> throw ApiException(HttpStatusCode.Conflict, "TRANSICION_INVALIDA", "El trayecto ya terminó ($actual)")
        }
    }

    /** Guarda el check-in esperado y, si llega, su confirmación. Regresa true si cambió algo. */
    private fun guardarCheckin(conn: Connection, id: UUID, esperadoTexto: String, confirmadoTexto: String?): Boolean {
        val esperado = hora(esperadoTexto) ?: throw errorValidacion(mapOf("esperadoEn" to "Debe ser una fecha ISO 8601"))
        val confirmado = confirmadoTexto?.let { hora(it) ?: throw errorValidacion(mapOf("confirmadoEn" to "Debe ser una fecha ISO 8601")) }
        return conn.ejecutar(
            """INSERT INTO checkin_trayecto (trayecto_id, esperado_en, confirmado_en) VALUES (?, ?, ?)
               ON CONFLICT (trayecto_id, esperado_en) DO UPDATE SET confirmado_en = COALESCE(checkin_trayecto.confirmado_en, EXCLUDED.confirmado_en)
               WHERE checkin_trayecto.confirmado_en IS NULL AND EXCLUDED.confirmado_en IS NOT NULL""",
            id, esperado, confirmado?.let { minOf(it, Instant.now()) },
        ) > 0
    }

    private fun propio(conn: Connection, usuarioId: UUID, idTexto: String): UUID {
        val id = runCatching { UUID.fromString(idTexto) }.getOrNull() ?: throw noEncontrado()
        val dueno = conn.consultarUno("SELECT usuario_id FROM trayecto WHERE id = ?", id) { it.uuid(COL_USUARIO) }
        if (dueno != usuarioId) throw noEncontrado()
        return id
    }

    private fun enRedDeApoyo(conn: Connection, dueno: UUID, quien: UUID): Boolean =
        conn.consultarUno("SELECT 1 FROM ($RED_CON_CUENTA) d WHERE d.id = ?", dueno, dueno, dueno, dueno, quien) { true } == true

    private fun leer(conn: Connection, id: UUID): Trayecto? {
        val checkins = conn.consultar(
            "SELECT esperado_en, confirmado_en FROM checkin_trayecto WHERE trayecto_id = ? ORDER BY esperado_en", id,
        ) { Checkin(it.instante("esperado_en").toString(), it.instante("confirmado_en")?.toString()) }
        return conn.consultarUno("SELECT * FROM trayecto WHERE id = ?", id) { it.aTrayecto(checkins) }
    }

    private fun ResultSet.aTrayecto(checkins: List<Checkin>) = Trayecto(
        id = uuid("id").toString(),
        usuarioId = uuid(COL_USUARIO).toString(),
        tipoRuta = getString("tipo_ruta"),
        estado = getString("estado"),
        origenLatitud = getDouble("origen_latitud"),
        origenLongitud = getDouble("origen_longitud"),
        destinoLatitud = getDouble("destino_latitud"),
        destinoLongitud = getDouble("destino_longitud"),
        distanciaM = getObject("distancia_m") as Int?,
        duracionEstMin = (getObject("duracion_est_min") as Number?)?.toInt(),
        toleranciaDesviacionM = (getObject("tolerancia_desviacion_m") as Number?)?.toInt(),
        intervaloCheckinMin = (getObject("intervalo_checkin_min") as Number?)?.toInt(),
        rutaPlanificada = getString("ruta_planificada")?.let { Json.parseToJsonElement(it).jsonObject },
        ultimaLatitud = dobleONulo("ultima_latitud"),
        ultimaLongitud = dobleONulo("ultima_longitud"),
        ultimaUbicacionEn = instante("ultima_ubicacion_en")?.toString(),
        iniciadoEn = instante("iniciado_en").toString(),
        finalizadoEn = instante("finalizado_en")?.toString(),
        checkins = checkins,
    )

    private fun hora(valor: String?): Instant? = valor?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private fun noEncontrado() = ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "El trayecto no existe")

    private companion object {
        const val COL_USUARIO = "usuario_id"

        /**
         * Cuentas de la red de apoyo de una persona (parámetros: la persona cuatro veces): contactos confirmados con
         * cuenta vinculada y quienes comparten círculo con ella, sin ella misma.
         */
        const val RED_CON_CUENTA = """
            SELECT c.usuario_vinculado_id AS id FROM contacto_confianza c
            WHERE c.usuario_id = ? AND c.activo AND c.estado_confirmacion = 'CONFIRMADO' AND c.usuario_vinculado_id IS NOT NULL
            UNION
            SELECT p.usuario_id FROM miembro_circulo m
            JOIN (SELECT circulo_id, usuario_id FROM miembro_circulo WHERE estado = 'ACTIVA'
                  UNION SELECT id, coordinador_id FROM circulo) p ON p.circulo_id = m.circulo_id
            JOIN usuario u ON u.id = p.usuario_id AND u.estado = 'ACTIVA'
            WHERE m.usuario_id = ? AND m.estado = 'ACTIVA' AND p.usuario_id <> ?
            EXCEPT SELECT ?::uuid"""
    }
}
