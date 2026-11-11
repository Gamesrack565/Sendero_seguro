package com.senderoseguro.server.administracion

import com.senderoseguro.server.cuentas.errorDuplicado
import com.senderoseguro.server.cuentas.errorValidacion
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.dobleONulo
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import com.senderoseguro.server.plataforma.uuidONulo
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

private val TIPOS_CORPORACION = setOf("SEGURIDAD_ESCOLAR", "POLICIA", "BOMBEROS", "ERUM", "OTRA")
private val CANAL_REGEX = Regex("^[A-Z0-9_]{2,30}$")

/** Lee un id de la ruta o del cuerpo; si no es un UUID responde 400 con el campo. */
internal fun uuidDe(texto: String?, campo: String): UUID =
    texto?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
        ?: throw errorValidacion(mapOf(campo to "No es un id válido"))

private fun noEncontrado(mensaje: String) = ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", mensaje)
private fun conflicto(codigo: String, mensaje: String) = ApiException(HttpStatusCode.Conflict, codigo, mensaje)

/**
 * Administración de la plataforma (P3 y P1, consola web W-08 y W-09):
 * - CU-07: buscar cuentas, asignar el perfil (y la unidad del Operador), desbloquear y suspender.
 * - CU-10: corporaciones (canales), unidades de atención con los tipos que atienden y sus operadores.
 * Todo cambio queda en la bitácora en la misma transacción. Cambiar el perfil o suspender cierra las sesiones de
 * la cuenta, para que el siguiente token ya traiga los permisos nuevos.
 */
class AdministracionServicio(private val dataSource: DataSource) {

    // ---------------------------------------------------------------- Usuarios y perfiles (CU-07, W-09)

    fun buscarUsuarios(buscar: String?, rol: String?, limite: Int = 50): List<UsuarioAdmin> {
        val texto = buscar?.trim().orEmpty()
        val rolFiltro = rol?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        if (rolFiltro != null && rolFiltro !in Rol.entries.map { it.name }) {
            throw errorValidacion(mapOf("rol" to "Debe ser uno de: ${Rol.entries.joinToString()}"))
        }
        if (texto.length < 3 && rolFiltro == null) {
            throw errorValidacion(mapOf("buscar" to "Escribe al menos 3 caracteres"))
        }
        val patron = if (texto.isEmpty()) null else "%" + texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return dataSource.connection.use { conn ->
            conn.consultar(
                """$SELECT_USUARIO
                   WHERE u.estado <> 'ELIMINADA'
                     AND (?::text IS NULL OR u.nombre ILIKE ? OR u.identificador_institucional ILIKE ? OR u.telefono_movil LIKE ?)
                     AND (?::text IS NULL OR u.rol = ?::usuario_rol)
                   ORDER BY u.nombre
                   LIMIT ?""",
                patron, patron, patron, patron, rolFiltro, rolFiltro, limite.coerceIn(1, 200),
            ) { it.aUsuario() }
        }
    }

