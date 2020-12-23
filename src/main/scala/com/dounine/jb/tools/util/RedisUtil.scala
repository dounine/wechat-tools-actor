package com.dounine.jb.tools.util

import com.dounine.jb.tools.json.Suport
import com.typesafe.config.{Config, ConfigFactory}
import org.json4s.Formats
import org.json4s.jackson.Serialization
import org.json4s.native.Serialization.{read, write}
import org.slf4j.Logger
import redis.clients.jedis.params.SetParams
import redis.clients.jedis.{Jedis, JedisPool, JedisPoolConfig, Protocol}
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.duration.Duration

object RedisUtil {

  private val config: Config = ConfigFactory.load().getConfig("jb")
  private val logger: Logger = org.slf4j.LoggerFactory.getLogger(RedisUtil.getClass)
  private var jedisPool: Option[JedisPool] = Option.empty
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  implicit val serialization: Serialization.type = Suport.serialization

  def pools: JedisPool = synchronized {
    if (jedisPool.isEmpty) {
      jedisPool = Option(init())
    }
    jedisPool.get
  }

  private def init(): JedisPool = {
    logger.info("redis pool init")
    val c: JedisPoolConfig = new JedisPoolConfig
    c.setMaxIdle(config.getInt("redis.maxIdle"))
    c.setMaxTotal(config.getInt("redis.maxTotal"))
    c.setTestOnReturn(true)
    c.setTestWhileIdle(true)
    c.setTestOnBorrow(true)
    c.setMaxWaitMillis(
      config.getLong("redis.maxWaitMillis")
    )
    val redisHost: String = config.getString("redis.host")
    val redisPort: Int = config.getInt("redis.port")
    val redisPassword: String = config.getString("redis.password")
    if (redisPassword != "") {
      new JedisPool(
        c,
        redisHost,
        redisPort,
        0,
        redisPassword,
        Protocol.DEFAULT_DATABASE
      )
    } else new JedisPool(c, redisHost, redisPort, 0)
  }

  implicit class RedisExt(redis: Jedis) {
    def getOrSetAndDefault[T: Manifest](
                                         key: String,
                                         ttl: Duration = 3.seconds,
                                         cache: Boolean = true,
                                         default: () => Future[T]
                                       )(implicit formats: Formats): Future[T] = {
      if (cache) {
        RedisUtil.getOrDefault[T](redis, key, ttl, default)
      } else {
        default()
      }
    }

  }

  private def getOrDefault[T: Manifest](
                                         redis: Jedis,
                                         key: String,
                                         ttl: Duration = 3.seconds,
                                         default: () => Future[T]
                                       )(implicit formats: Formats): Future[T] = {
    val str: String = redis.get(key)
    if (str != null) {
      redis.close()
      try {
        val value: T = read[T](str)
        Future.successful(value)
      } catch {
        case e: Exception =>
          e.printStackTrace()
          logger.info(s"""json $key -> $str""")
          Future.failed(new Exception(s"转换失败 ${e.getMessage}"))
      }
    } else {
      default().map(result => {
        if (result != null) {
          val jsonValue: String = write(result)
          redis.set(
            key,
            jsonValue,
            new SetParams().ex(ttl.toSeconds.toInt)
          )
        } else {
          redis.set(
            key,
            "",
            new SetParams().ex(ttl.toSeconds.toInt)
          )
        }
        redis.close()
        result
      })
    }
  }

}
