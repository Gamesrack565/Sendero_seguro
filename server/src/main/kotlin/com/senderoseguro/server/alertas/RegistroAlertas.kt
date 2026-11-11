package com.senderoseguro.server.alertas

import com.senderoseguro.server.cuentas.errorValidacion
import com.senderoseguro.server.dispersion.AlertasRecibidas
import com.senderoseguro.server.dispersion.Dispersion
import com.senderoseguro.server.pasarela.RegistroAlertasSms
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import com.senderoseguro.server.sincronizacion.ManejadorSync
import com.senderoseguro.server.sincronizacion.ResultadoManejo
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import javax.sql.DataSource

/**
 * P4: registro de las alertas en el servidor. La misma alerta puede llegar por internet (`POST /alertas`), por el
 * lote de sincronización (tipo ALERTA), por SMS (pasarela) o por la malla, y siempre se guarda una sola vez:
 * se reconoce por su `id` (UUID del teléfono) o, si antes llegó por SMS, por id corto y hora ±60 s.
 *
 * Guarda `alerta` (ENVIADA), su primera `ubicacion_alerta`, su primer `evento_alerta` y la bitácora. Una alerta
 * nueva se despacha (P5) después del commit: aquí en [registrar]; en la sincronización y en el SMS lo hace
 * quien los llama.
 */
class RegistroAlertas(private val dataSource: DataSource, private val dispersion: Dispersion) {

    /** Lo que se guardó, o lo que ya estaba si [nueva] es false. */
    data class Resultado(val id: UUID, val folio: String, val estado: String, val registradaEn: Instant, val nueva: Boolean) {
        fun respuesta() = AlertaRegistrada(id.toString(), folio, estado, registradaEn.toString(), null)
    }

    /** `POST /alertas` y la malla. Valida, guarda y, si es nueva, la despacha. */
    fun registrar(usuarioId: UUID, dispositivoToken: UUID?, entrada: AlertaNueva, ip: String?, canal: String? = null): Resultado {
        val datos = validar(entrada, canal)
        val r = dataSource.transaccion { conn -> guardar(conn, usuarioId, dispositivoToken, datos, ip) }
        if (r.nueva) dispersion.despachar(r.id)
        return r
    }

    /** Manejador de las ALERTA de `/sync/lote`. Los `datos` son el mismo cuerpo que `POST /alertas`. */
    val manejadorSync = ManejadorSync { conn, contexto, elemento ->
        val entrada = runCatching { json.decodeFromJsonElement<AlertaNueva>(elemento.datos) }.getOrNull()
            ?: return@ManejadorSync ResultadoManejo.Rechazado("VALIDACION", "Los datos no tienen la forma de AlertaNueva")
        try {
            val datos = validar(entrada, null)
            if (datos.id != elemento.id) {
                return@ManejadorSync ResultadoManejo.Rechazado("VALIDACION", "El id de los datos no es el del elemento")
            }
            val r = guardar(conn, contexto.usuarioId, contexto.dispositivoId, datos, null)
            if (r.nueva) ResultadoManejo.Aceptado(referencia = r.folio, despachar = r.id)
            else ResultadoManejo.Duplicado(referencia = r.folio)
        } catch (e: ApiException) {
            ResultadoManejo.Rechazado(e.codigo, e.message ?: e.codigo, temporal = e.status == HttpStatusCode.ServiceUnavailable)
        }
    }

    /**
     * Registro de las alertas que llegan por SMS (pasarela). El id empieza con el id corto del SS1 y el resto se
     * completa al azar; si después llega por internet o por sincronización, se reconoce por id corto y hora.
     */
    val registroSms = RegistroAlertasSms { conn, sms ->
        val d = sms.datos
        val id = UUID.fromString(d.idCorto + UUID.randomUUID().toString().substring(8))
        val datos = Validada(
            id = id, tipoIncidenteId = d.tipoIncidenteId, versionCatalogoId = null, tipoSugeridoId = null,
            confianzaSugerencia = null, descripcion = null, canal = "SMS", saltosMalla = null, detonador = "BOTON",
            nivelBateria = null, trayectoId = null, activadaEn = d.activadaEn, enviadaEn = d.activadaEn,
            latitud = d.latitud, longitud = d.longitud, precisionM = d.precisionM.toDouble(), velocidadMs = null,
            rumboGrados = null, bateria = null, fuente = "GNSS", capturadaEn = d.activadaEn,
        )
        val dispositivo = conn.consultarUno(
            "SELECT id FROM dispositivo WHERE usuario_id = ? ORDER BY ultimo_visto_en DESC NULLS LAST LIMIT 1", sms.usuarioId,
        ) { it.uuid("id") }
        guardar(conn, sms.usuarioId, dispositivo, datos, null).id
    }

