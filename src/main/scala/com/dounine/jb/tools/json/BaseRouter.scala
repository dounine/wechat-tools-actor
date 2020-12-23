package com.dounine.jb.tools.json

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.StandardRoute
import com.dounine.jb.behavior.ReplicatedCache
import com.dounine.jb.tools.util.{GlobalMap, RedisUtil}
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import org.json4s.Formats
import org.json4s.jackson.Serialization
import org.json4s.jackson.JsonMethods._
import org.json4s.native.Serialization.{read, write}
import redis.clients.jedis.JedisPool

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import com.dounine.jb.tools.util.RedisUtil.RedisExt
import com.typesafe.config.{Config, ConfigFactory}
import akka.actor.typed.scaladsl.AskPattern._
import com.dounine.jb.Bootstrap

trait BaseRouter extends Json4sSupport {
  implicit val serialization: Serialization.type = Suport.serialization
  implicit val formats: Formats = Suport.formats
  protected val config: Config = ConfigFactory.load().getConfig("jb")
  private lazy val redisPools: JedisPool = RedisUtil.pools
  private lazy val replicatedCache: ActorRef[ReplicatedCache.Command] =
    GlobalMap.get[ActorRef[ReplicatedCache.Command]](key = "ReplicatedCache")
  private val redisCacle: Boolean = config.getString("cache") == "redis"
  private val queryTimeout: FiniteDuration =
    config.getDuration("replicated.queryTimeout").toMillis.milliseconds
  private val deleteTimeout: FiniteDuration =
    config.getDuration("replicated.deleteTimeout").toMillis.milliseconds

  def cacheDel(key: String, system: ActorSystem[_]): Unit = {
    if (redisCacle) {
      redisPools.getResource.del(key)
    } else {
      implicit val executionContext: ExecutionContextExecutor =
        system.executionContext
      replicatedCache
        .ask(ReplicatedCache.GetCache(key))(deleteTimeout, system.scheduler)
    }
  }

  def cacheOrSet[T: Manifest](
      key: String,
      ttl: FiniteDuration,
      system: ActorSystem[_],
      default: () => Future[T],
      cache: Boolean = true
  )(implicit formats: Formats): Future[T] = {
    if (redisCacle) {
      redisPools.getResource.getOrSetAndDefault[T](key, ttl, cache, default)
    } else {
      implicit val executionContext: ExecutionContextExecutor =
        system.executionContext
      val resultFuture: Future[ReplicatedCache.Cached] = replicatedCache.ask(
        ReplicatedCache.GetCache(key)
      )(queryTimeout, system.scheduler)
      resultFuture
        .flatMap(result => {
          result.value match {
            case Some(value: Any) =>
              Future.successful(value.asInstanceOf[T])
            case None =>
              default()
                .map(f => {
                  replicatedCache.tell(ReplicatedCache.PutCache(key, f, ttl))
                  f
                })
          }
        })
    }
  }

  def ok(data: Any): StandardRoute = {
    complete(Response.Data(Option(data)))
  }

  val ok: StandardRoute = complete(Response.Ok())

  def fail(msg: String): StandardRoute = {
    complete(Response.Fail(Option(msg)))
  }

  val fail: StandardRoute = complete(Response.Fail())

  def toJsonString(data: Any): String = {
    write(data)
  }

  implicit class ToJson(data: Any) {
    def toJson: String = {
      write(data)
    }
  }

  def convertTo[T: Manifest](data: String): T = {
    read[T](data)
  }

  def parseJson[T: Manifest](data: String): T = {
    parse(data).extract[T]
  }

}
