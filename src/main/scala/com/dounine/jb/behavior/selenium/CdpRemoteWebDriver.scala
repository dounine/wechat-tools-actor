package com.dounine.jb.behavior.selenium

import org.openqa.selenium.WebDriver
import org.openqa.selenium.{By, WebElement}
import org.openqa.selenium.WebDriver.Options
import java.{util => ju}

import org.openqa.selenium.{By, WebElement}
import java.{util => ju}

import org.openqa.selenium.remote.{
  Command,
  CommandInfo,
  HttpCommandExecutor,
  RemoteWebDriver
}
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
    // getExecuteMethod() //此方法会使用产生 org.openqa.selenium.InvalidArgumentException: invalid argument: Invalid parameters 序列化异常
    //   .execute(
    //     "executeCdpCommand",
    //     Map(
    //       "cmd" -> commandName,
    //       "params" -> parameters
    //     ).asJava
    //   )
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