    fun cambiarPerfil(admin: UsuarioToken, usuarioId: UUID, c: CambioPerfil, ip: String?): UsuarioAdmin {
        val rol = c.rol.trim().uppercase()
        val errores = buildMap {
            if (rol !in Rol.entries.map { it.name }) put("rol", "Debe ser uno de: ${Rol.entries.joinToString()}")
            if (c.motivo.trim().length < 10) put("motivo", "Escribe el motivo del cambio (mínimo 10 caracteres)")
            if (rol == Rol.OPERADOR.name && c.unidadId.isNullOrBlank()) put("unidadId", "El Operador necesita su unidad de atención")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        if (usuarioId == admin.usuarioId) throw conflicto("PROPIA_CUENTA", "No puedes cambiar tu propio perfil; pídeselo a otro Administrador")
        val unidadId = if (rol == Rol.OPERADOR.name) uuidDe(c.unidadId, "unidadId") else null

        return dataSource.transaccion { conn ->
            val anterior = conn.consultarUno("SELECT rol::text, estado::text FROM usuario WHERE id = ? FOR UPDATE", usuarioId) {
                it.getString(1) to it.getString(2)
            } ?: throw noEncontrado("La cuenta no existe")
            if (anterior.second == "ELIMINADA") throw conflicto("CUENTA_ELIMINADA", "La cuenta fue eliminada")
            val unidadAnterior = conn.consultarUno("SELECT unidad_id FROM operador WHERE usuario_id = ? AND activo", usuarioId) { it.getObject(1, UUID::class.java) }

            if (unidadId != null) {
                val activa = conn.consultarUno("SELECT activa FROM unidad_atencion WHERE id = ?", unidadId) { it.getBoolean(1) }
                    ?: throw errorValidacion(mapOf("unidadId" to "La unidad no existe"))
                if (!activa) throw errorValidacion(mapOf("unidadId" to "La unidad está dada de baja"))
                conn.ejecutar(
                    """INSERT INTO operador (usuario_id, unidad_id, activo) VALUES (?, ?, true)
                       ON CONFLICT (usuario_id) DO UPDATE SET unidad_id = EXCLUDED.unidad_id, activo = true,
                           desde = CASE WHEN operador.unidad_id = EXCLUDED.unidad_id AND operador.activo THEN operador.desde ELSE now() END""",
                    usuarioId, unidadId,
                )
            } else {
                conn.ejecutar("UPDATE operador SET activo = false WHERE usuario_id = ? AND activo", usuarioId)
            }
            conn.ejecutar("UPDATE usuario SET rol = ?::usuario_rol WHERE id = ?", rol, usuarioId)
            val sesiones = cerrarSesiones(conn, usuarioId)
            Bitacora.registrar(
                conn, "CAMBIAR_PERFIL", admin.usuarioId, "usuario", usuarioId.toString(),
                buildJsonObject {
                    put("rolAnterior", anterior.first)
                    put("rolNuevo", rol)
                    unidadAnterior?.let { put("unidadAnterior", it.toString()) }
                    unidadId?.let { put("unidadNueva", it.toString()) }
                    put("motivo", c.motivo.trim().take(500))
                    put("sesionesCerradas", sesiones)
                },
                ip,
            )
            leerUsuario(conn, usuarioId)!!
        }
    }

    fun cambiarEstado(admin: UsuarioToken, usuarioId: UUID, c: CambioEstadoCuenta, ip: String?): UsuarioAdmin {
        val estado = c.estado.trim().uppercase()
        val errores = buildMap {
            if (estado !in setOf("ACTIVA", "SUSPENDIDA")) put("estado", "Debe ser ACTIVA (desbloquear) o SUSPENDIDA")
            if (c.motivo.trim().length < 10) put("motivo", "Escribe el motivo (mínimo 10 caracteres)")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        if (usuarioId == admin.usuarioId) throw conflicto("PROPIA_CUENTA", "No puedes cambiar el estado de tu propia cuenta")

        return dataSource.transaccion { conn ->
            val anterior = conn.consultarUno("SELECT estado::text FROM usuario WHERE id = ? FOR UPDATE", usuarioId) { it.getString(1) }
                ?: throw noEncontrado("La cuenta no existe")
            if (anterior in setOf("ELIMINADA", "BAJA_SOLICITADA")) {
                throw conflicto("TRANSICION_INVALIDA", "La cuenta pidió su baja o fue eliminada; no se puede cambiar su estado")
            }
            var sesiones = 0
            if (estado == "ACTIVA") {
                conn.ejecutar("UPDATE usuario SET estado = 'ACTIVA', intentos_fallidos = 0, bloqueada_hasta = NULL WHERE id = ?", usuarioId)
            } else {
                conn.ejecutar("UPDATE usuario SET estado = 'SUSPENDIDA' WHERE id = ?", usuarioId)
                sesiones = cerrarSesiones(conn, usuarioId)
            }
            Bitacora.registrar(
                conn, if (estado == "ACTIVA") "DESBLOQUEAR_CUENTA" else "SUSPENDER_CUENTA", admin.usuarioId, "usuario", usuarioId.toString(),
                buildJsonObject {
                    put("estadoAnterior", anterior)
                    put("estadoNuevo", estado)
                    put("motivo", c.motivo.trim().take(500))
                    put("sesionesCerradas", sesiones)
                },
                ip,
            )
            leerUsuario(conn, usuarioId)!!
        }
    }

    private fun cerrarSesiones(conn: Connection, usuarioId: UUID): Int =
        conn.ejecutar("UPDATE sesion SET revocada_en = now() WHERE usuario_id = ? AND revocada_en IS NULL", usuarioId)

    private fun leerUsuario(conn: Connection, usuarioId: UUID): UsuarioAdmin? =
        conn.consultarUno("$SELECT_USUARIO WHERE u.id = ?", usuarioId) { it.aUsuario() }

    private fun ResultSet.aUsuario() = UsuarioAdmin(
        id = uuid("id").toString(),
        nombre = getString("nombre"),
        identificador = getString("identificador_institucional"),
        telefono = getString("telefono_movil"),
        escuela = getString("escuela"),
        rol = getString("rol"),
        estado = getString("estado"),
        unidadId = uuidONulo("unidad_id")?.toString(),
        unidad = getString("unidad"),
    )

    // ---------------------------------------------------------------- Corporaciones (CU-10, W-08)

    fun corporaciones(): List<Corporacion> = dataSource.connection.use { conn ->
        conn.consultar(
            """SELECT c.id, c.nombre, c.tipo::text AS tipo, c.canal_codigo, c.activa,
                      (SELECT count(*) FROM unidad_atencion u WHERE u.corporacion_id = c.id AND u.activa) AS unidades
               FROM corporacion c ORDER BY c.nombre""",
        ) { it.aCorporacion() }
    }

    fun crearCorporacion(admin: UsuarioToken, e: CorporacionEntrada, ip: String?): Corporacion {
        val nombre = e.nombre.trim()
        val tipo = e.tipo.trim().uppercase()
        val canal = e.canalCodigo.trim().uppercase()
        validarCorporacion(nombre, tipo, canal)
        return dataSource.transaccion { conn ->
            val id = conDuplicados("nombre", "Ya existe una corporación con ese nombre o código de canal") {
                conn.consultarUno(
                    "INSERT INTO corporacion (nombre, tipo, canal_codigo) VALUES (?, ?::corporacion_tipo, ?) RETURNING id",
                    nombre, tipo, canal,
                ) { it.getInt(1) }!!
            }
            Bitacora.registrar(conn, "CREAR_CORPORACION", admin.usuarioId, "corporacion", id.toString(),
                buildJsonObject { put("nombre", nombre); put("tipo", tipo); put("canalCodigo", canal) }, ip)
            leerCorporacion(conn, id)!!
        }
    }

    fun editarCorporacion(admin: UsuarioToken, id: Int, c: CorporacionCambio, ip: String?): Corporacion {
        val nombre = c.nombre?.trim()
        val tipo = c.tipo?.trim()?.uppercase()
        validarCorporacion(nombre, tipo, null)
        return dataSource.transaccion { conn ->
            leerCorporacion(conn, id) ?: throw noEncontrado("La corporación no existe")
            conDuplicados("nombre", "Ya existe una corporación con ese nombre") {
                conn.ejecutar(
                    """UPDATE corporacion SET nombre = COALESCE(?, nombre), tipo = COALESCE(?::corporacion_tipo, tipo),
                                              activa = COALESCE(?, activa) WHERE id = ?""",
                    nombre, tipo, c.activa, id,
                )
            }
            Bitacora.registrar(conn, "EDITAR_CORPORACION", admin.usuarioId, "corporacion", id.toString(),
                buildJsonObject {
                    nombre?.let { put("nombre", it) }
                    tipo?.let { put("tipo", it) }
                    c.activa?.let { put("activa", it) }
                }, ip)
            leerCorporacion(conn, id)!!
        }
    }

    private fun validarCorporacion(nombre: String?, tipo: String?, canal: String?) {
        val errores = buildMap {
            if (nombre != null && nombre.length !in 3..100) put("nombre", "Debe tener entre 3 y 100 caracteres")
            if (tipo != null && tipo !in TIPOS_CORPORACION) put("tipo", "Debe ser uno de: ${TIPOS_CORPORACION.joinToString()}")
            if (canal != null && !CANAL_REGEX.matches(canal)) put("canalCodigo", "Mayúsculas, números o guion bajo (2 a 30)")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
    }

    private fun leerCorporacion(conn: Connection, id: Int): Corporacion? = conn.consultarUno(
        """SELECT c.id, c.nombre, c.tipo::text AS tipo, c.canal_codigo, c.activa,
                  (SELECT count(*) FROM unidad_atencion u WHERE u.corporacion_id = c.id AND u.activa) AS unidades
           FROM corporacion c WHERE c.id = ?""",
        id,
    ) { it.aCorporacion() }

    private fun ResultSet.aCorporacion() = Corporacion(
        getInt("id"), getString("nombre"), getString("tipo"), getString("canal_codigo"), getBoolean("activa"), getInt("unidades"),
    )

    // ---------------------------------------------------------------- Unidades y operadores (CU-10, W-08)

    fun unidades(corporacionId: Int?): List<Unidad> = dataSource.connection.use { conn ->
        val ids = conn.consultar(
            "SELECT u.id FROM unidad_atencion u JOIN corporacion c ON c.id = u.corporacion_id WHERE (?::int IS NULL OR u.corporacion_id = ?) ORDER BY c.nombre, u.nombre",
            corporacionId, corporacionId,
        ) { it.getObject(1, UUID::class.java) }
        ids.mapNotNull { leerUnidad(conn, it) }
    }

    fun unidad(id: UUID): Unidad = dataSource.connection.use { conn -> leerUnidad(conn, id) } ?: throw noEncontrado("La unidad no existe")

    fun crearUnidad(admin: UsuarioToken, e: UnidadEntrada, ip: String?): Unidad {
        val nombre = e.nombre.trim()
        if (nombre.length !in 2..120) throw errorValidacion(mapOf("nombre" to "Debe tener entre 2 y 120 caracteres"))
        return dataSource.transaccion { conn ->
            val corporacionActiva = conn.consultarUno("SELECT activa FROM corporacion WHERE id = ?", e.corporacionId) { it.getBoolean(1) }
                ?: throw errorValidacion(mapOf("corporacionId" to "La corporación no existe"))
            if (!corporacionActiva) throw errorValidacion(mapOf("corporacionId" to "La corporación está inactiva"))
            val tipos = tiposActivos(conn, e.tipos)
            val id = conDuplicados("nombre", "Ya existe una unidad con ese nombre en la corporación") {
                conn.consultarUno(
                    "INSERT INTO unidad_atencion (corporacion_id, nombre) VALUES (?, ?) RETURNING id",
                    e.corporacionId, nombre,
                ) { it.getObject(1, UUID::class.java) }!!
            }
            guardarTipos(conn, id, tipos)
            Bitacora.registrar(conn, "CREAR_UNIDAD", admin.usuarioId, "unidad_atencion", id.toString(),
                buildJsonObject {
                    put("nombre", nombre)
                    put("corporacionId", e.corporacionId)
                    put("tipos", JsonArray(tipos.values.map(::JsonPrimitive)))
                }, ip)
            leerUnidad(conn, id)!!
        }
    }

    fun editarUnidad(admin: UsuarioToken, id: UUID, c: UnidadCambio, ip: String?): Unidad {
        val nombre = c.nombre?.trim()
        if (nombre != null && nombre.length !in 2..120) throw errorValidacion(mapOf("nombre" to "Debe tener entre 2 y 120 caracteres"))
        return dataSource.transaccion { conn ->
            val estado = conn.consultarUno("SELECT estado::text FROM unidad_atencion WHERE id = ? FOR UPDATE", id) { it.getString(1) }
                ?: throw noEncontrado("La unidad no existe")
            if (c.activa == false && estado in setOf("ASIGNADA", "ATENDIENDO")) {
                throw conflicto("UNIDAD_OCUPADA", "La unidad está atendiendo una alerta; dala de baja cuando la libere")
            }
            val tipos = c.tipos?.let { tiposActivos(conn, it) }
            conDuplicados("nombre", "Ya existe una unidad con ese nombre en la corporación") {
                conn.ejecutar(
                    """UPDATE unidad_atencion SET nombre = COALESCE(?, nombre), activa = COALESCE(?, activa),
                              estado = CASE WHEN ? = false THEN 'FUERA_DE_SERVICIO'::unidad_estado ELSE estado END
                       WHERE id = ?""",
                    nombre, c.activa, c.activa, id,
                )
            }
            tipos?.let {
                conn.ejecutar("DELETE FROM unidad_tipo_atencion WHERE unidad_id = ?", id)
                guardarTipos(conn, id, it)
            }
            // Una unidad dada de baja ya no tiene operadores en turno.
            if (c.activa == false) conn.ejecutar("UPDATE operador SET activo = false WHERE unidad_id = ? AND activo", id)
            Bitacora.registrar(conn, if (c.activa == false) "BAJA_UNIDAD" else "EDITAR_UNIDAD", admin.usuarioId, "unidad_atencion", id.toString(),
                buildJsonObject {
                    nombre?.let { put("nombre", it) }
                    c.activa?.let { put("activa", it) }
                    tipos?.let { put("tipos", JsonArray(it.values.map(::JsonPrimitive))) }
                }, ip)
            leerUnidad(conn, id)!!
        }
    }

    /**
     * Deja exactamente esos operadores en la unidad (CU-10 paso 3). Cada uno debe tener el perfil OPERADOR; si estaba en
     * otra unidad, se cambia a esta. Los que estaban en esta unidad y no vienen en la lista quedan inactivos.
     */
    fun vincularOperadores(admin: UsuarioToken, unidadId: UUID, e: OperadoresEntrada, ip: String?): Unidad {
        val ids = e.operadores.map { uuidDe(it, "operadores") }.distinct()
        return dataSource.transaccion { conn ->
            val activa = conn.consultarUno("SELECT activa FROM unidad_atencion WHERE id = ? FOR UPDATE", unidadId) { it.getBoolean(1) }
                ?: throw noEncontrado("La unidad no existe")
            if (!activa && ids.isNotEmpty()) throw conflicto("UNIDAD_INACTIVA", "La unidad está dada de baja")
            val noOperadores = ids.filter { id ->
                conn.consultarUno("SELECT rol = 'OPERADOR' AND estado <> 'ELIMINADA' FROM usuario WHERE id = ?", id) { it.getBoolean(1) } != true
            }
            if (noOperadores.isNotEmpty()) {
                throw errorValidacion(mapOf("operadores" to "Estas cuentas no tienen el perfil Operador: ${noOperadores.joinToString()}"))
            }
            val quitados = conn.ejecutar(
                "UPDATE operador SET activo = false WHERE unidad_id = ? AND activo AND NOT (usuario_id = ANY (?))",
                unidadId, conn.createArrayOf("uuid", ids.toTypedArray()),
            )
            ids.forEach { id ->
                conn.ejecutar(
                    """INSERT INTO operador (usuario_id, unidad_id, activo) VALUES (?, ?, true)
                       ON CONFLICT (usuario_id) DO UPDATE SET unidad_id = EXCLUDED.unidad_id, activo = true,
                           desde = CASE WHEN operador.unidad_id = EXCLUDED.unidad_id AND operador.activo THEN operador.desde ELSE now() END""",
                    id, unidadId,
                )
            }
            Bitacora.registrar(conn, "VINCULAR_OPERADORES", admin.usuarioId, "unidad_atencion", unidadId.toString(),
                buildJsonObject {
                    put("operadores", JsonArray(ids.map { JsonPrimitive(it.toString()) }))
                    put("quitados", quitados)
                }, ip)
            leerUnidad(conn, unidadId)!!
        }
    }

    // ---------------------------------------------------------------- Turno del operador

    /** La unidad del operador, con su estado y ubicación. 404 si la cuenta no está vinculada a una unidad. */
    fun miUnidad(operador: UsuarioToken): Unidad = dataSource.connection.use { conn ->
        val id = unidadDeOperador(conn, operador.usuarioId)
        leerUnidad(conn, id)!!
    }

    /**
     * El operador abre su turno (DISPONIBLE), lo cierra (FUERA_DE_SERVICIO) o solo actualiza la ubicación de su unidad.
     * Mientras la unidad atiende una alerta (ASIGNADA o ATENDIENDO) no puede cambiar de estado, pero sí mandar su ubicación.
     */
    fun cambiarTurno(operador: UsuarioToken, c: TurnoCambio, ip: String?): Unidad {
        val estado = c.estado?.trim()?.uppercase()
        val errores = buildMap {
            if (estado != null && estado !in setOf("DISPONIBLE", "FUERA_DE_SERVICIO")) put("estado", "Debe ser DISPONIBLE o FUERA_DE_SERVICIO")
            if ((c.latitud == null) != (c.longitud == null)) put("latitud", "Manda latitud y longitud juntas")
            if (c.latitud != null && c.latitud !in -90.0..90.0) put("latitud", "Fuera de rango")
            if (c.longitud != null && c.longitud !in -180.0..180.0) put("longitud", "Fuera de rango")
            if (estado == null && c.latitud == null) put("estado", "Manda el estado, la ubicación o ambos")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        return dataSource.transaccion { conn ->
            val id = unidadDeOperador(conn, operador.usuarioId)
            val (actual, activa) = conn.consultarUno("SELECT estado::text, activa FROM unidad_atencion WHERE id = ? FOR UPDATE", id) {
                it.getString(1) to it.getBoolean(2)
            }!!
            if (!activa) throw conflicto("UNIDAD_INACTIVA", "Tu unidad está dada de baja")
            if (estado != null && estado != actual && actual in setOf("ASIGNADA", "ATENDIENDO")) {
                throw conflicto("UNIDAD_OCUPADA", "Tu unidad está atendiendo una alerta; ciérrala antes de cambiar el turno")
            }
            if (c.latitud != null) {
                conn.ejecutar(
                    "UPDATE unidad_atencion SET ultima_latitud = ?, ultima_longitud = ?, ultima_ubicacion_en = now() WHERE id = ?",
                    c.latitud, c.longitud, id,
                )
            }
            if (estado != null && estado != actual) {
                conn.ejecutar("UPDATE unidad_atencion SET estado = ?::unidad_estado WHERE id = ?", estado, id)
                Bitacora.registrar(conn, "CAMBIAR_TURNO_UNIDAD", operador.usuarioId, "unidad_atencion", id.toString(),
                    buildJsonObject { put("estadoAnterior", actual); put("estadoNuevo", estado) }, ip)
            }
            leerUnidad(conn, id)!!
        }
    }

    private fun unidadDeOperador(conn: Connection, usuarioId: UUID): UUID =
        conn.consultarUno("SELECT unidad_id FROM operador WHERE usuario_id = ? AND activo", usuarioId) { it.getObject(1, UUID::class.java) }
            ?: throw noEncontrado("Tu cuenta no está vinculada a una unidad de atención")

    /** Códigos de tipos activos del catálogo publicado → su id. Al menos uno. */
    private fun tiposActivos(conn: Connection, codigos: List<String>): Map<Short, String> {
        val pedidos = codigos.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
        if (pedidos.isEmpty()) throw errorValidacion(mapOf("tipos" to "Elige al menos un tipo de incidente que atienda"))
        val encontrados = conn.consultar(
            """SELECT t.id, t.codigo FROM tipo_incidente t
               JOIN tipo_incidente_version tv ON tv.tipo_incidente_id = t.id
               JOIN version_catalogo v ON v.id = tv.version_catalogo_id AND v.estado = 'PUBLICADA'
               WHERE tv.activo AND t.codigo = ANY (?)""",
            conn.createArrayOf("varchar", pedidos.toTypedArray()),
        ) { it.getShort(1) to it.getString(2) }.toMap()
        val faltan = pedidos - encontrados.values.toSet()
        if (faltan.isNotEmpty()) throw errorValidacion(mapOf("tipos" to "No existen o no están activos: ${faltan.joinToString()}"))
        return encontrados
    }

    private fun guardarTipos(conn: Connection, unidadId: UUID, tipos: Map<Short, String>) {
        tipos.keys.forEach { conn.ejecutar("INSERT INTO unidad_tipo_atencion (unidad_id, tipo_incidente_id) VALUES (?, ?)", unidadId, it) }
    }

    private fun leerUnidad(conn: Connection, id: UUID): Unidad? {
        val tipos = conn.consultar(
            "SELECT t.codigo FROM unidad_tipo_atencion x JOIN tipo_incidente t ON t.id = x.tipo_incidente_id WHERE x.unidad_id = ? ORDER BY t.codigo",
            id,
        ) { it.getString(1) }
        val operadores = conn.consultar(
            """SELECT o.usuario_id, us.nombre, o.activo FROM operador o JOIN usuario us ON us.id = o.usuario_id
               WHERE o.unidad_id = ? AND o.activo ORDER BY us.nombre""",
            id,
        ) { OperadorUnidad(it.getObject(1, UUID::class.java).toString(), it.getString(2), it.getBoolean(3)) }
        return conn.consultarUno(
            """SELECT u.id, u.corporacion_id, c.nombre AS corporacion, u.nombre, u.estado::text AS estado, u.activa,
                      u.ultima_latitud::double precision AS lat, u.ultima_longitud::double precision AS lon, u.ultima_ubicacion_en
               FROM unidad_atencion u JOIN corporacion c ON c.id = u.corporacion_id WHERE u.id = ?""",
            id,
        ) {
            Unidad(
                id = it.uuid("id").toString(),
                corporacionId = it.getInt("corporacion_id"),
                corporacion = it.getString("corporacion"),
                nombre = it.getString("nombre"),
                estado = it.getString("estado"),
                activa = it.getBoolean("activa"),
                tipos = tipos,
                latitud = it.dobleONulo("lat"),
                longitud = it.dobleONulo("lon"),
                ubicacionEn = it.instante("ultima_ubicacion_en")?.toString(),
                operadores = operadores,
            )
        }
    }

    private fun <T> conDuplicados(campo: String, mensaje: String, bloque: () -> T): T = try {
        bloque()
    } catch (e: SQLException) {
        if (e.sqlState == "23505") throw errorDuplicado(campo, mensaje) else throw e
    }

    private companion object {
        const val SELECT_USUARIO = """SELECT u.id, u.nombre, u.identificador_institucional, u.telefono_movil, e.nombre AS escuela,
                                             u.rol::text AS rol, u.estado::text AS estado, o.unidad_id, ua.nombre AS unidad
                                      FROM usuario u
                                      JOIN escuela e ON e.id = u.escuela_id
                                      LEFT JOIN operador o ON o.usuario_id = u.id AND o.activo
                                      LEFT JOIN unidad_atencion ua ON ua.id = o.unidad_id"""
    }
}
