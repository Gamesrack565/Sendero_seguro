package com.escom.sendero_seguro.emergencias.remoto

import com.escom.sendero_seguro.emergencias.AlertaEmergencia
import com.escom.sendero_seguro.emergencias.TipoDetonador
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.time.Instant

class ServidorAlertasMallaTest {

    @Test
    fun unReenvioDuplicadoIdentificaQueLaAlertaOriginalLlegoPorMalla() = runBlocking {
        val api = ApiSimulada(codigoRegistro = 200, canal = "MALLA")

        val resultado = ServidorAlertasRetrofit(api).enviar(alerta()) as ResultadoServidor.Registrada

        assertEquals("MALLA", resultado.canalRecepcion)
        assertEquals(1, api.consultasDeDetalle)
    }

    @Test
    fun unaAlertaNuevaNoConsultaElCanalDeRecepcion() = runBlocking {
        val api = ApiSimulada(codigoRegistro = 201, canal = "INTERNET")

        val resultado = ServidorAlertasRetrofit(api).enviar(alerta()) as ResultadoServidor.Registrada

        assertEquals(null, resultado.canalRecepcion)
        assertEquals(0, api.consultasDeDetalle)
    }

    private fun alerta() = AlertaEmergencia(
        idAlerta = "00000001-1111-4111-8111-111111111111",
        detonador = TipoDetonador.BOTON_SOS,
        latitudInicial = 19.5,
        longitudInicial = -99.1,
        precisionMetros = 10f,
        nivelBateria = 80,
        timestampInicio = Instant.parse("2026-11-15T20:00:00Z").toEpochMilli()
    )

    private class ApiSimulada(
        private val codigoRegistro: Int,
        private val canal: String
    ) : EmergenciasApi {
        var consultasDeDetalle = 0

        override suspend fun registrarAlerta(body: AlertaNuevaDto): Response<AlertaRegistradaDto> =
            Response.success(
                codigoRegistro,
                AlertaRegistradaDto(
                    id = body.id,
                    folio = "ALR-2026-000001",
                    estado = "ENVIADA",
                    registradaEn = "2026-11-15T20:00:00Z"
                )
            )

        override suspend fun consultarAlerta(alertaId: String): Response<AlertaCanalDto> {
            consultasDeDetalle++
            return Response.success(AlertaCanalDto(canal))
        }

        override suspend fun agregarUbicaciones(
            alertaId: String,
            body: LoteUbicacionesDto
        ): Response<UbicacionesAgregadasDto> = Response.success(UbicacionesAgregadasDto(0, 0))

        override suspend fun consultarLineaTiempo(alertaId: String): Response<LineaTiempoAlertaDto> =
            Response.success(LineaTiempoAlertaDto(alertaId, emptyList()))

        override suspend fun obtenerTokenDesarrollo(body: SolicitudTokenDesarrolloDto): Response<TokenDesarrolloDto> =
            Response.success(TokenDesarrolloDto("token", "usuario", "INTEGRANTE", "2026-11-15T21:00:00Z"))
    }
}
