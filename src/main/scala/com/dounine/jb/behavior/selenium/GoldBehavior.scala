package com.dounine.jb.behavior.platform.selenium

import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, PreRestart, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.scaladsl.{
  Effect,
  EventSourcedBehavior,
  RetentionCriteria
}
import akka.persistence.typed._
import com.dounine.jb.model.BaseSerializer

import scala.concurrent.duration._
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.remote.{
  CapabilityType,
  Command,
  RemoteExecuteMethod,
  RemoteWebDriver
}
import com.dounine.jb.tools.json.BaseRouter
import java.net.URL

import org.openqa.selenium.Dimension
import org.openqa.selenium.By
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import java.io.File

import org.openqa.selenium.OutputType
import java.util.UUID

import org.apache.commons.io.FileUtils
import net.coobird.thumbnailator.Thumbnails
import org.openqa.selenium.chrome.ChromeDriver

import scala.jdk.CollectionConverters._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.ContentTypes
import com.dounine.jb.tools.akka.ProxySetting
import akka.http.scaladsl.model.HttpResponse
import akka.util.ByteString
import akka.stream.SystemMaterializer
import akka.stream.Materializer

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import scala.util.Success
import scala.util.Failure
import akka.http.scaladsl.model.headers.RawHeader
import com.dounine.jb.model.GoldModel

import scala.concurrent.Await
import org.openqa.selenium.remote.html5.RemoteWebStorage
import com.dounine.jb.behavior.selenium.CdpRemoteWebDriver

object GoldBehavior extends BaseRouter {

  val typeKey: EntityTypeKey[GoldBehavior.Event] =
    EntityTypeKey[GoldBehavior.Event](s"GoldBehavior")

  sealed trait State extends BaseSerializer

  sealed trait Event extends BaseSerializer

  final case object Error extends Event

  final case object Shutdown extends Event

  final case class Create()(val replyTo: ActorRef[BaseSerializer]) extends Event

  private final case object CheckLogin extends Event

  private final case object GetLoginScan extends Event

  private final case class GetGold(gameName: String) extends Event

  private final case object Timeout extends Event

  final case class ErrorResponse(msg: String)(
      val replyTo: ActorRef[GoldBehavior.Event]
  ) extends Event

  final case object Stop extends Event

  final case class CreateResponse(id: String) extends Event

  final case class DeleteResponse(id: String) extends Event

  final case class ProcessingResponse(id: String, name: String, finish: Boolean)
      extends Event

  final case class LoginScanResponse(id: String, imgUrl: String, timeouts: Int)
      extends Event

  final case class GoldQueryResponse(
      id: String,
      finish: Boolean,
      name: String,
      gold: String,
      expireGold: String
  ) extends Event

  final case object Rep extends Event

  final case class Data(
      actor: ActorRef[BaseSerializer],
      localStorages: Map[String, String],
      cookies: Map[String, String]
  ) extends State

