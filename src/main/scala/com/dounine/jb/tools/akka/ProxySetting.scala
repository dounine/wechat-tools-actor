package com.dounine.jb.tools.akka

import java.net.InetSocketAddress
import java.time

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.ClientTransport
import akka.http.scaladsl.settings.{
  ClientConnectionSettings,
  ConnectionPoolSettings
}
import akka.stream.SystemMaterializer
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._

object ProxySetting {

  private val config: Config = ConfigFactory.load().getConfig("jb")

  def proxy(system: ActorSystem[_]): ConnectionPoolSettings = {
    val enableProxy: Boolean = config.getBoolean("proxy.enable")
    val proxyHost: String = config.getString("proxy.host")
    val proxyPort: Int = config.getInt("proxy.port")
    val timeout: time.Duration = config.getDuration("proxy.timeout")
    val materializer = SystemMaterializer(system).materializer

    val conn = ConnectionPoolSettings(materializer.system)
      .withConnectionSettings(
        ClientConnectionSettings(materializer.system)
          .withConnectingTimeout(timeout.toMillis.milliseconds)
          .withIdleTimeout(3.seconds)
      )
    if (enableProxy) {
      val httpsProxyTransport = ClientTransport
        .httpsProxy(
          InetSocketAddress.createUnresolved(
            proxyHost,
            proxyPort
          )
        )
      conn.withTransport(httpsProxyTransport)
    } else conn
  }

}
