package com.dounine.jb.behavior.selenium

import org.openqa.selenium.WebDriver
import org.openqa.selenium.{By, WebElement}
import org.openqa.selenium.WebDriver.Options
import java.{util => ju}

import org.openqa.selenium.{By, WebElement}
import java.{util => ju}

import org.openqa.selenium.remote.{Command, CommandInfo, HttpCommandExecutor, RemoteWebDriver}
import java.util.Objects

import scala.jdk.CollectionConverters._
import java.net.URL

import org.openqa.selenium.Capabilities
import org.openqa.selenium.remote.http.HttpMethod

class CdpRemoteWebDriver(remoteAddress: URL, capabilities: Capabilities)
    extends RemoteWebDriver(
      new HttpCommandExecutor(
        Map(
          "executeCdpCommand" -> new CommandInfo(
            "/session/:sessionId/goog/cdp/execute",
            HttpMethod.POST
          ),
           "launchApp" -> new CommandInfo(
            "/session/:sessionId/chromium/launch_app",
            HttpMethod.POST
          ),
           "getNetworkConditions" -> new CommandInfo(
            "/session/:sessionId/chromium/network_conditions",
            HttpMethod.GET
          ),
          "deleteNetworkConditions" -> new CommandInfo(
            "/session/:sessionId/chromium/network_conditions",
            HttpMethod.DELETE
          ),
           "setNetworkConditions" -> new CommandInfo(
            "/session/:sessionId/chromium/network_conditions",
            HttpMethod.POST
          ),
           "getCastSinks" -> new CommandInfo(
            "/session/:sessionId/goog/cast/get_sinks",
            HttpMethod.GET
          ),
           "selectCastSink" -> new CommandInfo(
            "/session/:sessionId/goog/cast/set_sink_to_use",
            HttpMethod.POST
          ),
           "startCastTabMirroring" -> new CommandInfo(
            "/session/:sessionId/goog/cast/start_tab_mirroring",
            HttpMethod.POST
          ),
           "getCastIssueMessage" -> new CommandInfo(
            "/session/:sessionId/goog/cast/get_issue_message",
            HttpMethod.GET
          ),
           "stopCasting" -> new CommandInfo(
            "/session/:sessionId/goog/cast/stop_casting",
            HttpMethod.POST
          ),
           "setPermission" -> new CommandInfo(
            "/session/:sessionId/permissions",
            HttpMethod.POST
          )
        ).asJava,
        remoteAddress
      ),
      capabilities
    ) {

  def executeCdpCommand(
      commandName: String,
      parameters: Map[String, Object]
  ): Unit = {
    Objects.requireNonNull(commandName, "Command name must be set.");
    Objects.requireNonNull(parameters, "Parameters for command must be set.");
    getCommandExecutor.execute(
      new Command(
        getSessionId,
        "executeCdpCommand",
        Map(
          "cmd" -> commandName,
          "params" -> parameters
        ).asJava
      )
    )
  }
}
