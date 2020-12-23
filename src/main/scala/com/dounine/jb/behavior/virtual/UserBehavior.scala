package com.dounine.jb.behavior.virtual


import java.time.Clock
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PreRestart, SupervisorStrategy}
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import akka.persistence.typed._
import com.dounine.jb.model.{AuthModel, BaseSerializer, UserModel}
import com.dounine.jb.service.UserService
import com.dounine.jb.tools.json.BaseRouter
import com.dounine.jb.tools.util.SingletonService
import com.dounine.jb.types.UserStatus
import org.json4s.native.Serialization.write
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object UserBehavior extends BaseRouter {

  val typeKey: EntityTypeKey[UserBehavior.Event] = EntityTypeKey[UserBehavior.Event]("User")

  trait Event extends BaseSerializer

  final case class UserInfo(phone: String)(val replyTo: ActorRef[Event]) extends Event

  final case class UserInfoOk(userInfo: UserModel.UserInfo) extends Event

  final case class UserInfoFail(msg: String) extends Event

  final case class Login(phone: String, password: String)(val replyTo: ActorRef[Event]) extends Event

  final case class LoginOk(userInfo: UserModel.UserInfo, token: String) extends Event

  final case class LoginFail(msg: String) extends Event

  final case object Init extends Event

  final case object InitSuccess extends Event

  final case class Valid(token: String, data: Option[Map[String, String]] = Option.empty)(val replyTo: ActorRef[Event]) extends Event

  final case class ValidOk(session: AuthModel.Session) extends Event

  final case class ValidFail(msg: String) extends Event

  final case object Stop extends Event

  final case object Shutdown extends Event

  private final case class DataStore() extends BaseSerializer

  private val jwtSecretStr: String = config.getString("jwt.secret")
  private val jwtExpireInt: Int = config.getInt("jwt.expire")


  def apply(persistenceId: PersistenceId, shard: ActorRef[ClusterSharding.ShardCommand]): Behavior[Event] = Behaviors.supervise(
    Behaviors.setup {
      context: ActorContext[Event] =>
        context.log.info("****  create user behavior  ****")
        val userService: UserService = SingletonService.instance(classOf[UserService], context.system)

        val commandHandler: (DataStore, Event) => Effect[Event, DataStore] = {
          (data, cmd) =>
            cmd match {
              case e@Valid(_, _) => Effect.persist(e)
              case e@Init => Effect.persist(e)
              case e@Login(_, _) => Effect.persist(e)
              case e@UserInfo(_) => Effect.persist(e)
              case e@UserInfoOk(_) => Effect.persist(e)
              case e@UserInfoFail(_) => Effect.persist(e)
              case e@Stop =>
                context.log.info(s"user behavior stop")
                shard.tell(ClusterSharding.Passivate(context.self))
                Effect.none
              case Shutdown => Effect.none.thenStop()
              case _ => Effect.unhandled
            }
        }

        val eventHandler: (DataStore, Event) => DataStore = {
          (data, cmd) =>
            cmd match {
              case e@Valid(token, options) =>
                userService.tokenValid(token) match {
                  case Some(value) => e.replyTo ! ValidOk(value)
                  case _ => e.replyTo.tell(ValidFail("token 无效"))
                }
                data
              case Init => data
              case e@UserInfo(phone) =>
                implicit val ec: ExecutionContextExecutor = context.executionContext
                userService.info(phone).onComplete {
                  case Success(value) =>
                    value match {
                      case Some(user) =>
                        e.replyTo.tell(UserInfoOk(user))
                      case _ => e.replyTo.tell(UserInfoFail("用户不存在"))
                    }
                  case Failure(exception) => e.replyTo.tell(LoginFail(exception.getMessage))
                }
                data
              case UserInfoOk(_) => data
              case UserInfoFail(_) => data
              case e@Login(phone, password) =>
                implicit val ec: ExecutionContextExecutor = context.executionContext
                userService.info(phone).onComplete {
                  case Success(value) =>
                    value match {
                      case Some(user) =>
                        (user.status, password) match {
                          case (UserStatus.normal, user.password) =>
                            implicit val clock: Clock = Clock.systemUTC
                            val claim: AuthModel.Session = AuthModel.Session(phone = user.phone)
                            val token: String = Jwt.encode(
                              JwtHeader(JwtAlgorithm.HS256),
                              JwtClaim(write(claim)).issuedNow
                                .expiresIn(jwtExpireInt),
                              jwtSecretStr
                            )
                            e.replyTo.tell(LoginOk(user, token))
                          case (UserStatus.locked, user.password) =>
                            e.replyTo.tell(LoginFail("帐户已被锁定"))
                          case (UserStatus.inactive, user.password) =>
                            e.replyTo.tell(LoginFail("帐户未激活"))
                          case _ =>
                            e.replyTo.tell(LoginFail("用户名或密码错误"))
                        }
                      case _ => e.replyTo.tell(LoginFail("用户不存在"))
                    }
                  case Failure(exception) => e.replyTo.tell(LoginFail(exception.getMessage))
                }
                data
            }
        }

        EventSourcedBehavior(
          persistenceId = persistenceId,
          emptyState = DataStore(),
          commandHandler = (state: DataStore, command: Event) => commandHandler(state, command),
          eventHandler = (state: DataStore, command: Event) => eventHandler(state, command)
        ).onPersistFailure(
          SupervisorStrategy.restartWithBackoff(
            minBackoff = 1.seconds,
            maxBackoff = 10.seconds,
            randomFactor = 0.2
          )
            .withMaxRestarts(maxRestarts = 3)
            .withResetBackoffAfter(10.seconds)
        ).receiveSignal({
          case (state, RecoveryCompleted) =>
            context.log.debug("******  Recovery Completed with state: {}  *******", state)
          case (state, RecoveryFailed(err)) =>
            context.log.error("******  Recovery failed with: {}  ******", err.getMessage)
          case (state, SnapshotCompleted(meta)) =>
            context.log.debug("******  Snapshot Completed with state: {},id({},{})  ******", state, meta.persistenceId, meta.sequenceNr)
          case (state, SnapshotFailed(meta, err)) =>
            context.log.error("******  Snapshot failed with: {}  ******", err.getMessage)
          case (_, PreRestart) =>
            context.log.info(s"******  PreRestart  ******")
          case (_, single) =>
            context.log.debug(s"******  其它事件 ${single}  ******")
        })
          .snapshotWhen((state, event, _) => true)
          .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 4, keepNSnapshots = 1).withDeleteEventsOnSnapshot)
    }
  ).onFailure(
    SupervisorStrategy
      .restartWithBackoff(minBackoff = 3.seconds, maxBackoff = 10.seconds, randomFactor = 0.2)
      .withMaxRestarts(maxRestarts = 3)
      .withResetBackoffAfter(10.seconds)
  )
}
