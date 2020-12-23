package com.dounine.jb.model

import java.time.LocalDateTime

import com.dounine.jb.types.UserStatus.UserStatus

object UserModel {

  final case class UserInfo(
                             phone: String,
                             password: String,
                             createTime: LocalDateTime,
                             status: UserStatus
                           ) extends BaseSerializer

  final case class UserAuthInfo(
                                 phone: String,
                                 password: String,
                                 admin: Boolean,
                                 createTime: LocalDateTime,
                                 status: UserStatus
                               ) extends BaseSerializer

  final case class UserEditData(
                                 password: String
                               ) extends BaseSerializer

  final case class UserApi(
                            accessKey: String,
                            accessSecret: String
                          ) extends BaseSerializer

  final case class ValidUserInfo(
                                  phone: String
                                ) extends BaseSerializer

}
