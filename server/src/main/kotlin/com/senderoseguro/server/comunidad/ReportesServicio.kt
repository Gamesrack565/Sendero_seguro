package com.senderoseguro.server.comunidad

import com.senderoseguro.server.cuentas.errorValidacion
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.dobleONulo
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import com.senderoseguro.server.plataforma.uuidONulo
import com.senderoseguro.server.sincronizacion.ManejadorSync
import com.senderoseguro.server.sincronizacion.ResultadoManejo
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

private val CLAVE_EVIDENCIA = Regex("^evidencia/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{2,5}$")
private val HASH = Regex("^[0-9a-f]{64}$")
private val TELEFONO = Regex("\\+?\\d[\\d\\s().-]{7,}\\d")
private val CORREO = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
private const val OCULTO = "[dato oculto]"

/**
 * Reportes comunitarios (Módulo 3 en el servidor: P9 y P10).
 *
 * - CU-28 y CU-29: la app manda el reporte por internet (`POST /reportes`) o en la cola sin señal (tipo REPORTE). Queda
 *   PENDIENTE de moderación. Si es anónimo no se guarda el autor, sino su id cifrado ([AutorProtegido]).
 * - CU-30: el Administrador publica, rechaza o retira. Publicar crea la versión pública con la ubicación aproximada a
 *   la celda de ~150 m (geohash de 7) y sin el autor si es anónimo; rechazar avisa al autor.
 * - CU-31: consultar la identidad de un anónimo pide justificación y queda en la bitácora.
 * - CU-32: el feed muestra solo lo publicado y vigente, con filtros de tipo, categoría, periodo, cercanía y zona.
 * Cada publicación o retiro pide recalcular el mapa de calor ([alCambiarPublicados]).
 */
