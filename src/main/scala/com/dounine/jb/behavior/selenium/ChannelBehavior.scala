package com.dounine.jb.behavior.selenium

import java.io.{File, FileOutputStream}
import java.net.URL
import java.time.{LocalDate, LocalDateTime}
import java.util.concurrent.TimeUnit

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, PreRestart, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.model.headers.RawHeader
import akka.persistence.typed._
import akka.persistence.typed.scaladsl.{
  Effect,
  EventSourcedBehavior,
  RetentionCriteria
}
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.ByteString
import com.dounine.jb.model.{BaseSerializer, ChannelModel, GoldModel}
import com.dounine.jb.service.ChannelService
import com.dounine.jb.tools.json.BaseRouter
import com.dounine.jb.tools.util.SingletonService
import org.apache.poi.hssf.usermodel.{HSSFRow, HSSFWorkbook}
import org.openqa.selenium.{By, Dimension, OutputType}
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.remote.RemoteExecuteMethod
import org.openqa.selenium.remote.html5.RemoteWebStorage

import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

object ChannelBehavior extends BaseRouter {

  val typeKey: EntityTypeKey[ChannelBehavior.Event] =
    EntityTypeKey[ChannelBehavior.Event](s"ChannelBehavior")

  sealed trait State extends BaseSerializer

  sealed trait Event extends BaseSerializer

  final case object Error extends Event

  final case object Shutdown extends Event

  final case class Create()(val replyTo: ActorRef[BaseSerializer]) extends Event

  final case class UUIDCreate(uuid: String) extends Event

  final case class QrCodeQuery() extends Event

  final case class QrCodeQueryResponse(
      img: Option[String],
      error: Option[String]
  ) extends Event

  final case class QrCodeRefresh() extends Event

  final case class QrCodeLoginSuccess() extends Event

  final case class QueryData(day: Int, games: Seq[String]) extends Event

  final case class HandleResponse(
      game: String,
      process: Boolean,
      success: Boolean,
      delay: Option[Long]
  ) extends Event

  final case class DataDownloadResponse(
      url: Option[String],
      error: Option[String]
  ) extends Event

  private final case object CheckLogin extends Event

  private final case class StatusQuerySuccess(
      success: Boolean,
      wxCode: String
  ) extends Event

  private final case class StatusQueryFail(msg: String) extends Event

  private final case object Timeout extends Event

  final case class ErrorResponse(msg: String)(
      val replyTo: ActorRef[ChannelBehavior.Event]
  ) extends Event

  final case object Stop extends Event

