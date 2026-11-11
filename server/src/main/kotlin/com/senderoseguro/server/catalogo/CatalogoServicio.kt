package com.senderoseguro.server.catalogo

import com.senderoseguro.server.cuentas.errorDuplicado
import com.senderoseguro.server.cuentas.errorValidacion
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuidONulo
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

private val CATEGORIAS = setOf("FISICO", "SOCIAL", "DIGITAL")
private val APLICA = setOf("ALERTA", "REPORTE", "AMBOS")
private val DATOS = setOf("ubicacion", "descripcion", "audio", "foto")
private val DESTINOS = setOf("CORPORACION", "OPERADOR", "COORDINADOR_CIRCULO")
private val CODIGO_REGEX = Regex("^[A-Z0-9_]{3,40}$")
private val EDITABLES = setOf("BORRADOR", "EN_VALIDACION")

/**
 * Catálogo de incidentes y reglas (P3, CU-08, CU-09, A-08, E-09, consola W-06 y W-07).
 *
 * - La app descarga la versión PUBLICADA (`GET /catalogo`).
 * - El Administrador abre una versión BORRADOR (copia de la vigente), edita tipos y reglas, la valida y la publica.
 *   Solo puede haber una versión en edición. Al publicar, la anterior queda REEMPLAZADA; las alertas ya registradas
 *   conservan la versión con la que llegaron.
 * - La validación (A-08) impide publicar si un tipo activo de alerta no tiene canal, si su canal está inactivo o si no
 *   tiene cadena de escalamiento, y advierte si el canal no tiene unidades.
 */
class CatalogoServicio(private val dataSource: DataSource) {

    // ---------------------------------------------------------------- Lo que descarga la app

