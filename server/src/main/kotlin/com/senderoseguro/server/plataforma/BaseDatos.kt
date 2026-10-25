package com.senderoseguro.server.plataforma

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import org.flywaydb.core.Flyway
import javax.sql.DataSource

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
            }
            val dataSource = HikariDataSource(hikari)

            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            app.monitor.subscribe(ApplicationStopped) { dataSource.close() }
            return BaseDatos(dataSource)
        }
    }
}
