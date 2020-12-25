package com.dounine.jb.behavior

import java.time.LocalDate

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PreRestart, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{
  ClusterSharding,
  Entity,
  EntityRef,
  EntityTypeKey
}
import akka.http.scaladsl.model.RemoteAddress
import akka.persistence.typed.scaladsl.{
  Effect,
  EventSourcedBehavior,
  RetentionCriteria
}
import akka.persistence.typed.{
  PersistenceId,
  RecoveryCompleted,
  RecoveryFailed,
  SnapshotCompleted,
  SnapshotFailed
}
import com.dounine.jb.behavior.virtual.UserBehavior
import com.dounine.jb.model.{BaseSerializer, SocketModel}
import com.dounine.jb.tools.json.BaseRouter
import com.dounine.jb.types.MessageType.MessageType
import com.dounine.jb.types.MessageType
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import com.dounine.jb.behavior.platform.selenium.GoldBehavior
import java.util.UUID

import com.dounine.jb.behavior.selenium.ChannelBehavior

object ChannelWebsocketBehavior extends BaseRouter {

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer](s"ChannelWebsocketBehavior")

  trait Event extends BaseSerializer

  abstract class State() extends BaseSerializer {
    val data: DataStore
  }

  protected final case class Idle(data: DataStore) extends State

  protected final case class Busy(data: DataStore) extends State

  final case class ErrorMessage(text: String) extends Event

  final case class OutgoingMessage(
      `type`: MessageType,
      data: Option[Any] = Option.empty,
      msg: Option[String] = Option.empty
  ) extends Event

  final case object Stop extends Event

  final case object Shutdown extends Event

  final case class ReceiveMessage(text: String) extends Event

  final case class PushMessage(`type`: MessageType, data: Any) extends Event

  final case class Error(msg: String) extends Event

  final case class Connected(
      client: akka.actor.ActorRef,
      ip: RemoteAddress
  ) extends Event

  final case object Create extends Event

  final case class DataStore(
      client: Option[akka.actor.ActorRef],
      channelActor: Option[EntityRef[ChannelBehavior.Event]]
  ) extends BaseSerializer

  def apply(
      persistenceId: PersistenceId,
      shard: ActorRef[ClusterSharding.ShardCommand]
  ): Behavior[BaseSerializer] =
    Behaviors
      .supervise(
        Behaviors.setup { context: ActorContext[BaseSerializer] =>
          context.log.info("channel websocket behavior create")
          val sharding: ClusterSharding = ClusterSharding(context.system)
          val logOn: Boolean = config.getBoolean("log.websocket")
          val commandHandler
              : (State, BaseSerializer) => Effect[BaseSerializer, State] = {
            (state, cmd) =>
              state match {
                case Busy(data) =>
                  cmd match {
                    case e @ Connected(_, _) => Effect.persist(e)
                    case e @ Error(_)        => Effect.persist(e).thenUnstashAll()
                    case Stop =>
                      shard.tell(ClusterSharding.Passivate(context.self))
                      Effect.none
                    case Shutdown => Effect.none.thenStop()
                    case _        => Effect.stash()
                  }
                case Idle(data) =>
                  cmd match {
                    case e @ ChannelBehavior.DataDownloadResponse(_, _) =>
                      Effect.persist(e)
                    case e @ Create =>
                      Effect.persist(e)
                    case e @ ChannelBehavior.QrCodeLoginSuccess() =>
                      Effect.persist(e)
                    case e @ ChannelBehavior.QrCodeQueryResponse(_, _) =>
                      Effect.persist(e)
                    case e @ ChannelBehavior.HandleResponse(_, _, _, _) =>
                      Effect.persist(e)
                    case e @ Error(_)          => Effect.persist(e).thenUnstashAll()
                    case e @ ReceiveMessage(_) => Effect.persist(e)
                    case Stop =>
                      context.log.info("channel websocket behavior stop")
                      data.channelActor.foreach(actor => {
                        actor.tell(ChannelBehavior.Stop)
                      })
                      shard.tell(ClusterSharding.Passivate(context.self))
                      Effect.none
                    case Shutdown =>
                      Effect.none.thenStop()
                  }
              }
          }

          val eventHandler: (State, BaseSerializer) => State = { (state, cmd) =>
            state match {
              case self @ Busy(data) =>
                cmd match {
                  case Error(msg) =>
                    data.client.foreach(_ ! Done)
                    self
                  case Connected(client, ip) =>
                    val channelBehavior: EntityRef[ChannelBehavior.Event] =
                      sharding.entityRefFor(
                        ChannelBehavior.typeKey,
                        UUID.randomUUID().toString()
                      )
                    channelBehavior.tell(
                      ChannelBehavior.Create()(context.self)
                    )
                    Idle(
                      data = data.copy(
                        client = Option(client),
                        channelActor = Option(channelBehavior)
                      )
                    )
                }
              case self @ Idle(data) =>
                cmd match {
                  case ChannelBehavior.DataDownloadResponse(img, error) =>
                    data.client.foreach(client => {
                      client ! OutgoingMessage(
                        `type` = MessageType.channel,
                        data = Option(
                          Map(
                            "type" -> "gamesFinish",
                            "download" -> img.map(src => {
                              config
                                .getString(
                                  "domain"
                                ) + s"/download/${LocalDate.now()}.xlsx?path=" + src
                            }),
                            "error" -> error
                          )
                        )
                      )
                    })
                    self
                  case ChannelBehavior
                        .HandleResponse(game, process, success, delay) =>
                    data.client.foreach(client => {
                      client ! OutgoingMessage(
                        `type` = MessageType.channel,
                        data = Option(
                          Map(
                            "type" -> "gameFinish",
                            "process" -> process,
                            "game" -> game,
                            "delay" -> delay,
                            "success" -> success
                          )
                        )
                      )
                    })
                    self
                  case ChannelBehavior.QrCodeLoginSuccess() =>
                    data.client.foreach(client => {
                      client ! OutgoingMessage(
                        `type` = MessageType.channel,
                        data = Option(
                          Map(
                            "type" -> "qrcodeLoginSuccess"
                          )
                        )
                      )
                    })
                    self
                  case ChannelBehavior.QrCodeQueryResponse(img, error) =>
                    data.client.foreach(client => {
                      client ! OutgoingMessage(
                        `type` = MessageType.channel,
                        data = Option(
                          Map(
                            "type" -> "qrcodeQuery",
                            "img" -> img.map(src => {
                              config
                                .getString("domain") + "/image?path=" + src
                            }),
                            "error" -> error
                          )
                        )
                      )
                    })
                    self
                  case ReceiveMessage(text: String) =>
                    context.log.info("ReceiveMessage {}", text)
                    val message: SocketModel.ClientMessage =
                      convertTo[SocketModel.ClientMessage](text)
                    message.`type` match {
                      case MessageType.channel =>
                        if (message.data("type").toString == "qrcodeQuery") {
                          data.channelActor.foreach(actor => {
                            actor.tell(
                              ChannelBehavior.QrCodeQuery()
                            )
                          })
                          self
                        } else if (
                          message.data("type").toString == "dataQuery"
                        ) {
                          val games: Seq[String] =
                            message.data("games").asInstanceOf[List[String]]
                          data.channelActor.foreach(actor => {
                            actor.tell(
                              ChannelBehavior.QueryData(
                                day = message
                                  .data("day")
                                  .asInstanceOf[BigInt]
                                  .intValue,
                                games = games
                              )
                            )
                          })
                          self
                        } else self
                      case _ => self
                    }
                }
            }
          }

          EventSourcedBehavior(
            persistenceId = persistenceId,
            emptyState = Busy(
              DataStore(Option.empty, Option.empty)
            ),
            commandHandler = commandHandler,
            eventHandler = eventHandler
          ).onPersistFailure(
            SupervisorStrategy
              .restartWithBackoff(
                minBackoff = 1.seconds,
                maxBackoff = 3.seconds,
                randomFactor = 0.1
              )
              .withMaxRestarts(maxRestarts = 3)
              .withResetBackoffAfter(3.seconds)
          ).receiveSignal({
            case (state, RecoveryCompleted) =>
              if (logOn)
                context.log.debug(
                  "******  Recovery Completed with state: {}  *******",
                  state
                )
            case (state, RecoveryFailed(err)) =>
              if (logOn)
                context.log.error(
                  "******  Recovery failed with: {}  ******",
                  err.getMessage
                )
            case (state, SnapshotCompleted(meta)) =>
              if (logOn)
                context.log.debug(
                  "******  Snapshot Completed with state: {},id({},{})  *******",
                  state,
                  meta.persistenceId,
                  meta.sequenceNr
                )
            case (state, SnapshotFailed(meta, err)) =>
              if (logOn)
                context.log.error(
                  "******  Snapshot failed with: {}  ******",
                  err.getMessage
                )
            case (_, PreRestart) =>
              if (logOn) context.log.info(s"******  PreRestart  ******")
            case (_, single) =>
              if (logOn) context.log.debug(s"******  其它事件 ${single}  ******")
          }).snapshotWhen((state, event, _) => true)
            .withRetention(
              RetentionCriteria
                .snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2)
            )
        }
      )
      .onFailure(
        SupervisorStrategy
          .restartWithBackoff(
            minBackoff = 3.seconds,
            maxBackoff = 10.seconds,
            randomFactor = 0.2
          )
          .withMaxRestarts(maxRestarts = 3)
          .withResetBackoffAfter(10.seconds)
      )

}
