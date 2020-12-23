package com.dounine.jb.tools.akka

import java.net.InetSocketAddress
import java.time

import akka.actor.ActorSystem
import akka.http.scaladsl.ClientTransport
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._

object ProxySetting {

  private val config: Config = ConfigFactory.load().getConfig("jb")

  def proxy(system: ActorSystem): ConnectionPoolSettings = {
    val enableProxy: Boolean = config.getBoolean("proxy.enable")
    val proxyHost: String = config.getString("proxy.host")
    val proxyPort: Int = config.getInt("proxy.port")
    val timeout: time.Duration = config.getDuration("proxy.timeout")

    val httpsProxyTransport: ClientTransport = ClientTransport
      .httpsProxy(InetSocketAddress.createUnresolved(proxyHost, proxyPort))

    val conn: ConnectionPoolSettings = ConnectionPoolSettings(system)
      .withConnectionSettings(ClientConnectionSettings(system)
        .withConnectingTimeout(timeout.getSeconds.seconds)
      )
    if (enableProxy) {
      conn.withTransport(httpsProxyTransport)
    } else conn
  }


}
