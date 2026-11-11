package com.senderoseguro.server.comunidad

import com.senderoseguro.server.cuentas.errorValidacion
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.time.Duration

/** Periodos de la capa (A-17: 7 o 30 días). */
val PERIODOS = listOf(7, 30)

/**
 * Mapa de calor (P11, CU-33, CU-34, A-06). Con los reportes publicados y vigentes de cada periodo:
 * 1. Cada reporte pesa lo que dice `peso_riesgo` de su tipo en el catálogo.
 * 2. DBSCAN agrupa los reportes a `dbscan_eps_m` metros o menos, con `dbscan_min_puntos` como mínimo.
 * 3. Cada grupo es una zona: su nivel sale de la suma de pesos (`riesgo_umbral_medio`, `riesgo_umbral_alto`) y su
 *    figura es la envolvente de sus reportes.
 * Se hace una zona general por grupo (sin tipo) y otra por cada tipo que forme su propio grupo. Cada cálculo es una
 * versión nueva de `capa_riesgo`; la anterior deja de ser la vigente. También calcula el riesgo de cada tramo del grafo
 * peatonal (`riesgo_tramo`) para la ruta segura.
 */
class MapaCalorServicio(private val dataSource: DataSource) {
    private val log = LoggerFactory.getLogger(MapaCalorServicio::class.java)
    private val pendiente = AtomicBoolean(false)

    private data class Reporte(val id: UUID, val tipo: Int, val p: Geo.Punto, val peso: Double)

    fun generar(usuarioId: UUID? = null): GeneracionCapa = dataSource.transaccion { conn ->
        conn.consultarUno("SELECT pg_advisory_xact_lock(hashtext('sendero_mapa_calor'))") { }
        val eps = parametro(conn, "dbscan_eps_m", 100.0)
        val minPts = parametro(conn, "dbscan_min_puntos", 3.0).toInt().coerceAtLeast(1)
        val medio = parametro(conn, "riesgo_umbral_medio", 4.0)
        val alto = parametro(conn, "riesgo_umbral_alto", 8.0)

        val version = conn.consultarUno("SELECT COALESCE(max(version), 0) + 1 FROM capa_riesgo") { it.getInt(1) }!!
        conn.ejecutar("UPDATE capa_riesgo SET es_vigente = false WHERE es_vigente")
        val capa = conn.consultarUno(
            "INSERT INTO capa_riesgo (version, parametros, es_vigente) VALUES (?, ?::jsonb, true) RETURNING id",
            version,
            buildJsonObject {
                put("epsM", eps); put("minPuntos", minPts); put("umbralMedio", medio); put("umbralAlto", alto)
                put("periodos", JsonArray(PERIODOS.map(::JsonPrimitive)))
            }.toString(),
        ) { it.getInt(1) }!!

        var zonas = 0
        val usados = mutableSetOf<UUID>()
        for (dias in PERIODOS) {
            val reportes = conn.consultar(
                """SELECT r.id, r.tipo_incidente_id, r.latitud::double precision AS lat, r.longitud::double precision AS lon,
                          tv.peso_riesgo::double precision AS peso
                   FROM reporte_publico p
                   JOIN reporte r ON r.id = p.reporte_id
                   JOIN tipo_incidente_version tv ON tv.version_catalogo_id = r.version_catalogo_id AND tv.tipo_incidente_id = r.tipo_incidente_id
                   WHERE p.retirado_en IS NULL AND p.publicado_en > now() - make_interval(days => ?)""",
                dias,
            ) { Reporte(it.uuid("id"), it.getInt("tipo_incidente_id"), Geo.Punto(it.getDouble("lat"), it.getDouble("lon")), it.getDouble("peso")) }
            usados += reportes.map { it.id }
            val grupos = mutableListOf<Pair<Int?, List<Reporte>>>()
            dbscan(reportes, eps, minPts).forEach { grupos += null to it }
            reportes.groupBy { it.tipo }.forEach { (tipo, deTipo) -> dbscan(deTipo, eps, minPts).forEach { grupos += tipo to it } }
            for ((tipo, grupo) in grupos) {
                guardarZona(conn, capa, tipo, dias, grupo, eps, medio, alto)
                zonas++
            }
        }
        conn.ejecutar("UPDATE capa_riesgo SET num_zonas = ? WHERE id = ?", zonas, capa)
        val tramos = calcularTramos(conn, capa)
        Bitacora.registrar(conn, "GENERAR_MAPA_CALOR", usuarioId, "capa_riesgo", version.toString(),
            buildJsonObject { put("zonas", zonas); put("reportes", usados.size); put("tramos", tramos) })
        GeneracionCapa(version, zonas, usados.size, tramos)
    }

