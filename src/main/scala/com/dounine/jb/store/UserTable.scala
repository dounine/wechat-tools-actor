package com.dounine.jb.store

import java.time.LocalDateTime

import com.dounine.jb.model.UserModel
import com.dounine.jb.tools.json.EnumMapper
import com.dounine.jb.types.UserStatus.UserStatus
import slick.jdbc.MySQLProfile.api._
import slick.lifted.{PrimaryKey, ProvenShape}

class UserTable(tag: Tag)
    extends Table[UserModel.UserInfo](tag, _tableName = "jb-user")
    with EnumMapper {

  override def * : ProvenShape[UserModel.UserInfo] =
    (
      phone,
      password,
      createTime,
      status
    )
      .mapTo[UserModel.UserInfo]

  def phone: Rep[String] = column[String]("phone", O.Length(13))

  def password: Rep[String] = column[String]("password", O.Length(20))

  def status: Rep[UserStatus] = column[UserStatus]("status", O.Length(10))

  def createTime: Rep[LocalDateTime] =
    column[LocalDateTime]("createTime", O.Length(23))(localDateTime2timestamp)

  def pk: PrimaryKey = primaryKey("jb-user-primaryKey", phone)
}
