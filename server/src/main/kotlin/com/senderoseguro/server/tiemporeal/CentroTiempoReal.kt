package com.senderoseguro.server.tiemporeal

import com.senderoseguro.server.dispersion.AlertaResumen
import com.senderoseguro.server.dispersion.ConsultaAlertas
import com.senderoseguro.server.dispersion.EnlacesServicio
import com.senderoseguro.server.dispersion.EscalamientoJson
import com.senderoseguro.server.dispersion.UbicacionJson
import com.senderoseguro.server.dispersion.UltimaUbicacion
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.uuid
import com.senderoseguro.server.plataforma.uuidONulo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.time.Duration

/**
 * Reparte en tiempo real lo que pasa con las alertas a la consola (`/ws/consola`) y al enlace de la red de apoyo
 * (`/ws/enlace/{token}`). Los mensajes tienen la forma `{ tipo, ts, datos }` de `api/README.md`.
 *
 * Cada [cada] lee de la base las filas nuevas de `alerta`, `asignacion`, `escalamiento`, `evento_alerta` y
 * `ubicacion_alerta`. Así recibe también lo que escriben otros módulos (por ejemplo las ubicaciones del Módulo 2)
 * y lo que pasa en otro servidor, sin que nadie tenga que avisarle y sin Redis.
 */
