package com.dounine.jb.router.controller

import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityRef}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import akka.util.Timeout
import com.dounine.jb.behavior.virtual.UserBehavior
import com.dounine.jb.model.AuthModel
import com.dounine.jb.router.directive.TokenAuth
import com.dounine.jb.tools.json.BaseRouter
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}
import org.slf4j.LoggerFactory

class UserRouter(system: ActorSystem[_]) extends BaseRouter {

  private val logger = LoggerFactory.getLogger(classOf[UserRouter])

  private val sharding: ClusterSharding = ClusterSharding(system)
  val admins: Array[String] = config.getString("admins").split(",")
  implicit val authTimeout: Timeout = Timeout(
    config.getDuration("timeout.auth").getSeconds.seconds
  )
  val tokenValid: Directive1[AuthModel.Session] = new TokenAuth() {
    override val jwtSecret: String = config.getString("jwt.secret")
    override val jwtExpire: Int = config.getInt("jwt.expire")

    override def tokenValid(token: String): UserBehavior.Event = {
      val result: Future[UserBehavior.Event] = sharding
        .entityRefFor(UserBehavior.typeKey, UserBehavior.typeKey.name)
        .ask(UserBehavior.Valid(token = token))
      Await.result(result, Duration.Inf)
    }
  }.valid

  val routeFragment: Route =
    pathPrefix(pm = "user") {
      get {
        path(pm = "valid") {
          parameterMap { params =>
            params.get("token") match {
              case Some(value) =>
                val entityRef: EntityRef[UserBehavior.Event] = sharding
                  .entityRefFor(UserBehavior.typeKey, UserBehavior.typeKey.name)
                val result: Future[UserBehavior.Event] =
                  entityRef.ask(UserBehavior.Valid(token = value))
                onComplete[UserBehavior.Event](result) {
                  case Success(value) =>
                    value match {
                      case UserBehavior.ValidOk(_)     => ok
                      case UserBehavior.ValidFail(msg) => fail(msg)
                    }
                  case Failure(exception) => fail(exception.getMessage)
                }
              case _ => fail(msg = "token 缺失")
            }
          }
        }
      } ~ get {
        tokenValid { session =>
          path(pm = "info") {
            val entityRef: EntityRef[UserBehavior.Event] =
              sharding.entityRefFor(UserBehavior.typeKey, entityId = "User")
            val result: Future[UserBehavior.Event] =
              entityRef.ask(UserBehavior.UserInfo(session.phone))
            onComplete[UserBehavior.Event](result) {
              case Success(value) =>
                value match {
                  case UserBehavior.UserInfoOk(userInfo) =>
                    ok(
                      Map(
                        "userid" -> userInfo.phone,
                        "name" -> userInfo.phone,
                        "avatar" -> "https://gw.alipayobjects.com/zos/antfincdn/XAosXuNZyF/BiazfanxmamNRoxxVxka.png",
                        "email" -> "",
                        "phone" -> userInfo.phone,
                        "access" -> (if (admins.contains(userInfo.phone))
                                       "admin"
                                     else "user"),
                        "status" -> userInfo.status
                      )
                    )
                  case UserBehavior.UserInfoFail(msg) => fail(msg)
                }
              case Failure(exception) => fail(exception.getMessage)
            }
          }
        }
      } ~
        post {
          path(pm = "login") {
            entity(as[AuthModel.UserData]) { formData =>
              val entityRef: EntityRef[UserBehavior.Event] =
                sharding.entityRefFor(UserBehavior.typeKey, entityId = "User")
              val result: Future[UserBehavior.Event] = entityRef.ask(
                UserBehavior.Login(
                  formData.phone.getOrElse(formData.username.getOrElse("")),
                  formData.password
                )
              )
              onComplete[UserBehavior.Event](result) {
                case Success(value) =>
                  value match {
                    case UserBehavior.LoginOk(userInfo, token) =>
                      ok(
                        Map(
                          "token" -> token
                        )
                      )
                    case UserBehavior.LoginFail(msg) => fail(msg)
                  }
                case Failure(exception) => fail(exception.getMessage)
              }
            }
          }
        }
    }
}
