package com.dounine.jb.behavior.selenium

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, PreRestart, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import akka.http.scaladsl.Http
import akka.persistence.typed._
import akka.persistence.typed.scaladsl.{
  Effect,
  EventSourcedBehavior,
  RetentionCriteria
}
import akka.stream.scaladsl.{
  Broadcast,
  Flow,
  GraphDSL,
  Merge,
  RunnableGraph,
  Sink,
  Source,
  Zip
}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.stream.{
  ClosedShape,
  Materializer,
  OverflowStrategy,
  SystemMaterializer
}
import com.dounine.jb.model.{BaseSerializer, ChannelModel}
import com.dounine.jb.service.{ChannelService, ChannelStream}
import com.dounine.jb.tools.json.BaseRouter
import com.dounine.jb.tools.util.SingletonService
import org.apache.poi.hssf.usermodel.{HSSFRow, HSSFWorkbook}
import org.slf4j.LoggerFactory

import java.io.{File, FileOutputStream}
import java.time.{LocalDate, LocalDateTime}
import scala.collection.parallel._
import scala.collection.parallel.mutable.ParArray
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object ChannelBehavior extends BaseRouter {

  private val logger = LoggerFactory.getLogger(ChannelBehavior.getClass)

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

  final case class QrCodeLoginSuccess() extends Event

  final case class QueryData(day: Int, games: Seq[String]) extends Event

  final case object Mergeing extends Event

  final case object MergeFinish extends Event

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
      actor: Option[ActorRef[BaseSerializer]]
//      uuid: Option[String],
//      wxCode: Option[String],
//      oauthSid: Option[String]
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
            Materializer(context)
          implicit val ec: ExecutionContextExecutor = context.executionContext
          val (clientActor, clientSource) = ActorSource
            .actorRef[QueryData](
              completionMatcher = PartialFunction.empty,
              failureMatcher = PartialFunction.empty,
              bufferSize = 2,
              overflowStrategy = OverflowStrategy.dropNew
            )
            .preMaterialize()(materializer)

          def data(dataStore: DataStore): Behavior[Event] =
            Behaviors.receiveMessage[Event] {
              case Stop => {
                Behaviors.stopped
              }
              case e @ Create() => {
                data(
                  dataStore.copy(
                    actor = Some(e.replyTo)
                  )
                )
              }
              case e @ QueryData(_, _) => {
                logger.info("QueryData -> {}", e)
                clientActor.tell(e)
                Behaviors.same
              }
              case QrCodeQuery() => {

                //              val downloadQrCode: String => Unit = (uuid: String) => {
                //                try {
                //                  val qrcodeFile: File =
                //                    Await.result(channelService.qrcode(uuid), Duration.Inf)
                //                  data.actor.tell(
                //                    QrCodeQueryResponse(
                //                      img = Option(qrcodeFile.getAbsolutePath),
                //                      error = Option.empty
                //                    )
                //                  )
                //                } catch {
                //                  case e: Throwable =>
                //                    data.actor.tell(
                //                      QrCodeQueryResponse(
                //                        img = Option.empty,
                //                        error = Option(e.getMessage)
                //                      )
                //                    )
                //                }
                //              }

                val dataMerge = Flow[(QueryData, ChannelStream.OauthSid)]
                  .flatMapConcat { result =>
                    {
                      val day = result._1.day
                      val games = result._1.games
                      val oauthSid = result._2
                      type APPID = String
                      Source(games)
                        .map(_.split(" "))
                        .flatMapConcat(appid => {
                          dataStore.actor.foreach(
                            _.tell(
                              HandleResponse(
                                game = appid.mkString(" "),
                                process = true,
                                success = true,
                                delay = Option.empty
                              )
                            )
                          )
                          val beginTime: LocalDateTime = LocalDateTime.now()
                          Source
                            .single(appid)
                            .mapAsync(1)(appid => {
                              channelService
                                .getsharepermuserinfo(
                                  appid.head.trim,
                                  oauthSid
                                )
                                .map(data => {
                                  data.data.share_perm_data.perm_list
                                    .map(i =>
                                      Tuple2[ChannelModel.PermItem, APPID](
                                        i,
                                        appid.head
                                      )
                                    )
                                })
                            })
                            .mapConcat(identity)
                            .map(item => {
                              item._1.stat_list.map(i =>
                                (
                                  item._1.copy(
                                    stat_list = Nil
                                  ),
                                  i,
                                  item._2
                                )
                              )
                            })
                            .mapConcat(identity)
                            .throttle(1, 500.milliseconds)
                            .mapAsync(1)(tp3 => {
                              val appid = tp3._3
                              val registerOrActive = tp3._2
                              val item = tp3._1
                              channelService
                                .dataQuery(
                                  appid,
                                  day,
                                  registerOrActive.stat_type,
                                  registerOrActive.data_field_id,
                                  item.channel_name,
                                  item.out_group_id,
                                  item.out_channel_id,
                                  oauthSid
                                )
                                .map(data => {
                                  if (data.errcode != 0) {
                                    throw new Exception(data.errmsg)
                                  }
                                  data.data.sequence_data_list.head.point_list
                                    .map(dataItem => {
                                      ChannelModel.ApiCSVData(
                                        item.channel_name,
                                        appid,
                                        dataItem.label,
                                        registerOrActive.stat_type == 1000091,
                                        dataItem.value
                                          .getOrElse(0)
                                      )
                                    })
                                })
                            })
                            .mapConcat(identity)
                            .recoverWithRetries(
                              1,
                              {
                                case e => {
                                  e.printStackTrace()
                                  dataStore.actor.foreach(
                                    _.tell(
                                      HandleResponse(
                                        game = appid.mkString(" "),
                                        process = false,
                                        success = false,
                                        delay = Option.empty
                                      )
                                    )
                                  )
                                  Source.empty[ChannelModel.ApiCSVData]
                                }
                              }
                            )
                            .watchTermination()((fp, f) => {
                              f.foreach(_ => {
                                dataStore.actor.foreach(
                                  _.tell(
                                    HandleResponse(
                                      game = appid.mkString(" "),
                                      process = false,
                                      success = true,
                                      delay = Option(
                                        java.time.Duration
                                          .between(
                                            beginTime,
                                            LocalDateTime.now()
                                          )
                                          .getSeconds
                                      )
                                    )
                                  )
                                )
                              })
                              fp
                            })
                        })
                        .fold(Seq[ChannelModel.ApiCSVData]())(_ ++ Seq(_))
                        .flatMapConcat(list => {
                          dataStore.actor.foreach(
                            _.tell(
                              Mergeing
                            )
                          )
                          val mergeBeginTime: LocalDateTime =
                            LocalDateTime.now()
                          val days: Seq[String] = (1 to day)
                            .map(i => LocalDate.now().minusDays(i).toString)
                            .sorted
                          val gamesDistinct: Seq[String] =
                            list.map(_.name).distinct
                          Source(games)
                            .map(_.split(" "))
                            .mapAsync(4) { tp2 =>
                              {
                                val appid: String = tp2.head
                                val appName: String = tp2.last
                                Future {
                                  gamesDistinct.flatMap(game => {
                                    days
                                      .flatMap(day => {
                                        val registerOrActive
                                            : Seq[ChannelModel.ApiCSVData] =
                                          list.filter(p =>
                                            p.appid == appid && p.name == game && p.date == day
                                          )
                                        val register
                                            : Option[ChannelModel.ApiCSVData] =
                                          registerOrActive.find(p =>
                                            p.appid == appid && p.name == game && p.register && p.date == day
                                          )
                                        val active
                                            : Option[ChannelModel.ApiCSVData] =
                                          registerOrActive.find(p =>
                                            p.appid == appid && p.name == game && !p.register && p.date == day
                                          )
                                        if (
                                          register.isDefined || active.isDefined
                                        ) {
                                          Seq(
                                            ChannelModel.ApiExportData(
                                              appid = appid,
                                              name = appName,
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
                                }
                              }
                            }
                            .mapConcat(identity)
                            .fold(Seq.empty[ChannelModel.ApiExportData])(
                              _ ++ Seq(_)
                            )
                            .map(mergeData => {
                              dataStore.actor.foreach(
                                _.tell(
                                  MergeFinish
                                )
                              )
                              logger.info(
                                "合并处理耗时：{}秒、总行数为：{}",
                                java.time.Duration
                                  .between(mergeBeginTime, LocalDateTime.now())
                                  .getSeconds,
                                mergeData.size
                              )

                              val fileBeginTime: LocalDateTime =
                                LocalDateTime.now()
                              val book = new HSSFWorkbook()
                              val sheet = book.createSheet("数据")
                              val headerRow = sheet.createRow(0)
                              val titles =
                                Array(
                                  "广告主appid",
                                  "广告主游戏",
                                  "渠道名称",
                                  "数据日期",
                                  "注册数",
                                  "活跃数"
                                )
                              titles.indices.foreach(i => {
                                headerRow.createCell(i).setCellValue(titles(i))
                              })
                              mergeData.toList.indices.foreach(i => {
                                val item: ChannelModel.ApiExportData =
                                  mergeData(i)
                                val row: HSSFRow = sheet.createRow(i + 1)
                                row.createCell(0).setCellValue(item.appid)
                                row.createCell(1).setCellValue(item.name)
                                row.createCell(2).setCellValue(item.ccode)
                                row.createCell(3).setCellValue(item.date)
                                row.createCell(4).setCellValue(item.register)
                                row.createCell(5).setCellValue(item.active)
                              })
                              val downloadFile: File =
                                File.createTempFile(
                                  s"${LocalDate.now()}",
                                  ".xlsx"
                                )
                              val fos = new FileOutputStream(downloadFile)
                              book.write(fos)
                              fos.flush()
                              fos.close()
                              book.close()

                              logger.info(
                                "文件生成耗时：{}秒",
                                java.time.Duration
                                  .between(fileBeginTime, LocalDateTime.now())
                                  .getSeconds
                              )
                              downloadFile
                            })
                        })
                    }
                  }
                  .map(Right.apply)
                  .recover {
                    case e => {
                      e.printStackTrace()
                      Left(e)
                    }
                  }

                val graph = RunnableGraph.fromGraph(GraphDSL.create() {
                  implicit builder =>
                    {
                      import GraphDSL.Implicits._

                      val uuid = builder.add(ChannelStream.uuid(context.system))
                      val broadcast = builder.add(Broadcast[String](2))
                      val downloadQrcode = builder.add(
                        ChannelStream.downloadQrcodeFlow(context.system)
                      )
                      val qrcodeResponseFlow = builder.add(
                        Flow[
                          Either[Throwable, File]
                        ].collect { case Right(qrcode) =>
                          QrCodeQueryResponse(
                            img = Option(qrcode.getAbsolutePath),
                            error = Option.empty
                          )
                        }
                      )
                      val sinkMerge = builder.add(Merge[Event](3))
                      val sinkToClient =
                        builder.add(
                          Sink.foreach[Event](r =>
                            dataStore.actor.foreach(_.tell(r))
                          )
                        )

                      val loginWait = builder.add(
                        Flow[String]
                          .flatMapConcat { uuid =>
                            Source(1 to 60)
                              .map(_ => uuid)
                              .throttle(1, 1.seconds)
                              .via(
                                ChannelStream.qrcodeStatusQuery(context.system)
                              )
                              .collect { case ee @ Right(wxCode) => ee }
                              .take(1)
                              .orElse(
                                Source.single(Left(new Exception("60秒超时未登录")))
                              )
                          }
                      )
                      val wxCodeFlow =
                        builder.add(Flow[Either[Throwable, String]].collect {
                          case Right(wxCode) => wxCode
                        })

                      val oauthSid =
                        builder.add(ChannelStream.oauthSidQuery(context.system))

                      val oauthSidFlow = builder.add(
                        Flow[Either[Throwable, ChannelStream.OauthSid]]
                          .collect { case Right(oauthSid) =>
                            oauthSid
                          }
                      )

                      val oauthSidQueryFlow = builder.add(
                        Flow[ChannelStream.OauthSid]
                          .map(_ => QrCodeLoginSuccess())
                      )

                      val oauthBroadcast =
                        builder.add(Broadcast[ChannelStream.OauthSid](2))

                      val zip =
                        builder.add(Zip[QueryData, ChannelStream.OauthSid]())

                      val dataMergeFlow = builder.add(dataMerge)

                      val mergeDataFlow =
                        builder.add(Flow[Either[Throwable, File]].collect {
                          case Left(value) =>
                            DataDownloadResponse(
                              url = None,
                              error = Some(value.getMessage)
                            )
                          case Right(downloadFile) => {
                            DataDownloadResponse(
                              url = Option(downloadFile.getAbsolutePath),
                              error = Option.empty
                            )
                          }
                        })

                      val sourceForClient = builder.add(clientSource)

                      /*                                                ┌──────────────────────────────────────┐
                       *                                                │                                      │
                       *                                                ▼                                      │
                       *                             ┌─────────┐   ┌─────────┐                                 │
                       *                           ┌▶│ qrcode  │──▶│ client  │───────────┐                     │
                       *                           │ └─────────┘   └─────────┘           │                     │
                       * ┌─────────┐   ┌─────────┐ │                                     │                     │
                       * │  uuid   □──▶□brodcast │─┤                                     │                     │
                       * └─────────┘   └─────────┘ │                                     ▼                     │
                       *                           │ ┌─────────┐      ┌─────────┐   ┌────□────┐   ┌────□────┐  │
                       *                           └▶│ logging │─────▶□ logined │──▶□   zip   □──▶□  data   □──┘
                       *                             └─────────┘      └─────────┘   └─────────┘   └─────────┘
                       */

                      uuid ~> broadcast
                      broadcast.out(
                        0
                      ) ~> downloadQrcode ~> qrcodeResponseFlow ~> sinkMerge.in(
                        0
                      )

                      broadcast.out(
                        1
                      ) ~> loginWait ~> wxCodeFlow ~> oauthSid ~> oauthSidFlow ~> oauthBroadcast

                      oauthBroadcast.out(0) ~> zip.in1

                      oauthBroadcast.out(1) ~> oauthSidQueryFlow ~> sinkMerge
                        .in(1)

                      sourceForClient ~> zip.in0

                      zip.out ~> dataMergeFlow ~> mergeDataFlow ~> sinkMerge.in(
                        2
                      )

                      sinkMerge.out ~> sinkToClient

                      ClosedShape
                    }
                })

                graph.run()

                //                ChannelStream
                //                  .uuid(context.system)
                //                  .via(ChannelStream.downloadQrcodeFlow(context.system))
                //                  .collect {
                //                    case Right(uuid)     => uuid
                //                    case Left(exception) => throw exception
                //                  }
                //                  .via(ChannelStream.qrcodeStatusQuery(context.system))
                //                  .recover { case e =>
                //                  }

                //                dataStore.uuid match {
                //                  case Some(uuid) =>
                //                    //                  downloadQrCode(uuid)
                //                    Behaviors.same
                //                  case None => {
                //                    ChannelStream.uuid(context.system)
                //
                //                    Behaviors.same
                //
                //                    //                  val uuid: ChannelModel.ApiScanLoginResponse =
                //                    //                    Await.result(channelService.uuid(), Duration.Inf)
                //                    ////                  downloadQrCode(uuid.uuid)
                //                    //                  println("创建uuid", uuid.uuid)
                //                    //                  timers.startSingleTimer(CheckLogin, 1.seconds)
                //                    //                  data(dataStore.copy(
                //                    //                    uuid = Option(uuid.uuid)
                //                    //                  ))
                //                    //                }
                //                  }
                Behaviors.same
                //                }
              }
            }

          data(DataStore(None))
        //          val commandHandler: (DataStore, Event) => Effect[Event, DataStore] = {
        //            (data, cmd) =>
        //              cmd match {
        //                case e @ StatusQuerySuccess(_, _) => Effect.persist(e)
        //                case e @ StatusQueryFail(_)       => Effect.persist(e)
        //                case e @ QrCodeQuery()            => Effect.persist(e)
        //                case e @ QueryData(_, _)          => Effect.persist(e)
        //                case Shutdown =>
        //                  Effect.none.thenStop()
        //                case e @ Stop =>
        //                  context.log.info("channel behavior stop")
        //                  shard.tell(ClusterSharding.Passivate(context.self))
        //                  Effect.none
        //                case e @ Create() => Effect.persist(e)
        //                case e @ Timeout =>
        //                  shard.tell(ClusterSharding.Passivate(context.self))
        //                  Effect.none
        //                case e @ CheckLogin => Effect.persist(e)
        //                case e @ Error      => throw new Exception("手动错误")
        //              }
        //          }
        //
        //          val eventHandler: (DataStore, Event) => DataStore = { (data, cmd) =>
        //            val downloadQrCode: String => Unit = (uuid: String) => {
        //              try {
        //                val qrcodeFile: File =
        //                  Await.result(channelService.qrcode(uuid), Duration.Inf)
        //                data.actor.tell(
        //                  QrCodeQueryResponse(
        //                    img = Option(qrcodeFile.getAbsolutePath),
        //                    error = Option.empty
        //                  )
        //                )
        //              } catch {
        //                case e: Throwable =>
        //                  data.actor.tell(
        //                    QrCodeQueryResponse(
        //                      img = Option.empty,
        //                      error = Option(e.getMessage)
        //                    )
        //                  )
        //              }
        //            }
        //            cmd match {
        //              case QueryData(day, gameList) =>
        //                data.oauthSid.foreach(oauth_sid => {
        //                  val gamesBeginTime: LocalDateTime = LocalDateTime.now()
        //                  val list: Seq[ChannelModel.ApiCSVData] = gameList
        //                    .map(_.split(" "))
        //                    .flatMap(appid => {
        //                      val beginTime: LocalDateTime = LocalDateTime.now()
        //                      data.actor.tell(
        //                        HandleResponse(
        //                          game = appid.mkString(" "),
        //                          process = true,
        //                          success = true,
        //                          delay = Option.empty
        //                        )
        //                      )
        //                      val appResult: Seq[ChannelModel.ApiCSVData] =
        //                        try {
        //                          val value: Seq[ChannelModel.ApiCSVData] =
        //                            Await.result(
        //                              channelService
        //                                .getsharepermuserinfo(
        //                                  appid.head.trim,
        //                                  oauth_sid
        //                                )
        //                                .flatMap { userInfo =>
        //                                  val pemListFuture =
        //                                    userInfo.data.share_perm_data.perm_list
        //                                      .map { item =>
        //                                        val futureList: Seq[
        //                                          Future[List[ChannelModel.ApiCSVData]]
        //                                        ] = item.stat_list.map { registerOrActive =>
        //                                          channelService
        //                                            .dataQuery(
        //                                              appid.head,
        //                                              day,
        //                                              registerOrActive.stat_type,
        //                                              registerOrActive.data_field_id,
        //                                              item.channel_name,
        //                                              item.out_group_id,
        //                                              item.out_channel_id,
        //                                              oauth_sid
        //                                            )
        //                                            .map(data => {
        //                                              data.data.sequence_data_list.head.point_list
        //                                                .map(dataItem => {
        //                                                  ChannelModel.ApiCSVData(
        //                                                    item.channel_name,
        //                                                    appid.head,
        //                                                    dataItem.label,
        //                                                    registerOrActive.stat_type == 1000091,
        //                                                    dataItem.value
        //                                                      .getOrElse(0)
        //                                                  )
        //                                                })
        //                                            })
        //                                        }
        //                                        Future.sequence(futureList)
        //                                      }
        //                                  Future
        //                                    .sequence(pemListFuture)
        //                                    .map(_.flatten.flatten)
        //                                },
        //                              Duration.Inf
        //                            )
        //                          data.actor.tell(
        //                            HandleResponse(
        //                              game = appid.mkString(" "),
        //                              process = false,
        //                              success = true,
        //                              delay = Option(
        //                                java.time.Duration
        //                                  .between(beginTime, LocalDateTime.now())
        //                                  .getSeconds
        //                              )
        //                            )
        //                          )
        //                          value
        //                        } catch {
        //                          case e: Throwable =>
        //                            e.printStackTrace()
        //                            data.actor.tell(
        //                              HandleResponse(
        //                                game = appid.mkString(" "),
        //                                process = false,
        //                                success = false,
        //                                delay = Option.empty
        //                              )
        //                            )
        //                            Seq.empty
        //                        }
        //
        //                      appResult
        //                    })
        //
        //                  context.log.info(
        //                    "数据查询耗时：{}秒",
        //                    java.time.Duration
        //                      .between(gamesBeginTime, LocalDateTime.now())
        //                      .getSeconds
        //                  )
        //
        ////                  FileUtils.writeStringToFile(
        ////                    new File("/tmp/gameList.txt"),
        ////                    toJsonString(gameList),
        ////                    "utf-8"
        ////                  )
        ////
        ////                  FileUtils.writeStringToFile(
        ////                    new File("/tmp/list.txt"),
        ////                    toJsonString(list),
        ////                    "utf-8"
        ////                  )
        //
        //                  data.actor.tell(
        //                    Mergeing
        //                  )
        //
        //                  val mergeBeginTime: LocalDateTime = LocalDateTime.now()
        //                  val days: Seq[String] = (1 to day)
        //                    .map(i => LocalDate.now().minusDays(i).toString)
        //                    .sorted
        //                  val games: Seq[String] = list.map(_.name).distinct
        //                  val mergeData: ParArray[ChannelModel.ApiExportData] =
        //                    gameList.toParArray
        //                      .map(_.split(" "))
        //                      .flatMap(tp2 => {
        //                        val appid: String = tp2.head
        //                        val appName: String = tp2.last
        //                        games.flatMap(game => {
        //                          days
        //                            .flatMap(day => {
        //                              val registerOrActive
        //                                  : Seq[ChannelModel.ApiCSVData] =
        //                                list.filter(p =>
        //                                  p.appid == appid && p.name == game && p.date == day
        //                                )
        //                              val register: Option[ChannelModel.ApiCSVData] =
        //                                registerOrActive.find(p =>
        //                                  p.appid == appid && p.name == game && p.register && p.date == day
        //                                )
        //                              val active: Option[ChannelModel.ApiCSVData] =
        //                                registerOrActive.find(p =>
        //                                  p.appid == appid && p.name == game && !p.register && p.date == day
        //                                )
        //                              if (register.isDefined || active.isDefined) {
        //                                Seq(
        //                                  ChannelModel.ApiExportData(
        //                                    appid = appid,
        //                                    name = appName,
        //                                    ccode = game,
        //                                    date = day,
        //                                    register = register
        //                                      .getOrElse(
        //                                        ChannelModel.ApiCSVData(
        //                                          "",
        //                                          "",
        //                                          "",
        //                                          register = false,
        //                                          0
        //                                        )
        //                                      )
        //                                      .value,
        //                                    active = active
        //                                      .getOrElse(
        //                                        ChannelModel.ApiCSVData(
        //                                          "",
        //                                          "",
        //                                          "",
        //                                          register = false,
        //                                          0
        //                                        )
        //                                      )
        //                                      .value
        //                                  )
        //                                )
        //                              } else Seq.empty
        //                            })
        //                        })
        //                      })
        //
        //                  data.actor.tell(
        //                    MergeFinish
        //                  )
        //
        //                  context.log.info(
        //                    "合并处理耗时：{}秒、总行数为：{}",
        //                    java.time.Duration
        //                      .between(mergeBeginTime, LocalDateTime.now())
        //                      .getSeconds,
        //                    mergeData.size
        //                  )
        //
        //                  val fileBeginTime: LocalDateTime = LocalDateTime.now()
        //                  val book = new HSSFWorkbook()
        //                  val sheet = book.createSheet("数据")
        //                  val headerRow = sheet.createRow(0)
        //                  val titles =
        //                    Array("广告主appid", "广告主游戏", "渠道名称", "数据日期", "注册数", "活跃数")
        //                  titles.indices.foreach(i => {
        //                    headerRow.createCell(i).setCellValue(titles(i))
        //                  })
        //
        //                  mergeData.toList.indices.foreach(i => {
        //                    val item: ChannelModel.ApiExportData = mergeData(i)
        //                    val row: HSSFRow = sheet.createRow(i + 1)
        //                    row.createCell(0).setCellValue(item.appid)
        //                    row.createCell(1).setCellValue(item.name)
        //                    row.createCell(2).setCellValue(item.ccode)
        //                    row.createCell(3).setCellValue(item.date)
        //                    row.createCell(4).setCellValue(item.register)
        //                    row.createCell(5).setCellValue(item.active)
        //                  })
        //                  val downloadFile: File =
        //                    File.createTempFile(s"${LocalDate.now()}", ".xlsx")
        //                  val fos = new FileOutputStream(downloadFile)
        //                  book.write(fos)
        //                  fos.flush()
        //                  fos.close()
        //                  book.close()
        //                  context.log.info(
        //                    "文件生成耗时：{}秒",
        //                    java.time.Duration
        //                      .between(fileBeginTime, LocalDateTime.now())
        //                      .getSeconds
        //                  )
        //                  data.actor.tell(
        //                    DataDownloadResponse(
        //                      url = Option(downloadFile.getAbsolutePath),
        //                      error = Option.empty
        //                    )
        //                  )
        //                })
        //                data
        //              case QrCodeQuery() =>
        //                data.uuid match {
        //                  case Some(uuid) =>
        //                    downloadQrCode(uuid)
        //                    data
        //                  case None => {
        //                    data
        //                  }
        ////                    val uuid: ChannelModel.ApiScanLoginResponse =
        ////                      Await.result(channelService.uuid(), Duration.Inf)
        ////                    downloadQrCode(uuid.uuid)
        ////                    println("创建uuid", uuid.uuid)
        ////                    timers.startSingleTimer(CheckLogin, 1.seconds)
        ////                    data.copy(
        ////                      uuid = Option(uuid.uuid)
        ////                    )
        //                }
        //              case StatusQuerySuccess(success, wxCode) =>
        //                context.log.info("查询状态 {} {}", success, wxCode)
        //                if (success) {
        //                  val oauth_sid: ChannelModel.ApiScanLoginAuthResponse =
        //                    Await.result(
        //                      channelService.loginauth2(
        //                        wxCode.split("/").last
        //                      ),
        //                      Duration.Inf
        //                    )
        //                  //用户已经扫码登录,wx_code已经获取到、可以查询数据
        //                  data.actor.tell(QrCodeLoginSuccess())
        //                  data.copy(
        //                    oauthSid = Option(oauth_sid.data.oauth_sid)
        //                  )
        //                } else {
        //                  timers.startSingleTimer(CheckLogin, 1.seconds)
        //                  data
        //                }
        //              case StatusQueryFail(msg) =>
        //                context.log.error("登录状态查询异常 {}", msg)
        //                timers.startSingleTimer(CheckLogin, 1.seconds)
        //                data
        //              case e @ CheckLogin =>
        //                data.uuid.foreach(uuid => {
        //                  context.pipeToSelf {
        //                    channelService.statusQuery(uuid)
        //                  } {
        //                    case Failure(exception) =>
        //                      StatusQueryFail(exception.getMessage)
        //                    case Success(value) =>
        //                      StatusQuerySuccess(value.status, value.wx_code)
        //                  }
        //                })
        //                data
        //              case e @ Create() =>
        //                data.copy(actor = e.replyTo)
        //            }
        //          }
        //          EventSourcedBehavior(
        //            persistenceId = persistenceId,
        //            emptyState = DataStore(null, Option.empty, Option.empty),
        //            commandHandler = commandHandler,
        //            eventHandler = eventHandler
        //          ).onPersistFailure(
        //            SupervisorStrategy
        //              .restartWithBackoff(
        //                minBackoff = 1.seconds,
        //                maxBackoff = 10.seconds,
        //                randomFactor = 0.2
        //              )
        //              .withMaxRestarts(maxRestarts = 3)
        //              .withResetBackoffAfter(10.seconds)
        //          ).receiveSignal({
        //
        //            case (state, RecoveryCompleted) =>
        //              context.log.debug(
        //                "************** Recovery Completed with state: {} ***************",
        //                state
        //              )
        //            case (state, RecoveryFailed(err)) =>
        //              context.log.error(
        //                "************** Recovery failed with: {} **************",
        //                err.getMessage
        //              )
        //            case (state, SnapshotCompleted(meta)) =>
        //              context.log.debug(
        //                "************** Snapshot Completed with state: {},id({},{}) ***************",
        //                state,
        //                meta.persistenceId,
        //                meta.sequenceNr
        //              )
        //            case (state, SnapshotFailed(meta, err)) =>
        //              context.log.error(
        //                "************** Snapshot failed with: {} **************",
        //                err.getMessage
        //              )
        //            case (_, PreRestart) =>
        //            case (_, single)     =>
        //          }).snapshotWhen((state, event, _) => {
        //            true
        //          }).withRetention(
        //            RetentionCriteria
        //              .snapshotEvery(numberOfEvents = 4, keepNSnapshots = 1)
        //              .withDeleteEventsOnSnapshot
        //          )
//        }
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
