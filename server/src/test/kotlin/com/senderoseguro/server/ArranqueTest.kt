package com.senderoseguro.server

import com.senderoseguro.server.plataforma.problemasDeArranque
import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Revisión de las llaves al arrancar: en la nube no se aceptan las del repo. */
class ArranqueTest {

    private fun config(jwt: String, enlace: String) = MapApplicationConfig("jwt.secreto" to jwt, "enlace.secreto" to enlace)
    private val propia = "x".repeat(40)

    @Test
    fun `en modo de prueba no exige nada`() {
        val repo = config("solo-para-desarrollo-cambiar-en-la-nube", "solo-para-desarrollo-enlaces")
        assertTrue(problemasDeArranque(repo, modoDesarrollo = true).isEmpty())
    }

    @Test
    fun `en la nube rechaza las llaves del repo y las cortas, y acepta las propias`() {
        val repo = problemasDeArranque(config("solo-para-desarrollo-cambiar-en-la-nube", "solo-para-desarrollo-enlaces"), false)
        assertEquals(2, repo.size)
        assertTrue(repo[0].contains("JWT_SECRETO") && repo[1].contains("ENLACE_SECRETO"))
        assertTrue(problemasDeArranque(config(propia, "corta"), false).single().contains("ENLACE_SECRETO"))
        assertTrue(problemasDeArranque(config(propia, propia), false).isEmpty())
    }
}
