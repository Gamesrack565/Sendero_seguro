package com.senderoseguro.server.auditoria

import com.senderoseguro.server.plataforma.AUTH_JWT
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.dobleONulo
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.exigirRol
import com.senderoseguro.server.plataforma.transaccion
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.IsoFields
import javax.sql.DataSource
import kotlin.math.roundToInt

@Serializable
data class Periodo(val desde: String, val hasta: String)

@Serializable
data class EstadisticaTipo(val tipoIncidenteId: Int, val nombre: String, val total: Int, val medianaAcuseSeg: Int? = null)

@Serializable
data class EstadisticaSemana(val semana: String, val total: Int)

@Serializable
data class EstadisticasAlertas(
    val periodo: Periodo,
    val totalAlertas: Int,
    val medianaAcuseSeg: Int? = null,
    val pctCumpleMetaAcuse: Double? = null,
    val pctEscaladas: Double,
    val pctCanceladas: Double,
    val porCanal: Map<String, Int>,
    val porEstado: Map<String, Int>,
    val porTipo: List<EstadisticaTipo>,
    val porSemana: List<EstadisticaSemana>,
)

data class FiltrosEstadisticas(
    /** Días completos, de [desde] a [hasta] incluidos, en la hora de la Ciudad de México. */
    val desde: LocalDate,
    val hasta: LocalDate,
    val tipoIncidenteId: Int? = null,
    val zonaId: Int? = null,
)

/**
 * Indicadores de alertas para el tablero (P13, RF-26, W-08), a partir de `v_alerta_tiempos`. Solo cuentas,
 * porcentajes y tiempos: ningún dato de las personas.
 *
 * - El periodo y las semanas se cuentan por `activada_en` en la hora de la Ciudad de México.
 * - La zona es la caja de `zona_geografica` que contiene la primera ubicación de la alerta.
 * - `pctCumpleMetaAcuse`: de las alertas que se acusaron o a las que ya se les pasó la meta sin acuse (aunque
 *   después se cancelaran), cuántas se acusaron dentro de `meta_acuse_seg`. Las canceladas antes de la meta y
 *   las que todavía van dentro de la meta no cuentan.
 * - `pctEscaladas`: alertas que llegaron a escalar al menos una vez (`nivel_escalamiento > 0`).
 *
 * Todo se lee en una sola transacción de solo lectura, para que los totales cuadren entre sí.
 */
class Estadisticas(private val dataSource: DataSource) {

    fun alertas(f: FiltrosEstadisticas): EstadisticasAlertas = dataSource.transaccion { conn ->
        conn.ejecutar("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY")
        if (f.zonaId != null && conn.consultarUno("SELECT 1 FROM zona_geografica WHERE id = ?", f.zonaId) { true } == null) {
            throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "No existe la zona ${f.zonaId}")
        }
        val (donde, parametros) = filtro(f)
        fun <T> leer(sql: String, mapeo: (java.sql.ResultSet) -> T) =
            conn.consultar("WITH base AS (SELECT t.*, a.nivel_escalamiento FROM v_alerta_tiempos t JOIN alerta a ON a.id = t.alerta_id WHERE $donde) $sql", *parametros) { mapeo(it) }

        val resumen = leer(
            """, meta AS (SELECT valor::integer AS seg FROM parametro_sistema WHERE clave = 'meta_acuse_seg')
               SELECT count(*) AS total,
                      round(percentile_cont(0.5) WITHIN GROUP (ORDER BY seg_activacion_acuse)) AS mediana,
                      count(*) FILTER (WHERE cumple_meta_acuse) AS cumplen,
                      count(*) FILTER (WHERE acusada_en IS NOT NULL
                                          OR coalesce(cancelada_en, now()) > activada_en + make_interval(secs => meta.seg)) AS medibles,
                      count(*) FILTER (WHERE nivel_escalamiento > 0) AS escaladas,
                      count(*) FILTER (WHERE estado = 'CANCELADA') AS canceladas
               FROM base, meta GROUP BY meta.seg""",
        ) { rs ->
            Resumen(rs.getInt("total"), rs.dobleONulo("mediana")?.roundToInt(), rs.getInt("cumplen"), rs.getInt("medibles"),
                rs.getInt("escaladas"), rs.getInt("canceladas"))
        }.singleOrNull() ?: Resumen(0, null, 0, 0, 0, 0)

