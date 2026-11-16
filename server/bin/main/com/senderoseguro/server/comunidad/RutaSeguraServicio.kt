package com.senderoseguro.server.comunidad

import com.senderoseguro.server.cuentas.errorValidacion
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.transaccion
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.util.PriorityQueue
import javax.sql.DataSource
import kotlin.math.cos
import kotlin.math.roundToInt

private const val VELOCIDAD_PEATON_MS = 1.3
private const val MAX_DISTANCIA_A_GRAFO_M = 400.0
private const val MARGEN_CAJA_M = 1_500.0
private const val MAX_NODOS_CARGA = 100_000

/**
 * Ruta peatonal segura (P12, CU-35, A-07) y grafo para la caché del teléfono (CU-36).
 *
 * El grafo (nodos y tramos de OpenStreetMap) lo carga el Administrador con `POST /grafo`. Con internet, el servidor
 * calcula dos rutas con A*: la más corta (solo distancia) y la segura, con peso = distancia × (1 + α · riesgo del
 * tramo), α = `riesgo_alfa`. El riesgo de cada tramo sale de la capa vigente del mapa de calor. Sin internet, la app
 * hace el mismo cálculo con el grafo que descargó de `GET /grafo`.
 */
class RutaSeguraServicio(private val dataSource: DataSource, private val mapaCalor: MapaCalorServicio) {

    private class Nodo(val id: Long, val p: Geo.Punto)
    private class Tramo(val destino: Long, val largo: Double, val riesgo: Double)

    fun calcular(s: SolicitudRuta): RutasSugeridas {
        val o = Geo.Punto(s.origen.latitud, s.origen.longitud)
        val d = Geo.Punto(s.destino.latitud, s.destino.longitud)
        val errores = buildMap {
            if (o.lat !in -90.0..90.0 || d.lat !in -90.0..90.0) put("latitud", "Debe estar entre -90 y 90")
            if (o.lon !in -180.0..180.0 || d.lon !in -180.0..180.0) put("longitud", "Debe estar entre -180 y 180")
            if (Geo.distanciaM(o, d) > 20_000) put("destino", "La ruta a pie no puede pasar de 20 km")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)

        return dataSource.connection.use { conn ->
            val alfa = conn.consultarUno("SELECT valor FROM parametro_sistema WHERE clave = 'riesgo_alfa'") { it.getString(1).toDoubleOrNull() } ?: 0.35
            val capa = conn.consultarUno("SELECT id, version FROM capa_riesgo WHERE es_vigente") { it.getInt(1) to it.getInt(2) }
            val caja = cajaDe(listOf(o, d), MARGEN_CAJA_M)
            val (nodos, tramos) = cargar(conn, caja, capa?.first)
            val inicio = masCercano(nodos.values, o) ?: throw fueraDeArea("origen")
            val fin = masCercano(nodos.values, d) ?: throw fueraDeArea("destino")

            val corta = aEstrella(nodos, tramos, inicio.id, fin.id) { it.largo }
                ?: throw ApiException(HttpStatusCode.UnprocessableEntity, "SIN_RUTA", "No hay un camino a pie entre esos puntos en el grafo")
            val segura = aEstrella(nodos, tramos, inicio.id, fin.id) { it.largo * (1 + alfa * it.riesgo) }!!

            val rutaCorta = ruta("MAS_CORTA", corta, nodos, tramos, o, d)
            val rutaSegura = ruta("SEGURA", segura, nodos, tramos, o, d)
            val advertencias = capa?.let { advertencias(conn, it.first, rutaCorta) }.orEmpty()
            val explicacion = when {
                corta == segura -> "La ruta más corta ya es la más segura con la información disponible."
                else -> "La ruta segura mide ${(rutaSegura.distanciaM - rutaCorta.distanciaM).roundToInt()} m más y evita tramos con reportes " +
                    "(riesgo medio ${"%.2f".format(rutaSegura.riesgoMedio)} contra ${"%.2f".format(rutaCorta.riesgoMedio)})."
            }
            RutasSugeridas(rutaSegura, rutaCorta, advertencias, capa?.second, explicacion = explicacion)
        }
    }