  def apply(
      persistenceId: PersistenceId,
      shard: ActorRef[ClusterSharding.ShardCommand]
  ): Behavior[Event] = Behaviors
    .supervise(
      Behaviors.withTimers((timers: TimerScheduler[Event]) => {
        val goldId: String = persistenceId.id.split("\\|").last
        val timeout: Int = 50
        Behaviors.setup { context: ActorContext[Event] =>
          var chromeDriver = Option.empty[CdpRemoteWebDriver]
          implicit val materializer: Materializer =
            SystemMaterializer(context.system).materializer
          implicit val ec: ExecutionContextExecutor =
            materializer.executionContext
          val http = Http(materializer.system)
          // var chromeDriver = Option.empty[ChromeDriver]
          val createChrome = (actor: ActorRef[BaseSerializer]) => {
            // chromeDriver = Option(new ChromeDriver())
            val chromeOptions: ChromeOptions = new ChromeOptions()
            // chromeOptions.setExperimentalOption(
            //   "excludeSwitches",
            //   Array("enable-automation")
            // )
            // chromeOptions.setExperimentalOption("useAutomationExtension", false)
            // chromeOptions.addEncodedExtensions(
            //   List("excludeSwitches", "enable-automation").asJava
            // )
            //  chromeOptions.addEncodedExtensions(
            //   List("useAutomationExtension", "false").asJava
            // )
            val hubUrl: String = config.getString("model") match {
              case "pro"   => "http://chrome:4444/wd/hub"
              case "stage" => config.getString("selenium.remoteUrl")
              case "jar"   => config.getString("selenium.remoteUrl")
              case _       => config.getString("selenium.remoteUrl")
            }
            chromeDriver =
              Option(new CdpRemoteWebDriver(new URL(hubUrl), chromeOptions))

            val file =
              new File(
                GoldBehavior.getClass.getResource("/stealth.min.js").getPath()
              )
            val params: Map[String, Object] = Map(
              "source" -> FileUtils.readFileToString(file, "utf-8")
            )

            chromeDriver.get.executeCdpCommand(
              "Page.addScriptToEvaluateOnNewDocument",
              params
            )

            chromeDriver.get
              .manage()
              .timeouts()
              .implicitlyWait(
                config.getInt("selenium.implicitlyWait"),
                TimeUnit.SECONDS
              )
            chromeDriver.get
              .manage()
              .window()
              .setSize(
                new Dimension(
                  config.getInt("selenium.size.width"),
                  config.getInt("selenium.size.height")
                )
              )
            chromeDriver.get.get("https://mp.weixin.qq.com")

            TimeUnit.SECONDS.sleep(1)

            val qrcodeFile = chromeDriver.get
              .findElement(By.className("login__type__container__scan__qrcode"))
              .getScreenshotAs(OutputType.FILE)

            // val screenFile: File =
            //   chromeDriver.get.getScreenshotAs(OutputType.FILE)
            // val screenPath: String =
            //   config.getString("qrcodePath") + s"""/${UUID
            //     .randomUUID()
            //     .toString
            //     .replaceAll("-", "")}.png"""
            // FileUtils.copyFile(screenFile, new File(screenPath))
            // context.log.info("screen " + screenPath)

            // val region1: Int = 637
            // val region2: Int = 177
            // val region3: Int = 112
            // val region4: Int = 112
            // val sizeWith: Int = 124
            // val sizeHeight: Int = 124
            // val qrcodePath: String =
            //   config.getString("qrcodePath") + s"""/${UUID
            //     .randomUUID()
            //     .toString
            //     .replaceAll("-", "")}.png"""

            // Thumbnails
            //   .of(screenPath)
            //   .sourceRegion(region1, region2, region3, region4)
            //   .size(sizeWith, sizeHeight)
            //   .keepAspectRatio(true)
            //   .toFile(qrcodePath)

            context.log.info("qrcode " + qrcodeFile.getAbsolutePath())

            actor.tell(
              LoginScanResponse(
                goldId,
                config.getString("domain") + "/image?path=" + qrcodeFile,
                timeout - 10
              )
            )
          }

          val commandHandler: (Data, Event) => Effect[Event, Data] = {
            (data, cmd) =>
              cmd match {
                case Shutdown =>
                  chromeDriver.foreach(chrome => {
                    chrome.quit()
                  })
                  Effect.none.thenStop()
                case e @ Stop =>
                  shard.tell(ClusterSharding.Passivate(context.self))
                  Effect.none
                case e @ Create()   => Effect.persist(e)
                case e @ GetGold(_) => Effect.persist(e)
                case e @ Timeout =>
                  data.actor.tell(DeleteResponse(goldId))
                  shard.tell(ClusterSharding.Passivate(context.self))
                  Effect.none
                case e @ CheckLogin => Effect.persist(e)
                case e @ Error      => throw new Exception("手动错误")
              }
          }

          val getBalance = (cookies: Map[String, String], token: String) => {
            val responseFuture = http
              .singleRequest(
                request = HttpRequest(
                  uri =
                    s"https://mp.weixin.qq.com/wxopen/weapp_publisher_account?action=general_action&cmd=get_game_divice_save&page=1&page_size=10&token=${token}&appid=&spid=",
                  method = HttpMethods.GET,
                  headers = Seq(
                    RawHeader(
                      "Cookie",
                      cookies
                        .map(kv => {
                          s"${kv._1}=${kv._2}"
                        })
                        .mkString(";")
                    )
                  )
                )
              )

            val result = responseFuture.flatMap {
              case HttpResponse(_, _, entity, _) =>
                entity.dataBytes
                  .runFold(ByteString(""))(_ ++ _)
                  .map(_.utf8String)
                  .map(convertTo[GoldModel.GoldBalance](_))
              case msg @ _ =>
                Future.failed(new Exception("hello"))
            }
            Await.result(result, Duration.Inf)
          }

          val getGameName =
            (cookies: Map[String, String], url: String) => {
              val responseFuture = http
                .singleRequest(
                  request = HttpRequest(
                    uri = s"$url",
                    method = HttpMethods.GET,
                    headers = Seq(
                      RawHeader(
                        "Cookie",
                        cookies
                          .map(kv => {
                            s"${kv._1}=${kv._2}"
                          })
                          .mkString(";")
                      ),
                      RawHeader(
                        "referer",
                        url
                      )
                    )
                  )
                )

              val result = responseFuture.flatMap {
                case HttpResponse(_, _, entity, _) =>
                  entity.dataBytes
                    .runFold(ByteString(""))(_ ++ _)
                    .map(_.utf8String)
                    .map(body => {
                      val json = body
                        .split("\n")
                        .filter(_.contains("userInfo:"))
                        .mkString("")
                        .replace("            userInfo: ", """"userInfo":""")
                      "{" + json.trim + "}"
                    })
                    .map(convertTo[GoldModel.GoldResponse](_))
                case msg @ _ =>
                  Future.failed(new Exception("hello"))
              }
              Await.result(result, Duration.Inf).userInfo.nickName
            }

          val eventHandler: (Data, Event) => Data = { (data, cmd) =>
            cmd match {
              case e @ GetGold(gameName) =>
                try {
                  chromeDriver.foreach(chrome => {
                    data.actor.tell(ProcessingResponse(goldId, gameName, false))
                    val pattern = "token=(\\d+)".r
                    val token =
                      pattern.findFirstIn(chrome.getCurrentUrl()) match {
                        case Some(value) => value
                        case None        => throw new Exception("token 找不到、请检查")
                      }
                    val balance =
                      getBalance(data.cookies, token.split("=").last)
                    data.actor.tell(
                      GoldQueryResponse(
                        goldId,
                        true,
                        gameName,
                        (balance.total_balance / 100d).formatted("%.2f"),
                        (balance.total_balance / 100d).formatted("%.2f")
                      )
                    )
                    shard.tell(ClusterSharding.Passivate(context.self))
                  })
                } catch {
                  case e: Throwable =>
                    e.printStackTrace()
                    data.actor.tell(ErrorResponse(e.getMessage())(context.self))
                    data.actor.tell(DeleteResponse(goldId))
                }

                data
              case e @ CheckLogin =>
                var localStorages = Map.empty[String, String]
                var cookies = Map.empty[String, String]
                chromeDriver.foreach(chrome => {
                  if (chrome.getCurrentUrl().contains("wxamp")) {
                    context.log.info("登录成功")
                    // localStorages = chrome
                    //   .getLocalStorage()
                    //   .keySet()
                    //   .asScala
                    //   .map(key => {
                    //     val value = chrome.getLocalStorage().getItem(key)
                    //     (key, value)
                    //   })
                    //   .toMap
                    try {
                      val executeMethod = new RemoteExecuteMethod(chrome)
                      val webStorage = new RemoteWebStorage(executeMethod)
                      localStorages = webStorage.getLocalStorage
                        .keySet()
                        .asScala
                        .map(key => {
                          val value = webStorage.getLocalStorage
                            .getItem(key)
                          (key, value)
                        })
                        .toMap
                    } catch {
                      case e: Throwable =>
                    }
                    cookies = chrome
                      .manage()
                      .getCookies()
                      .asScala
                      .map(cookie => {
                        (cookie.getName(), cookie.getValue())
                      })
                      .toMap
                    val gameName = getGameName(cookies, chrome.getCurrentUrl())
                    context.self.tell(GetGold(gameName))
                  } else {
                    timers.startSingleTimer(CheckLogin, 1.seconds)
                  }
                })
                data.copy(
                  localStorages = localStorages,
                  cookies = cookies
                )
              case e @ Create() =>
                e.replyTo.tell(CreateResponse(goldId))
                createChrome(e.replyTo)
                timers.startSingleTimer(CheckLogin, 1.seconds)
                timers.startSingleTimer(Timeout, timeout.seconds)
                data.copy(actor = e.replyTo)
            }
          }
          EventSourcedBehavior(
            persistenceId = persistenceId,
            emptyState = Data(null, Map.empty, Map.empty),
            commandHandler = commandHandler,
            eventHandler = eventHandler
          ).onPersistFailure(
            SupervisorStrategy
              .restartWithBackoff(
                minBackoff = 1.seconds,
                maxBackoff = 10.seconds,
                randomFactor = 0.2
              )
              .withMaxRestarts(maxRestarts = 3)
              .withResetBackoffAfter(10.seconds)
          ).receiveSignal({

            case (state, RecoveryCompleted) =>
              context.log.debug(
                "************** Recovery Completed with state: {} ***************",
                state
              )
            case (state, RecoveryFailed(err)) =>
              context.log.error(
                "************** Recovery failed with: {} **************",
                err.getMessage
              )
            case (state, SnapshotCompleted(meta)) =>
              context.log.debug(
                "************** Snapshot Completed with state: {},id({},{}) ***************",
                state,
                meta.persistenceId,
                meta.sequenceNr
              )
            case (state, SnapshotFailed(meta, err)) =>
              context.log.error(
                "************** Snapshot failed with: {} **************",
                err.getMessage
              )
            case (_, PreRestart) =>
            case (_, single)     =>
          }).snapshotWhen((state, event, _) => {
            true
          }).withRetention(
            RetentionCriteria
              .snapshotEvery(numberOfEvents = 4, keepNSnapshots = 1)
              .withDeleteEventsOnSnapshot
          )
        }
      })
    )
    .onFailure(
      SupervisorStrategy
        .restartWithBackoff(
          minBackoff = 1.seconds, //错误过1秒钟后重启
          maxBackoff = 10.seconds, //只在10秒内重启
          randomFactor = 0.2
        )
        .withMaxRestarts(maxRestarts = 3) //最多重启3次
        .withResetBackoffAfter(10.seconds)
    )

}
