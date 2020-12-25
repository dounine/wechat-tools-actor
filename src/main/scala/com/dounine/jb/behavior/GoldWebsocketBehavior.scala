package com.dounine.jb.behavior

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
import com.dounine.jb.types.{MessageType}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._
import com.dounine.jb.behavior.platform.selenium.GoldBehavior
import java.util.UUID

object GoldWebsocketBehavior extends BaseRouter {

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer](s"GoldWebsocketBehavior")

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
      golds: Seq[EntityRef[GoldBehavior.Event]]
  ) extends BaseSerializer

  def apply(
      persistenceId: PersistenceId,
      shard: ActorRef[ClusterSharding.ShardCommand]
  ): Behavior[BaseSerializer] =
    Behaviors
      .supervise(
        Behaviors.setup { context: ActorContext[BaseSerializer] =>
          val logOn: Boolean = config.getBoolean("log.websocket")
          val sharding: ClusterSharding = ClusterSharding(context.system)
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
                    case e @ Create =>
                      Effect.persist(e)
                    case e @ GoldBehavior.DeleteResponse(id) =>
                      data.client.foreach(actor => {
                        actor ! OutgoingMessage(
                          MessageType.gold,
                          data = Option(
                            Map(
                              "type" -> "goldDelete",
                              "data" -> Map("id" -> id)
                            )
                          )
                        )
                      })
                      Effect.none
                    case e @ GoldBehavior.ProcessingResponse(_, _, _) =>
                      data.client.foreach(actor => {
                        actor ! OutgoingMessage(
                          MessageType.gold,
                          data = Option(
                            Map("type" -> "goldProcessing", "data" -> e)
                          )
                        )
                      })
                      Effect.none
                    case e @ GoldBehavior.CreateResponse(id) =>
                      data.client.foreach(actor => {
                        actor ! OutgoingMessage(
                          MessageType.gold,
                          data = Option(
                            Map(
                              "type" -> "goldCreate",
                              "data" -> {
                                "id" -> id
                              }
                            )
                          )
                        )
                      })
                      Effect.none
                    case e @ GoldBehavior
                          .GoldQueryResponse(_, _, _, _, _) =>
                      data.client.foreach(actor => {
                        actor ! OutgoingMessage(
                          MessageType.gold,
                          data = Option(
                            Map("type" -> "goldQuery", "data" -> e)
                          )
                        )
                      })
                      Effect.none
                    case e @ GoldBehavior.ErrorResponse(msg) =>
                      Effect.persist(e)
                    case e @ GoldBehavior.LoginScanResponse(_, _, _) =>
                      data.client.foreach(actor => {
                        actor ! OutgoingMessage(
                          MessageType.gold,
                          data = Option(
                            Map(
                              "type" -> "loginScan",
                              "data" -> e
                            )
                          )
                        )
                      })
                      Effect.none
                    case e @ Error(_)                     => Effect.persist(e).thenUnstashAll()
                    case e @ ReceiveMessage(text: String) => Effect.persist(e)
                    case Stop =>
                      context.log.info("gold websocket behavior stop")
                      data.golds.foreach(item => {
                        item.tell(GoldBehavior.Stop)
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
                    data.client.foreach(
                      _ ! GoldWebsocketBehavior.OutgoingMessage(
                        `type` = MessageType.errorPush,
                        msg = Option(msg)
                      )
                    )
                    data.client.foreach(_ ! Done)
                    self
                  case Connected(client, ip) =>
                    Idle(
                      data = data.copy(
                        client = Option(client)
                      )
                    )
                }
              case self @ Idle(data) =>
                cmd match {
                  case e @ GoldBehavior.ErrorResponse(msg) =>
                    e.replyTo.tell(GoldBehavior.Stop)
                    self.copy(
                      data = self.data.copy(
                        golds = self.data.golds.filterNot(p => p == e.replyTo)
                      )
                    )
                  case Error(msg) =>
                    data.client.foreach(
                      _ ! GoldWebsocketBehavior.OutgoingMessage(
                        `type` = MessageType.errorPush,
                        msg = Option(msg)
                      )
                    )
                    data.client.foreach(_ ! Done)
                    self
                  case ReceiveMessage(text: String) =>
                    context.log.info("ReceiveMessage {}", text)
                    convertTo[SocketModel.ClientMessage](text).`type` match {
                      case MessageType.gold =>
                        val goldBehavior = sharding.entityRefFor(
                          GoldBehavior.typeKey,
                          UUID.randomUUID().toString()
                        )
                        goldBehavior.tell(GoldBehavior.Create()(context.self))
                        self.copy(
                          data = self.data.copy(
                            golds = self.data.golds ++ Seq(goldBehavior)
                          )
                        )
                      case _ => self
                    }
                }
            }
          }

          EventSourcedBehavior(
            persistenceId = persistenceId,
            emptyState = Busy(
              DataStore(Option.empty, Seq.empty)
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
