package com.senderoseguro.server.dispersion

import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.dobleONulo
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.transaccion
import com.senderoseguro.server.plataforma.uuid
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.sql.Connection
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/** Respuesta de `GET /enlace/{token}` (esquema `EnlacePublico`). No lleva datos del perfil (X-01). */
@Serializable
data class EnlacePublico(
    val tipo: String,
    val primerNombre: String,
    val estado: String,
    val venceEn: String,
    val activa: Boolean,
    val ultimaUbicacion: UbicacionJson? = null,
)

/**
 * Enlace seguro de ubicación de una alerta para la red de apoyo (RF-9, CU-16).
 *
 * Hay un enlace por alerta. El token es HMAC-SHA256(secreto, id del enlace) en base64url (43 caracteres):
 * la base solo guarda su SHA-256 (`token_hash`) y el servidor lo puede volver a calcular con el secreto
 * cuando arma el mensaje para la red de apoyo. Sin el secreto, una copia de la base no sirve para abrir enlaces.
 *
 * El enlace deja de funcionar cuando la alerta se cierra o se cancela (`revocado_en`) o al llegar a `vence_en`.
 */
class EnlacesServicio(
    private val dataSource: DataSource,
    secreto: String,
    private val horasMax: Int,
) {
    private val llave = SecretKeySpec(secreto.toByteArray(), "HmacSHA256")

    /** Token del enlace. Se calcula; nunca se guarda. */
    fun token(enlaceId: UUID): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(llave) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(enlaceId.toString().toByteArray()))
    }

    /** Token del enlace vigente de la alerta, para armar el mensaje a la red de apoyo. Null si no hay. */
    fun tokenDeAlerta(alertaId: UUID): String? = dataSource.connection.use { conn ->
        conn.consultarUno(
            "SELECT id FROM enlace_ubicacion WHERE alerta_id = ? AND revocado_en IS NULL AND vence_en > now()",
            alertaId,
        ) { it.uuid("id") }
    }?.let(::token)

    /** Crea el enlace de la alerta si todavía no tiene uno; regresa true si lo creó. Corre en la transacción de quien llama. */
    internal fun crear(conn: Connection, alertaId: UUID): Boolean {
        val existe = conn.consultarUno("SELECT 1 FROM enlace_ubicacion WHERE alerta_id = ?", alertaId) { true } ?: false
        if (existe) return false
        val id = UUID.randomUUID()
        conn.ejecutar(
            """INSERT INTO enlace_ubicacion (id, alerta_id, token_hash, vence_en)
               VALUES (?, ?, ?, now() + make_interval(hours => ?))""",
            id, alertaId, sha256(token(id)), horasMax,
        )
        return true
    }

    /** Revoca el enlace de la alerta. Lo llaman el cierre (aquí) y la cancelación (Módulo 1). */
    fun revocar(alertaId: UUID) = dataSource.transaccion { revocar(it, alertaId) }

    internal fun revocar(conn: Connection, alertaId: UUID) {
        conn.ejecutar("UPDATE enlace_ubicacion SET revocado_en = now() WHERE alerta_id = ? AND revocado_en IS NULL", alertaId)
    }

    /** Lo que ve la red de apoyo al abrir el enlace. 404 si el token no existe; 410 si ya no está vigente. */
    fun consultar(token: String): EnlacePublico = dataSource.connection.use { conn ->
        if (!Regex("^[A-Za-z0-9_-]{43}$").matches(token)) throw noEncontrado()
        val e = conn.consultarUno(
            """SELECT en.vence_en, en.revocado_en IS NOT NULL OR en.vence_en <= now() AS vencido,
                      a.estado::text AS estado, split_part(u.nombre, ' ', 1) AS primer_nombre,
                      ${UltimaUbicacion.COLUMNAS}
               FROM enlace_ubicacion en
               JOIN alerta a ON a.id = en.alerta_id
               JOIN usuario u ON u.id = a.usuario_id
               ${UltimaUbicacion.JOIN}
               WHERE en.token_hash = ?""",
            sha256(token),
        ) { rs ->
            val estado = rs.getString("estado")
            if (rs.getBoolean("vencido") || estado in setOf("CERRADA", "CANCELADA")) null
            else EnlacePublico(
                tipo = "ALERTA",
                primerNombre = rs.getString("primer_nombre"),
                estado = ESTADO_LEGIBLE[estado] ?: "Activa",
                venceEn = rs.instante("vence_en")!!.toString(),
                activa = true,
                ultimaUbicacion = UltimaUbicacion.leer(rs),
            )
        } ?: run {
            // Si el token existe pero el enlace ya no está vigente: 410. Si no existe: 404.
            val existe = conn.consultarUno("SELECT 1 FROM enlace_ubicacion WHERE token_hash = ?", sha256(token)) { true } ?: false
            if (existe) throw ApiException(HttpStatusCode.Gone, "VENCIDO", "El enlace ya no está disponible: la alerta terminó")
            throw noEncontrado()
        }
        e
    }

    private fun noEncontrado() = ApiException(HttpStatusCode.NotFound, "NO_ENCONTRADO", "El enlace no existe")

    private fun sha256(texto: String): String =
        MessageDigest.getInstance("SHA-256").digest(texto.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        /** Estado en palabras para la red de apoyo, sin detalles internos. */
        val ESTADO_LEGIBLE = mapOf(
            "ENVIADA" to "Enviada",
            "ASIGNADA" to "Unidad asignada",
            "ESCALADA" to "Buscando unidad",
            "ACUSADA" to "Recibida por la unidad",
            "EN_ATENCION" to "En atención",
            "ATENDIDA" to "Atendida",
        )
    }
}