  final case class DataStore(
      actor: ActorRef[BaseSerializer],
      uuid: Option[String],
//      wxCode: Option[String],
      oauthSid: Option[String]
  ) extends State
  def apply(
      persistenceId: PersistenceId,
      shard: ActorRef[ClusterSharding.ShardCommand]
  ): Behavior[Event] = Behaviors
    .supervise(
      Behaviors.withTimers((timers: TimerScheduler[Event]) => {
        val timeout: Int = 50
        Behaviors.setup { context: ActorContext[Event] =>
          context.log.info("channel behavior create")
          val channelService: ChannelService =
            SingletonService.instance(classOf[ChannelService], context.system)
          implicit val materializer: Materializer =
            SystemMaterializer(context.system).materializer
          implicit val ec: ExecutionContextExecutor =
            materializer.executionContext
          val http = Http(materializer.system)

          val commandHandler: (DataStore, Event) => Effect[Event, DataStore] = {
            (data, cmd) =>
              cmd match {
                case e @ StatusQuerySuccess(_, _) => Effect.persist(e)
                case e @ StatusQueryFail(_)       => Effect.persist(e)
                case e @ QrCodeQuery()            => Effect.persist(e)
                case e @ QrCodeRefresh()          => Effect.persist(e)
                case e @ QueryData(_, _)          => Effect.persist(e)
                case Shutdown =>
                  Effect.none.thenStop()
                case e @ Stop =>
                  context.log.info("channel behavior stop")
                  shard.tell(ClusterSharding.Passivate(context.self))
                  Effect.none
                case e @ Create() => Effect.persist(e)
                case e @ Timeout =>
                  shard.tell(ClusterSharding.Passivate(context.self))
                  Effect.none
                case e @ CheckLogin => Effect.persist(e)
                case e @ Error      => throw new Exception("手动错误")
              }
          }

          val eventHandler: (DataStore, Event) => DataStore = { (data, cmd) =>
            val downloadQrCode: String => Unit = (uuid: String) => {
              try {
                val qrcodeFile: File =
                  Await.result(channelService.qrcode(uuid), Duration.Inf)
                data.actor.tell(
                  QrCodeQueryResponse(
                    img = Option(qrcodeFile.getAbsolutePath),
                    error = Option.empty
                  )
                )
              } catch {
                case e: Throwable =>
                  data.actor.tell(
                    QrCodeQueryResponse(
                      img = Option.empty,
                      error = Option(e.getMessage)
                    )
                  )
              }
            }
            cmd match {
              case QrCodeRefresh() =>
                val uuid: String =
                  Await.result(channelService.uuid(), Duration.Inf).uuid
                downloadQrCode(uuid)
                data.copy(
                  uuid = Option(uuid)
                )
              case QueryData(day, gameList) =>
                data.oauthSid.foreach(oauth_sid => {
                  val list: Seq[ChannelModel.ApiCSVData] = gameList
                    .map(_.split(" "))
                    .flatMap(appid => {
                      val beginTime: LocalDateTime = LocalDateTime.now()
                      data.actor.tell(
                        HandleResponse(
                          game = appid.mkString(" "),
                          process = true,
                          success = true,
                          delay = Option.empty
                        )
                      )
                      val appResult: Seq[ChannelModel.ApiCSVData] =
                        try {
                          val value: Seq[ChannelModel.ApiCSVData] =
                            Await.result(
                              channelService
                                .getsharepermuserinfo(
                                  appid.head.trim,
                                  oauth_sid
                                )
                                .flatMap { userInfo =>
                                  val pemListFuture =
                                    userInfo.data.share_perm_data.perm_list
                                      .map { item =>
                                        val futureList: Seq[
                                          Future[List[ChannelModel.ApiCSVData]]
                                        ] = item.stat_list.map { registerOrActive =>
                                          channelService
                                            .dataQuery(
                                              appid.head,
                                              day,
                                              registerOrActive.stat_type,
                                              registerOrActive.data_field_id,
                                              item.channel_name,
                                              item.out_group_id,
                                              item.out_channel_id,
                                              oauth_sid
                                            )
                                            .map(data => {
                                              data.data.sequence_data_list.head.point_list
                                                .map(dataItem => {
                                                  ChannelModel.ApiCSVData(
                                                    item.channel_name,
                                                    appid.head,
                                                    dataItem.label,
                                                    registerOrActive.stat_type == 1000091,
                                                    dataItem.value
                                                      .getOrElse(0)
                                                  )
                                                })
                                            })
                                        }
                                        Future.sequence(futureList)
                                      }
                                  Future
                                    .sequence(pemListFuture)
                                    .map(_.flatten.flatten)
                                },
                              Duration.Inf
                            )
                          data.actor.tell(
                            HandleResponse(
                              game = appid.mkString(" "),
                              process = false,
                              success = true,
                              delay = Option(
                                java.time.Duration
                                  .between(beginTime, LocalDateTime.now())
                                  .getSeconds
                              )
                            )
                          )
                          value
                        } catch {
                          case e: Throwable =>
                            e.printStackTrace()
                            data.actor.tell(
                              HandleResponse(
                                game = appid.mkString(" "),
                                process = false,
                                success = false,
                                delay = Option.empty
                              )
                            )
                            Seq.empty
                        }

                      appResult
                    })
                  val days: Seq[String] = (1 to day)
                    .map(i => LocalDate.now().minusDays(i).toString)
                    .sorted
                  val games: Seq[String] = list.map(_.name).distinct
                  val mergeData: Seq[ChannelModel.ApiExportData] = gameList
                    .map(_.split(" "))
                    .flatMap(appid => {
                      games.flatMap(game => {
                        days
                          .flatMap(day => {
                            val register: Option[ChannelModel.ApiCSVData] =
                              list.find(p =>
                                p.appid == appid.head && p.name == game && p.register && p.date == day
                              )
                            val active: Option[ChannelModel.ApiCSVData] =
                              list.find(p =>
                                p.appid == appid.head && p.name == game && !p.register && p.date == day
                              )
                            if (register.isDefined || active.isDefined) {
                              Seq(
                                ChannelModel.ApiExportData(
                                  appid = appid.head,
                                  name = appid.last,
                                  ccode = game,
                                  date = day,
                                  register = register
                                    .getOrElse(
                                      ChannelModel.ApiCSVData(
                                        "",
                                        "",
                                        "",
                                        register = false,
                                        0
                                      )
                                    )
                                    .value,
                                  active = active
                                    .getOrElse(
                                      ChannelModel.ApiCSVData(
                                        "",
                                        "",
                                        "",
                                        register = false,
                                        0
                                      )
                                    )
                                    .value
                                )
                              )
                            } else Seq.empty
                          })
                      })
                    })

                  val book = new HSSFWorkbook()
                  val sheet = book.createSheet("数据")
                  val headerRow = sheet.createRow(0)
                  val titles =
                    Array("广告主appid", "广告主游戏", "渠道名称", "数据日期", "注册数", "活跃数")
                  titles.indices.foreach(i => {
                    headerRow.createCell(i).setCellValue(titles(i))
                  })
                  mergeData.indices.foreach(i => {
                    val item: ChannelModel.ApiExportData = mergeData(i)
                    val row: HSSFRow = sheet.createRow(i + 1)
                    row.createCell(0).setCellValue(item.appid)
                    row.createCell(1).setCellValue(item.name)
                    row.createCell(2).setCellValue(item.ccode)
                    row.createCell(3).setCellValue(item.date)
                    row.createCell(4).setCellValue(item.register)
                    row.createCell(5).setCellValue(item.active)
                  })
                  val downloadFile: File =
                    File.createTempFile(s"${LocalDate.now()}", ".xlsx")
                  val fos = new FileOutputStream(downloadFile)
                  book.write(fos)
                  fos.flush()
                  fos.close()
                  book.close()
                  data.actor.tell(
                    DataDownloadResponse(
                      url = Option(downloadFile.getAbsolutePath),
                      error = Option.empty
                    )
                  )
                })
                data
              case QrCodeQuery() =>
                data.uuid match {
                  case Some(uuid) =>
                    downloadQrCode(uuid)
                    data
                  case None =>
                    val uuid: ChannelModel.ApiScanLoginResponse =
                      Await.result(channelService.uuid(), Duration.Inf)
                    downloadQrCode(uuid.uuid)
                    println("创建uuid", uuid.uuid)
                    timers.startSingleTimer(CheckLogin, 1.seconds)
                    data.copy(
                      uuid = Option(uuid.uuid)
                    )
                }
              case StatusQuerySuccess(success, wxCode) =>
                context.log.info("查询状态 {} {}", success, wxCode)
                if (success) {
                  val oauth_sid: ChannelModel.ApiScanLoginAuthResponse =
                    Await.result(
                      channelService.loginauth2(
                        wxCode.split("/").last
                      ),
                      Duration.Inf
                    )
                  //用户已经扫码登录,wx_code已经获取到、可以查询数据
                  data.actor.tell(QrCodeLoginSuccess())
                  data.copy(
                    oauthSid = Option(oauth_sid.data.oauth_sid)
                  )
                } else {
                  timers.startSingleTimer(CheckLogin, 1.seconds)
                  data
                }
              case StatusQueryFail(msg) =>
                context.log.error("登录状态查询异常 {}", msg)
                timers.startSingleTimer(CheckLogin, 1.seconds)
                data
              case e @ CheckLogin =>
                data.uuid.foreach(uuid => {
                  context.pipeToSelf {
                    channelService.statusQuery(uuid)
                  } {
                    case Failure(exception) =>
                      StatusQueryFail(exception.getMessage)
                    case Success(value) =>
                      StatusQuerySuccess(value.status, value.wx_code)
                  }
                })
                data
              case e @ Create() =>
                data.copy(actor = e.replyTo)
            }
          }
          EventSourcedBehavior(
            persistenceId = persistenceId,
            emptyState = DataStore(null, Option.empty, Option.empty),
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
