package com.dounine.jb.router.directive

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import com.dounine.jb.behavior.virtual.UserBehavior
import com.dounine.jb.model.AuthModel
import com.dounine.jb.tools.json.BaseRouter
import pdi.jwt.{Jwt, JwtAlgorithm}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait TokenAuth extends BaseRouter {

  val jwtSecret: String
  val jwtExpire: Int
  val tokenName: String = "token"

  def tokenValid(token: String): UserBehavior.Event = {
    null
  }

  val valid: Directive1[AuthModel.Session] =
    for {
      parameters <- parameterMap
      headerToken <- optionalHeaderValueByName(headerName = tokenName)
      userData <- jwtAuthenticateToken((headerToken match {
        case Some(token) => Map(tokenName -> token)
        case _ => Map.empty[String, String]
      }) ++ parameters)
    } yield userData

  private val algorithms: Seq[JwtAlgorithm.HS256.type] = Seq(JwtAlgorithm.HS256)

  def jwtAuthenticateToken(params: Map[String, String]): Directive1[AuthModel.Session] = for {
    authorizedToken <- checkAuthorization(params)
    decodedToken <- decodeToken(authorizedToken)
    userData <- convertToUserData(decodedToken)
  } yield userData

  private def convertToUserData(session: AuthModel.Session): Directive1[AuthModel.Session] = {
    extractExecutionContext.flatMap { implicit ctx =>
      extractMaterializer.flatMap { implicit mat =>
        onComplete(Future.successful(session)).flatMap(handleError)
      }
    }
  }

  private def handleError(unmarshalledSession: Try[AuthModel.Session]): Directive1[AuthModel.Session] = unmarshalledSession match {
    case Success(value) => provide(value)
    case Failure(RejectionError(r)) => reject(r)
    case Failure(Unmarshaller.NoContentException) => reject(RequestEntityExpectedRejection)
    case Failure(e: Unmarshaller.UnsupportedContentTypeException) => reject(UnsupportedRequestContentTypeRejection(e.supported, e.actualContentType))
    case Failure(x: IllegalArgumentException) => reject(ValidationRejection(x.getMessage, Some(x)))
    case Failure(x) => reject(MalformedRequestContentRejection(x.getMessage, x))
  }

  private def checkAuthorization(params: Map[String, String]): Directive1[String] = params.get(tokenName) match {
    case Some(jwt) => provide(jwt)
    case None => fail(msg = "token is missing in header or parameter.")
  }

  private def isValid(jwt: String): Boolean = {
    Jwt.isValid(jwt, jwtSecret, algorithms)
  }

  private def decodeToken(jwt: String): Directive1[AuthModel.Session] = {
    tokenValid(jwt) match {
      case UserBehavior.ValidOk(data) => provide(data)
      case UserBehavior.ValidFail(msg) => fail(msg)
    }
  }
}
