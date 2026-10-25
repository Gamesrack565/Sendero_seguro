package com.senderoseguro.server.desarrollo

import com.senderoseguro.server.dispersion.DispersionServicio
import com.senderoseguro.server.dispersion.EscalamientoServicio
import com.senderoseguro.server.dispersion.ResultadoDispersion
import com.senderoseguro.server.dispersion.ResultadoEscalamiento
import com.senderoseguro.server.plataforma.ApiException
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.senderoseguro.server.plataforma.transaccion
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.sql.DataSource

/**
 * Rutas solo para desarrollo. Se registran únicamente con `db.datosPrueba = true`, nunca en la nube.
 * Sirven para probar la dispersión sin esperar a `POST /alertas`.
 */
fun Route.rutasDesarrollo(dataSource: DataSource, dispersion: DispersionServicio, escalamiento: EscalamientoServicio) {
    route("/dev") {
        // Crea una alerta de Ana (datos de prueba) en la ubicación indicada y la despacha en ese momento.
        post("/alertas") {
            val texto = call.receiveText()
            val peticion = if (texto.isBlank()) AlertaPrueba() else runCatching { json.decodeFromString<AlertaPrueba>(texto) }
                .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "Cuerpo inválido: ${it.message}") }
            val (alertaId, folio) = AlertasPrueba.crear(dataSource, peticion)
            call.respond(HttpStatusCode.Created, AlertaPruebaRespuesta.de(alertaId, folio, dispersion.despacharAhora(alertaId)))
        }

        // Vence ya el plazo de acuse de la alerta y la escala, sin esperar al temporizador.
        post("/alertas/{id}/vencer") {
            val id = runCatching { UUID.fromString(call.parameters["id"]) }
                .getOrElse { throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "El id no es un UUID") }
            AlertasPrueba.vencerPlazo(dataSource, id)
            call.respond(EscalamientoPruebaRespuesta.de(id, escalamiento.escalarPorFaltaDeAcuse(id)))
        }

        // Regresa a DISPONIBLE las unidades ocupadas, para repetir la prueba.
        post("/unidades/liberar") {
            call.respond(mapOf("unidadesLiberadas" to AlertasPrueba.liberarUnidades(dataSource)))
        }
    }
}

private val json = Json { ignoreUnknownKeys = true }

/** Cuerpo de `POST /dev/alertas`. Sin cuerpo: alerta genérica frente a la ESCOM. */
@Serializable
data class AlertaPrueba(
    val tipo: String = "GENERICA",
    val latitud: Double = 19.504507,
    val longitud: Double = -99.146911,
)

@Serializable
data class AlertaPruebaRespuesta(
    val alertaId: String,
    val folio: String,
    val resultado: String,
    val unidad: String? = null,
    val corporacion: String? = null,
    val distanciaM: Double? = null,
    val acuseLimiteEn: String? = null,
) {
    companion object {
        fun de(alertaId: UUID, folio: String, r: ResultadoDispersion) = when (r) {
            is ResultadoDispersion.Asignada -> AlertaPruebaRespuesta(
                alertaId.toString(), folio, "ASIGNADA", r.unidadNombre, r.corporacion, r.distanciaM, r.acuseLimiteEn.toString(),
            )
            is ResultadoDispersion.SinUnidad -> AlertaPruebaRespuesta(alertaId.toString(), folio, "SIN_UNIDAD", corporacion = r.corporacion)
            is ResultadoDispersion.YaDespachada -> AlertaPruebaRespuesta(alertaId.toString(), folio, "YA_DESPACHADA")
            ResultadoDispersion.SinRegla -> AlertaPruebaRespuesta(alertaId.toString(), folio, "SIN_REGLA")
            ResultadoDispersion.NoExiste -> AlertaPruebaRespuesta(alertaId.toString(), folio, "NO_EXISTE")
        }
    }
}