    /**
     * Relevo de la malla (RF-14, A-01): un teléfono con internet entrega el paquete que recibió por Bluetooth.
     * El paquete solo trae ids cortos: el de la alerta (primeros 8 hex de su UUID, como el SMS) y el de la persona
     * (últimos 8 hex de su UUID). Si la alerta ya llegó por otro camino se regresa esa; si no, se registra con
     * `canal_recepcion = MALLA` a nombre de la persona y se despacha. [relevadorId] queda en la bitácora.
     */
    fun registrarRelevo(relevadorId: UUID, r: RelevoMalla, ip: String?): Resultado {
        val errores = mutableMapOf<String, String>()
        val idCorto = r.idCortoAlerta.lowercase()
        val persona = r.idCortoUsuario.lowercase()
        if (!HEX8.matches(idCorto)) errores["idCortoAlerta"] = "Deben ser 8 caracteres hexadecimales"
        if (!HEX8.matches(persona)) errores["idCortoUsuario"] = "Deben ser 8 caracteres hexadecimales"
        if (r.latitud !in -90.0..90.0) errores["latitud"] = "Debe estar entre -90 y 90"
        if (r.longitud !in -180.0..180.0) errores["longitud"] = "Debe estar entre -180 y 180"
        if (r.nivelBateria != null && r.nivelBateria !in 0..100) errores["nivelBateria"] = "Debe estar entre 0 y 100"
        if (r.saltosMalla != null && r.saltosMalla !in 0..255) errores["saltosMalla"] = "Fuera de rango"
        val ahora = Instant.now()
        val activadaEn = hora(r.activadaEn)?.let { minOf(it, ahora) }
        if (activadaEn == null) errores["activadaEn"] = "Debe ser una fecha ISO 8601"
        else if (activadaEn.isBefore(ahora.minus(java.time.Duration.ofHours(24)))) errores["activadaEn"] = "El paquete tiene más de 24 horas"
        if (errores.isNotEmpty()) throw errorValidacion(errores)

        val resultado = dataSource.transaccion { conn ->
            val usuarios = conn.consultar(
                "SELECT id FROM usuario WHERE estado = 'ACTIVA' AND right(replace(id::text, '-', ''), 8) = ? LIMIT 2", persona,
            ) { it.uuid("id") }
            val usuarioId = usuarios.singleOrNull()
                ?: throw ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "No se reconoce a la persona del paquete")
            val datos = Validada(
                id = UUID.fromString(idCorto + UUID.randomUUID().toString().substring(8)), tipoIncidenteId = null,
                versionCatalogoId = null, tipoSugeridoId = null, confianzaSugerencia = null, descripcion = null,
                canal = "MALLA", saltosMalla = r.saltosMalla, detonador = "BOTON", nivelBateria = r.nivelBateria,
                trayectoId = null, activadaEn = activadaEn!!, enviadaEn = activadaEn, latitud = r.latitud,
                longitud = r.longitud, precisionM = null, velocidadMs = null, rumboGrados = null, bateria = r.nivelBateria,
                fuente = "GNSS", capturadaEn = activadaEn,
            )
            val dispositivo = conn.consultarUno(
                "SELECT id FROM dispositivo WHERE usuario_id = ? ORDER BY ultimo_visto_en DESC NULLS LAST LIMIT 1", usuarioId,
            ) { it.uuid("id") }
            guardar(conn, usuarioId, dispositivo, datos, ip, retransmitidaPor = relevadorId)
        }
        if (resultado.nueva) dispersion.despachar(resultado.id)
        return resultado
    }

    private data class Validada(
        val id: UUID, val tipoIncidenteId: Int?, val versionCatalogoId: Int?, val tipoSugeridoId: Int?,
        val confianzaSugerencia: Double?, val descripcion: String?, val canal: String, val saltosMalla: Int?,
        val detonador: String, val nivelBateria: Int?, val trayectoId: UUID?, val activadaEn: Instant,
        val enviadaEn: Instant, val latitud: Double, val longitud: Double, val precisionM: Double?,
        val velocidadMs: Double?, val rumboGrados: Int?, val bateria: Int?, val fuente: String, val capturadaEn: Instant,
    )

    private fun validar(e: AlertaNueva, canalForzado: String?): Validada {
        val errores = mutableMapOf<String, String>()
        val ahora = Instant.now()
        val id = uuidONulo(e.id).also { if (it == null) errores["id"] = "Debe ser un UUID" }
        val activadaEn = hora(e.activadaEn).also { if (it == null) errores["activadaEn"] = "Debe ser una fecha ISO 8601" }
        val enviadaEn = e.enviadaEn?.let { v -> hora(v).also { if (it == null) errores["enviadaEn"] = "Debe ser una fecha ISO 8601" } }
        val u = e.ubicacion
        if (u.latitud !in -90.0..90.0) errores["ubicacion.latitud"] = "Debe estar entre -90 y 90"
        if (u.longitud !in -180.0..180.0) errores["ubicacion.longitud"] = "Debe estar entre -180 y 180"
        if (u.precisionM != null && u.precisionM !in 0.0..99_999.0) errores["ubicacion.precisionM"] = "No puede ser negativa"
        if (u.velocidadMs != null && u.velocidadMs !in 0.0..999.0) errores["ubicacion.velocidadMs"] = "Fuera de rango"
        if (u.rumboGrados != null && u.rumboGrados !in 0..359) errores["ubicacion.rumboGrados"] = "Debe estar entre 0 y 359"
        if (u.bateria != null && u.bateria !in 0..100) errores["ubicacion.bateria"] = "Debe estar entre 0 y 100"
        val fuente = u.fuente ?: "GNSS"
        if (fuente !in FUENTES) errores["ubicacion.fuente"] = "Debe ser ${FUENTES.joinToString()}"
        val capturadaEn = u.capturadaEn?.let { v -> hora(v).also { if (it == null) errores["ubicacion.capturadaEn"] = "Debe ser una fecha ISO 8601" } }
        val canal = canalForzado ?: e.canalRecepcion ?: "INTERNET"
        if (canal !in CANALES) errores["canalRecepcion"] = "Debe ser ${CANALES.joinToString()}"
        if (e.saltosMalla != null && e.saltosMalla !in 0..255) errores["saltosMalla"] = "Fuera de rango"
        val detonador = e.detonador ?: "BOTON"
        if (detonador !in DETONADORES) errores["detonador"] = "Debe ser ${DETONADORES.joinToString()}"
        if (e.nivelBateria != null && e.nivelBateria !in 0..100) errores["nivelBateria"] = "Debe estar entre 0 y 100"
        if (e.confianzaSugerencia != null && e.confianzaSugerencia !in 0.0..1.0) errores["confianzaSugerencia"] = "Debe estar entre 0 y 1"
        if ((e.descripcion?.length ?: 0) > 2000) errores["descripcion"] = "Máximo 2000 caracteres"
        val trayecto = e.trayectoId?.let { v -> uuidONulo(v).also { if (it == null) errores["trayectoId"] = "Debe ser un UUID" } }
        if (errores.isNotEmpty()) throw errorValidacion(errores)

        // El reloj del teléfono puede ir adelantado: nada queda en el futuro.
        val activada = minOf(activadaEn!!, ahora)
        return Validada(
            id = id!!, tipoIncidenteId = e.tipoIncidenteId, versionCatalogoId = e.versionCatalogoId,
            tipoSugeridoId = e.tipoSugeridoId, confianzaSugerencia = e.confianzaSugerencia?.takeIf { e.tipoSugeridoId != null },
            descripcion = e.descripcion?.trim()?.ifEmpty { null }, canal = canal,
            saltosMalla = e.saltosMalla.takeIf { canal == "MALLA" }, detonador = detonador, nivelBateria = e.nivelBateria,
            trayectoId = trayecto, activadaEn = activada, enviadaEn = minOf(enviadaEn ?: activada, ahora).coerceAtLeast(activada),
            latitud = u.latitud, longitud = u.longitud, precisionM = u.precisionM, velocidadMs = u.velocidadMs,
            rumboGrados = u.rumboGrados, bateria = u.bateria ?: e.nivelBateria, fuente = fuente,
            capturadaEn = minOf(capturadaEn ?: activada, ahora),
        )
    }

    private fun guardar(
        conn: Connection, usuarioId: UUID, dispositivoToken: UUID?, d: Validada, ip: String?, retransmitidaPor: UUID? = null,
    ): Resultado {
        AlertasRecibidas.bloquear(conn, usuarioId)
        val dueno = conn.consultarUno("SELECT usuario_id FROM alerta WHERE id = ?", d.id) { it.uuid("usuario_id") }
        if (dueno != null && dueno != usuarioId) {
            throw ApiException(HttpStatusCode.Conflict, "CONFLICTO", "Ese id de alerta ya existe")
        }
        val existente = dueno?.let { d.id }
            ?: AlertasRecibidas.buscar(conn, usuarioId, d.id.toString().take(8), d.activadaEn)
        if (existente != null) return leer(conn, existente)

        val dispositivo = dispositivoToken?.takeIf {
            conn.consultarUno("SELECT 1 FROM dispositivo WHERE id = ? AND usuario_id = ?", it, usuarioId) { true } != null
        }
        val trayecto = d.trayectoId?.let {
            conn.consultarUno("SELECT 1 FROM trayecto WHERE id = ? AND usuario_id = ?", it, usuarioId) { true }
                ?: throw errorValidacion(mapOf("trayectoId" to "No es un trayecto tuyo"))
            it
        }
        // El tipo que eligió (en su versión del catálogo o en la publicada); si no está, la genérica.
        val (tipo, version) = conn.consultarUno(
            """SELECT tv.tipo_incidente_id, tv.version_catalogo_id
               FROM tipo_incidente_version tv
               JOIN version_catalogo v ON v.id = tv.version_catalogo_id
               WHERE tv.activo AND tv.aplica_a IN ('ALERTA', 'AMBOS')
                 AND (v.estado = 'PUBLICADA' OR v.id = ?)
                 AND (tv.tipo_incidente_id = ? OR tv.es_generica)
               ORDER BY COALESCE(tv.tipo_incidente_id = ?, false) DESC, COALESCE(v.id = ?, false) DESC,
                        (v.estado = 'PUBLICADA') DESC
               LIMIT 1""",
            d.versionCatalogoId, d.tipoIncidenteId, d.tipoIncidenteId, d.versionCatalogoId,
        ) { it.getInt("tipo_incidente_id") to it.getInt("version_catalogo_id") }
            ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "ERROR_INTERNO", "No hay catálogo de incidentes publicado")
        val sugerido = d.tipoSugeridoId?.takeIf { conn.consultarUno("SELECT 1 FROM tipo_incidente WHERE id = ?", it) { true } != null }

        conn.ejecutar(
            """INSERT INTO alerta (id, usuario_id, dispositivo_id, tipo_incidente_id, version_catalogo_id, tipo_sugerido_id,
                                   confianza_sugerencia, descripcion, canal_recepcion, saltos_malla, detonador, nivel_bateria,
                                   trayecto_id, activada_en, enviada_en)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::canal_recepcion, ?, ?::alerta_detonador, ?, ?, ?, ?)""",
            d.id, usuarioId, dispositivo, tipo, version, sugerido, sugerido?.let { d.confianzaSugerencia }, d.descripcion,
            d.canal, d.saltosMalla, d.detonador, d.nivelBateria, trayecto, d.activadaEn, d.enviadaEn,
        )
        conn.ejecutar(
            """INSERT INTO ubicacion_alerta (alerta_id, latitud, longitud, precision_m, velocidad_ms, rumbo_grados, bateria, fuente, capturada_en)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?::fuente_ubicacion, ?)
               ON CONFLICT DO NOTHING""",
            d.id, d.latitud, d.longitud, d.precisionM, d.velocidadMs, d.rumboGrados, d.bateria, d.fuente, d.capturadaEn,
        )
        conn.ejecutar(
            """INSERT INTO evento_alerta (alerta_id, estado_anterior, estado_nuevo, actor_id, detalle)
               VALUES (?, NULL, 'ENVIADA', ?, ?::jsonb)""",
            d.id, usuarioId, buildJsonObject { put("canal", d.canal); put("detonador", d.detonador) }.toString(),
        )
        Bitacora.registrar(conn, "ENVIAR_ALERTA", usuarioId, "alerta", d.id.toString(),
            buildJsonObject {
                put("canal", d.canal); put("detonador", d.detonador)
                retransmitidaPor?.let { put("retransmitidaPor", it.toString()) }
            }, ip)
        return leer(conn, d.id).copy(nueva = true)
    }

    private fun leer(conn: Connection, id: UUID): Resultado = conn.consultarUno(
        "SELECT folio, estado::text AS estado, registrada_en FROM alerta WHERE id = ?", id,
    ) { Resultado(id, it.getString("folio"), it.getString("estado"), it.instante("registrada_en")!!, nueva = false) }!!

    private fun uuidONulo(v: String) = runCatching { UUID.fromString(v) }.getOrNull()?.takeIf { it.toString() == v.lowercase() }

    private fun hora(v: String): Instant? = try {
        Instant.ofEpochMilli(Instant.parse(v).toEpochMilli())
    } catch (_: DateTimeParseException) {
        null
    }

    private fun Instant.coerceAtLeast(otro: Instant) = if (isBefore(otro)) otro else this

    companion object {
        val CANALES = setOf("INTERNET", "MALLA", "SMS")
        val DETONADORES = setOf("BOTON", "ATAJO_FISICO", "WIDGET", "VOZ", "CAIDA", "TEMPORIZADOR")
        val FUENTES = setOf("GNSS", "WIFI", "CELULAR", "ULTIMA_CONOCIDA")
        private val json = Json { ignoreUnknownKeys = true }
        private val HEX8 = Regex("^[0-9a-f]{8}$")
    }
}