class CentroTiempoReal(
    private val dataSource: DataSource,
    private val consulta: ConsultaAlertas,
    private val enlaces: EnlacesServicio,
) {
    /** Una consola conectada. `unidadId` es la unidad del operador; null para el administrador. */
    internal class Consola(val usuarioId: UUID, val esAdministrador: Boolean, val unidadId: UUID?) : Suscriptor()

    /**
     * Alguien de la red de apoyo con el enlace abierto. Solo recibe un estado si cambió y una ubicación si es más
     * reciente que la última que ya tiene (el saludo pudo adelantarse a la vuelta que lee las mismas filas).
     */
    internal class Seguidor(val enlaceId: UUID, val alertaId: UUID) : Suscriptor() {
        private var estado: String? = null
        private var capturadaEn: Instant? = null

        @Synchronized
        fun mandarEstado(legible: String) {
            if (legible == estado) return
            estado = legible
            mandar(mensaje("estado", buildJsonObject { put("estado", legible) }))
        }

        @Synchronized
        fun mandarUbicacion(u: UbicacionJson) {
            val en = Instant.parse(u.capturadaEn)
            if (capturadaEn != null && !en.isAfter(capturadaEn)) return
            capturadaEn = en
            mandar(mensaje("ubicacion", formato.encodeToJsonElement(UbicacionJson.serializer(), u)))
        }
    }

    internal abstract class Suscriptor {
        /** Mensajes por enviar. Si el cliente no los lee a tiempo, se cierra su conexión. */
        val salida = Channel<String>(CAPACIDAD)

        fun mandar(mensaje: String) {
            if (salida.trySend(mensaje).isFailure) salida.close()
        }
    }

    private val consolas = ConcurrentHashMap.newKeySet<Consola>()
    private val seguidores = ConcurrentHashMap.newKeySet<Seguidor>()
    private val log = LoggerFactory.getLogger(CentroTiempoReal::class.java)

    // Lo ya enviado de cada tabla. Ver Marcador.
    private val asignaciones = Marcador()
    private val escalamientos = Marcador()
    private val eventos = Marcador()
    private val ubicaciones = Marcador()
    private val alertasVistas = ConcurrentHashMap<UUID, Instant>()
    @Volatile private var iniciado = false

    fun iniciar(scope: CoroutineScope, cada: Duration) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    revisar()
                } catch (e: Exception) {
                    log.error("Falló la vuelta del tiempo real", e)
                }
                delay(cada)
            }
        }
    }

    internal fun conectar(s: Suscriptor) {
        when (s) {
            is Consola -> consolas.add(s)
            is Seguidor -> seguidores.add(s)
        }
    }

    internal fun desconectar(s: Suscriptor) {
        when (s) {
            is Consola -> consolas.remove(s)
            is Seguidor -> seguidores.remove(s)
        }
        s.salida.close()
    }

    /** El enlace y su alerta si el token sigue vigente. */
    internal fun vigente(token: String): Pair<UUID, UUID>? = enlaces.vigente(token)

    /** La unidad del operador, o null si no tiene una activa. */
    internal fun unidadDeOperador(usuarioId: UUID): UUID? = dataSource.connection.use { conn ->
        conn.consultarUno("SELECT unidad_id FROM operador WHERE usuario_id = ? AND activo", usuarioId) { it.uuid("unidad_id") }
    }

    /** Lo primero que recibe quien abre el enlace: el estado y la última ubicación. */
    internal fun saludar(s: Seguidor, token: String) {
        val e = runCatching { enlaces.consultar(token) }.getOrNull() ?: return
        s.mandarEstado(e.estado)
        e.ultimaUbicacion?.let(s::mandarUbicacion)
    }

    /**
     * Una vuelta: busca filas nuevas y las manda a quien le tocan. Todas las consultas ven la misma foto de la base
     * (REPEATABLE READ), así una asignación y su cambio de estado, que se guardan juntos, llegan juntos.
     */
    internal fun revisar() = dataSource.connection.use { conn ->
        conn.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        conn.autoCommit = false
        conn.isReadOnly = true
        try {
            revisar(conn)
        } finally {
            conn.rollback()
        }
    }

    private fun revisar(conn: Connection) {
        if (!iniciado || (consolas.isEmpty() && seguidores.isEmpty())) {
            // Al arrancar, o si nadie escucha, solo se recorren las marcas: no se manda historia vieja.
            ponerAlDia(conn)
            iniciado = true
            return
        }
        val destinos = Destinos(conn)
        alertasNuevas(conn)
        conn.consultar(
            "SELECT id, alerta_id, unidad_id FROM asignacion WHERE id > ? ORDER BY id",
            asignaciones.desde(),
        ) { Triple(it.getLong("id"), it.uuid("alerta_id"), it.uuid("unidad_id")) }.forEach { (id, alertaId, unidadId) ->
            if (!asignaciones.nuevo(id)) return@forEach
            val operadores = consolas.filter { it.unidadId == unidadId }
            if (operadores.isEmpty()) return@forEach
            val r = consulta.resumen(conn, alertaId) ?: return@forEach
            val m = mensaje("alerta.nueva", formato.encodeToJsonElement(AlertaResumen.serializer(), r))
            operadores.forEach { it.mandar(m) }
        }
        conn.consultar(
            """SELECT e.id, e.alerta_id, e.nivel, e.destino_tipo::text AS destino, c.nombre AS corporacion,
                      e.operador_id, e.motivo, e.escalada_en
               FROM escalamiento e LEFT JOIN corporacion c ON c.id = e.corporacion_id
               WHERE e.id > ? ORDER BY e.id""",
            escalamientos.desde(),
        ) { rs ->
            Triple(
                rs.getLong("id"), rs.uuid("alerta_id"),
                EscalamientoJson(
                    rs.getInt("nivel"), rs.getString("destino"), rs.getString("corporacion"),
                    rs.uuidONulo("operador_id")?.toString(), rs.getString("motivo"), rs.instante("escalada_en")!!.toString(),
                ),
            )
        }.forEach { (id, alertaId, esc) ->
            if (!escalamientos.nuevo(id)) return@forEach
            destinos.consolas(alertaId).forEach {
                it.mandar(mensaje("alerta.escalada", buildJsonObject {
                    put("alertaId", alertaId.toString())
                    put("escalamiento", formato.encodeToJsonElement(EscalamientoJson.serializer(), esc))
                }))
            }
        }
        conn.consultar(
            """SELECT id, alerta_id, estado_anterior::text AS anterior, estado_nuevo::text AS nuevo, detalle::text AS detalle
               FROM evento_alerta WHERE id > ? ORDER BY id""",
            eventos.desde(),
        ) { rs ->
            Evento(rs.getLong("id"), rs.uuid("alerta_id"), rs.getString("anterior"), rs.getString("nuevo"), formato.parseToJsonElement(rs.getString("detalle")).jsonObject)
        }
            .forEach { e ->
                if (!eventos.nuevo(e.id)) return@forEach
                enviarEvento(e, destinos)
            }
        conn.consultar(
            "SELECT ub.id, ub.alerta_id, ${UltimaUbicacion.COLUMNAS} FROM ubicacion_alerta ub WHERE ub.id > ? ORDER BY ub.id",
            ubicaciones.desde(),
        ) { rs -> Triple(rs.getLong("id"), rs.uuid("alerta_id"), UltimaUbicacion.leer(rs)!!) }.forEach { (id, alertaId, ub) ->
            if (!ubicaciones.nuevo(id)) return@forEach
            val datos = formato.encodeToJsonElement(UbicacionJson.serializer(), ub)
            destinos.consolas(alertaId).forEach {
                it.mandar(mensaje("alerta.ubicacion", buildJsonObject {
                    put("alertaId", alertaId.toString())
                    put("ubicacion", datos)
                }))
            }
            destinos.seguidores(alertaId).forEach { it.mandarUbicacion(ub) }
        }
        cerrarEnlacesVencidos(conn)
    }

    private data class Evento(val id: Long, val alertaId: UUID, val anterior: String?, val nuevo: String, val detalle: JsonObject) {
        fun texto(campo: String): String? = (detalle[campo] as? JsonPrimitive)?.contentOrNull
    }

    private fun enviarEvento(e: Evento, destinos: Destinos) {
        if (e.anterior != null && e.anterior != e.nuevo) {
            val m = mensaje("alerta.estado", buildJsonObject {
                put("alertaId", e.alertaId.toString())
                put("estadoAnterior", e.anterior)
                put("estadoNuevo", e.nuevo)
            })
            destinos.consolas(e.alertaId).forEach { it.mandar(m) }
            if (e.nuevo in FINALES) {
                terminar(destinos.seguidores(e.alertaId), e.nuevo)
            } else {
                destinos.seguidores(e.alertaId).forEach { it.mandarEstado(EnlacesServicio.estadoLegible(e.nuevo)) }
            }
        }
        // Sin acuse: se agotó la cadena de escalamiento, o no había regla para canalizarla.
        if (e.texto("motivo") == "CADENA_AGOTADA" || e.texto("motivo") == "SIN_REGLA") {
            val m = mensaje("alerta.sin_acuse", buildJsonObject { put("alertaId", e.alertaId.toString()) })
            destinos.consolas(e.alertaId).forEach { it.mandar(m) }
        }
        // Un aviso de la alerta no se pudo entregar después de los reintentos (E-03): solo a los Administradores.
        if (e.texto("accion") == "ENTREGA_DESCARTADA") {
            val m = mensaje("alerta.entrega_fallida", buildJsonObject {
                put("alertaId", e.alertaId.toString())
                put("notificacionId", e.texto("notificacionId")?.toLongOrNull())
                put("tipo", e.texto("tipo"))
                put("canal", e.texto("canal"))
                put("error", e.texto("error"))
            })
            consolas.filter { it.esAdministrador }.forEach { it.mandar(m) }
        }
    }

    /** Alertas recién registradas: los administradores las ven todas (los operadores, cuando se asignan a su unidad). */
    private fun alertasNuevas(conn: Connection) {
        val recientes = conn.consultar(
            "SELECT id, registrada_en FROM alerta WHERE $RECIENTES",
        ) { it.uuid("id") to it.instante("registrada_en")!! }
        val administradores = consolas.filter { it.esAdministrador }
        recientes.forEach { (id, registrada) ->
            if (alertasVistas.putIfAbsent(id, registrada) != null || administradores.isEmpty()) return@forEach
            val r = consulta.resumen(conn, id) ?: return@forEach
            val m = mensaje("alerta.nueva", formato.encodeToJsonElement(AlertaResumen.serializer(), r))
            administradores.forEach { it.mandar(m) }
        }
        val limite = Instant.now().minusSeconds(300)
        alertasVistas.entries.removeIf { it.value < limite }
    }

    /** El enlace dejó de servir (venció, se revocó o la alerta terminó): se avisa `fin` y se cierra. */
    private fun cerrarEnlacesVencidos(conn: Connection) {
        if (seguidores.isEmpty()) return
        val porEnlace = seguidores.groupBy { it.enlaceId }
        val ids = conn.createArrayOf("uuid", porEnlace.keys.toTypedArray())
        conn.consultar(
            """SELECT en.id, a.estado::text AS estado FROM enlace_ubicacion en JOIN alerta a ON a.id = en.alerta_id
               WHERE en.id = ANY(?) AND (en.revocado_en IS NOT NULL OR en.vence_en <= now() OR a.estado IN ('CERRADA', 'CANCELADA'))""",
            ids,
        ) { it.uuid("id") to it.getString("estado") }.forEach { (enlaceId, estado) ->
            terminar(porEnlace[enlaceId].orEmpty(), if (estado in FINALES) estado else "VENCIDA")
        }
    }

    private fun terminar(lista: List<Seguidor>, motivo: String) {
        val m = mensaje("fin", buildJsonObject { put("motivo", motivo) })
        lista.forEach {
            seguidores.remove(it)
            it.mandar(m)
            it.salida.close()   // al vaciarse la salida, la ruta cierra la conexión
        }
    }

    private fun ponerAlDia(conn: Connection) {
        asignaciones.ponerEn(maximo(conn, "asignacion"))
        escalamientos.ponerEn(maximo(conn, "escalamiento"))
        eventos.ponerEn(maximo(conn, "evento_alerta"))
        ubicaciones.ponerEn(maximo(conn, "ubicacion_alerta"))
        conn.consultar("SELECT id, registrada_en FROM alerta WHERE $RECIENTES") {
            it.uuid("id") to it.instante("registrada_en")!!
        }.forEach { (id, registrada) -> alertasVistas.putIfAbsent(id, registrada) }
    }

    private fun maximo(conn: Connection, tabla: String): Long =
        conn.consultarUno("SELECT coalesce(max(id), 0) FROM $tabla") { it.getLong(1) }!!

    /** A quién le toca cada alerta en esta vuelta. Se consulta una sola vez por alerta. */
    private inner class Destinos(private val conn: Connection) {
        private val unidades = HashMap<UUID, Set<UUID>>()

        /** Los administradores y los operadores de las unidades que tienen o tuvieron la alerta. */
        fun consolas(alertaId: UUID): List<Consola> {
            if (consolas.isEmpty()) return emptyList()
            val deLaAlerta = unidades.getOrPut(alertaId) {
                conn.consultar("SELECT DISTINCT unidad_id FROM asignacion WHERE alerta_id = ?", alertaId) { it.uuid("unidad_id") }.toSet()
            }
            return consolas.filter { it.esAdministrador || it.unidadId in deLaAlerta }
        }

        fun seguidores(alertaId: UUID): List<Seguidor> = seguidores.filter { it.alertaId == alertaId }
    }

    internal companion object {
        const val CAPACIDAD = 256
        private val FINALES = setOf("CERRADA", "CANCELADA")

        // Una alerta recién registrada sigue activa; el filtro por estado usa el índice ix_alerta_activas.
        private const val RECIENTES =
            "estado IN ('ENVIADA','ASIGNADA','ESCALADA','ACUSADA','EN_ATENCION') AND registrada_en > now() - interval '2 minutes'"
        private val formato = Json { explicitNulls = false }

        fun mensaje(tipo: String, datos: JsonElement): String = buildJsonObject {
            put("tipo", tipo)
            put("ts", Instant.now().toString())
            put("datos", datos)
        }.toString()
    }
}

/**
 * Lleva qué filas de una tabla ya se enviaron. Los ids son consecutivos, pero una transacción larga puede guardar
 * un id menor después de uno mayor; por eso cada vuelta vuelve a mirar los últimos [VENTANA] ids y descarta los
 * que ya vio. Todo lo que está en o abajo de `piso` se da por enviado (lo que existía cuando nadie escuchaba).
 */
internal class Marcador {
    private var piso = 0L
    private var mayor = 0L
    private val vistos = HashSet<Long>()

    @Synchronized
    fun desde(): Long = maxOf(piso, mayor - VENTANA)

    @Synchronized
    fun nuevo(id: Long): Boolean {
        if (id <= piso || !vistos.add(id)) return false
        if (id > mayor) mayor = id
        vistos.removeIf { it <= mayor - VENTANA }
        return true
    }

    /** Da por enviado todo hasta [maximo]. */
    @Synchronized
    fun ponerEn(maximo: Long) {
        piso = maxOf(piso, maximo)
        mayor = maxOf(mayor, maximo)
        vistos.removeIf { it <= piso }
    }

    private companion object {
        const val VENTANA = 500
    }
}
