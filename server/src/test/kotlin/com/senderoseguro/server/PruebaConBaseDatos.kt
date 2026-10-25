package com.senderoseguro.server

import com.senderoseguro.server.desarrollo.AlertaPrueba
import com.senderoseguro.server.desarrollo.AlertasPrueba
import com.senderoseguro.server.dispersion.DispersionServicio
import com.senderoseguro.server.dispersion.EnlacesServicio
import com.senderoseguro.server.plataforma.consultarUno
import com.senderoseguro.server.plataforma.ejecutar
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/**
 * Base de las pruebas que usan una base real con los datos de prueba.
 * Solo corren si existe la variable SENDERO_TEST_DB_URL, por ejemplo:
 *   SENDERO_TEST_DB_URL=jdbc:postgresql://localhost:5432/sendero_seguro
 * Sin la variable se marcan como omitidas. Apaga el servidor antes de correrlas, para que su
 * temporizador de escalamiento no tome las alertas de las pruebas.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PruebaConBaseDatos {

    protected lateinit var dataSource: HikariDataSource
    protected lateinit var dispersion: DispersionServicio
    protected lateinit var enlaces: EnlacesServicio

    @OptIn(DelicateCoroutinesApi::class)
    @BeforeAll
    fun conectar() {
        val url = System.getenv("SENDERO_TEST_DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Sin SENDERO_TEST_DB_URL: se omiten las pruebas con base de datos")
        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = System.getenv("SENDERO_TEST_DB_USUARIO") ?: "postgres"
            password = System.getenv("SENDERO_TEST_DB_PASSWORD") ?: "sendero"
            maximumPoolSize = 4
        })
        Flyway.configure().dataSource(dataSource)
            .locations("classpath:db/migration", "classpath:db/datos-prueba")
            .load().migrate()
        enlaces = EnlacesServicio(dataSource, secreto = "secreto-de-prueba", horasMax = 24)
        dispersion = DispersionServicio(dataSource, GlobalScope, enlaces)
    }

    @AfterAll
    fun cerrar() {
        if (::dataSource.isInitialized) dataSource.close()
    }

    /** Cada prueba empieza con las unidades libres y sin alertas anteriores pendientes de escalar. */
    @BeforeEach
    fun limpiar() {
        AlertasPrueba.liberarUnidades(dataSource)
        sql("UPDATE alerta SET sin_acuse = true WHERE NOT sin_acuse AND acusada_en IS NULL")
    }

    protected fun alerta(tipo: String = "GENERICA"): UUID = AlertasPrueba.crear(dataSource, AlertaPrueba(tipo = tipo)).first

    protected fun sql(sentencia: String, vararg parametros: Any?) =
        dataSource.connection.use { it.ejecutar(sentencia, *parametros) }

    protected fun texto(consulta: String, vararg parametros: Any?): String? =
        dataSource.connection.use { conn -> conn.consultarUno(consulta, *parametros) { it.getString(1) } }

    protected fun numero(consulta: String, vararg parametros: Any?): Int =
        dataSource.connection.use { conn -> conn.consultarUno(consulta, *parametros) { it.getInt(1) }!! }
}
