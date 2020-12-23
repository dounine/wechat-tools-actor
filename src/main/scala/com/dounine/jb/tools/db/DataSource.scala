package com.dounine.jb.tools.db

import java.util.concurrent._

import com.typesafe.config.{Config, ConfigFactory}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc.MySQLProfile
import slick.jdbc.MySQLProfile.api._
import slick.util.AsyncExecutor
import com.dounine.jb.tools.json.BaseRouter

object DataSource extends BaseRouter{
  private val logger: Logger = LoggerFactory.getLogger(DataSource.getClass)
  private val hikariConfig: HikariConfig = {
    val c = new HikariConfig()
//    c.setPoolName("HikariCP")
    c.setDriverClassName(config.getString("db.driver"))
    c.setJdbcUrl(config.getString("db.url"))
    c.addDataSourceProperty("maxThreads", "30")
    c.setUsername(config.getString("db.username"))
    c.setPassword(config.getString("db.password"))
    c.setMinimumIdle(config.getInt("db.hikaricp.minimumIdle"))
    c.setMaximumPoolSize(config.getInt("db.hikaricp.maximumPoolSize"))
    c.setMaxLifetime(config.getLong("db.hikaricp.maxLifetime"))
    c.setConnectionTimeout(TimeUnit.SECONDS.toMillis(config.getInt("db.hikaricp.connectionTimeout")))
    c.setConnectionInitSql(config.getString("db.hikaricp.connectionInitSql"))
    c.setIdleTimeout(TimeUnit.SECONDS.toMillis(config.getInt("db.hikaricp.idleTimeout")))
    c.setAutoCommit(true)
    c
  }
  val poolSize: Int = config.getInt("db.hikaricp.maximumPoolSize")
  val dataSource: HikariDataSource = new HikariDataSource(hikariConfig)

  val db: MySQLProfile.backend.DatabaseDef = {
    logger.info("HikariDataSource init")
    val asyncExecutor: AsyncExecutor = AsyncExecutor(
      name = "CorePostgresDriver.AsyncExecutor",
      minThreads = poolSize,
      maxThreads = poolSize,
      maxConnections = poolSize,
      queueSize = 100 // the default used by AsyncExecutor.default()
    )
    Database.forDataSource(dataSource, Some(poolSize), asyncExecutor)
  }
}

