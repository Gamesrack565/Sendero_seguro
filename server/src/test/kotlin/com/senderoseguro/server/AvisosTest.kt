package com.senderoseguro.server

import com.senderoseguro.server.avisos.EnvioAvisos
import com.senderoseguro.server.avisos.Mensaje
import com.senderoseguro.server.avisos.Proveedor
import com.senderoseguro.server.avisos.ResultadoEnvio
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.dispersion.AtencionServicio
import com.senderoseguro.server.dispersion.CambioEstado
import com.senderoseguro.server.dispersion.ConsultaAlertas
import com.senderoseguro.server.dispersion.EscalamientoServicio
import com.senderoseguro.server.dispersion.ResultadoDispersion
import com.senderoseguro.server.plataforma.Rol
import com.senderoseguro.server.plataforma.UsuarioToken
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Envío de avisos con la política de reintentos de E-03, con un proveedor de prueba. */
class AvisosTest : PruebaConBaseDatos() {

    /** Guarda lo que se le pide enviar y contesta lo que diga [respuesta]. */
    private class ProveedorDePrueba(override val nombre: String, override val confirmaEntrega: Boolean = false) : Proveedor {
        val enviados = mutableListOf<Mensaje>()
        var respuesta: (Mensaje) -> ResultadoEnvio = { ResultadoEnvio.Aceptado("${nombre}-${it.notificacionId}", entregado = true) }
        override fun enviar(m: Mensaje): ResultadoEnvio = respuesta(m).also { enviados += m }
    }

    private val push = ProveedorDePrueba("push")
    private val sms = ProveedorDePrueba("sms")

    private fun envio(esperaBase: Duration = Duration.ZERO, max: Int = 3, smsConfirma: Boolean = false): Pair<EnvioAvisos, ProveedorDePrueba> {
        val proveedorSms = if (smsConfirma) ProveedorDePrueba("sms", confirmaEntrega = true) else sms
        val e = EnvioAvisos(
            dataSource, push, proveedorSms, enlaces,
            EnvioAvisos.Politica(maxReintentos = max, esperaBase = esperaBase, plazoConfirmacion = 10.minutes, lote = 500),
            urlEnlace = "https://sendero.example/enlace",
        )
        return e to proveedorSms
    }

    /** Se envían (con éxito) los avisos que quedaron de otras pruebas, para que cada prueba vea solo los suyos. */
    private fun vaciarCola() {
        push.respuesta = { ResultadoEnvio.Aceptado("push-${it.notificacionId}", entregado = true) }
        sms.respuesta = { ResultadoEnvio.Aceptado("sms-${it.notificacionId}", entregado = true) }
        val (e, _) = envio()
        while (e.procesarPendientes() > 0) Unit
        push.enviados.clear()
        sms.enviados.clear()
    }

    private fun alertaDespachada(): UUID = alerta().also {
        assertIs<ResultadoDispersion.Asignada>(dispersion.despacharAhora(it))
    }

    private fun estado(id: UUID, tipo: String, extra: String = "") =
        texto("SELECT string_agg(estado::text, ',' ORDER BY id) FROM notificacion WHERE alerta_id = ? AND tipo = '$tipo'::notificacion_tipo $extra", id)

    @Test
    fun `la red de apoyo recibe push o SMS con el enlace y queda ENTREGADA`() {
        vaciarCola()
        val id = alertaDespachada()
        val (e, _) = envio()
        e.procesarPendientes()

        // Mamá (contacto sin app) por SMS, sin acentos y con el enlace en vivo.
        val mama = sms.enviados.single { it.destino == "5520000001" }
        val token = enlaces.tokenDeAlerta(id)!!
        assertTrue(mama.texto.contains("https://sendero.example/enlace/$token"), mama.texto)
        assertTrue(mama.texto.startsWith("Sendero Seguro: Ana activo una alerta."), mama.texto)
        assertTrue(mama.texto.none { it in "áéíóúñÁÉÍÓÚÑ" })
        // Beto no tiene teléfono con push registrado: su aviso sale por SMS a su número (E-03, elegir canal).
        assertTrue(sms.enviados.any { it.destino == "5510000002" })
        assertEquals("ENTREGADA,ENTREGADA", estado(id, "RED_APOYO"))
        assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND tipo = 'RED_APOYO' AND usuario_id IS NOT NULL AND canal = 'SMS'", id))
        assertEquals("sms-", texto("SELECT left(proveedor_mensaje_id, 4) FROM notificacion WHERE alerta_id = ? AND contacto_id IS NOT NULL", id))
    }

