package com.senderoseguro.server.redapoyo

import com.senderoseguro.server.cuentas.Validaciones
import com.senderoseguro.server.cuentas.errorDuplicado
import com.senderoseguro.server.cuentas.errorValidacion
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import com.senderoseguro.server.plataforma.uuidONulo
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/**
 * P2 (Módulo 1): contactos de confianza, círculo escolar y avisos de vinculación.
 *
 * - Los contactos se guardan ya CONFIRMADO: la persona los agrega desde su teléfono y el aviso CONTACTO_AGREGADO
 *   le dice al contacto que quedó en la red. Si el teléfono es de una cuenta, se vincula y el canal pasa a PUSH.
 * - Cada persona está en un solo círculo a la vez. El coordinador también es integrante de su círculo.
 * - El código de invitación tiene el formato AAA-999 y dura `codigo_invitacion_dias` (7). Al vencer, unirse
 *   responde 410 CODIGO_VENCIDO; el coordinador puede generar otro.
 */
class RedApoyoServicio(private val dataSource: DataSource) {

    // ------------------------------------------------------------------ contactos

    fun contactos(usuarioId: UUID): List<Contacto> = dataSource.connection.use { listarContactos(it, usuarioId) }

    fun guardarContacto(usuarioId: UUID, idRuta: String, entrada: ContactoEntrada, ip: String?): Contacto {
        val id = uuidDe(idRuta) ?: throw noEncontrado("El contacto no existe")
        if (entrada.id != idRuta) throw errorValidacion(mapOf("id" to "El id del cuerpo no coincide con el de la ruta"))
        val nombre = entrada.nombre.trim()
        val telefono = Validaciones.normalizarTelefono(entrada.telefono)
        val errores = buildMap {
            if (nombre.length !in 2..80) put(Validaciones.NOMBRE, "Debe tener entre 2 y 80 caracteres")
            Validaciones.telefono(entrada.telefono)?.let { put(Validaciones.TELEFONO, it) }
            if (entrada.prioridad < 1) put(Validaciones.PRIORIDAD, "La prioridad empieza en 1")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)

        return conTelefonoRepetido {
            dataSource.transaccion { conn ->
                val dueno = conn.consultarUno("SELECT usuario_id FROM contacto_confianza WHERE id = ? FOR UPDATE", id) { it.uuid("usuario_id") }
                if (dueno != null && dueno != usuarioId) throw noEncontrado("El contacto no existe")
                val propio = conn.consultarUno("SELECT telefono_movil FROM usuario WHERE id = ?", usuarioId) { it.getString(1) }
                if (propio == telefono) throw errorValidacion(mapOf(Validaciones.TELEFONO to "No puedes agregarte a ti misma o a ti mismo"))
                val repetido = conn.consultarUno(
                    "SELECT activo FROM contacto_confianza WHERE usuario_id = ? AND telefono = ? AND id <> ?",
                    usuarioId, telefono, id,
                ) { it.getBoolean(1) }
                if (repetido != null) {
                    // Uno borrado antes con el mismo número: si nadie lo usa, se quita para que el nuevo tome su lugar.
                    if (repetido || !borrarSiSePuede(conn, usuarioId, telefono)) throw telefonoRepetido()
                }

                val vinculado = conn.consultarUno(
                    "SELECT id FROM usuario WHERE telefono_movil = ? AND id <> ? AND estado IN ('ACTIVA', 'BLOQUEADA_TEMPORAL')",
                    telefono, usuarioId,
                ) { it.uuid("id") }
                val canal = if (vinculado != null) "PUSH" else "SMS"
                conn.ejecutar(
                    """INSERT INTO contacto_confianza (id, usuario_id, nombre, telefono, usuario_vinculado_id,
                                                     estado_confirmacion, canal_preferido, prioridad, activo)
                       VALUES (?, ?, ?, ?, ?, 'CONFIRMADO', ?::notificacion_canal, ?, true)
                       ON CONFLICT (id) DO UPDATE
                       SET nombre = EXCLUDED.nombre, telefono = EXCLUDED.telefono,
                           usuario_vinculado_id = EXCLUDED.usuario_vinculado_id, canal_preferido = EXCLUDED.canal_preferido,
                           prioridad = EXCLUDED.prioridad, activo = true""",
                    id, usuarioId, nombre, telefono, vinculado, canal, entrada.prioridad,
                )
                Bitacora.registrar(
                    conn, if (dueno == null) "AGREGAR_CONTACTO" else "EDITAR_CONTACTO", usuarioId, "contacto_confianza", id.toString(),
                    buildJsonObject { put("prioridad", entrada.prioridad); put("vinculado", vinculado != null) }, ip,
                )
                leerContacto(conn, id)!!
            }
        }
    }

