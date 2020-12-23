package com.dounine.jb.model

import com.dounine.jb.types.RouterStatus.RouterStatus

object AuthModel {

  final case class UserData(
      username: Option[String],
      phone: Option[String],
      password: String
  ) extends BaseSerializer

  final case class UserAddData(
      phone: String,
      password: String
  ) extends BaseSerializer

  final case class LoginResult(
      msg: Option[String],
      data: Option[LoginSuccess],
      status: RouterStatus
  ) extends BaseSerializer

  final case class LoginSuccess(
      token: String
  ) extends BaseSerializer

  final case class Session(
      data: Option[Map[String, String]] = Option.empty,
      exp: Option[Long] = None,
      iat: Option[Long] = None,
      phone: String
  ) extends BaseSerializer

}