    /** Pide un cálculo nuevo sin esperar (al publicar o retirar un reporte). Varios pedidos juntos se hacen una sola vez. */
    fun pedirRecalculo(scope: CoroutineScope) {
        if (!pendiente.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            delay(2_000)
            pendiente.set(false)
            runCatching { generar() }.onFailure { log.error("Mapa de calor: falló el cálculo", it) }
        }
    }

    /** Recalcula cada [cada], para que los reportes que salen del periodo dejen de contar. */
    fun iniciar(scope: CoroutineScope, cada: Duration) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(cada)
                runCatching { generar() }.onFailure { log.error("Mapa de calor: falló el cálculo programado", it) }
            }
        }
    }

    fun versionVigente(): Int? = dataSource.connection.use { conn ->
        conn.consultarUno("SELECT version FROM capa_riesgo WHERE es_vigente") { it.getInt(1) }
    }

    /** La capa vigente de un periodo, con o sin [tipoId]. Sin tipo, las zonas generales. */
    fun capa(periodoDias: Int, tipoId: Int?): CapaRiesgo {
        if (periodoDias !in PERIODOS) throw errorValidacion(mapOf("periodo" to "Debe ser ${PERIODOS.joinToString(" o ")}"))
        return dataSource.connection.use { conn ->
            val (capaId, version, en) = conn.consultarUno("SELECT id, version, generada_en FROM capa_riesgo WHERE es_vigente") {
                Triple(it.getInt(1), it.getInt(2), it.instante("generada_en").toString())
            } ?: throw ApiException(HttpStatusCode.NotFound, "SIN_CAPA", "Todavía no hay mapa de calor: se genera con los reportes publicados")
            val zonas = conn.consultar(
                """SELECT * FROM zona_riesgo WHERE capa_id = ? AND periodo_dias = ?
                     AND ((?::int IS NULL AND tipo_incidente_id IS NULL) OR tipo_incidente_id = ?)
                   ORDER BY densidad_ponderada DESC""",
                capaId, periodoDias, tipoId, tipoId,
            ) { it.aZona() }
            CapaRiesgo(version, en, periodoDias, zonas)
        }
    }

    fun zona(id: Long): ZonaDetalle = dataSource.connection.use { conn ->
        val zona = conn.consultarUno("SELECT * FROM zona_riesgo WHERE id = ?", id) { it.aZona() }
            ?: throw ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "La zona no existe")
        val motivos = conn.consultar(
            """SELECT tv.nombre, count(*) FROM zona_reporte z JOIN reporte r ON r.id = z.reporte_id
               JOIN tipo_incidente_version tv ON tv.version_catalogo_id = r.version_catalogo_id AND tv.tipo_incidente_id = r.tipo_incidente_id
               WHERE z.zona_id = ? GROUP BY tv.nombre ORDER BY count(*) DESC""",
            id,
        ) { MotivoZona(it.getString(1), it.getInt(2)) }
        val ultimo = conn.consultarUno(
            "SELECT max(p.publicado_en) FROM zona_reporte z JOIN reporte_publico p ON p.reporte_id = z.reporte_id WHERE z.zona_id = ?", id,
        ) { it.instante("max")?.toString() }
        ZonaDetalle(zona, motivos, ultimo)
    }

    /** DBSCAN clásico. Regresa los grupos (el ruido se descarta). */
    private fun dbscan(puntos: List<Reporte>, eps: Double, minPts: Int): List<List<Reporte>> {
        val vecinos = puntos.map { a -> puntos.indices.filter { Geo.distanciaM(a.p, puntos[it].p) <= eps } }
        val grupo = IntArray(puntos.size) { -1 }   // -1 sin visitar, -2 ruido
        var siguiente = 0
        for (i in puntos.indices) {
            if (grupo[i] != -1) continue
            if (vecinos[i].size < minPts) { grupo[i] = -2; continue }
            val g = siguiente++
            grupo[i] = g
            val cola = ArrayDeque(vecinos[i])
            while (cola.isNotEmpty()) {
                val j = cola.removeFirst()
                if (grupo[j] == -2) grupo[j] = g
                if (grupo[j] != -1) continue
                grupo[j] = g
                if (vecinos[j].size >= minPts) cola.addAll(vecinos[j])
            }
        }
        return (0 until siguiente).map { g -> puntos.filterIndexed { i, _ -> grupo[i] == g } }
    }

    private fun guardarZona(conn: Connection, capa: Int, tipo: Int?, dias: Int, g: List<Reporte>, eps: Double, medio: Double, alto: Double) {
        val suma = g.sumOf { it.peso }
        val nivel = when { suma >= alto -> "ALTO"; suma >= medio -> "MEDIO"; else -> "BAJO" }
        val centro = Geo.Punto(g.map { it.p.lat }.average(), g.map { it.p.lon }.average())
        var figura = Geo.envolvente(g.map { it.p })
        if (figura.size < 3) figura = Geo.cuadro(centro, eps / 2)
        val anillo = (figura + figura.first()).map { JsonArray(listOf(JsonPrimitive(it.lon), JsonPrimitive(it.lat))) }
        val geometria = buildJsonObject { put("type", "Polygon"); put("coordinates", JsonArray(listOf(JsonArray(anillo)))) }
        val zona = conn.consultarUno(
            """INSERT INTO zona_riesgo (capa_id, tipo_incidente_id, periodo_dias, nivel, densidad_ponderada, num_reportes,
                                        centroide_latitud, centroide_longitud, bbox_min_lat, bbox_min_lon, bbox_max_lat, bbox_max_lon, geometria)
               VALUES (?, ?, ?, ?::riesgo_nivel, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id""",
            capa, tipo?.toShort(), dias.toShort(), nivel, suma, g.size, centro.lat, centro.lon,
            figura.minOf { it.lat }, figura.minOf { it.lon }, figura.maxOf { it.lat }, figura.maxOf { it.lon }, geometria.toString(),
        ) { it.getLong(1) }!!
        g.forEach { conn.ejecutar("INSERT INTO zona_reporte (zona_id, reporte_id) VALUES (?, ?) ON CONFLICT DO NOTHING", zona, it.id) }
    }

    /**
     * Riesgo de cada tramo del grafo en la capa (A-07): el del nivel más alto de las zonas generales de 30 días cuya caja
     * contiene la mitad del tramo (BAJO 0.3, MEDIO 0.6, ALTO 1). Los tramos fuera de zonas no se guardan (riesgo 0).
     */
    internal fun calcularTramos(conn: Connection, capa: Int): Int = conn.ejecutar(
        """INSERT INTO riesgo_tramo (capa_id, arista_id, riesgo)
           SELECT ?, a.id, max(CASE z.nivel WHEN 'ALTO' THEN 1.0 WHEN 'MEDIO' THEN 0.6 ELSE 0.3 END)
           FROM arista_vial a
           JOIN nodo_vial o ON o.id = a.nodo_origen
           JOIN nodo_vial d ON d.id = a.nodo_destino
           JOIN zona_riesgo z ON z.capa_id = ? AND z.periodo_dias = 30 AND z.tipo_incidente_id IS NULL
            AND (o.latitud + d.latitud) / 2 BETWEEN z.bbox_min_lat AND z.bbox_max_lat
            AND (o.longitud + d.longitud) / 2 BETWEEN z.bbox_min_lon AND z.bbox_max_lon
           GROUP BY a.id
           ON CONFLICT (capa_id, arista_id) DO UPDATE SET riesgo = EXCLUDED.riesgo""",
        capa, capa,
    )

    private fun java.sql.ResultSet.aZona() = Zona(
        id = getLong("id"), nivel = getString("nivel"), tipoIncidenteId = (getObject("tipo_incidente_id") as Number?)?.toInt(),
        periodoDias = getInt("periodo_dias"), densidadPonderada = getDouble("densidad_ponderada"), numReportes = getInt("num_reportes"),
        centroLatitud = getDouble("centroide_latitud"), centroLongitud = getDouble("centroide_longitud"),
        geometria = Json.parseToJsonElement(getString("geometria")).jsonObject,
    )

    private fun parametro(conn: Connection, clave: String, defecto: Double): Double =
        conn.consultarUno("SELECT valor FROM parametro_sistema WHERE clave = ?", clave) { it.getString(1).toDoubleOrNull() } ?: defecto
}