@Serializable
data class EscalamientoPruebaRespuesta(
    val alertaId: String,
    val resultado: String,
    val nivel: Int? = null,
    val destino: String? = null,
    val corporacion: String? = null,
    val unidad: String? = null,
    val acuseLimiteEn: String? = null,
) {
    companion object {
        fun de(alertaId: UUID, r: ResultadoEscalamiento) = when (r) {
            is ResultadoEscalamiento.Escalada -> EscalamientoPruebaRespuesta(
                alertaId.toString(), "ESCALADA", r.nivel, r.destino, r.corporacion, r.unidad, r.acuseLimiteEn.toString(),
            )
            ResultadoEscalamiento.CadenaAgotada -> EscalamientoPruebaRespuesta(alertaId.toString(), "CADENA_AGOTADA")
            ResultadoEscalamiento.NoAplica -> EscalamientoPruebaRespuesta(alertaId.toString(), "NO_APLICA")
        }
    }
}

/** Inserta alertas como lo hará el registro de alertas (P4), con los usuarios de los datos de prueba. */
object AlertasPrueba {
    val ANA: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val DISPOSITIVO_ANA: UUID = UUID.fromString("00000000-0000-4000-8000-0000000000d1")

    /** Crea la alerta (estado ENVIADA), su primera ubicación y su primer evento. Regresa el id y el folio. */
    fun crear(dataSource: DataSource, p: AlertaPrueba): Pair<UUID, String> = dataSource.transaccion { conn ->
        val id = UUID.randomUUID()
        val folio = conn.consultarUno(
            """INSERT INTO alerta (id, usuario_id, dispositivo_id, tipo_incidente_id, version_catalogo_id,
                                   canal_recepcion, detonador, activada_en, enviada_en)
               SELECT ?, ?, ?, tv.tipo_incidente_id, tv.version_catalogo_id, 'INTERNET', 'BOTON', now(), now()
               FROM tipo_incidente_version tv
               JOIN version_catalogo v ON v.id = tv.version_catalogo_id
               JOIN tipo_incidente t ON t.id = tv.tipo_incidente_id
               WHERE v.estado = 'PUBLICADA' AND t.codigo = ? AND tv.activo AND tv.aplica_a IN ('ALERTA', 'AMBOS')
               RETURNING folio""",
            id, ANA, DISPOSITIVO_ANA, p.tipo,
        ) { it.getString("folio") }
            ?: throw ApiException(HttpStatusCode.BadRequest, "VALIDACION", "El tipo ${p.tipo} no existe o no aplica a alertas")

        conn.ejecutar(
            """INSERT INTO ubicacion_alerta (alerta_id, latitud, longitud, precision_m, fuente, capturada_en)
               VALUES (?, ?, ?, 10, 'GNSS', now())""",
            id, p.latitud, p.longitud,
        )
        conn.ejecutar(
            "INSERT INTO evento_alerta (alerta_id, estado_anterior, estado_nuevo, actor_id) VALUES (?, NULL, 'ENVIADA', ?)",
            id, ANA,
        )
        id to folio
    }

    /** Pone el plazo de acuse en el pasado, como si ya hubiera vencido. */
    fun vencerPlazo(dataSource: DataSource, alertaId: UUID) = dataSource.transaccion { conn ->
        conn.ejecutar(
            "UPDATE alerta SET acuse_limite_en = now() - interval '1 second' WHERE id = ? AND acuse_limite_en IS NOT NULL",
            alertaId,
        )
    }

    /** Libera las unidades ocupadas y cierra sus asignaciones abiertas. Regresa cuántas unidades liberó. */
    fun liberarUnidades(dataSource: DataSource): Int = dataSource.transaccion { conn ->
        conn.ejecutar("UPDATE asignacion SET liberada_en = now() WHERE liberada_en IS NULL")
        conn.ejecutar("UPDATE unidad_atencion SET estado = 'DISPONIBLE' WHERE estado IN ('ASIGNADA', 'ATENDIENDO')")
    }
}
