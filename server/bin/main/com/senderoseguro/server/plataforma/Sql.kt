package com.senderoseguro.server.plataforma

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Ejecuta [bloque] en una transacción: si termina bien hace commit, y si lanza una excepción hace rollback.
 * Los enumerados de PostgreSQL se pasan como texto y se convierten en el SQL, por ejemplo `?::alerta_estado`.
 */
fun <T> DataSource.transaccion(bloque: (Connection) -> T): T =
    connection.use { conn ->
        conn.autoCommit = false
        try {
            bloque(conn).also { conn.commit() }
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        }
    }

/** Prepara [sql], le pone los [parametros] en orden y ejecuta [bloque] con la sentencia lista. */
fun <T> Connection.sentencia(sql: String, vararg parametros: Any?, bloque: (PreparedStatement) -> T): T =
    prepareStatement(sql).use { ps ->
        parametros.forEachIndexed { i, valor ->
            when (valor) {
                is Instant -> ps.setTimestamp(i + 1, Timestamp.from(valor))
                else -> ps.setObject(i + 1, valor)
            }
        }
        bloque(ps)
    }

/** Ejecuta un INSERT, UPDATE o DELETE y regresa cuántas filas cambió. */
fun Connection.ejecutar(sql: String, vararg parametros: Any?): Int =
    sentencia(sql, *parametros) { it.executeUpdate() }

/** Ejecuta una consulta y regresa la primera fila convertida con [mapeo], o null si no hubo filas. */
fun <T> Connection.consultarUno(sql: String, vararg parametros: Any?, mapeo: (ResultSet) -> T): T? =
    sentencia(sql, *parametros) { ps -> ps.executeQuery().use { rs -> if (rs.next()) mapeo(rs) else null } }

/** Ejecuta una consulta y regresa todas las filas convertidas con [mapeo]. */
fun <T> Connection.consultar(sql: String, vararg parametros: Any?, mapeo: (ResultSet) -> T): List<T> =
    sentencia(sql, *parametros) { ps ->
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(mapeo(rs)) } }
    }

fun ResultSet.uuid(columna: String): UUID = getObject(columna, UUID::class.java)
fun ResultSet.uuidONulo(columna: String): UUID? = getObject(columna, UUID::class.java)
fun ResultSet.instante(columna: String): Instant? = getTimestamp(columna)?.toInstant()
fun ResultSet.dobleONulo(columna: String): Double? = getDouble(columna).takeUnless { wasNull() }