    /** El catálogo vigente, solo con tipos activos. [aplicaA] ALERTA o REPORTE filtra (AMBOS entra en los dos). */
    fun vigente(aplicaA: String?): CatalogoVigente = dataSource.connection.use { conn ->
        val filtro = aplicaA?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (filtro != null && filtro !in setOf("ALERTA", "REPORTE")) throw errorValidacion(mapOf("aplicaA" to "Debe ser ALERTA o REPORTE"))
        val v = conn.consultarUno("SELECT * FROM version_catalogo WHERE estado = 'PUBLICADA'") { it.aVersion() }
            ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "SIN_CATALOGO", "No hay un catálogo publicado")
        val tipos = tipos(conn, v.id).filter { it.activo && (filtro == null || it.aplicaA == filtro || it.aplicaA == "AMBOS") }
        CatalogoVigente(v.id, v.numero, v.publicadaEn!!, tipos)
    }

    /** Número de la versión publicada, para que la app pregunte si cambió sin bajar todo. */
    fun numeroVigente(): Int? = dataSource.connection.use { conn ->
        conn.consultarUno("SELECT numero FROM version_catalogo WHERE estado = 'PUBLICADA'") { it.getInt(1) }
    }

    // ---------------------------------------------------------------- Administración (W-06, W-07)

    fun versiones(): List<VersionResumen> = dataSource.connection.use { conn ->
        conn.consultar("SELECT * FROM version_catalogo ORDER BY numero DESC") { it.aVersion() }
    }

    fun detalle(numero: Int): VersionDetalle = dataSource.connection.use { conn -> detalle(conn, version(conn, numero)) }

    /** Abre una versión BORRADOR copiando la vigente con sus reglas y cadenas. 409 si ya hay una en edición. */
    fun crearBorrador(admin: UsuarioToken, ip: String?): VersionDetalle = dataSource.transaccion { conn ->
        conn.consultarUno("SELECT pg_advisory_xact_lock(hashtext('sendero_catalogo'))") { }
        conn.consultarUno("SELECT numero FROM version_catalogo WHERE estado IN ('BORRADOR', 'EN_VALIDACION')") { it.getInt(1) }?.let {
            throw ApiException(HttpStatusCode.Conflict, "EDICION_ABIERTA", "Ya hay una versión en edición (v$it); publícala o descártala",
                buildJsonObject { put("numero", it) })
        }
        val vigente = conn.consultarUno("SELECT id FROM version_catalogo WHERE estado = 'PUBLICADA'") { it.getInt(1) }
        val nueva = conn.consultarUno(
            """INSERT INTO version_catalogo (numero, estado, creada_por)
               VALUES ((SELECT COALESCE(max(numero), 0) + 1 FROM version_catalogo), 'BORRADOR', ?) RETURNING id, numero""",
            admin.usuarioId,
        ) { it.getInt(1) to it.getInt(2) }!!
        if (vigente != null) {
            conn.ejecutar(
                """INSERT INTO tipo_incidente_version (version_catalogo_id, tipo_incidente_id, es_generica, nombre, categoria, prioridad,
                                                       aplica_a, datos_minimos, peso_riesgo, activo)
                   SELECT ?, tipo_incidente_id, es_generica, nombre, categoria, prioridad, aplica_a, datos_minimos, peso_riesgo, activo
                   FROM tipo_incidente_version WHERE version_catalogo_id = ?""",
                nueva.first, vigente,
            )
            val reglas = conn.consultar("SELECT id, tipo_incidente_id FROM regla_canalizacion WHERE version_catalogo_id = ?", vigente) {
                it.getInt(1) to it.getShort(2)
            }
            for ((reglaVieja, _) in reglas) {
                val reglaNueva = conn.consultarUno(
                    """INSERT INTO regla_canalizacion (version_catalogo_id, tipo_incidente_id, corporacion_destino_id, tiempo_max_acuse_seg)
                       SELECT ?, tipo_incidente_id, corporacion_destino_id, tiempo_max_acuse_seg FROM regla_canalizacion WHERE id = ?
                       RETURNING id""",
                    nueva.first, reglaVieja,
                ) { it.getInt(1) }!!
                conn.ejecutar(
                    """INSERT INTO paso_escalamiento (regla_id, orden, destino_tipo, corporacion_id, operador_id)
                       SELECT ?, orden, destino_tipo, corporacion_id, operador_id FROM paso_escalamiento WHERE regla_id = ?""",
                    reglaNueva, reglaVieja,
                )
            }
        }
        Bitacora.registrar(conn, "CREAR_VERSION_CATALOGO", admin.usuarioId, "version_catalogo", nueva.second.toString(),
            buildJsonObject { put("numero", nueva.second) }, ip)
        detalle(conn, version(conn, nueva.second))
    }

    /** Crea o edita un tipo en la versión en edición (CU-08 paso 2, 2a). */
    fun guardarTipo(admin: UsuarioToken, numero: Int, codigoTexto: String, e: TipoEntrada, ip: String?): VersionDetalle {
        val codigo = codigoTexto.trim().uppercase()
        val nombre = e.nombre.trim()
        val categoria = e.categoria?.trim()?.uppercase()?.ifEmpty { null }
        val aplica = e.aplicaA.trim().uppercase()
        val datos = e.datosMinimos.map { it.trim().lowercase() }.distinct()
        val errores = buildMap {
            if (!CODIGO_REGEX.matches(codigo)) put("codigo", "Mayúsculas, números o guion bajo (3 a 40)")
            if (nombre.length !in 3..60) put("nombre", "Debe tener entre 3 y 60 caracteres")
            if (e.prioridad !in 1..5) put("prioridad", "Debe estar entre 1 (máxima) y 5")
            if (aplica !in APLICA) put("aplicaA", "Debe ser ALERTA, REPORTE o AMBOS")
            if (categoria != null && categoria !in CATEGORIAS) put("categoria", "Debe ser FISICO, SOCIAL o DIGITAL")
            if (datos.any { it !in DATOS }) put("datosMinimos", "Solo ${DATOS.joinToString()}")
            if (e.pesoRiesgo != null && e.pesoRiesgo !in 0.0..99.0) put("pesoRiesgo", "Debe estar entre 0 y 99")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        return dataSource.transaccion { conn ->
            val v = editable(conn, numero)
            val existente = conn.consultarUno("SELECT id, es_generica FROM tipo_incidente WHERE codigo = ?", codigo) {
                it.getShort(1) to it.getBoolean(2)
            }
            val generica = existente?.second == true
            val errores2 = buildMap {
                if (generica && categoria != null) put("categoria", "La genérica no lleva categoría")
                if (!generica && categoria == null) put("categoria", "Obligatoria: FISICO, SOCIAL o DIGITAL")
                if (generica && aplica != "ALERTA") put("aplicaA", "La genérica solo aplica a alertas")
                if (generica && !e.activo) put("activo", "La genérica no se puede desactivar: es el respaldo de toda alerta")
            }
            if (errores2.isNotEmpty()) throw errorValidacion(errores2)
            val tipoId = existente?.first ?: conn.consultarUno(
                "INSERT INTO tipo_incidente (codigo, es_generica) VALUES (?, false) RETURNING id", codigo,
            ) { it.getShort(1) }!!
            conDuplicado("nombre", "Ya hay un tipo con ese nombre en la versión") {
                conn.ejecutar(
                    """INSERT INTO tipo_incidente_version (version_catalogo_id, tipo_incidente_id, es_generica, nombre, categoria, prioridad,
                                                           aplica_a, datos_minimos, peso_riesgo, activo)
                       VALUES (?, ?, ?, ?, ?::incidente_categoria, ?, ?::tipo_aplica_a, ?::jsonb, COALESCE(?, 1), ?)
                       ON CONFLICT (version_catalogo_id, tipo_incidente_id) DO UPDATE SET
                         nombre = EXCLUDED.nombre, categoria = EXCLUDED.categoria, prioridad = EXCLUDED.prioridad,
                         aplica_a = EXCLUDED.aplica_a, datos_minimos = EXCLUDED.datos_minimos,
                         peso_riesgo = COALESCE(?, tipo_incidente_version.peso_riesgo), activo = EXCLUDED.activo""",
                    v.id, tipoId, generica, nombre, categoria, e.prioridad.toShort(), aplica,
                    JsonArray(datos.map(::JsonPrimitive)).toString(), e.pesoRiesgo, e.activo, e.pesoRiesgo,
                )
            }
            // Un tipo que deja de aplicar a alertas ya no necesita regla.
            if (aplica == "REPORTE") conn.ejecutar("DELETE FROM regla_canalizacion WHERE version_catalogo_id = ? AND tipo_incidente_id = ?", v.id, tipoId)
            regresarABorrador(conn, v)
            Bitacora.registrar(conn, if (existente == null) "CREAR_TIPO_INCIDENTE" else "EDITAR_TIPO_INCIDENTE", admin.usuarioId,
                "tipo_incidente", codigo,
                buildJsonObject {
                    put("version", numero); put("nombre", nombre); put("prioridad", e.prioridad)
                    put("aplicaA", aplica); put("activo", e.activo)
                }, ip)
            detalle(conn, version(conn, numero))
        }
    }

    /** Canal, tiempo de acuse y cadena de escalamiento de un tipo (CU-09). Reemplaza la regla que tuviera. */
    fun guardarRegla(admin: UsuarioToken, numero: Int, codigoTexto: String, e: ReglaEntrada, ip: String?): VersionDetalle {
        val codigo = codigoTexto.trim().uppercase()
        val errores = buildMap {
            if (e.tiempoMaxAcuseSeg !in 30..600) put("tiempoMaxAcuseSeg", "Debe estar entre 30 y 600 segundos")
            if (e.pasos.isEmpty()) put("pasos", "Define al menos un nivel de escalamiento")
            if (e.pasos.size > 10) put("pasos", "Máximo 10 niveles")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        return dataSource.transaccion { conn ->
            val v = editable(conn, numero)
            val (tipoId, aplica) = conn.consultarUno(
                """SELECT tv.tipo_incidente_id, tv.aplica_a::text FROM tipo_incidente_version tv
                   JOIN tipo_incidente t ON t.id = tv.tipo_incidente_id WHERE tv.version_catalogo_id = ? AND t.codigo = ?""",
                v.id, codigo,
            ) { it.getShort(1) to it.getString(2) } ?: throw ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "El tipo $codigo no está en la versión")
            if (aplica == "REPORTE") throw errorValidacion(mapOf("tipo" to "Los tipos solo de reporte no se canalizan"))
            if (!corporacionActiva(conn, e.corporacionId)) throw errorValidacion(mapOf("corporacionId" to "La corporación no existe o está inactiva"))

            val vistos = mutableSetOf("CORPORACION:${e.corporacionId}")
            val pasos = e.pasos.mapIndexed { i, p ->
                val destino = p.destinoTipo.trim().uppercase()
                val campo = "pasos[$i]"
                when (destino) {
                    "CORPORACION" -> {
                        val c = p.corporacionId ?: throw errorValidacion(mapOf(campo to "Falta corporacionId"))
                        if (!corporacionActiva(conn, c)) throw errorValidacion(mapOf(campo to "La corporación no existe o está inactiva"))
                        if (!vistos.add("CORPORACION:$c")) throw errorValidacion(mapOf(campo to "Destino repetido"))
                        Triple(destino, c, null as UUID?)
                    }
                    "OPERADOR" -> {
                        val o = p.operadorId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                            ?: throw errorValidacion(mapOf(campo to "Falta operadorId"))
                        val esOperador = conn.consultarUno("SELECT rol = 'OPERADOR' AND estado = 'ACTIVA' FROM usuario WHERE id = ?", o) { it.getBoolean(1) }
                        if (esOperador != true) throw errorValidacion(mapOf(campo to "La cuenta no es un Operador activo"))
                        if (!vistos.add("OPERADOR:$o")) throw errorValidacion(mapOf(campo to "Destino repetido"))
                        Triple(destino, null as Int?, o)
                    }
                    "COORDINADOR_CIRCULO" -> {
                        if (!vistos.add(destino)) throw errorValidacion(mapOf(campo to "Destino repetido"))
                        Triple(destino, null as Int?, null as UUID?)
                    }
                    else -> throw errorValidacion(mapOf(campo to "destinoTipo debe ser ${DESTINOS.joinToString()}"))
                }
            }
            conn.ejecutar("DELETE FROM regla_canalizacion WHERE version_catalogo_id = ? AND tipo_incidente_id = ?", v.id, tipoId)
            val regla = conn.consultarUno(
                """INSERT INTO regla_canalizacion (version_catalogo_id, tipo_incidente_id, corporacion_destino_id, tiempo_max_acuse_seg)
                   VALUES (?, ?, ?, ?) RETURNING id""",
                v.id, tipoId, e.corporacionId, e.tiempoMaxAcuseSeg,
            ) { it.getInt(1) }!!
            pasos.forEachIndexed { i, (destino, corp, operador) ->
                conn.ejecutar(
                    "INSERT INTO paso_escalamiento (regla_id, orden, destino_tipo, corporacion_id, operador_id) VALUES (?, ?, ?::destino_escalamiento, ?, ?)",
                    regla, (i + 1).toShort(), destino, corp, operador,
                )
            }
            regresarABorrador(conn, v)
            Bitacora.registrar(conn, "GUARDAR_REGLA_CANALIZACION", admin.usuarioId, "tipo_incidente", codigo,
                buildJsonObject {
                    put("version", numero); put("corporacionId", e.corporacionId); put("tiempoMaxAcuseSeg", e.tiempoMaxAcuseSeg)
                    put("pasos", pasos.size)
                }, ip)
            detalle(conn, version(conn, numero))
        }
    }

    /** Corre A-08. Sin errores la versión pasa a EN_VALIDACION; con errores se queda en BORRADOR. */
    fun validar(admin: UsuarioToken, numero: Int, ip: String?): VersionDetalle = dataSource.transaccion { conn ->
        val v = editable(conn, numero)
        val d = detalle(conn, v)
        val nuevo = if (d.errores.isEmpty()) "EN_VALIDACION" else "BORRADOR"
        conn.ejecutar("UPDATE version_catalogo SET estado = ?::catalogo_estado WHERE id = ?", nuevo, v.id)
        Bitacora.registrar(conn, "VALIDAR_CATALOGO", admin.usuarioId, "version_catalogo", numero.toString(),
            buildJsonObject { put("errores", d.errores.size); put("advertencias", d.advertencias.size) }, ip)
        detalle(conn, version(conn, numero))
    }

    /** Publica la versión si pasa A-08 (CU-08 paso 4). La anterior queda REEMPLAZADA. */
    fun publicar(admin: UsuarioToken, numero: Int, ip: String?): VersionDetalle = dataSource.transaccion { conn ->
        conn.consultarUno("SELECT pg_advisory_xact_lock(hashtext('sendero_catalogo'))") { }
        val v = editable(conn, numero)
        val d = detalle(conn, v)
        if (d.errores.isNotEmpty()) {
            throw ApiException(HttpStatusCode.Conflict, "CATALOGO_INVALIDO", "La versión tiene errores; corrígelos antes de publicar",
                buildJsonObject {
                    put("errores", JsonArray(d.errores.map { p -> buildJsonObject { put("codigo", p.codigo); put("mensaje", p.mensaje); p.tipo?.let { put("tipo", it) } } }))
                })
        }
        val anterior = conn.consultarUno("SELECT numero FROM version_catalogo WHERE estado = 'PUBLICADA'") { it.getInt(1) }
        conn.ejecutar("UPDATE version_catalogo SET estado = 'REEMPLAZADA' WHERE estado = 'PUBLICADA'")
        conn.ejecutar("UPDATE version_catalogo SET estado = 'PUBLICADA', publicada_en = now() WHERE id = ?", v.id)
        Bitacora.registrar(conn, "PUBLICAR_CATALOGO", admin.usuarioId, "version_catalogo", numero.toString(),
            buildJsonObject { put("numero", numero); anterior?.let { put("reemplaza", it) }; put("advertencias", d.advertencias.size) }, ip)
        detalle(conn, version(conn, numero))
    }

    /** Descarta la versión en edición con sus tipos y reglas. Los tipos nuevos quedan sin versión y no se muestran. */
    fun descartar(admin: UsuarioToken, numero: Int, ip: String?) = dataSource.transaccion { conn ->
        val v = editable(conn, numero)
        conn.ejecutar("DELETE FROM regla_canalizacion WHERE version_catalogo_id = ?", v.id)
        conn.ejecutar("DELETE FROM tipo_incidente_version WHERE version_catalogo_id = ?", v.id)
        conn.ejecutar("DELETE FROM version_catalogo WHERE id = ?", v.id)
        Bitacora.registrar(conn, "DESCARTAR_VERSION_CATALOGO", admin.usuarioId, "version_catalogo", numero.toString(), ip = ip)
    }

    // ---------------------------------------------------------------- Lectura y validación

    private fun detalle(conn: Connection, v: VersionResumen): VersionDetalle {
        val tipos = tipos(conn, v.id)
        val reglas = reglas(conn, v.id)
        val conRegla = tipos.map { TipoConRegla(it, reglas[it.id]) }
        val errores = mutableListOf<Problema>()
        val advertencias = mutableListOf<Problema>()
        if (tipos.none { it.esGenerica && it.activo }) {
            errores += Problema("SIN_GENERICA", "Falta la alerta genérica activa: es el respaldo de toda alerta sin tipo")
        }
        for ((t, r) in conRegla) {
            if (!t.activo || t.aplicaA == "REPORTE") continue
            when {
                r == null -> errores += Problema("SIN_REGLA", "${t.nombre}: no tiene canal destinatario", t.codigo)
                r.pasos.isEmpty() -> errores += Problema("SIN_ESCALAMIENTO", "${t.nombre}: no tiene cadena de escalamiento", t.codigo)
            }
            if (r != null) {
                if (!corporacionActiva(conn, r.corporacionId)) {
                    errores += Problema("CANAL_INACTIVO", "${t.nombre}: el canal ${r.corporacion} está inactivo", t.codigo)
                } else if (!canalConUnidades(conn, r.corporacionId, t.id)) {
                    advertencias += Problema("CANAL_SIN_UNIDADES", "${t.nombre}: ${r.corporacion} no tiene unidades activas que lo atiendan", t.codigo)
                }
                r.pasos.filter { it.destinoTipo == "CORPORACION" && it.corporacionId != null && !corporacionActiva(conn, it.corporacionId) }.forEach {
                    errores += Problema("ESCALAMIENTO_INACTIVO", "${t.nombre}: el nivel ${it.orden} va a una corporación inactiva", t.codigo)
                }
            }
        }
        return VersionDetalle(v, conRegla, errores, advertencias)
    }

    private fun tipos(conn: Connection, versionId: Int): List<TipoCatalogo> = conn.consultar(
        """SELECT tv.tipo_incidente_id, t.codigo, tv.nombre, tv.categoria::text AS categoria, tv.prioridad, tv.aplica_a::text AS aplica,
                  tv.datos_minimos::text AS datos, tv.es_generica, tv.activo, tv.peso_riesgo::double precision AS peso
           FROM tipo_incidente_version tv JOIN tipo_incidente t ON t.id = tv.tipo_incidente_id
           WHERE tv.version_catalogo_id = ?
           ORDER BY tv.es_generica DESC, tv.prioridad, tv.nombre""",
        versionId,
    ) {
        TipoCatalogo(
            id = it.getInt("tipo_incidente_id"), codigo = it.getString("codigo"), nombre = it.getString("nombre"),
            categoria = it.getString("categoria"), prioridad = it.getInt("prioridad"), aplicaA = it.getString("aplica"),
            datosMinimos = Json.parseToJsonElement(it.getString("datos")).jsonArray.map { d -> d.jsonPrimitive.content },
            esGenerica = it.getBoolean("es_generica"), activo = it.getBoolean("activo"), pesoRiesgo = it.getDouble("peso"),
        )
    }

    private fun reglas(conn: Connection, versionId: Int): Map<Int, Regla> {
        val pasos = conn.consultar(
            """SELECT p.regla_id, p.orden, p.destino_tipo::text AS destino, p.corporacion_id, c.nombre AS corporacion, p.operador_id
               FROM paso_escalamiento p JOIN regla_canalizacion r ON r.id = p.regla_id
               LEFT JOIN corporacion c ON c.id = p.corporacion_id
               WHERE r.version_catalogo_id = ? ORDER BY p.regla_id, p.orden""",
            versionId,
        ) {
            it.getInt("regla_id") to PasoEscalamiento(
                it.getInt("orden"), it.getString("destino"), (it.getObject("corporacion_id") as Number?)?.toInt(),
                it.getString("corporacion"), it.uuidONulo("operador_id")?.toString(),
            )
        }.groupBy({ it.first }, { it.second })
        return conn.consultar(
            """SELECT r.id, r.tipo_incidente_id, r.corporacion_destino_id, c.nombre, r.tiempo_max_acuse_seg
               FROM regla_canalizacion r JOIN corporacion c ON c.id = r.corporacion_destino_id WHERE r.version_catalogo_id = ?""",
            versionId,
        ) { it.getInt("tipo_incidente_id") to Regla(it.getInt("corporacion_destino_id"), it.getString("nombre"), it.getInt("tiempo_max_acuse_seg"), pasos[it.getInt("id")].orEmpty()) }
            .toMap()
    }

    private fun corporacionActiva(conn: Connection, id: Int): Boolean =
        conn.consultarUno("SELECT activa FROM corporacion WHERE id = ?", id) { it.getBoolean(1) } == true

    private fun canalConUnidades(conn: Connection, corporacionId: Int, tipoId: Int): Boolean = conn.consultarUno(
        """SELECT 1 FROM unidad_atencion u WHERE u.corporacion_id = ? AND u.activa
             AND (NOT EXISTS (SELECT 1 FROM unidad_tipo_atencion x WHERE x.unidad_id = u.id)
                  OR EXISTS (SELECT 1 FROM unidad_tipo_atencion x WHERE x.unidad_id = u.id AND x.tipo_incidente_id = ?))
           LIMIT 1""",
        corporacionId, tipoId,
    ) { true } == true

    private fun version(conn: Connection, numero: Int): VersionResumen =
        conn.consultarUno("SELECT * FROM version_catalogo WHERE numero = ?", numero) { it.aVersion() }
            ?: throw ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "La versión $numero no existe")

    private fun editable(conn: Connection, numero: Int): VersionResumen {
        val v = conn.consultarUno("SELECT * FROM version_catalogo WHERE numero = ? FOR UPDATE", numero) { it.aVersion() }
            ?: throw ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "La versión $numero no existe")
        if (v.estado !in EDITABLES) throw ApiException(HttpStatusCode.Conflict, "VERSION_CERRADA", "La versión $numero ya está ${v.estado}; abre un borrador nuevo")
        return v
    }

    /** Editar una versión que ya pasó la validación la regresa a BORRADOR: hay que validarla otra vez. */
    private fun regresarABorrador(conn: Connection, v: VersionResumen) {
        if (v.estado == "EN_VALIDACION") conn.ejecutar("UPDATE version_catalogo SET estado = 'BORRADOR' WHERE id = ?", v.id)
    }

    private fun ResultSet.aVersion() = VersionResumen(
        getInt("id"), getInt("numero"), getString("estado"), instante("creada_en").toString(), instante("publicada_en")?.toString(),
    )

    private fun <T> conDuplicado(campo: String, mensaje: String, bloque: () -> T): T = try {
        bloque()
    } catch (e: SQLException) {
        if (e.sqlState == "23505") throw errorDuplicado(campo, mensaje) else throw e
    }
}