    /** Idempotente: si no existe o es de otra persona, no hace nada. Si ya recibió avisos, queda inactivo. */
    fun eliminarContacto(usuarioId: UUID, idRuta: String, ip: String?) {
        val id = uuidDe(idRuta) ?: return
        dataSource.transaccion { conn ->
            conn.consultarUno(
                "SELECT 1 FROM contacto_confianza WHERE id = ? AND usuario_id = ? AND activo FOR UPDATE", id, usuarioId,
            ) { true } ?: return@transaccion
            val usado = conn.consultarUno("SELECT 1 FROM notificacion WHERE contacto_id = ? LIMIT 1", id) { true } ?: false
            if (usado) conn.ejecutar("UPDATE contacto_confianza SET activo = false WHERE id = ?", id)
            else conn.ejecutar("DELETE FROM contacto_confianza WHERE id = ?", id)
            Bitacora.registrar(conn, "ELIMINAR_CONTACTO", usuarioId, "contacto_confianza", id.toString(), ip = ip)
        }
    }

    /** `ids` en el orden nuevo; deben ser exactamente los contactos activos. Quedan con prioridad 1..n. */
    fun reordenar(usuarioId: UUID, orden: OrdenContactos, ip: String?): List<Contacto> = dataSource.transaccion { conn ->
        val actuales = conn.consultar(
            "SELECT id FROM contacto_confianza WHERE usuario_id = ? AND activo FOR UPDATE", usuarioId,
        ) { it.uuid("id").toString() }.toSet()
        val pedidos = orden.ids.map { it.lowercase() }
        if (pedidos.size != pedidos.toSet().size || pedidos.toSet() != actuales) {
            throw errorValidacion(mapOf(Validaciones.PRIORIDAD to "Manda todos tus contactos, cada uno una vez"))
        }
        pedidos.forEachIndexed { i, id ->
            conn.ejecutar("UPDATE contacto_confianza SET prioridad = ? WHERE id = ?", i + 1, UUID.fromString(id))
        }
        Bitacora.registrar(conn, "ORDENAR_CONTACTOS", usuarioId, "contacto_confianza", null, ip = ip)
        listarContactos(conn, usuarioId)
    }

    // ------------------------------------------------------------------ círculo

    fun miCirculo(usuarioId: UUID): MiCirculo = dataSource.connection.use { conn ->
        val id = circuloActivo(conn, usuarioId)
        MiCirculo(id?.let { leerCirculo(conn, it, usuarioId) })
    }