    @Test
    fun `con push registrado va por push y el contacto SOLO_AVISO no recibe el enlace`() {
        vaciarCola()
        sql("UPDATE contacto_confianza SET nivel_acceso = 'SOLO_AVISO' WHERE id = '00000000-0000-4000-8000-0000000000c1'")
        try {
            val id = alertaDespachada()
            // El aviso al emisor (ACUSE) va a la cuenta de Ana, que sí tiene token FCM.
            sql("INSERT INTO notificacion (alerta_id, usuario_id, tipo, canal) VALUES (?, ?, 'ACUSE', 'PUSH')", id, AlertasPrueba.ANA)
            val (e, _) = envio()
            e.procesarPendientes()

            val acuse = push.enviados.single { it.datos["alertaId"] == id.toString() }
            assertEquals("token-fcm-de-prueba-ana", acuse.destino)
            assertEquals("Tu alerta fue recibida", acuse.titulo)
            assertEquals("ACUSE", acuse.datos["tipo"])
            assertFalse(sms.enviados.single { it.destino == "5520000001" }.texto.contains("enlace"))
        } finally {
            sql("UPDATE contacto_confianza SET nivel_acceso = 'SEGUIMIENTO' WHERE id = '00000000-0000-4000-8000-0000000000c1'")
        }
    }

    @Test
    fun `un aviso que falla se reintenta con espera creciente`() {
        vaciarCola()
        val id = alertaDespachada()
        val (e, _) = envio(esperaBase = 60.seconds)
        sms.respuesta = { ResultadoEnvio.Fallo("Pasarela caída") }
        e.procesarPendientes()
        val mama = "AND contacto_id IS NOT NULL"
        assertEquals("FALLIDA", estado(id, "RED_APOYO", mama))
        assertEquals("1", texto("SELECT reintentos::text FROM notificacion WHERE alerta_id = ? AND tipo = 'RED_APOYO' $mama", id))
        assertEquals("Pasarela caída", texto("SELECT ultimo_error FROM notificacion WHERE alerta_id = ? AND tipo = 'RED_APOYO' $mama", id))
        // Primera espera: 60 s. Mientras no se cumpla, no se vuelve a intentar.
        assertEquals(60, numero("SELECT round(extract(epoch FROM proximo_reintento_en - now()))::int FROM notificacion WHERE alerta_id = ? AND tipo = 'RED_APOYO' $mama", id))
        val antes = sms.enviados.size
        e.procesarPendientes()
        assertEquals(antes, sms.enviados.count())
        // Ya pasó la espera: segundo intento, y la siguiente espera es del doble.
        sql("UPDATE notificacion SET proximo_reintento_en = now() WHERE alerta_id = ? AND estado = 'FALLIDA'", id)
        e.procesarPendientes()
        assertEquals(120, numero("SELECT round(extract(epoch FROM proximo_reintento_en - now()))::int FROM notificacion WHERE alerta_id = ? AND tipo = 'RED_APOYO' $mama", id))
        // Se recupera la pasarela: se entrega.
        sms.respuesta = { ResultadoEnvio.Aceptado("ok-${it.notificacionId}", entregado = true) }
        sql("UPDATE notificacion SET proximo_reintento_en = now() WHERE alerta_id = ? AND estado = 'FALLIDA'", id)
        e.procesarPendientes()
        assertEquals("ENTREGADA", estado(id, "RED_APOYO", mama))
        assertNull(texto("SELECT ultimo_error FROM notificacion WHERE alerta_id = ? AND tipo = 'RED_APOYO' $mama", id))
    }