    /** Grafo de una caja para el teléfono, con el riesgo de cada tramo en la capa vigente. */
    fun grafo(caja: Geo.Caja): Grafo {
        val dLat = caja.maxLat - caja.minLat
        val dLon = caja.maxLon - caja.minLon
        if (dLat <= 0 || dLon <= 0 || dLat > 0.2 || dLon > 0.2) throw errorValidacion(mapOf("caja" to "La caja debe medir menos de 0.2 grados por lado"))
        return dataSource.connection.use { conn ->
            val capa = conn.consultarUno("SELECT id, version FROM capa_riesgo WHERE es_vigente") { it.getInt(1) to it.getInt(2) }
            val (nodos, _) = cargar(conn, caja, capa?.first)
            val aristas = conn.consultar(
                """SELECT a.id, a.nodo_origen, a.nodo_destino, a.longitud_m::double precision, a.bidireccional, COALESCE(t.riesgo, 0)::double precision
                   FROM arista_vial a
                   JOIN nodo_vial o ON o.id = a.nodo_origen
                   LEFT JOIN riesgo_tramo t ON t.arista_id = a.id AND t.capa_id = ?
                   WHERE o.latitud BETWEEN ? AND ? AND o.longitud BETWEEN ? AND ?""",
                capa?.first, caja.minLat, caja.maxLat, caja.minLon, caja.maxLon,
            ) { Arista(it.getLong(1), it.getLong(2), it.getLong(3), it.getDouble(4), it.getBoolean(5), it.getDouble(6)) }
            Grafo(capa?.second, nodos.values.map { NodoEntrada(it.id, it.p.lat, it.p.lon) }, aristas)
        }
    }

