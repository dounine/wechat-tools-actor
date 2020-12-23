package com.dounine.jb.router.controller

import java.util.UUID

import akka.{Done, NotUsed, actor}
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef}
import akka.http.scaladsl.model.RemoteAddress
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.Route
import akka.persistence.typed.PersistenceId
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{CompletionStrategy, OverflowStrategy, _}
import com.dounine.jb.behavior
import com.dounine.jb.behavior.{WebsocketBehavior, virtual}
import com.dounine.jb.model.BaseSerializer
import com.dounine.jb.tools.json.BaseRouter
import org.json4s.native.Serialization.write

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class WebsocketRouter(system: ActorSystem[_]) extends BaseRouter {

  implicit val materializer: Materializer = SystemMaterializer(
    system
  ).materializer
  implicit val actorSystem: actor.ActorSystem = materializer.system
  implicit val executionContext: ExecutionContextExecutor =
    materializer.executionContext

  val routeFragment: Route = concat(
    get {
      path(pm = "ws") {
        extractClientIP { ip: RemoteAddress =>
          handleWebSocketMessages(createConnect(ip))
        }
      }
    }
  )

  private def createConnect(
      ip: RemoteAddress
  ): Flow[Message, Message, _] = {
    val uuid = UUID.randomUUID().toString.replaceAll("-", "")
    val sharding: ClusterSharding = ClusterSharding(system)
    val socketBehavior: EntityRef[BaseSerializer] = sharding
      .entityRefFor(WebsocketBehavior.typeKey, uuid)

    val completion: PartialFunction[Any, CompletionStrategy] = { case Done =>
      CompletionStrategy.immediately
    }
    val incomingMessages: Sink[Message, _] =
      Flow[Message]
        .collect({
          case TextMessage.Strict(text)     => Future.successful(text)
          case TextMessage.Streamed(stream) => stream.runFold(zero = "")(_ + _)
          case _                            => Future.failed(new Exception(s"错误消息类型"))
        })
        .mapAsync(parallelism = 1) { elem =>
          elem.andThen({
            case Success(value) =>
              socketBehavior.tell(WebsocketBehavior.ReceiveMessage(value))
            case Failure(exception) =>
              socketBehavior.tell(WebsocketBehavior.Error(exception.getMessage))
          })
        }
        .to(Sink.ignore)

    val outgoingMessages: Source[Message, _] =
      Source
        .actorRef[WebsocketBehavior.OutgoingMessage](
          completionMatcher = completion,
          failureMatcher = PartialFunction.empty,
          bufferSize = 100,
          overflowStrategy = OverflowStrategy.dropHead
        )
        .mapMaterializedValue { outActor =>
          socketBehavior.tell(WebsocketBehavior.Connected(outActor, ip))
        }
        .map(msg => TextMessage.Strict(write(msg)))
        .keepAlive(
          maxIdle = 3.seconds,
          () => TextMessage("""{"type":"ping"}""")
        )
    Flow
      .fromSinkAndSourceCoupled(incomingMessages, outgoingMessages)
      .watchTermination() { (_, c) =>
        c.onComplete(_ => socketBehavior.tell(WebsocketBehavior.Stop))
        NotUsed
      }
  }

}
