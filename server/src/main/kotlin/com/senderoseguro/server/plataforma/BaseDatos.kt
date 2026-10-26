package com.senderoseguro.server.plataforma

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import org.flywaydb.core.Flyway
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

/** Pool de conexiones a PostgreSQL. Al arrancar aplica las migraciones pendientes con Flyway. */
class BaseDatos(val dataSource: DataSource) {

    fun estaViva(): Boolean =
        runCatching { dataSource.connection.use { it.isValid(2) } }.getOrDefault(false)

    companion object {
        fun iniciar(app: Application): BaseDatos {
            val config = app.environment.config
            val hikari = HikariConfig().apply {
                jdbcUrl = config.property("db.url").getString()
                username = config.property("db.usuario").getString()
                password = config.property("db.password").getString()
                maximumPoolSize = config.property("db.poolMax").getString().toInt()
                poolName = "sendero"
                // En la nube la red puede cortar una conexión sin avisar, y una consulta se queda esperando ~15 min.
                // Se manda un ping a las conexiones sin uso, se renuevan seguido y ninguna lectura espera más de 60 s.
                keepaliveTime = 2.minutes.inWholeMilliseconds
                maxLifetime = 10.minutes.inWholeMilliseconds
                addDataSourceProperty("tcpKeepAlive", "true")
                addDataSourceProperty("connectTimeout", "10")
                addDataSourceProperty("socketTimeout", "60")
            }
            val dataSource = HikariDataSource(hikari)

            // Los datos de prueba solo se cargan en desarrollo (db.datosPrueba = true).
            val ubicaciones = buildList {
                add("classpath:db/migration")
                if (config.property("db.datosPrueba").getString().toBoolean()) add("classpath:db/datos-prueba")
            }
            Flyway.configure()
                .dataSource(dataSource)
                .locations(*ubicaciones.toTypedArray())
                .ignoreMigrationPatterns("*:missing")  // apagar los datos de prueba no rompe la validación
                .load()
                .migrate()

            app.monitor.subscribe(ApplicationStopped) { dataSource.close() }
            return BaseDatos(dataSource)
        }
    }
}