    fun crearCirculo(usuarioId: UUID, entrada: CrearCirculo, ip: String?): Circulo {
        val id = uuidDe(entrada.id) ?: throw errorValidacion(mapOf("id" to "El id del círculo debe ser un UUID"))
        Validaciones.nombreGrupo(entrada.nombreGrupo)?.let { throw errorValidacion(mapOf(Validaciones.NOMBRE_GRUPO to it)) }
        return dataSource.transaccion { conn ->
            val (rol, escuela) = conn.consultarUno("SELECT rol::text AS rol, escuela_id FROM usuario WHERE id = ? FOR UPDATE", usuarioId) {
                it.getString("rol") to it.getInt("escuela_id")
            } ?: throw noEncontrado("La cuenta no existe")
            if (rol != "COORDINADOR") throw sinPermiso("Solo un coordinador puede crear un círculo")

            // Reintento con el mismo id (la app no supo si llegó): se responde el mismo círculo.
            conn.consultarUno("SELECT coordinador_id FROM circulo WHERE id = ?", id) { it.uuid("coordinador_id") }?.let { dueno ->
                if (dueno != usuarioId) throw errorDuplicado("id", "Ese id ya es de otro círculo")
                return@transaccion leerCirculo(conn, id, usuarioId)!!
            }
            if (circuloActivo(conn, usuarioId) != null) throw yaTieneCirculo()

            val dias = parametro(conn, "codigo_invitacion_dias", 7)
            val codigo = codigoLibre(conn)
            conn.ejecutar(
                """INSERT INTO circulo (id, coordinador_id, escuela_id, nombre_grupo, codigo_invitacion, codigo_vence_en)
                   VALUES (?, ?, ?, ?, ?, now() + make_interval(days => ?))""",
                id, usuarioId, escuela, entrada.nombreGrupo.trim(), codigo, dias,
            )
            conn.ejecutar("INSERT INTO miembro_circulo (circulo_id, usuario_id) VALUES (?, ?)", id, usuarioId)
            Bitacora.registrar(conn, "CREAR_CIRCULO", usuarioId, "circulo", id.toString(), ip = ip)
            leerCirculo(conn, id, usuarioId)!!
        }
    }

    fun unirse(usuarioId: UUID, entrada: Unirse, ip: String?): Circulo {
        val codigo = Validaciones.normalizarCodigo(entrada.codigo)
            ?: throw errorValidacion(mapOf(Validaciones.CODIGO to "El código tiene el formato AAA-999"))
        return dataSource.transaccion { conn ->
            conn.consultarUno("SELECT id FROM usuario WHERE id = ? FOR UPDATE", usuarioId) { true }
            val (id, vigente) = conn.consultarUno(
                """SELECT id, estado_codigo = 'VIGENTE' AND codigo_vence_en > now() AS vigente FROM circulo
                   WHERE codigo_invitacion = ? ORDER BY (estado_codigo = 'VIGENTE') DESC, codigo_vence_en DESC LIMIT 1""",
                codigo,
            ) { it.uuid("id") to it.getBoolean("vigente") }
                ?: throw ApiException(HttpStatusCode.NotFound, "CODIGO_INVALIDO", "El código no existe")

            val actual = circuloActivo(conn, usuarioId)
            if (actual == id) return@transaccion leerCirculo(conn, id, usuarioId)!!
            if (!vigente) throw ApiException(HttpStatusCode.Gone, "CODIGO_VENCIDO", "El código ya venció; pide uno nuevo a tu coordinador")
            if (actual != null) throw yaTieneCirculo()

            conn.ejecutar("INSERT INTO miembro_circulo (circulo_id, usuario_id) VALUES (?, ?)", id, usuarioId)
            Bitacora.registrar(conn, "UNIRSE_CIRCULO", usuarioId, "circulo", id.toString(), ip = ip)
            leerCirculo(conn, id, usuarioId)!!
        }
    }

    fun regenerarCodigo(usuarioId: UUID, idRuta: String, ip: String?): Circulo {
        val id = uuidDe(idRuta) ?: throw noEncontrado("El círculo no existe")
        return dataSource.transaccion { conn ->
            exigirCoordinador(conn, id, usuarioId)
            val dias = parametro(conn, "codigo_invitacion_dias", 7)
            // El código viejo deja de servir en el mismo momento.
            conn.ejecutar("UPDATE circulo SET estado_codigo = 'VENCIDO' WHERE id = ?", id)
            val codigo = codigoLibre(conn)
            conn.ejecutar(
                """UPDATE circulo SET codigo_invitacion = ?, estado_codigo = 'VIGENTE', codigo_vence_en = now() + make_interval(days => ?)
                   WHERE id = ?""",
                codigo, dias, id,
            )
            Bitacora.registrar(conn, "REGENERAR_CODIGO", usuarioId, "circulo", id.toString(), ip = ip)
            leerCirculo(conn, id, usuarioId)!!
        }
    }

