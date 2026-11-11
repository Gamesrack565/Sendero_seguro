package com.senderoseguro.server.comunidad

import com.senderoseguro.server.cuentas.errorDuplicado
import com.senderoseguro.server.cuentas.errorValidacion
import com.senderoseguro.server.plataforma.Bitacora
import com.senderoseguro.server.plataforma.UsuarioToken
import com.senderoseguro.server.plataforma.consultar
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.instante
import com.senderoseguro.server.plataforma.transaccion
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.SQLException
import javax.sql.DataSource

private val CLAVE_MAPA = Regex("^mapa/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(zip|bin)$")
private val HASH = Regex("^[0-9a-f]{64}$")

/**
 * Mapas offline (P12, CU-36), puntos de interés del mapa comunitario (P9) y zonas con nombre (filtros del feed y
 * del tablero). El archivo del paquete se sube antes con `POST /archivos/mapa`; aquí se publica su registro.
 */
class MapasServicio(private val dataSource: DataSource) {

    fun paquetes(): List<PaqueteMapa> = dataSource.connection.use { conn ->
        conn.consultar("SELECT * FROM paquete_mapa WHERE vigente ORDER BY region") {
            PaqueteMapa(
                it.getInt("id"), it.getString("region"), it.getString("version"),
                it.getDouble("bbox_min_lat"), it.getDouble("bbox_min_lon"), it.getDouble("bbox_max_lat"), it.getDouble("bbox_max_lon"),
                it.getLong("tamano_bytes"), it.getString("hash_sha256"), "/archivos/" + it.getString("ruta_archivo"),
                it.instante("publicado_en").toString(),
            )
        }
    }

    /** Publica la versión nueva de una región; la anterior deja de ser la vigente. */
    fun publicarPaquete(admin: UsuarioToken, e: PaqueteEntrada, ip: String?): PaqueteMapa {
        val region = e.region.trim()
        val version = e.version.trim()
        val errores = buildMap {
            if (region.length !in 3..100) put("region", "Entre 3 y 100 caracteres")
            if (version.isEmpty() || version.length > 20) put("version", "Entre 1 y 20 caracteres")
            if (!CLAVE_MAPA.matches(e.clave)) put("clave", "Usa la clave que regresó POST /archivos/mapa")
            if (!HASH.matches(e.hashSha256)) put("hashSha256", "SHA-256 en hexadecimal")
            if (e.tamanoBytes <= 0) put("tamanoBytes", "Debe ser mayor que 0")
            if (e.minLatitud >= e.maxLatitud || e.minLongitud >= e.maxLongitud) put("minLatitud", "La caja está al revés")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        dataSource.transaccion { conn ->
            conn.ejecutar("UPDATE paquete_mapa SET vigente = false WHERE region = ? AND vigente", region)
            try {
                conn.ejecutar(
                    """INSERT INTO paquete_mapa (region, version, bbox_min_lat, bbox_min_lon, bbox_max_lat, bbox_max_lon, tamano_bytes, hash_sha256, ruta_archivo)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    region, version, e.minLatitud, e.minLongitud, e.maxLatitud, e.maxLongitud, e.tamanoBytes, e.hashSha256, e.clave,
                )
            } catch (ex: SQLException) {
                if (ex.sqlState == "23505") throw errorDuplicado("version", "Esa versión de la región ya existe") else throw ex
            }
            Bitacora.registrar(conn, "PUBLICAR_PAQUETE_MAPA", admin.usuarioId, "paquete_mapa", "$region@$version",
                buildJsonObject { put("tamanoBytes", e.tamanoBytes) }, ip)
        }
        return paquetes().first { it.region == region }
    }

    fun versiones(capaRiesgo: Int?, catalogo: Int?): VersionesMapas =
        VersionesMapas(capaRiesgo, catalogo, paquetes().map { PaqueteVersion(it.region, it.version) })

    fun puntosInteres(caja: Geo.Caja?): List<PuntoInteres> = dataSource.connection.use { conn ->
        conn.consultar(
            """SELECT id, nombre, tipo, latitud::double precision, longitud::double precision FROM punto_interes
               WHERE activo AND (?::double precision IS NULL OR (latitud BETWEEN ? AND ? AND longitud BETWEEN ? AND ?))
               ORDER BY nombre LIMIT 1000""",
            caja?.minLat, caja?.minLat, caja?.maxLat, caja?.minLon, caja?.maxLon,
        ) { PuntoInteres(it.getInt(1), it.getString(2), it.getString(3), it.getDouble(4), it.getDouble(5)) }
    }

    fun crearPuntoInteres(admin: UsuarioToken, e: PuntoInteresEntrada, ip: String?): PuntoInteres {
        val errores = buildMap {
            if (e.nombre.trim().length !in 3..150) put("nombre", "Entre 3 y 150 caracteres")
            if (e.tipo.trim().length !in 3..40) put("tipo", "Entre 3 y 40 caracteres, por ejemplo CASETA, BOTON_EMERGENCIA o LUMINARIA")
            if (e.latitud !in -90.0..90.0 || e.longitud !in -180.0..180.0) put("latitud", "Coordenadas fuera de rango")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        return dataSource.transaccion { conn ->
            val id = conn.consultarUno(
                "INSERT INTO punto_interes (nombre, tipo, latitud, longitud) VALUES (?, ?, ?, ?) RETURNING id",
                e.nombre.trim(), e.tipo.trim().uppercase(), e.latitud, e.longitud,
            ) { it.getInt(1) }!!
            Bitacora.registrar(conn, "CREAR_PUNTO_INTERES", admin.usuarioId, "punto_interes", id.toString(), ip = ip)
            PuntoInteres(id, e.nombre.trim(), e.tipo.trim().uppercase(), e.latitud, e.longitud)
        }
    }

    fun zonas(): List<ZonaGeografica> = dataSource.connection.use { conn ->
        conn.consultar("SELECT * FROM zona_geografica WHERE activa ORDER BY nombre") {
            ZonaGeografica(it.getInt("id"), it.getString("nombre"), it.getDouble("bbox_min_lat"), it.getDouble("bbox_min_lon"),
                it.getDouble("bbox_max_lat"), it.getDouble("bbox_max_lon"))
        }
    }

    fun crearZona(admin: UsuarioToken, e: ZonaGeograficaEntrada, ip: String?): ZonaGeografica {
        val nombre = e.nombre.trim()
        val errores = buildMap {
            if (nombre.length !in 3..100) put("nombre", "Entre 3 y 100 caracteres")
            if (e.minLatitud >= e.maxLatitud || e.minLongitud >= e.maxLongitud) put("minLatitud", "La caja está al revés")
        }
        if (errores.isNotEmpty()) throw errorValidacion(errores)
        return dataSource.transaccion { conn ->
            val id = try {
                conn.consultarUno(
                    "INSERT INTO zona_geografica (nombre, bbox_min_lat, bbox_min_lon, bbox_max_lat, bbox_max_lon) VALUES (?, ?, ?, ?, ?) RETURNING id",
                    nombre, e.minLatitud, e.minLongitud, e.maxLatitud, e.maxLongitud,
                ) { it.getInt(1) }!!
            } catch (ex: SQLException) {
                if (ex.sqlState == "23505") throw errorDuplicado("nombre", "Ya existe una zona con ese nombre") else throw ex
            }
            Bitacora.registrar(conn, "CREAR_ZONA", admin.usuarioId, "zona_geografica", id.toString(), ip = ip)
            ZonaGeografica(id, nombre, e.minLatitud, e.minLongitud, e.maxLatitud, e.maxLongitud)
        }
    }
}