    @Test
    fun `al llegar al maximo de reintentos se descarta y se avisa al administrador`() {
        vaciarCola()
        val id = alertaDespachada()
        val (e, _) = envio(max = 3)
        sms.respuesta = { ResultadoEnvio.Fallo("Número no disponible") }
        repeat(3) { e.procesarPendientes() }
        val mama = "AND contacto_id IS NOT NULL"
        assertEquals("DESCARTADA", estado(id, "RED_APOYO", mama))
        assertEquals("3", texto("SELECT reintentos::text FROM notificacion WHERE alerta_id = ? AND tipo = 'RED_APOYO' $mama", id))
        assertEquals(3, sms.enviados.count { it.destino == "5520000001" })
        // E-03: "avisar al Admin". Queda en la historia de la alerta y la consola lo recibe por WebSocket.
        assertEquals(
            "RED_APOYO|SMS",
            texto("SELECT (detalle->>'tipo') || '|' || (detalle->>'canal') FROM evento_alerta WHERE alerta_id = ? AND detalle->>'accion' = 'ENTREGA_DESCARTADA' ORDER BY id LIMIT 1", id),
        )
        // Ya no se intenta más.
        e.procesarPendientes()
        assertEquals(3, sms.enviados.count { it.destino == "5520000001" })
    }

    @Test
    fun `un error definitivo descarta sin reintentar`() {
        vaciarCola()
        val id = alertaDespachada()
        val (e, _) = envio(max = 5)
        sms.respuesta = { ResultadoEnvio.Fallo("Número inválido", definitivo = true) }
        e.procesarPendientes()
        assertEquals("DESCARTADA", estado(id, "RED_APOYO", "AND contacto_id IS NOT NULL"))
        assertEquals(1, sms.enviados.count { it.destino == "5520000001" })
    }

    @Test
    fun `sin confirmacion a tiempo se reintenta y la confirmacion la marca ENTREGADA`() {
        vaciarCola()
        val id = alertaDespachada()
        val (e, smsConfirma) = envio(smsConfirma = true)
        smsConfirma.respuesta = { ResultadoEnvio.Aceptado("tw-${it.notificacionId}", entregado = false) }
        e.procesarPendientes()
        val mama = "AND contacto_id IS NOT NULL"
        assertEquals("ENVIADA", estado(id, "RED_APOYO", mama))
        val idProveedor = texto("SELECT proveedor_mensaje_id FROM notificacion WHERE alerta_id = ? AND tipo = 'RED_APOYO' $mama", id)!!

        // No llegó la confirmación dentro del plazo: FALLIDA y se reintenta.
        sql("UPDATE notificacion SET proximo_reintento_en = now() - interval '1 second' WHERE proveedor_mensaje_id = ?", idProveedor)
        assertTrue(e.vencerSinConfirmacion() >= 1)
        assertEquals("FALLIDA", estado(id, "RED_APOYO", mama))
        e.procesarPendientes()
        assertEquals("ENVIADA", estado(id, "RED_APOYO", mama))

        // Llega la confirmación del proveedor.
        assertTrue(e.confirmarEntrega(idProveedor, entregada = true))
        assertEquals("ENTREGADA", estado(id, "RED_APOYO", mama))
        // Una confirmación de fallo que llega después no cambia un aviso ya entregado; un id desconocido se ignora.
        assertTrue(e.confirmarEntrega(idProveedor, entregada = false, "tarde"))
        assertEquals("ENTREGADA", estado(id, "RED_APOYO", mama))
        assertFalse(e.confirmarEntrega("no-existe", entregada = true))
    }

    @Test
    fun `el cambio de estado dice el estado de ese momento aunque se envie despues`() {
        vaciarCola()
        val id = alertaDespachada()
        val caseta = UsuarioToken(AlertasPrueba.CUENTAS.getValue("caseta"), Rol.OPERADOR, null, null)
        val atencion = AtencionServicio(dataSource, ConsultaAlertas(dataSource), EscalamientoServicio(dataSource), enlaces)
        atencion.acusar(caseta, id)
        atencion.cambiarEstado(caseta, id, CambioEstado("EN_ATENCION"))
        atencion.cambiarEstado(caseta, id, CambioEstado("ATENDIDA"))
        val (e, _) = envio()
        e.procesarPendientes()
        val deAna = push.enviados.filter { it.datos["alertaId"] == id.toString() && it.datos["tipo"] == "CAMBIO_ESTADO" }.map { it.texto }
        assertEquals(listOf("Estado: En atención.", "Estado: Atendida."), deAna)
    }

