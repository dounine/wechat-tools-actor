package com.dounine.jb

import org.openqa.selenium.chrome.ChromeDriver
import java.util.concurrent.TimeUnit
import org.openqa.selenium.By
import org.openqa.selenium.OutputType
import org.apache.commons.io.FileUtils
import java.io.File
import scala.jdk.CollectionConverters._

object SeleniumTest {

  def main(args: Array[String]): Unit = {
    System.setProperty("webdriver.chrome.bin", "/usr/local/bin/chromedriver")
    val chrome = new ChromeDriver()

    val file = new File("/tmp/stealth.min.js")
    val map: Map[String, Object] = Map(
      "source" -> FileUtils.readFileToString(file, "utf-8")
    )
    chrome.executeCdpCommand(
      "Page.addScriptToEvaluateOnNewDocument",
      map.asJava
    )

    chrome.get("https://mp.weixin.qq.com/")
    TimeUnit.SECONDS.sleep(60)
    val qrcodeFile = chrome
      .findElement(By.className("login__type__container__scan__qrcode"))
      .getScreenshotAs(OutputType.FILE)

    // FileUtils.copyFile(qrcodeFile, new File("/tmp/hello.png"))
    // chrome.quit()
  }
}