    /** Lo ven los integrantes activos del círculo. De cada persona, su último estado. */
    fun miembros(usuarioId: UUID, idRuta: String): List<Miembro> {
        val id = uuidDe(idRuta) ?: throw noEncontrado("El círculo no existe")
        return dataSource.connection.use { conn ->
            if (!esIntegrante(conn, id, usuarioId)) throw noEncontrado("El círculo no existe")
            conn.consultar(
                """SELECT DISTINCT ON (m.usuario_id) m.usuario_id, u.nombre, m.estado::text AS estado
                   FROM miembro_circulo m JOIN usuario u ON u.id = m.usuario_id
                   WHERE m.circulo_id = ?
                   ORDER BY m.usuario_id, m.unido_en DESC, m.id DESC""",
                id,
            ) { Miembro(it.uuid("usuario_id").toString(), it.getString("nombre"), it.getString("estado")) }
                .sortedWith(compareBy({ it.estado != "ACTIVA" }, { it.nombre }))
        }
    }

    /** Solo el coordinador; a sí mismo no (para eso no hay salida: el círculo se quedaría sin coordinador). */
    fun darDeBaja(usuarioId: UUID, idRuta: String, integranteRuta: String, ip: String?) {
        val id = uuidDe(idRuta) ?: throw noEncontrado("El círculo no existe")
        val integrante = uuidDe(integranteRuta) ?: return
        dataSource.transaccion { conn ->
            exigirCoordinador(conn, id, usuarioId)
            if (integrante == usuarioId) throw conflicto("No puedes darte de baja de tu propio círculo")
            val cambio = conn.ejecutar(
                "UPDATE miembro_circulo SET estado = 'DADA_DE_BAJA', baja_en = now() WHERE circulo_id = ? AND usuario_id = ? AND estado = 'ACTIVA'",
                id, integrante,
            )
            if (cambio > 0) {
                Bitacora.registrar(conn, "DAR_DE_BAJA_INTEGRANTE", usuarioId, "circulo", id.toString(),
                    buildJsonObject { put("integrante", integrante.toString()) }, ip)
            }
        }
    }

    fun retirarme(usuarioId: UUID, idRuta: String, ip: String?) {
        val id = uuidDe(idRuta) ?: return
        dataSource.transaccion { conn ->
            val coordinador = conn.consultarUno("SELECT coordinador_id FROM circulo WHERE id = ?", id) { it.uuid("coordinador_id") } ?: return@transaccion
            if (coordinador == usuarioId) throw conflicto("El coordinador no puede retirarse de su propio círculo")
            val cambio = conn.ejecutar(
                "UPDATE miembro_circulo SET estado = 'RETIRADA', baja_en = now() WHERE circulo_id = ? AND usuario_id = ? AND estado = 'ACTIVA'",
                id, usuarioId,
            )
            if (cambio > 0) Bitacora.registrar(conn, "RETIRARSE_CIRCULO", usuarioId, "circulo", id.toString(), ip = ip)
        }
    }

    // ------------------------------------------------------------------ avisos (X-01)

