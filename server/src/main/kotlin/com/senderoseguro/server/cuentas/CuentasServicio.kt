package com.senderoseguro.server.cuentas

import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.EmisorTokens
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.SesionesVigentes
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration
import kotlinx.serialization.json.add

/**
 * P1 (Módulo 1): registro, inicio de sesión con bloqueo por intentos, cierre de sesión, perfil y baja.
 *
 * - La contraseña se guarda con Argon2id ([Contrasenas]); nunca en texto.
 * - Cada inicio de sesión crea una fila en `sesion` con el SHA-256 del token, y el token lleva su id (`sid`). El
 *   cierre de sesión la revoca y [vigente] la rechaza desde ese momento.
 * - Al 5.º intento fallido (`login_max_intentos`) la cuenta queda BLOQUEADA_TEMPORAL por `login_bloqueo_minutos`.
 */
class CuentasServicio(
    private val dataSource: DataSource,
    private val tokens: EmisorTokens,
    private val duracionSesion: Duration,
) : SesionesVigentes {

    // ------------------------------------------------------------------ catálogos públicos

    fun avisoVigente(): AvisoPrivacidad = dataSource.connection.use { conn ->
        conn.consultarUno(
            "SELECT id, version, contenido_hash FROM aviso_privacidad WHERE vigente ORDER BY vigente_desde DESC LIMIT 1",
        ) { AvisoPrivacidad(it.getInt("id"), it.getString("version"), it.getString("contenido_hash")) }
    } ?: throw ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "No hay un aviso de privacidad vigente")

    fun instituciones(): List<Institucion> = dataSource.connection.use { conn ->
        conn.consultar("SELECT id, nombre, siglas FROM institucion WHERE activa ORDER BY nombre") {
            Institucion(it.getInt("id"), it.getString("nombre"), it.getString("siglas"))
        }
    }

    fun escuelas(institucionId: Int): List<Escuela> = dataSource.connection.use { conn ->
        conn.consultar(
            "SELECT id, institucion_id, nombre FROM escuela WHERE institucion_id = ? AND activa ORDER BY nombre", institucionId,
        ) { Escuela(it.getInt("id"), it.getInt("institucion_id"), it.getString("nombre")) }
    }

    // ------------------------------------------------------------------ registro

    fun registrar(e: RegistroEntrada, ip: String?): Sesion {
        val telefono = Validaciones.normalizarTelefono(e.telefonoMovil)
        val identificador = e.identificadorInstitucional.trim()
        val tipo = e.tipoComunidad.trim().uppercase()
        exigirValidos(
            mapOf(
                Validaciones.NOMBRE to Validaciones.nombre(e.nombre),
                Validaciones.TELEFONO to Validaciones.telefono(e.telefonoMovil),
                Validaciones.IDENTIFICADOR to Validaciones.identificador(identificador),
                Validaciones.CARRERA to e.carreraAdscripcion?.takeIf { it.isNotBlank() }?.let(Validaciones::carrera),
                Validaciones.CONTRASENA to Validaciones.contrasena(e.contrasena),
                Validaciones.TIPO_COMUNIDAD to if (tipo in TIPOS_COMUNIDAD) null else "Elige alumno, docente o personal",
                Validaciones.DISPOSITIVO to validarDispositivo(e.dispositivo),
            ),
        )
        // Hasta aquí no se toca la base: Argon2 tarda y no debe hacerse dentro de la transacción.
        val hash = Contrasenas.cifrar(e.contrasena)
        // Quien se registra como docente queda como coordinador, para poder crear el círculo de su grupo (P2).
        val rol = if (tipo == "DOCENTE") Rol.COORDINADOR else Rol.INTEGRANTE

        return conDuplicados {
            dataSource.transaccion { conn ->
                val escuelaActiva = conn.consultarUno("SELECT activa FROM escuela WHERE id = ?", e.escuelaId) { it.getBoolean(1) }
                if (escuelaActiva != true) throw errorValidacion(mapOf(Validaciones.ESCUELA to "Elige una escuela de la lista"))
                val avisoVigente = conn.consultarUno("SELECT vigente FROM aviso_privacidad WHERE id = ?", e.consentimiento.avisoId) { it.getBoolean(1) }
                if (avisoVigente != true) {
                    throw errorValidacion(mapOf(Validaciones.CONSENTIMIENTO to "Acepta el aviso de privacidad vigente"))
                }
                if (conn.consultarUno("SELECT 1 FROM usuario WHERE telefono_movil = ?", telefono) { true } == true) {
                    throw errorDuplicado(Validaciones.TELEFONO, "Ese teléfono ya tiene una cuenta")
                }
                if (conn.consultarUno("SELECT 1 FROM usuario WHERE upper(identificador_institucional) = upper(?)", identificador) { true } == true) {
                    throw errorDuplicado(Validaciones.IDENTIFICADOR, "Esa matrícula ya tiene una cuenta")
                }

                val usuarioId = UUID.randomUUID()
                conn.ejecutar(
                    """INSERT INTO usuario (id, nombre, telefono_movil, identificador_institucional, tipo_comunidad, escuela_id,
                                            carrera_adscripcion, password_hash, acepta_entrenamiento_ia, rol, ultimo_acceso_en)
                       VALUES (?, ?, ?, ?, ?::tipo_comunidad, ?, ?, ?, ?, ?::usuario_rol, now())""",
                    usuarioId, e.nombre.trim().replace(Regex("\\s+"), " "), telefono, identificador, tipo, e.escuelaId,
                    e.carreraAdscripcion?.trim()?.ifBlank { null }, hash, e.aceptaEntrenamientoIa, rol.name,
                )
                conn.ejecutar(
                    "INSERT INTO consentimiento (usuario_id, aviso_id, aceptado_en) VALUES (?, ?, ?)",
                    usuarioId, e.consentimiento.avisoId, fechaAceptacion(e.consentimiento.aceptadoEn),
                )
                conn.ejecutar("INSERT INTO configuracion_usuario (usuario_id) VALUES (?) ON CONFLICT DO NOTHING", usuarioId)
                val dispositivoId = guardarDispositivo(conn, usuarioId, e.dispositivo)
                Bitacora.registrar(
                    conn, "REGISTRO_CUENTA", usuarioId, "usuario", usuarioId.toString(),
                    buildJsonObject { put("rol", rol.name); put("avisoId", e.consentimiento.avisoId) }, ip,
                )
                abrirSesion(conn, usuarioId, rol, dispositivoId, ip)
            }
        }
    }

    // ------------------------------------------------------------------ inicio de sesión

    private sealed interface ResultadoLogin {
        data class Correcto(val sesion: Sesion) : ResultadoLogin
        data class Error(val error: ApiException) : ResultadoLogin
    }

    fun iniciarSesion(e: LoginEntrada, ip: String?): Sesion {
        val entrada = e.usuario.trim()
        if (entrada.isEmpty() || e.contrasena.isEmpty()) {
            throw errorValidacion(mapOf(Validaciones.USUARIO to "Escribe tu teléfono o matrícula y tu contraseña"))
        }
        e.dispositivo?.let { d -> validarDispositivo(d)?.let { throw errorValidacion(mapOf(Validaciones.DISPOSITIVO to it)) } }
        val telefono = Validaciones.normalizarTelefono(entrada).takeIf { it.length == 10 }

        // Primero se lee la cuenta sin bloquearla: Argon2 tarda y no debe hacerse con la fila apartada.
        val cuenta = dataSource.connection.use { conn -> buscarCuenta(conn, telefono, entrada) }
        val correcta = Contrasenas.verificar(e.contrasena, cuenta?.second ?: Contrasenas.HASH_SIMULADO)
        if (cuenta == null) throw credencialesInvalidas(null)

        // El intento fallido se guarda aunque la respuesta sea un error, por eso el error se lanza después del commit.
        val resultado = dataSource.transaccion { conn -> resolverLogin(conn, cuenta.first, correcta, e.dispositivo, ip) }
        return when (resultado) {
            is ResultadoLogin.Correcto -> resultado.sesion
            is ResultadoLogin.Error -> throw resultado.error
        }
    }

    private fun buscarCuenta(conn: Connection, telefono: String?, entrada: String): Pair<UUID, String>? =
        conn.consultarUno(
            """SELECT id, password_hash FROM usuario
               WHERE telefono_movil = ? OR upper(identificador_institucional) = upper(?)
               ORDER BY (telefono_movil = ?) DESC LIMIT 1""",
            telefono ?: "", entrada, telefono ?: "",
        ) { it.uuid("id") to it.getString("password_hash") }

    private fun resolverLogin(conn: Connection, usuarioId: UUID, correcta: Boolean, dispositivo: DispositivoEntrada?, ip: String?): ResultadoLogin {
        val u = conn.consultarUno(
            "SELECT estado::text AS estado, rol::text AS rol, intentos_fallidos, bloqueada_hasta FROM usuario WHERE id = ? FOR UPDATE",
            usuarioId,
        ) { EstadoCuenta(it.getString("estado"), Rol.valueOf(it.getString("rol")), it.getInt("intentos_fallidos"), it.instante("bloqueada_hasta")) }!!
        val ahora = Instant.now()

        when (u.estado) {
            "SUSPENDIDA" -> return ResultadoLogin.Error(ApiException(HttpStatusCode.Forbidden, "CUENTA_SUSPENDIDA", "La cuenta está suspendida"))
            "BAJA_SOLICITADA", "ELIMINADA" ->
                return ResultadoLogin.Error(ApiException(HttpStatusCode.Forbidden, "CUENTA_SUSPENDIDA", "La cuenta tiene una baja solicitada"))
        }
        if (u.bloqueadaHasta != null && u.bloqueadaHasta.isAfter(ahora)) return ResultadoLogin.Error(cuentaBloqueada(u.bloqueadaHasta))
        // Pasó el bloqueo: la cuenta vuelve a empezar.
        val intentosPrevios = if (u.estado == "BLOQUEADA_TEMPORAL" || u.bloqueadaHasta != null) 0 else u.intentosFallidos

        if (!correcta) {
            val maximo = parametro(conn, "login_max_intentos", 5)
            val intentos = intentosPrevios + 1
            if (intentos >= maximo) {
                val hasta = ahora.plusSeconds(parametro(conn, "login_bloqueo_minutos", 15) * 60L)
                conn.ejecutar(
                    "UPDATE usuario SET intentos_fallidos = ?, estado = 'BLOQUEADA_TEMPORAL', bloqueada_hasta = ?, actualizado_en = now() WHERE id = ?",
                    intentos, hasta, usuarioId,
                )
                Bitacora.registrar(conn, "BLOQUEO", usuarioId, "usuario", usuarioId.toString(),
                    buildJsonObject { put("intentos", intentos); put("bloqueadaHasta", hasta.toString()) }, ip)
                return ResultadoLogin.Error(cuentaBloqueada(hasta))
            }
            conn.ejecutar(
                "UPDATE usuario SET intentos_fallidos = ?, estado = 'ACTIVA', bloqueada_hasta = NULL WHERE id = ?", intentos, usuarioId,
            )
            Bitacora.registrar(conn, "ACCESO_FALLIDO", usuarioId, "usuario", usuarioId.toString(),
                buildJsonObject { put("intentos", intentos) }, ip)
            return ResultadoLogin.Error(credencialesInvalidas(maximo - intentos))
        }

        // La app es para la comunidad; Operadores y Administradores usan la consola web (login sin dispositivo).
        if (dispositivo != null && u.rol in setOf(Rol.OPERADOR, Rol.ADMINISTRADOR)) {
            return ResultadoLogin.Error(
                ApiException(HttpStatusCode.Forbidden, "PERFIL_NO_PERMITIDO", "Tu perfil usa la consola web, no la app",
                    buildJsonObject { put("rol", u.rol.name) }),
            )
        }
        conn.ejecutar(
            """UPDATE usuario SET intentos_fallidos = 0, estado = 'ACTIVA', bloqueada_hasta = NULL, ultimo_acceso_en = now()
               WHERE id = ?""",
            usuarioId,
        )
        val dispositivoId = dispositivo?.let { guardarDispositivo(conn, usuarioId, it) }
        Bitacora.registrar(conn, "ACCESO", usuarioId, "usuario", usuarioId.toString(),
            buildJsonObject { put("canal", if (dispositivo != null) "APP" else "CONSOLA") }, ip)
        return ResultadoLogin.Correcto(abrirSesion(conn, usuarioId, u.rol, dispositivoId, ip))
    }

    private data class EstadoCuenta(val estado: String, val rol: Rol, val intentosFallidos: Int, val bloqueadaHasta: Instant?)

    // ------------------------------------------------------------------ sesión, perfil y baja

    fun cerrarSesion(usuario: UsuarioToken, ip: String?) {
        val sesionId = usuario.sesionId ?: return
        dataSource.transaccion { conn ->
            val cambio = conn.ejecutar(
                "UPDATE sesion SET revocada_en = now() WHERE id = ? AND usuario_id = ? AND revocada_en IS NULL", sesionId, usuario.usuarioId,
            )
            if (cambio > 0) Bitacora.registrar(conn, "CERRAR_SESION", usuario.usuarioId, "sesion", sesionId.toString(), ip = ip)
        }
    }

    override fun vigente(sesionId: UUID): Boolean = dataSource.connection.use { conn ->
        conn.consultarUno(
            """SELECT 1 FROM sesion s JOIN usuario u ON u.id = s.usuario_id
               WHERE s.id = ? AND s.revocada_en IS NULL AND s.expira_en > now()
                 AND u.estado NOT IN ('SUSPENDIDA', 'BAJA_SOLICITADA', 'ELIMINADA')""",
            sesionId,
        ) { true } == true
    }

    fun perfil(usuarioId: UUID): UsuarioSalida = dataSource.connection.use { conn -> leerUsuario(conn, usuarioId) }
        ?: throw ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "La cuenta no existe")

    fun actualizarPerfil(usuarioId: UUID, c: PerfilCambio, ip: String?): UsuarioSalida {
        val telefono = c.telefonoMovil?.let(Validaciones::normalizarTelefono)
        val identificador = c.identificadorInstitucional?.trim()
        exigirValidos(
            mapOf(
                Validaciones.TELEFONO to c.telefonoMovil?.let(Validaciones::telefono),
                Validaciones.IDENTIFICADOR to identificador?.let(Validaciones::identificador),
                Validaciones.CARRERA to c.carreraAdscripcion?.let(Validaciones::carrera),
            ),
        )
        return conDuplicados {
            dataSource.transaccion { conn ->
                if (telefono != null && conn.consultarUno("SELECT 1 FROM usuario WHERE telefono_movil = ? AND id <> ?", telefono, usuarioId) { true } == true) {
                    throw errorDuplicado(Validaciones.TELEFONO, "Ese teléfono ya tiene una cuenta")
                }
                if (identificador != null &&
                    conn.consultarUno("SELECT 1 FROM usuario WHERE upper(identificador_institucional) = upper(?) AND id <> ?", identificador, usuarioId) { true } == true
                ) {
                    throw errorDuplicado(Validaciones.IDENTIFICADOR, "Esa matrícula ya tiene una cuenta")
                }
                conn.ejecutar(
                    """UPDATE usuario SET telefono_movil = coalesce(?, telefono_movil),
                                          identificador_institucional = coalesce(?, identificador_institucional),
                                          carrera_adscripcion = coalesce(?, carrera_adscripcion),
                                          acepta_entrenamiento_ia = coalesce(?, acepta_entrenamiento_ia),
                                          actualizado_en = now()
                       WHERE id = ?""",
                    telefono, identificador, c.carreraAdscripcion?.trim(), c.aceptaEntrenamientoIa, usuarioId,
                )
                val campos = buildList {
                    if (telefono != null) add("telefonoMovil")
                    if (identificador != null) add("identificadorInstitucional")
                    if (c.carreraAdscripcion != null) add("carreraAdscripcion")
                    if (c.aceptaEntrenamientoIa != null) add("aceptaEntrenamientoIa")
                }
                if (campos.isNotEmpty()) {
                    Bitacora.registrar(conn, "ACTUALIZAR_PERFIL", usuarioId, "usuario", usuarioId.toString(),
                        buildJsonObject { putJsonArray("campos") { campos.forEach { add(it) } } }, ip)
                }
                leerUsuario(conn, usuarioId)!!
            }
        }
    }

    /** Pide la baja (derecho ARCO de cancelación): la cuenta queda BAJA_SOLICITADA y se cierran todas sus sesiones. */
    fun solicitarBaja(usuarioId: UUID, contrasena: String, ip: String?) {
        val hash = dataSource.connection.use { conn ->
            conn.consultarUno("SELECT password_hash FROM usuario WHERE id = ?", usuarioId) { it.getString(1) }
        } ?: throw ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "La cuenta no existe")
        if (!Contrasenas.verificar(contrasena, hash)) throw credencialesInvalidas(null)
        dataSource.transaccion { conn ->
            conn.ejecutar("UPDATE usuario SET estado = 'BAJA_SOLICITADA', actualizado_en = now() WHERE id = ?", usuarioId)
            conn.ejecutar("UPDATE sesion SET revocada_en = now() WHERE usuario_id = ? AND revocada_en IS NULL", usuarioId)
            conn.ejecutar("INSERT INTO solicitud_arco (usuario_id, tipo, detalle) VALUES (?, 'CANCELACION', 'Baja pedida desde la app')", usuarioId)
            Bitacora.registrar(conn, "SOLICITAR_BAJA", usuarioId, "usuario", usuarioId.toString(), ip = ip)
        }
    }

    // ------------------------------------------------------------------ internos

    private fun abrirSesion(conn: Connection, usuarioId: UUID, rol: Rol, dispositivoId: UUID?, ip: String?): Sesion {
        val sesionId = UUID.randomUUID()
        val (token, expira) = tokens.emitir(UsuarioToken(usuarioId, rol, dispositivoId, sesionId), duracionSesion)
        conn.ejecutar(
            "INSERT INTO sesion (id, usuario_id, dispositivo_id, token_hash, expira_en) VALUES (?, ?, ?, ?, ?)",
            sesionId, usuarioId, dispositivoId, sha256(token), expira,
        )
        return Sesion(token, expira.toString(), leerUsuario(conn, usuarioId)!!)
    }

    /**
     * Registra o actualiza el teléfono. Si el mismo teléfono tenía otra cuenta, pasa a esta y se le borra el token de
     * push, para que no reciba los avisos de la cuenta anterior.
     */
    private fun guardarDispositivo(conn: Connection, usuarioId: UUID, d: DispositivoEntrada): UUID {
        val id = UUID.fromString(d.id)
        conn.ejecutar(
            """INSERT INTO dispositivo (id, usuario_id, modelo, android_api, soporta_wifi_aware, ultimo_visto_en)
               VALUES (?, ?, ?, ?, ?, now())
               ON CONFLICT (id) DO UPDATE SET
                 fcm_token = CASE WHEN dispositivo.usuario_id = EXCLUDED.usuario_id THEN dispositivo.fcm_token END,
                 usuario_id = EXCLUDED.usuario_id, modelo = EXCLUDED.modelo, android_api = EXCLUDED.android_api,
                 soporta_wifi_aware = EXCLUDED.soporta_wifi_aware, ultimo_visto_en = now()""",
            id, usuarioId, d.modelo?.take(80), d.androidApi, d.soportaWifiAware,
        )
        return id
    }

    private fun validarDispositivo(d: DispositivoEntrada): String? = when {
        runCatching { UUID.fromString(d.id) }.isFailure -> "El id del dispositivo debe ser un UUID"
        d.androidApi != null && d.androidApi < 26 -> "Se necesita Android 8.0 o superior"
        else -> null
    }

    private fun leerUsuario(conn: Connection, usuarioId: UUID): UsuarioSalida? = conn.consultarUno(
        """SELECT id, nombre, telefono_movil, identificador_institucional, escuela_id, carrera_adscripcion,
                  rol::text AS rol, estado::text AS estado, acepta_entrenamiento_ia
           FROM usuario WHERE id = ?""",
        usuarioId,
    ) { it.aUsuario() }

    private fun ResultSet.aUsuario() = UsuarioSalida(
        id = uuid("id").toString(),
        nombre = getString("nombre"),
        telefonoMovil = getString("telefono_movil"),
        identificadorInstitucional = getString("identificador_institucional"),
        escuelaId = getInt("escuela_id"),
        carreraAdscripcion = getString("carrera_adscripcion"),
        rol = getString("rol"),
        estado = getString("estado"),
        aceptaEntrenamientoIa = getBoolean("acepta_entrenamiento_ia"),
    )

    /** La fecha en que se aceptó el aviso, según el teléfono; si no llega, es inválida o es del futuro, ahora. */
    private fun fechaAceptacion(valor: String?): Instant {
        val ahora = Instant.now()
        val fecha = try { valor?.let(Instant::parse) } catch (_: DateTimeParseException) { null } ?: return ahora
        return if (fecha.isAfter(ahora)) ahora else fecha
    }

    /** Dos registros al mismo tiempo con el mismo teléfono: el segundo choca con el índice único y sale 409. */
    private fun <T> conDuplicados(bloque: () -> T): T = try {
        bloque()
    } catch (e: SQLException) {
        if (e.sqlState != "23505") throw e
        val campo = if (e.message.orEmpty().contains("telefono")) Validaciones.TELEFONO else Validaciones.IDENTIFICADOR
        throw errorDuplicado(campo, "Ese dato ya está registrado en otra cuenta")
    }

    private fun parametro(conn: Connection, clave: String, defecto: Int): Int =
        conn.consultarUno("SELECT valor FROM parametro_sistema WHERE clave = ?", clave) { it.getString(1).toIntOrNull() } ?: defecto

    private fun credencialesInvalidas(restantes: Int?) = ApiException(
        HttpStatusCode.Unauthorized, "CREDENCIALES_INVALIDAS", "Usuario o contraseña incorrectos",
        restantes?.let { buildJsonObject { put("intentosRestantes", it) } },
    )

    private fun cuentaBloqueada(hasta: Instant) = ApiException(
        HttpStatusCode.Locked, "CUENTA_BLOQUEADA", "La cuenta está bloqueada por intentos fallidos",
        buildJsonObject { put("bloqueadaHasta", hasta.toString()) },
    )

    private companion object {
        val TIPOS_COMUNIDAD = setOf("ALUMNO", "DOCENTE", "PERSONAL")
        fun sha256(texto: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.toByteArray()))
    }
}
