package com.dounine.jb
import java.net.InetAddress

import akka.NotUsed
import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.cluster.Cluster
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.server.Directives.{complete, concat, extractClientIP, get, path, _}
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.cluster.scaladsl.ClusterHttpManagementRoutes
import akka.management.scaladsl.AkkaManagement
import akka.pattern.AskTimeoutException
import akka.persistence.typed.PersistenceId
import akka.stream.{Materializer, SystemMaterializer}
import com.dounine.jb.behavior.{ChannelWebsocketBehavior, GoldWebsocketBehavior, ReplicatedCache}
import com.dounine.jb.model.BaseSerializer
import com.dounine.jb.router.controller._
import com.dounine.jb.service.AkkaPersistenerService
import com.dounine.jb.tools.json.BaseRouter
import com.dounine.jb.tools.util.{GlobalMap, SingletonService}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}
import com.dounine.jb.behavior.platform.selenium.GoldBehavior
import java.nio.file.Files
import java.nio.file.Paths

import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.MediaTypes
import java.io.File
import java.time.LocalDate

import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import com.dounine.jb.behavior.selenium.ChannelBehavior
import com.dounine.jb.behavior.virtual.UserBehavior

object Bootstrap extends BaseRouter {

  implicit def rejectionHandler =
    RejectionHandler.default
      .mapRejectionResponse {
        case res @ HttpResponse(code, a, ent: HttpEntity.Strict, url) =>
          if (code.intValue() == 404) {
            res.withEntity(
              HttpEntity(
                ContentTypes.`application/json`,
                s"""{"code":"fail","msg": "请求地扯不存在"}"""
              )
            )
          } else {
            val message = ent.data.utf8String.replaceAll("\"", """\"""")
            res.withEntity(
              HttpEntity(
                ContentTypes.`application/json`,
                s"""{"code":"fail","msg": "$message"}"""
              )
            )
          }
        case x => x
      }

  implicit def exceptionHandler: ExceptionHandler = ExceptionHandler {
    case timeout: AskTimeoutException =>
      timeout.printStackTrace()
      fail("网络异常、请重试")
    case e: Exception =>
      e.printStackTrace()
      fail(e.getMessage)
  }

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[NotUsed] =
      ActorSystem(Behaviors.empty, config.getString("name"))
    implicit val materialize: Materializer = SystemMaterializer(
      system
    ).materializer
    implicit val executionContext: ExecutionContextExecutor =
      system.executionContext

//    if (config.getString("model") == "dev") {
//      val persistenerService =
//        SingletonService.instance(classOf[AkkaPersistenerService], system)
//      Await.result(persistenerService.deleteAll(), Duration.Inf)
//    }

    val route: Route = Route.seal(
      concat(
        get {
          path(pm = "") {
            extractClientIP { ip =>
              complete(
                s"""hello world for ${config.getString("name")} ${system.address} ${InetAddress.getLocalHost.getHostAddress}"""
              )
            }
          } ~ path("download" / Segment) { fileName =>
            parameterMap { parameters =>
              val file = new File(parameters("path"))
              val source: Source[ByteString, Unit] = FileIO
                .fromPath(file.toPath)
                .watchTermination() { case (_, result) =>
                }
              complete(
                HttpResponse(
                  200,
                  entity = HttpEntity(
                    ContentTypes.`application/octet-stream`,
                    source
                  )
                )
              )
            }
          } ~
            path("image") {
              parameterMap { parameters =>
                parameters.get("path") match {
                  case Some(path) =>
                    if (path.endsWith("png")) {
                      if (new File(path).exists()) {
                        val byteArray: Array[Byte] =
                          Files.readAllBytes(Paths.get(path))
                        complete(
                          HttpResponse(entity =
                            HttpEntity(
                              ContentType(MediaTypes.`image/png`),
                              byteArray
                            )
                          )
                        )
                      } else {
                        fail("图片不存在")
                      }
                    } else {
                      fail("图片路径错误")
                    }
                  case _ => fail("参数缺失")
                }
              }
            }
        },
        new HealthRouter(system).routeFragment,
        new GoldWebsocketRouter(system).routeFragment,
        new ChannelWebsocketRouter(system).routeFragment
      )
    )
    val cluster: Cluster = Cluster.get(system)
    val allRoutes: Route = ClusterHttpManagementRoutes(cluster)
    val bindingFuture: Future[Http.ServerBinding] = Http(system)
      .newServerAt(interface = "0.0.0.0", port = config.getInt("http.port"))
      .bind(concat(allRoutes, route))

    AkkaManagement(system).start()
    ClusterBootstrap(system).start()

    cluster.registerOnMemberUp(() => {
      system.log.info("******  node started  ******")
      GlobalMap.set(
        "ReplicatedCache",
        system.systemActorOf(ReplicatedCache(), name = "ReplicatedCache")
      )

      val sharding: ClusterSharding = ClusterSharding(system)

      val userBehavior: ActorRef[ShardingEnvelope[UserBehavior.Event]] =
        sharding.init(
          Entity(typeKey = UserBehavior.typeKey)(createBehavior =
            entityContext =>
              UserBehavior(
                PersistenceId.of(
                  UserBehavior.typeKey.name,
                  entityContext.entityId
                ),
                entityContext.shard
              )
          )
            .withStopMessage(UserBehavior.Shutdown)
        )

      sharding.init(
        Entity(typeKey = ChannelWebsocketBehavior.typeKey)(createBehavior =
          entityContext =>
            ChannelWebsocketBehavior(
              PersistenceId
                .of(
                  ChannelWebsocketBehavior.typeKey.name,
                  entityContext.entityId
                ),
              entityContext.shard
            )
        )
          .withStopMessage(ChannelWebsocketBehavior.Shutdown)
      )
      sharding.init(
        Entity(typeKey = ChannelBehavior.typeKey)(createBehavior =
          entityContext =>
            ChannelBehavior(
              PersistenceId
                .of(
                  ChannelBehavior.typeKey.name,
                  entityContext.entityId
                ),
              entityContext.shard
            )
        )
          .withStopMessage(ChannelBehavior.Shutdown)
      )
      sharding.init(
        Entity(typeKey = GoldWebsocketBehavior.typeKey)(createBehavior =
          entityContext =>
            GoldWebsocketBehavior(
              PersistenceId
                .of(GoldWebsocketBehavior.typeKey.name, entityContext.entityId),
              entityContext.shard
            )
        )
          .withStopMessage(GoldWebsocketBehavior.Shutdown)
      )

      sharding.init(
        Entity(typeKey = GoldBehavior.typeKey)(createBehavior =
          entityContext =>
            GoldBehavior(
              PersistenceId
                .of(GoldBehavior.typeKey.name, entityContext.entityId),
              entityContext.shard
            )
        )
          .withStopMessage(GoldBehavior.Shutdown)
      )

      userBehavior.tell(
        ShardingEnvelope(UserBehavior.typeKey.name, UserBehavior.Init)
      )

    })
    CoordinatedShutdown(system).addJvmShutdownHook {
      cluster.down(cluster.selfAddress)
      system.log.info("***********  JVM shutdown hook...  ***********")
    }
    bindingFuture
      .onComplete {
        case Success(binding) =>
          system.log.info(
            s"""***********  wechat-tools-actor online at http://${binding.localAddress.getHostString}:${binding.localAddress.getPort}  ***********"""
          )
        case Failure(exception) =>
          system.log.error(
            s"***********  binding failed with {}  ***********",
            exception
          )
          system.terminate()
      }
  }

}