    /**
     * Crea la notificación que [com.senderoseguro.server.avisos.EnvioAvisos] manda después:
     * - CONTACTO_AGREGADO: SMS al teléfono del contacto.
     * - NUEVO_INTEGRANTE: push (o SMS si no tiene teléfono con push) al coordinador del círculo.
     * Si la app lo pide dos veces en un día para lo mismo, la segunda no crea otra.
     */
    fun avisar(usuarioId: UUID, aviso: AvisoVinculacion) {
        dataSource.transaccion { conn ->
            when (aviso.tipo) {
                "CONTACTO_AGREGADO" -> {
                    val contacto = aviso.contactoId?.let(::uuidDe)
                        ?: throw errorValidacion(mapOf("contactoId" to "Falta el contacto"))
                    conn.consultarUno(
                        "SELECT 1 FROM contacto_confianza WHERE id = ? AND usuario_id = ? AND activo", contacto, usuarioId,
                    ) { true } ?: throw noEncontrado("El contacto no existe")
                    val reciente = conn.consultarUno(
                        "SELECT 1 FROM notificacion WHERE contacto_id = ? AND tipo = 'CONTACTO_AGREGADO' AND creada_en > now() - interval '1 day'",
                        contacto,
                    ) { true }
                    if (reciente == null) {
                        conn.ejecutar("INSERT INTO notificacion (contacto_id, tipo, canal) VALUES (?, 'CONTACTO_AGREGADO', 'SMS')", contacto)
                    }
                }
                "NUEVO_INTEGRANTE" -> {
                    val circulo = aviso.circuloId?.let(::uuidDe)
                        ?: throw errorValidacion(mapOf("circuloId" to "Falta el círculo"))
                    if (!esIntegrante(conn, circulo, usuarioId)) throw noEncontrado("El círculo no existe")
                    val coordinador = conn.consultarUno("SELECT coordinador_id FROM circulo WHERE id = ?", circulo) { it.uuid("coordinador_id") }!!
                    if (coordinador == usuarioId) return@transaccion
                    val reciente = conn.consultarUno(
                        """SELECT 1 FROM notificacion WHERE usuario_id = ? AND tipo = 'NUEVO_INTEGRANTE'
                           AND creada_en > now() - interval '1 day'
                           AND creada_en > (SELECT max(unido_en) FROM miembro_circulo WHERE circulo_id = ? AND usuario_id = ?)""",
                        coordinador, circulo, usuarioId,
                    ) { true }
                    if (reciente == null) {
                        conn.ejecutar("INSERT INTO notificacion (usuario_id, tipo, canal) VALUES (?, 'NUEVO_INTEGRANTE', 'PUSH')", coordinador)
                    }
                }
                else -> throw errorValidacion(mapOf("tipo" to "Debe ser CONTACTO_AGREGADO o NUEVO_INTEGRANTE"))
            }
        }
    }

    // ------------------------------------------------------------------ apoyo

    private fun listarContactos(conn: Connection, usuarioId: UUID) = conn.consultar(
        "$SELECT_CONTACTO WHERE usuario_id = ? AND activo ORDER BY prioridad, creado_en", usuarioId,
    ) { it.aContacto() }

    private fun leerContacto(conn: Connection, id: UUID) = conn.consultarUno("$SELECT_CONTACTO WHERE id = ?", id) { it.aContacto() }

    private fun ResultSet.aContacto() = Contacto(
        id = uuid("id").toString(),
        nombre = getString("nombre"),
        telefono = getString("telefono"),
        prioridad = getInt("prioridad"),
        canalPreferido = getString("canal_preferido"),
        usuarioVinculadoId = uuidONulo("usuario_vinculado_id")?.toString(),
        activo = getBoolean("activo"),
    )

    /** Borra un contacto inactivo con ese número si ningún aviso lo usa. */
    private fun borrarSiSePuede(conn: Connection, usuarioId: UUID, telefono: String): Boolean = conn.ejecutar(
        """DELETE FROM contacto_confianza c WHERE c.usuario_id = ? AND c.telefono = ? AND NOT c.activo
           AND NOT EXISTS (SELECT 1 FROM notificacion n WHERE n.contacto_id = c.id)""",
        usuarioId, telefono,
    ) > 0

    /** El círculo donde la persona es integrante activa, o null. */
    private fun circuloActivo(conn: Connection, usuarioId: UUID): UUID? = conn.consultarUno(
        "SELECT circulo_id FROM miembro_circulo WHERE usuario_id = ? AND estado = 'ACTIVA' ORDER BY unido_en DESC LIMIT 1", usuarioId,
    ) { it.uuid("circulo_id") }

    private fun esIntegrante(conn: Connection, circuloId: UUID, usuarioId: UUID): Boolean = conn.consultarUno(
        "SELECT 1 FROM miembro_circulo WHERE circulo_id = ? AND usuario_id = ? AND estado = 'ACTIVA'", circuloId, usuarioId,
    ) { true } ?: false