class ReportesServicio(
    private val dataSource: DataSource,
    private val autor: AutorProtegido,
    private val alCambiarPublicados: () -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------- Levantar reporte (CU-28, CU-29)

    fun crear(usuarioId: UUID, e: ReporteEntrada, ip: String?): Pair<ReporteRecibido, Boolean> =
        dataSource.transaccion { conn -> guardar(conn, usuarioId, e, ip) }

    /** Manejador de REPORTE en `/sync/lote`: los `datos` son el mismo cuerpo de `POST /reportes`. */
    val manejadorSync = ManejadorSync { conn, contexto, elemento ->
        val e = runCatching { json.decodeFromJsonElement<ReporteEntrada>(elemento.datos) }.getOrNull()
            ?: return@ManejadorSync ResultadoManejo.Rechazado("VALIDACION", "Faltan datos del reporte (id, tipoIncidenteId, descripcion, ubicación, creadoEn)")
        try {
            val (r, nuevo) = guardar(conn, contexto.usuarioId, e, null)
            if (nuevo) ResultadoManejo.Aceptado(r.folio) else ResultadoManejo.Duplicado(r.folio)
        } catch (ex: ApiException) {
            ResultadoManejo.Rechazado(ex.codigo, ex.detalle?.toString() ?: ex.message ?: ex.codigo)
        }
    }

    private fun guardar(conn: Connection, usuarioId: UUID, e: ReporteEntrada, ip: String?): Pair<ReporteRecibido, Boolean> {
        val id = runCatching { UUID.fromString(e.id) }.getOrNull()
        val creadoEn = runCatching { Instant.parse(e.creadoEn) }.getOrNull()
        val descripcion = e.descripcion.trim()
        val maxEvidencias = parametro(conn, "reporte_max_evidencias", 3)
        val errores = buildMap {
            if (id == null) put("id", "Debe ser un UUID")
            if (creadoEn == null) put("creadoEn", "Debe ser una fecha ISO 8601")
            else if (creadoEn.isBefore(Instant.now().minus(Duration.ofDays(7)))) put("creadoEn", "El reporte tiene más de 7 días")
            if (descripcion.length !in 10..2000) put("descripcion", "Describe lo que pasó (10 a 2000 caracteres)")
            if (e.latitud !in -90.0..90.0) put("latitud", "Debe estar entre -90 y 90")
            if (e.longitud !in -180.0..180.0) put("longitud", "Debe estar entre -180 y 180")
            if (e.precisionM != null && e.precisionM !in 0.0..99_999.0) put("precisionM", "Fuera de rango")
            if (e.evidencias.size > maxEvidencias) put("evidencias", "Máximo $maxEvidencias archivos")
            if (e.evidencias.any { !CLAVE_EVIDENCIA.matches(it.clave) || !HASH.matches(it.hashSha256) || it.tamanoBytes <= 0 || it.mime.length > 60 }) {
                put("evidencias", "Usa la clave, el tamaño y el hash que regresó POST /archivos/evidencia")
            }
            if (e.confianzaSugerencia != null && e.confianzaSugerencia !in 0.0..1.0) put("confianzaSugerencia", "Debe estar entre 0 y 1")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)

        conn.consultarUno("SELECT autor_id, folio, estado::text AS estado, autor_cifrado FROM reporte WHERE id = ?", id) {
            Triple(it.uuidONulo("autor_id"), it.getString("folio"), it.getString("estado")) to it.getBytes("autor_cifrado")
        }?.let { (fila, cifrado) ->
            val esMio = fila.first == usuarioId || (cifrado != null && runCatching { autor.descifrar(cifrado) }.getOrNull() == usuarioId)
            if (!esMio) throw ApiException(HttpStatusCode.Conflict, "CONFLICTO", "Ese id de reporte ya existe")
            return ReporteRecibido(id.toString(), fila.second, fila.third, celdaDe(conn, id!!)) to false
        }

        // El tipo tiene que aplicar a reportes en la versión que tiene el teléfono o, si no, en la vigente.
        val (tipoId, versionId) = conn.consultarUno(
            """SELECT tv.tipo_incidente_id, tv.version_catalogo_id
               FROM tipo_incidente_version tv JOIN version_catalogo v ON v.id = tv.version_catalogo_id
               WHERE tv.tipo_incidente_id = ? AND tv.activo AND tv.aplica_a IN ('REPORTE', 'AMBOS')
                 AND (v.estado = 'PUBLICADA' OR (v.id = ? AND v.estado = 'REEMPLAZADA'))
               ORDER BY (v.id = ?) DESC NULLS LAST LIMIT 1""",
            e.tipoIncidenteId, e.versionCatalogoId, e.versionCatalogoId,
        ) { it.getInt(1) to it.getInt(2) }
            ?: throw errorValidacion(mapOf("tipoIncidenteId" to "El tipo no existe o no aplica a reportes"))
        val sugerido = e.tipoSugeridoId?.takeIf { s ->
            e.confianzaSugerencia != null && conn.consultarUno("SELECT 1 FROM tipo_incidente WHERE id = ?", s) { true } == true
        }

        val folio = conn.consultarUno(
            """INSERT INTO reporte (id, tipo_incidente_id, version_catalogo_id, tipo_sugerido_id, confianza_sugerencia, descripcion,
                                    latitud, longitud, precision_m, es_anonimo, autor_id, autor_cifrado, clave_version, estado, creado_en)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDIENTE', ?) RETURNING folio""",
            id, tipoId.toShort(), versionId, sugerido?.toShort(), e.confianzaSugerencia?.takeIf { sugerido != null }, descripcion,
            e.latitud, e.longitud, e.precisionM, e.esAnonimo,
            if (e.esAnonimo) null else usuarioId,
            if (e.esAnonimo) autor.cifrar(usuarioId) else null,
            if (e.esAnonimo) autor.version else null,
            minOf(creadoEn!!, Instant.now()),
        ) { it.getString("folio") }!!
        e.evidencias.forEach { ev ->
            conn.ejecutar(
                """INSERT INTO evidencia (reporte_id, url_objeto, mime, tamano_bytes, hash_sha256) VALUES (?, ?, ?, ?, ?)
                   ON CONFLICT (reporte_id, url_objeto) DO NOTHING""",
                id, ev.clave, ev.mime, ev.tamanoBytes.toInt(), ev.hashSha256,
            )
        }
        // La bitácora no guarda quién levantó un reporte anónimo.
        Bitacora.registrar(conn, "LEVANTAR_REPORTE", if (e.esAnonimo) null else usuarioId, "reporte", id.toString(),
            buildJsonObject { put("folio", folio); put("anonimo", e.esAnonimo); put("evidencias", e.evidencias.size) }, if (e.esAnonimo) null else ip)
        return ReporteRecibido(id.toString(), folio, "PENDIENTE", Geo.geohash(e.latitud, e.longitud)) to true
    }

    /** Los reportes con nombre de la persona (los anónimos no se pueden ligar a ella). */
    fun mios(usuarioId: UUID): List<ReportePropio> = dataSource.connection.use { conn ->
        conn.consultar(
            """SELECT r.id, r.folio, tv.nombre AS tipo, r.descripcion, r.estado::text AS estado, r.creado_en, r.moderado_en
               FROM reporte r JOIN tipo_incidente_version tv ON tv.version_catalogo_id = r.version_catalogo_id AND tv.tipo_incidente_id = r.tipo_incidente_id
               WHERE r.autor_id = ? ORDER BY r.creado_en DESC LIMIT 100""",
            usuarioId,
        ) {
            ReportePropio(it.uuid("id").toString(), it.getString("folio"), it.getString("tipo"), it.getString("descripcion"),
                it.getString("estado"), it.instante("creado_en").toString(), it.instante("moderado_en")?.toString())
        }
    }

    // ---------------------------------------------------------------- Moderación (CU-30, CU-31)

    fun colaModeracion(estado: String?, limite: Int = 50): List<ReporteModeracion> {
        val filtro = estado?.trim()?.uppercase() ?: "PENDIENTE"
        if (filtro !in setOf("PENDIENTE", "PUBLICADO", "RECHAZADO", "RETIRADO")) {
            throw errorValidacion(mapOf("estado" to "Debe ser PENDIENTE, PUBLICADO, RECHAZADO o RETIRADO"))
        }
        return dataSource.connection.use { conn ->
            val ids = conn.consultar(
                "SELECT id FROM reporte WHERE estado = ?::reporte_estado ORDER BY recibido_en ${if (filtro == "PENDIENTE") "" else "DESC"} LIMIT ?",
                filtro, limite.coerceIn(1, 200),
            ) { it.uuid("id") }
            ids.map { leerModeracion(conn, it)!! }
        }
    }

    fun paraModerar(id: UUID): ReporteModeracion = dataSource.connection.use { conn -> leerModeracion(conn, id) } ?: throw noEncontrado()

    fun decidir(admin: UsuarioToken, id: UUID, d: DecisionEntrada, ip: String?): ReporteModeracion {
        val decision = d.decision.trim().uppercase()
        val motivo = d.motivo?.trim()?.ifEmpty { null }
        val errores = buildMap {
            if (decision !in setOf("PUBLICAR", "RECHAZAR", "RETIRAR")) put("decision", "Debe ser PUBLICAR, RECHAZAR o RETIRAR")
            if (decision != "PUBLICAR" && (motivo == null || motivo.length < 5)) put("motivo", "Escribe el motivo")
            if ((motivo?.length ?: 0) > 500) put("motivo", "Máximo 500 caracteres")
            if ((d.referencia?.length ?: 0) > 160) put("referencia", "Máximo 160 caracteres")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        val unidoA = d.unidoAId?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: throw errorValidacion(mapOf("unidoAId" to "No es un UUID")) }

        val cambio = dataSource.transaccion { conn ->
            val r = conn.consultarUno(
                """SELECT r.estado::text AS estado, r.autor_id, r.autor_cifrado, r.es_anonimo, r.descripcion, r.latitud::double precision AS lat,
                          r.longitud::double precision AS lon, r.tipo_incidente_id, r.folio, u.nombre
                   FROM reporte r LEFT JOIN usuario u ON u.id = r.autor_id WHERE r.id = ? FOR UPDATE OF r""",
                id,
            ) {
                FilaReporte(it.getString("estado"), it.uuidONulo("autor_id"), it.getBytes("autor_cifrado"), it.getBoolean("es_anonimo"),
                    it.getString("descripcion"), it.getDouble("lat"), it.getDouble("lon"), it.getShort("tipo_incidente_id"),
                    it.getString("folio"), it.getString("nombre"))
            } ?: throw noEncontrado()
            val estado = r.estado
            val permitido = when (decision) {
                "RETIRAR" -> estado == "PUBLICADO"
                else -> estado == "PENDIENTE"
            }
            if (!permitido) {
                throw ApiException(HttpStatusCode.Conflict, "TRANSICION_INVALIDA", "No se puede $decision un reporte $estado",
                    buildJsonObject { put("estadoActual", estado) })
            }
            if (unidoA != null) {
                if (decision != "PUBLICAR") throw errorValidacion(mapOf("unidoAId" to "Solo al publicar"))
                if (unidoA == id) throw errorValidacion(mapOf("unidoAId" to "No puede unirse a sí mismo"))
                val publicado = conn.consultarUno("SELECT estado = 'PUBLICADO' FROM reporte WHERE id = ?", unidoA) { it.getBoolean(1) }
                if (publicado != true) throw errorValidacion(mapOf("unidoAId" to "Debe ser un reporte publicado"))
            }
            conn.ejecutar(
                """INSERT INTO decision_moderacion (reporte_id, moderador_id, decision, motivo, unido_a_id, oculta_datos_personales)
                   VALUES (?, ?, ?::moderacion_decision, ?, ?, ?)""",
                id, admin.usuarioId, decision, motivo, unidoA, d.ocultaDatosPersonales && decision == "PUBLICAR",
            )
            val nuevo = mapOf("PUBLICAR" to "PUBLICADO", "RECHAZAR" to "RECHAZADO", "RETIRAR" to "RETIRADO").getValue(decision)
            conn.ejecutar("UPDATE reporte SET estado = ?::reporte_estado, moderado_en = now() WHERE id = ?", nuevo, id)
            when (decision) {
                "PUBLICAR" -> {
                    val celda = Geo.geohash(r.lat, r.lon)
                    val centro = Geo.centro(celda)
                    var texto = r.descripcion
                    if (d.ocultaDatosPersonales) texto = texto.replace(CORREO, OCULTO).replace(TELEFONO, OCULTO)
                    conn.ejecutar(
                        """INSERT INTO reporte_publico (reporte_id, tipo_incidente_id, descripcion, celda, latitud_aprox, longitud_aprox, referencia, autor_visible)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                        id, r.tipoId, texto, celda, centro.lat, centro.lon,
                        d.referencia?.trim()?.ifEmpty { null },
                        if (r.esAnonimo) null else r.nombre,
                    )
                }
                "RECHAZAR" -> {
                    // Se avisa al autor (CU-30 3a). Si es anónimo, solo el servidor sabe a quién.
                    val autorId = r.autorId ?: r.autorCifrado?.let { runCatching { autor.descifrar(it) }.getOrNull() }
                    autorId?.let {
                        conn.ejecutar("INSERT INTO notificacion (reporte_id, usuario_id, tipo, canal) VALUES (?, ?, 'REPORTE_RECHAZADO', 'PUSH')", id, it)
                    }
                }
                "RETIRAR" -> conn.ejecutar("UPDATE reporte_publico SET retirado_en = now() WHERE reporte_id = ?", id)
            }
            Bitacora.registrar(conn, "MODERAR_REPORTE", admin.usuarioId, "reporte", id.toString(),
                buildJsonObject {
                    put("decision", decision); put("folio", r.folio)
                    motivo?.let { put("motivo", it) }
                    unidoA?.let { put("unidoA", it.toString()) }
                }, ip)
            decision != "RECHAZAR"
        }
        if (cambio) runCatching(alCambiarPublicados)
        return paraModerar(id)
    }

    fun identidad(admin: UsuarioToken, id: UUID, s: SolicitudIdentidad, ip: String?): IdentidadAutor {
        val justificacion = s.justificacion.trim()
        if (justificacion.length !in 20..1000) throw errorValidacion(mapOf("justificacion" to "Explica por qué (20 a 1000 caracteres)"))
        return dataSource.transaccion { conn ->
            val (anonimo, cifrado) = conn.consultarUno("SELECT es_anonimo, autor_cifrado, folio FROM reporte WHERE id = ?", id) {
                it.getBoolean(1) to it.getBytes(2)
            } ?: throw noEncontrado()
            if (!anonimo || cifrado == null) throw ApiException(HttpStatusCode.Conflict, "NO_ES_ANONIMO", "El reporte no es anónimo: el autor ya es visible para moderación")
            val usuarioId = runCatching { autor.descifrar(cifrado) }.getOrElse {
                throw ApiException(HttpStatusCode.InternalServerError, "ERROR_INTERNO", "No se pudo descifrar: la llave del servidor cambió")
            }
            Bitacora.registrar(conn, "CONSULTAR_IDENTIDAD_PROTEGIDA", admin.usuarioId, "reporte", id.toString(),
                buildJsonObject { put("justificacion", justificacion) }, ip)
            conn.consultarUno(
                """SELECT u.id, u.nombre, u.identificador_institucional, u.telefono_movil, e.nombre AS escuela
                   FROM usuario u JOIN escuela e ON e.id = u.escuela_id WHERE u.id = ?""",
                usuarioId,
            ) { IdentidadAutor(it.uuid("id").toString(), it.getString("nombre"), it.getString("identificador_institucional"), it.getString("telefono_movil"), it.getString("escuela")) }
                ?: throw noEncontrado()
        }
    }

    // ---------------------------------------------------------------- Feed (CU-32)

    /**
     * Reportes publicados y vigentes, del más nuevo al más viejo. [siguiente] es el cursor de la página anterior.
     * Con [lat]/[lon] solo los que están a [radioM] o menos (por el centro de su celda).
     */
    fun feed(
        tipoId: Int?, categoria: String?, dias: Int?, lat: Double?, lon: Double?, radioM: Double?, zonaId: Int?,
        siguiente: String?, limite: Int = 30,
    ): PaginaFeed {
        val cat = categoria?.trim()?.uppercase()?.ifEmpty { null }
        val errores = buildMap {
            if (cat != null && cat !in setOf("FISICO", "SOCIAL", "DIGITAL")) put("categoria", "Debe ser FISICO, SOCIAL o DIGITAL")
            if (dias != null && dias !in 1..365) put("dias", "Entre 1 y 365")
            if ((lat == null) != (lon == null)) put("latitud", "Manda latitud y longitud juntas")
            if (radioM != null && radioM !in 50.0..50_000.0) put("radioM", "Entre 50 y 50000 metros")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        val cursor = siguiente?.let { decodificarCursor(it) }
        val radio = radioM ?: 2_000.0
        return dataSource.connection.use { conn ->
            val caja = zonaId?.let { z ->
                conn.consultarUno("SELECT bbox_min_lat, bbox_min_lon, bbox_max_lat, bbox_max_lon FROM zona_geografica WHERE id = ? AND activa", z) {
                    Geo.Caja(it.getDouble(1), it.getDouble(2), it.getDouble(3), it.getDouble(4))
                } ?: throw errorValidacion(mapOf("zonaId" to "La zona no existe"))
            }
            val filas = conn.consultar(
                """SELECT p.reporte_id, p.tipo_incidente_id, tv.nombre AS tipo, tv.categoria::text AS categoria, p.descripcion, p.celda,
                          p.latitud_aprox::double precision AS lat, p.longitud_aprox::double precision AS lon, p.referencia,
                          COALESCE(p.autor_visible, 'Anónimo') AS autor, p.publicado_en,
                          CASE WHEN ?::double precision IS NULL THEN NULL
                               ELSE fn_distancia_m(?::double precision, ?::double precision, p.latitud_aprox::double precision, p.longitud_aprox::double precision) END AS distancia
                   FROM reporte_publico p
                   JOIN reporte r ON r.id = p.reporte_id
                   JOIN tipo_incidente_version tv ON tv.version_catalogo_id = r.version_catalogo_id AND tv.tipo_incidente_id = r.tipo_incidente_id
                   WHERE p.retirado_en IS NULL
                     AND (?::int IS NULL OR p.tipo_incidente_id = ?)
                     AND (?::text IS NULL OR tv.categoria = ?::incidente_categoria)
                     AND (?::int IS NULL OR p.publicado_en > now() - make_interval(days => ?::int))
                     AND (?::double precision IS NULL OR fn_distancia_m(?::double precision, ?::double precision, p.latitud_aprox::double precision, p.longitud_aprox::double precision) <= ?)
                     AND (?::double precision IS NULL OR p.latitud_aprox BETWEEN ? AND ? AND p.longitud_aprox BETWEEN ? AND ?)
                     AND (?::timestamptz IS NULL OR (p.publicado_en, p.reporte_id) < (?::timestamptz, ?::uuid))
                   ORDER BY p.publicado_en DESC, p.reporte_id DESC
                   LIMIT ?""",
                lat, lat, lon,
                tipoId, tipoId, cat, cat, dias, dias,
                lat, lat, lon, radio,
                caja?.minLat, caja?.minLat, caja?.maxLat, caja?.minLon, caja?.maxLon,
                cursor?.first, cursor?.first, cursor?.second,
                limite.coerceIn(1, 100) + 1,
            ) { it.aPublico() }
            val pagina = filas.take(limite.coerceIn(1, 100))
            val hayMas = filas.size > pagina.size
            PaginaFeed(pagina, if (hayMas) pagina.last().let { codificarCursor(Instant.parse(it.publicadoEn), UUID.fromString(it.id)) } else null)
        }
    }

    // ---------------------------------------------------------------- Lectura

    private fun leerModeracion(conn: Connection, id: UUID): ReporteModeracion? {
        val evidencias = conn.consultar("SELECT url_objeto, mime, tamano_bytes FROM evidencia WHERE reporte_id = ? ORDER BY creada_en", id) {
            Evidencia(it.getString(1), "/archivos/" + it.getString(1), it.getString(2), it.getLong(3))
        }
        val decisiones = conn.consultar(
            """SELECT d.decision::text, d.motivo, d.decidido_en, u.folio FROM decision_moderacion d
               LEFT JOIN reporte u ON u.id = d.unido_a_id WHERE d.reporte_id = ? ORDER BY d.decidido_en""",
            id,
        ) { Decision(it.getString(1), it.getString(2), it.instante("decidido_en").toString(), it.getString(4)) }
        return conn.consultarUno(
            """SELECT r.*, r.latitud::double precision AS lat, r.longitud::double precision AS lon, tv.nombre AS tipo,
                      tv.categoria::text AS categoria, u.nombre AS autor_nombre
               FROM reporte r
               JOIN tipo_incidente_version tv ON tv.version_catalogo_id = r.version_catalogo_id AND tv.tipo_incidente_id = r.tipo_incidente_id
               LEFT JOIN usuario u ON u.id = r.autor_id
               WHERE r.id = ?""",
            id,
        ) {
            val celda = Geo.geohash(it.getDouble("lat"), it.getDouble("lon"))
            val centro = Geo.centro(celda)
            ReporteModeracion(
                id = it.uuid("id").toString(), folio = it.getString("folio"), tipoIncidenteId = it.getInt("tipo_incidente_id"),
                tipo = it.getString("tipo"), categoria = it.getString("categoria"), descripcion = it.getString("descripcion"),
                latitud = it.getDouble("lat"), longitud = it.getDouble("lon"), precisionM = it.dobleONulo("precision_m"),
                celda = celda, latitudPublica = centro.lat, longitudPublica = centro.lon,
                autor = if (it.getBoolean("es_anonimo")) "Anónimo" else it.getString("autor_nombre") ?: "—",
                esAnonimo = it.getBoolean("es_anonimo"), estado = it.getString("estado"),
                creadoEn = it.instante("creado_en").toString(), recibidoEn = it.instante("recibido_en").toString(),
                evidencias = evidencias, tipoSugeridoId = (it.getObject("tipo_sugerido_id") as Number?)?.toInt(),
                confianzaSugerencia = it.dobleONulo("confianza_sugerencia"), decisiones = decisiones,
            )
        }
    }

    private fun ResultSet.aPublico() = ReportePublico(
        id = uuid("reporte_id").toString(), tipoIncidenteId = getInt("tipo_incidente_id"), tipo = getString("tipo"),
        categoria = getString("categoria"), descripcion = getString("descripcion"), celda = getString("celda"),
        latitud = getDouble("lat"), longitud = getDouble("lon"), referencia = getString("referencia"), autor = getString("autor"),
        publicadoEn = instante("publicado_en").toString(), distanciaM = dobleONulo("distancia")?.let { Math.round(it).toDouble() },
    )

    private fun celdaDe(conn: Connection, id: UUID): String = conn.consultarUno(
        "SELECT latitud::double precision, longitud::double precision FROM reporte WHERE id = ?", id,
    ) { Geo.geohash(it.getDouble(1), it.getDouble(2)) }!!

    private fun parametro(conn: Connection, clave: String, defecto: Int): Int =
        conn.consultarUno("SELECT valor FROM parametro_sistema WHERE clave = ?", clave) { it.getString(1).toIntOrNull() } ?: defecto

    private fun codificarCursor(en: Instant, id: UUID) = Base64.getUrlEncoder().withoutPadding().encodeToString("$en|$id".toByteArray())

    private fun decodificarCursor(texto: String): Pair<Instant, UUID> = runCatching {
        val (en, id) = String(Base64.getUrlDecoder().decode(texto)).split('|')
        Instant.parse(en) to UUID.fromString(id)
    }.getOrElse { throw errorValidacion(mapOf("siguiente" to "Cursor inválido")) }

    private fun noEncontrado() = ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "El reporte no existe")

    private class FilaReporte(
        val estado: String, val autorId: UUID?, val autorCifrado: ByteArray?, val esAnonimo: Boolean, val descripcion: String,
        val lat: Double, val lon: Double, val tipoId: Short, val folio: String, val nombre: String?,
    )
}