        val porCanal = leer("SELECT canal_recepcion::text AS k, count(*) AS n FROM base GROUP BY 1 ORDER BY 1") { it.getString("k") to it.getInt("n") }
        val porEstado = leer("SELECT estado::text AS k, count(*) AS n FROM base GROUP BY 1 ORDER BY 1") { it.getString("k") to it.getInt("n") }
        val porTipo = leer(
            """SELECT b.tipo_incidente_id AS id, count(*) AS n,
                      round(percentile_cont(0.5) WITHIN GROUP (ORDER BY b.seg_activacion_acuse)) AS mediana,
                      (SELECT tv.nombre FROM tipo_incidente_version tv WHERE tv.tipo_incidente_id = b.tipo_incidente_id
                       ORDER BY tv.version_catalogo_id DESC LIMIT 1) AS nombre
               FROM base b GROUP BY b.tipo_incidente_id ORDER BY n DESC, id""",
        ) { rs -> EstadisticaTipo(rs.getInt("id"), rs.getString("nombre"), rs.getInt("n"), rs.dobleONulo("mediana")?.roundToInt()) }
        val semanas = leer(
            """SELECT to_char(activada_en AT TIME ZONE '$ZONA_SQL', 'IYYY-"W"IW') AS semana, count(*) AS n FROM base GROUP BY 1""",
        ) { it.getString("semana") to it.getInt("n") }.toMap()

        EstadisticasAlertas(
            periodo = Periodo(f.desde.toString(), f.hasta.toString()),
            totalAlertas = resumen.total,
            medianaAcuseSeg = resumen.mediana,
            pctCumpleMetaAcuse = if (resumen.medibles == 0) null else pct(resumen.cumplen, resumen.medibles),
            pctEscaladas = pct(resumen.escaladas, resumen.total),
            pctCanceladas = pct(resumen.canceladas, resumen.total),
            porCanal = porCanal.toMap(),
            porEstado = porEstado.toMap(),
            porTipo = porTipo,
            // Todas las semanas del periodo, también las que no tuvieron alertas, para que la gráfica no salte huecos.
            porSemana = semanasDe(f.desde, f.hasta).map { EstadisticaSemana(it, semanas[it] ?: 0) },
        )
    }

    private class Resumen(val total: Int, val mediana: Int?, val cumplen: Int, val medibles: Int, val escaladas: Int, val canceladas: Int)

    private fun filtro(f: FiltrosEstadisticas): Pair<String, Array<Any?>> {
        val condiciones = mutableListOf("t.activada_en >= ?", "t.activada_en < ?")
        val parametros = mutableListOf<Any?>(
            f.desde.atStartOfDay(ZONA).toInstant(),
            f.hasta.plusDays(1).atStartOfDay(ZONA).toInstant(),
        )
        f.tipoIncidenteId?.let { condiciones += "t.tipo_incidente_id = ?"; parametros += it }
        f.zonaId?.let {
            condiciones += """EXISTS (
                SELECT 1 FROM zona_geografica z,
                       LATERAL (SELECT u.latitud, u.longitud FROM ubicacion_alerta u WHERE u.alerta_id = t.alerta_id
                                ORDER BY u.capturada_en LIMIT 1) p
                WHERE z.id = ? AND p.latitud BETWEEN z.bbox_min_lat AND z.bbox_max_lat
                  AND p.longitud BETWEEN z.bbox_min_lon AND z.bbox_max_lon)"""
            parametros += it
        }
        return condiciones.joinToString(" AND ") to parametros.toTypedArray()
    }

    private fun pct(parte: Int, total: Int): Double = if (total == 0) 0.0 else (parte * 1000.0 / total).roundToInt() / 10.0

    companion object {
        private const val ZONA_SQL = "America/Mexico_City"
        val ZONA: ZoneId = ZoneId.of(ZONA_SQL)

        /** Semanas ISO (lunes a domingo) que toca el periodo, como `2026-W43`. */
        fun semanasDe(desde: LocalDate, hasta: LocalDate): List<String> =
            generateSequence(desde) { it.plusDays(1) }.takeWhile { !it.isAfter(hasta) }
                .map { "%d-W%02d".format(it.get(IsoFields.WEEK_BASED_YEAR), it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)) }
                .distinct().toList()
    }
}

fun Route.rutasEstadisticas(estadisticas: Estadisticas) {
    authenticate(AUTH_JWT) {
        get("/estadisticas/alertas") {
            call.exigirRol(Rol.ADMINISTRADOR)
            call.respond(estadisticas.alertas(call.filtrosEstadisticas()))
        }
    }
}

/** Periodo máximo de una consulta, para que el tablero no recorra años de alertas de golpe. */
private const val MAX_DIAS = 366L

internal fun ApplicationCall.filtrosEstadisticas(): FiltrosEstadisticas {
    val q = request.queryParameters
    fun invalido(mensaje: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", mensaje)
    fun fecha(nombre: String): LocalDate {
        val texto = q[nombre] ?: invalido("Falta $nombre, por ejemplo $nombre=2026-10-01")
        return try { LocalDate.parse(texto) } catch (_: DateTimeParseException) { invalido("$nombre debe ser una fecha como 2026-10-01") }
    }
    fun entero(nombre: String) = q[nombre]?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: invalido("$nombre debe ser un número entero positivo") }
    val desde = fecha("desde")
    val hasta = fecha("hasta")
    if (hasta.isBefore(desde)) invalido("hasta no puede ser antes de desde")
    if (desde.plusDays(MAX_DIAS).isBefore(hasta)) invalido("El periodo no puede pasar de $MAX_DIAS días")
    return FiltrosEstadisticas(desde, hasta, entero("tipoIncidenteId"), entero("zonaId"))
}