    /** 404 si el círculo no existe o la persona no está en él; 403 si está pero no es su coordinador. */
    private fun exigirCoordinador(conn: Connection, circuloId: UUID, usuarioId: UUID) {
        val coordinador = conn.consultarUno("SELECT coordinador_id FROM circulo WHERE id = ? FOR UPDATE", circuloId) { it.uuid("coordinador_id") }
        if (coordinador == null || (coordinador != usuarioId && !esIntegrante(conn, circuloId, usuarioId))) throw noEncontrado("El círculo no existe")
        if (coordinador != usuarioId) throw sinPermiso("Solo el coordinador del círculo puede hacer esto")
    }

    private fun leerCirculo(conn: Connection, id: UUID, usuarioId: UUID): Circulo? = conn.consultarUno(
        """SELECT id, nombre_grupo, escuela_id, coordinador_id, codigo_invitacion, codigo_vence_en,
                  CASE WHEN estado_codigo = 'VIGENTE' AND codigo_vence_en > now() THEN 'VIGENTE' ELSE 'VENCIDO' END AS estado
           FROM circulo WHERE id = ?""",
        id,
    ) {
        val coordinador = it.uuid("coordinador_id")
        val esCoordinador = coordinador == usuarioId
        Circulo(
            id = it.uuid("id").toString(),
            nombreGrupo = it.getString("nombre_grupo"),
            escuelaId = it.getInt("escuela_id"),
            coordinadorId = coordinador.toString(),
            codigoInvitacion = it.getString("codigo_invitacion").takeIf { esCoordinador },
            estadoCodigo = it.getString("estado"),
            codigoVenceEn = it.instante("codigo_vence_en")?.toString()?.takeIf { esCoordinador },
        )
    }

    /** Un código AAA-999 que no use otro círculo vigente. */
    private fun codigoLibre(conn: Connection): String {
        repeat(20) {
            val codigo = (1..6).map { ALFABETO[azar.nextInt(ALFABETO.length)] }.joinToString("").let { it.substring(0, 3) + "-" + it.substring(3) }
            val ocupado = conn.consultarUno(
                "SELECT 1 FROM circulo WHERE codigo_invitacion = ? AND estado_codigo = 'VIGENTE'", codigo,
            ) { true } ?: false
            if (!ocupado) return codigo
        }
        error("No se encontró un código de invitación libre")
    }

    private fun parametro(conn: Connection, clave: String, defecto: Int): Int =
        conn.consultarUno("SELECT valor FROM parametro_sistema WHERE clave = ?", clave) { it.getString(1).toIntOrNull() } ?: defecto

    /** Dos guardados al mismo tiempo con el mismo número: el segundo choca con UNIQUE (usuario_id, telefono). */
    private fun <T> conTelefonoRepetido(bloque: () -> T): T = try {
        bloque()
    } catch (e: SQLException) {
        if (e.sqlState == "23505") throw telefonoRepetido() else throw e
    }

    private fun uuidDe(texto: String): UUID? = runCatching { UUID.fromString(texto) }.getOrNull()

    private fun telefonoRepetido() = errorDuplicado(Validaciones.TELEFONO, "Ese número ya está en tu lista")
    private fun yaTieneCirculo() = conflicto("Ya perteneces a un círculo; retírate de él antes")
    private fun noEncontrado(mensaje: String) = ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", mensaje)
    private fun sinPermiso(mensaje: String) = ApiException(HttpStatusCode.Forbidden, "SIN_PERMISO", mensaje)
    private fun conflicto(mensaje: String) = ApiException(HttpStatusCode.Conflict, "CONFLICTO", mensaje)

    private companion object {
        const val SELECT_CONTACTO =
            "SELECT id, nombre, telefono, prioridad, canal_preferido::text AS canal_preferido, usuario_vinculado_id, activo FROM contacto_confianza"
        /** Sin 0/O ni 1/I/L, que se confunden al dictarlo. */
        const val ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val azar = SecureRandom()
    }
}