    /** Carga o actualiza el grafo (Administrador). Los tramos con nodos desconocidos se rechazan. */
    fun cargarGrafo(admin: UsuarioToken, g: GrafoEntrada, ip: String?): GrafoCargado {
        val errores = buildMap {
            if (g.nodos.size > MAX_NODOS_CARGA || g.aristas.size > MAX_NODOS_CARGA * 2) put("nodos", "Carga el grafo por partes (máximo $MAX_NODOS_CARGA nodos)")
            if (g.nodos.any { it.latitud !in -90.0..90.0 || it.longitud !in -180.0..180.0 }) put("nodos", "Hay coordenadas fuera de rango")
            if (g.aristas.any { it.origen == it.destino }) put("aristas", "Un tramo no puede empezar y terminar en el mismo nodo")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        return dataSource.transaccion { conn ->
            val ps = conn.prepareStatement(
                """INSERT INTO nodo_vial (id, latitud, longitud, tesela) VALUES (?, ?, ?, ?)
                   ON CONFLICT (id) DO UPDATE SET latitud = EXCLUDED.latitud, longitud = EXCLUDED.longitud, tesela = EXCLUDED.tesela""",
            )
            ps.use {
                g.nodos.forEach { n ->
                    it.setLong(1, n.id); it.setDouble(2, n.latitud); it.setDouble(3, n.longitud); it.setString(4, Geo.geohash(n.latitud, n.longitud, 6))
                    it.addBatch()
                }
                it.executeBatch()
            }
            val ids = g.aristas.flatMap { listOf(it.origen, it.destino) }.distinct()
            val ubicaciones = ids.chunked(5_000).flatMap { parte ->
                conn.consultar(
                    "SELECT id, latitud::double precision, longitud::double precision FROM nodo_vial WHERE id = ANY (?)",
                    conn.createArrayOf("bigint", parte.toTypedArray()),
                ) { it.getLong(1) to Geo.Punto(it.getDouble(2), it.getDouble(3)) }
            }.toMap()
            val faltan = ids.filter { it !in ubicaciones }
            if (faltan.isNotEmpty()) throw errorValidacion(mapOf("aristas" to "Nodos que no existen: ${faltan.take(10).joinToString()}"))
            conn.prepareStatement(
                """INSERT INTO arista_vial (nodo_origen, nodo_destino, longitud_m, nombre_via, tipo_via, bidireccional, tesela, osm_way_id)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT (nodo_origen, nodo_destino) DO UPDATE SET longitud_m = EXCLUDED.longitud_m, nombre_via = EXCLUDED.nombre_via,
                     tipo_via = EXCLUDED.tipo_via, bidireccional = EXCLUDED.bidireccional, tesela = EXCLUDED.tesela, osm_way_id = EXCLUDED.osm_way_id""",
            ).use {
                g.aristas.forEach { a ->
                    val po = ubicaciones.getValue(a.origen)
                    val largo = Geo.distanciaM(po, ubicaciones.getValue(a.destino)).coerceAtLeast(0.01)
                    it.setLong(1, a.origen); it.setLong(2, a.destino); it.setDouble(3, largo)
                    it.setString(4, a.nombreVia?.take(120)); it.setString(5, a.tipoVia?.take(30)); it.setBoolean(6, a.bidireccional)
                    it.setString(7, Geo.geohash(po.lat, po.lon, 6))
                    if (a.osmWayId == null) it.setNull(8, java.sql.Types.BIGINT) else it.setLong(8, a.osmWayId)
                    it.addBatch()
                }
                it.executeBatch()
            }
            val tramos = conn.consultarUno("SELECT id FROM capa_riesgo WHERE es_vigente") { it.getInt(1) }?.let { mapaCalor.calcularTramos(conn, it) } ?: 0
            Bitacora.registrar(conn, "CARGAR_GRAFO_PEATONAL", admin.usuarioId, "arista_vial", null,
                buildJsonObject { put("nodos", g.nodos.size); put("aristas", g.aristas.size) }, ip)
            GrafoCargado(g.nodos.size, g.aristas.size, tramos)
        }
    }

    private fun cargar(conn: Connection, caja: Geo.Caja, capaId: Int?): Pair<Map<Long, Nodo>, Map<Long, List<Tramo>>> {
        val nodos = conn.consultar(
            "SELECT id, latitud::double precision, longitud::double precision FROM nodo_vial WHERE latitud BETWEEN ? AND ? AND longitud BETWEEN ? AND ?",
            caja.minLat, caja.maxLat, caja.minLon, caja.maxLon,
        ) { Nodo(it.getLong(1), Geo.Punto(it.getDouble(2), it.getDouble(3))) }.associateBy { it.id }
        val tramos = mutableMapOf<Long, MutableList<Tramo>>()
        conn.consultar(
            """SELECT a.nodo_origen, a.nodo_destino, a.longitud_m::double precision, a.bidireccional, COALESCE(t.riesgo, 0)::double precision
               FROM arista_vial a
               JOIN nodo_vial o ON o.id = a.nodo_origen
               LEFT JOIN riesgo_tramo t ON t.arista_id = a.id AND t.capa_id = ?
               WHERE o.latitud BETWEEN ? AND ? AND o.longitud BETWEEN ? AND ?""",
            capaId, caja.minLat, caja.maxLat, caja.minLon, caja.maxLon,
        ) { listOf(it.getLong(1), it.getLong(2)) to Triple(it.getDouble(3), it.getBoolean(4), it.getDouble(5)) }
            .forEach { (par, datos) ->
                val (a, b) = par
                if (a !in nodos || b !in nodos) return@forEach
                tramos.getOrPut(a) { mutableListOf() } += Tramo(b, datos.first, datos.third)
                if (datos.second) tramos.getOrPut(b) { mutableListOf() } += Tramo(a, datos.first, datos.third)
            }
        return nodos to tramos
    }

    private fun masCercano(nodos: Collection<Nodo>, p: Geo.Punto): Nodo? =
        nodos.minByOrNull { Geo.distanciaM(it.p, p) }?.takeIf { Geo.distanciaM(it.p, p) <= MAX_DISTANCIA_A_GRAFO_M }

    /** A* con la distancia en línea recta como heurística (nunca sobreestima: el peso es al menos el largo). */
    private fun aEstrella(nodos: Map<Long, Nodo>, tramos: Map<Long, List<Tramo>>, inicio: Long, fin: Long, peso: (Tramo) -> Double): List<Long>? {
        val meta = nodos.getValue(fin).p
        val costo = HashMap<Long, Double>().apply { put(inicio, 0.0) }
        val previo = HashMap<Long, Long>()
        val abiertos = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
        abiertos += inicio to Geo.distanciaM(nodos.getValue(inicio).p, meta)
        val cerrados = HashSet<Long>()
        while (abiertos.isNotEmpty()) {
            val (actual, _) = abiertos.poll()
            if (actual == fin) break
            if (!cerrados.add(actual)) continue
            for (t in tramos[actual].orEmpty()) {
                val nuevo = costo.getValue(actual) + peso(t)
                if (nuevo < (costo[t.destino] ?: Double.MAX_VALUE)) {
                    costo[t.destino] = nuevo
                    previo[t.destino] = actual
                    abiertos += t.destino to nuevo + Geo.distanciaM(nodos.getValue(t.destino).p, meta)
                }
            }
        }
        if (fin != inicio && fin !in previo) return null
        val camino = mutableListOf(fin)
        while (camino.last() != inicio) camino += previo.getValue(camino.last())
        return camino.reversed()
    }

    private fun ruta(tipo: String, camino: List<Long>, nodos: Map<Long, Nodo>, tramos: Map<Long, List<Tramo>>, o: Geo.Punto, d: Geo.Punto): Ruta {
        var largo = 0.0
        var riesgo = 0.0
        camino.zipWithNext().forEach { (a, b) ->
            val t = tramos[a].orEmpty().filter { it.destino == b }.minBy { it.largo }
            largo += t.largo
            riesgo += t.largo * t.riesgo
        }
        val puntos = listOf(o) + camino.map { nodos.getValue(it).p } + d
        val total = largo + Geo.distanciaM(o, puntos[1]) + Geo.distanciaM(puntos[puntos.size - 2], d)
        return Ruta(
            tipo, puntos.map { Coordenada(it.lat, it.lon) }, Math.round(total).toDouble(),
            (total / VELOCIDAD_PEATON_MS / 60).roundToInt().coerceAtLeast(1),
            if (largo > 0) Math.round(riesgo / largo * 1000) / 1000.0 else 0.0,
        )
    }

    /** Zonas MEDIO o ALTO (30 días, generales) por las que pasa la ruta más corta. */
    private fun advertencias(conn: Connection, capaId: Int, r: Ruta): List<Advertencia> {
        val zonas = conn.consultar(
            """SELECT id, nivel::text, bbox_min_lat, bbox_min_lon, bbox_max_lat, bbox_max_lon, num_reportes FROM zona_riesgo
               WHERE capa_id = ? AND periodo_dias = 30 AND tipo_incidente_id IS NULL AND nivel IN ('MEDIO', 'ALTO')""",
            capaId,
        ) { Triple(it.getLong(1), it.getString(2), Geo.Caja(it.getDouble(3), it.getDouble(4), it.getDouble(5), it.getDouble(6))) to it.getInt(7) }
        return zonas.filter { (z, _) -> r.puntos.any { z.third.contiene(Geo.Punto(it.latitud, it.longitud), 20.0) } }
            .map { (z, n) -> Advertencia(z.first, z.second, "La ruta más corta pasa por una zona de riesgo ${z.second.lowercase()} ($n reportes en 30 días)") }
    }

    private fun cajaDe(puntos: List<Geo.Punto>, margenM: Double): Geo.Caja {
        val lat = puntos.map { it.lat }
        val lon = puntos.map { it.lon }
        val dLat = margenM / 111_320.0
        val dLon = margenM / (111_320.0 * cos(Math.toRadians(lat.average())).coerceAtLeast(0.01))
        return Geo.Caja(lat.min() - dLat, lon.min() - dLon, lat.max() + dLat, lon.max() + dLon)
    }

    private fun fueraDeArea(campo: String) = ApiException(
        HttpStatusCode.UnprocessableEntity, "FUERA_DE_AREA", "El $campo está fuera del área con mapa peatonal",
        buildJsonObject { put("campo", campo) },
    )
}
