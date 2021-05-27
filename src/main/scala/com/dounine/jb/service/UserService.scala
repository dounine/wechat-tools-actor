package com.dounine.jb.service

import akka.actor.typed.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import com.dounine.jb.model.{AuthModel, UserModel}
import com.dounine.jb.store.UserTable
import com.dounine.jb.tools.db.DataSource
import com.dounine.jb.tools.json.{BaseRouter, EnumMapper}
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.{Logger, LoggerFactory}
import pdi.jwt.{Jwt, JwtAlgorithm}
import slick.jdbc.MySQLProfile
import slick.jdbc.MySQLProfile.api._
import com.dounine.jb.tools.util.RedisUtil.RedisExt
import slick.lifted.TableQuery

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Try
import scala.concurrent.duration._

class UserService(system: ActorSystem[_]) extends BaseRouter with EnumMapper {

  private implicit val ec: ExecutionContextExecutor = system.executionContext
  private val logger: Logger = LoggerFactory.getLogger(classOf[UserService])
  private lazy val db: MySQLProfile.backend.DatabaseDef = DataSource.db
  private val dict: TableQuery[UserTable] = TableQuery[UserTable]
  private val jwtSecret: String = config.getString("jwt.secret")


  def tokenValid(token: String): Option[AuthModel.Session] = {
    if (Jwt.isValid(token.trim(), jwtSecret, Seq(JwtAlgorithm.HS256))) {
      val result: Try[(String, String, String)] = Jwt.decodeRawAll(token.trim(), jwtSecret, Seq(JwtAlgorithm.HS256))
      Option(convertTo[AuthModel.Session](result.get._2))
    } else Option.empty
  }

  def info(
            phone: String,
            cache: Boolean = true
          ): Future[Option[UserModel.UserInfo]] = {
    cacheOrSet(
      key = s"jb:user-info:$phone",
      ttl = 1.minutes,
      system = system,
      default = () => {
        db.run(dict.filter(_.phone === phone).result)
          .map(item => {
            item.headOption
          })
      },
      cache = cache
    )
  }
}