    @Test
    fun `si el token de push ya no sirve se borra y el aviso sale por SMS`() {
        vaciarCola()
        val id = alertaDespachada()
        sql("INSERT INTO notificacion (alerta_id, usuario_id, tipo, canal) VALUES (?, ?, 'ACUSE', 'PUSH')", id, AlertasPrueba.ANA)
        try {
            val (e, _) = envio(esperaBase = 60.seconds)
            push.respuesta = { ResultadoEnvio.Fallo("FCM 404 UNREGISTERED", destinoInvalido = true) }
            e.procesarPendientes()
            assertNull(texto("SELECT fcm_token FROM dispositivo WHERE usuario_id = ?", AlertasPrueba.ANA))
            // Sin espera: en la siguiente vuelta se elige otra vez el canal y ya no hay push.
            e.procesarPendientes()
            assertEquals("ENTREGADA", estado(id, "ACUSE"))
            assertEquals("SMS", texto("SELECT canal::text FROM notificacion WHERE alerta_id = ? AND tipo = 'ACUSE'", id))
            assertTrue(sms.enviados.any { it.destino == "5510000001" && it.texto.contains("recibio tu alerta") })
        } finally {
            sql("UPDATE dispositivo SET fcm_token = 'token-fcm-de-prueba-ana' WHERE id = '00000000-0000-4000-8000-0000000000d1'")
        }
    }

    @Test
    fun `un aviso de hace mas de 24 horas se descarta sin enviarlo`() {
        vaciarCola()
        val id = alertaDespachada()
        sql("UPDATE notificacion SET creada_en = now() - interval '25 hours' WHERE alerta_id = ? AND contacto_id IS NOT NULL", id)
        val (e, _) = envio()
        e.procesarPendientes()
        assertEquals("DESCARTADA", estado(id, "RED_APOYO", "AND contacto_id IS NOT NULL"))
        assertTrue(sms.enviados.none { it.destino == "5520000001" })
        assertEquals(0, numero("SELECT count(*) FROM evento_alerta WHERE alerta_id = ? AND detalle->>'accion' = 'ENTREGA_DESCARTADA'", id))
    }

    @Test
    fun `los avisos para atender una alerta que ya termino se descartan`() {
        vaciarCola()
        val id = alertaDespachada()
        sql("UPDATE alerta SET estado = 'CANCELADA', cancelada_en = now(), motivo_cancelacion = 'FALSA_ALARMA' WHERE id = ?", id)
        dispersion.cancelada(id)
        val (e, _) = envio()
        e.procesarPendientes()
        // El aviso de alerta asignada a la Caseta ya no sirve; el de cancelación sí sale.
        assertEquals("DESCARTADA", estado(id, "ALERTA_PRIORITARIA"))
        assertEquals("La alerta ya terminó", texto("SELECT ultimo_error FROM notificacion WHERE alerta_id = ? AND tipo = 'ALERTA_PRIORITARIA'", id))
        assertEquals(0, numero("SELECT count(*) FROM evento_alerta WHERE alerta_id = ? AND detalle->>'accion' = 'ENTREGA_DESCARTADA'", id))
        assertTrue(estado(id, "CANCELACION")!!.split(",").all { it == "ENTREGADA" })
        // El operador no tiene push registrado: la cancelación le llega por SMS.
        assertTrue(sms.enviados.any { it.destino == "5510000011" && it.texto.contains("cancelada") })
    }

    @Test
    fun `si el proveedor esta saturado se espera sin contar reintento y no se le manda nada mas`() {
        vaciarCola()
        val id = alertaDespachada()
        sms.respuesta = { ResultadoEnvio.Fallo("Twilio 429 20429: Too many requests", esperarSeg = 60) }
        val (e, _) = envio()
        e.procesarPendientes()
        // Solo se intentó el primero; los tres SMS de la alerta siguen PENDIENTE, sin reintentos, para dentro de ~60 s.
        assertEquals(1, sms.enviados.size)
        assertEquals("PENDIENTE,PENDIENTE", estado(id, "RED_APOYO"))
        assertEquals(0, numero("SELECT coalesce(sum(reintentos), 0) FROM notificacion WHERE alerta_id = ?", id))
        assertEquals(
            3,
            numero("SELECT count(*) FROM notificacion WHERE alerta_id = ? AND proximo_reintento_en > now() + interval '50 seconds'", id),
        )
    }
}
