package com.dounine.jb.model

import scala.concurrent.duration.{FiniteDuration, _}
import com.dounine.jb.types.MessageType
import com.dounine.jb.types.MessageType.MessageType

object SocketModel {

  final case class Update[T](
      data: T
  )

  final case class Login(
      token: Option[String]
  )

  final case class Sub(
      channels: Set[SocketModel.SubItem] = Set.empty
  )

  final case class Result(
      `type`: String,
      status: String,
      msg: Option[String] = Option.empty
  )

  final case class OkResult(
      `type`: String,
      status: String,
      data: Option[Any] = Option.empty[Any]
  )

  final case class SubItem(
      `type`: String,
      json: String
  )

  sealed trait MessageBody extends BaseSerializer

  final case class ClientMessage(
      `type`: MessageType
  ) extends BaseSerializer

  final case class ClientTypeData[T: Manifest](
      data: T
  ) extends BaseSerializer

  final case class ClientSubMessageData(
      data: ClientSubMessageBody
  ) extends BaseSerializer

  case class ClientSubMessageBody(
      `type`: MessageType,
  ) extends BaseSerializer

  final case class ClientSubMessageData2(
      `type`: MessageType,
      data: Option[ClientSubMessageBody]
  ) extends BaseSerializer

  final case class ClientUnSubMessageData(
      data: ClientUnSubMessageBody
  ) extends BaseSerializer

  final case class ClientUnSubMessageBody(
      `type`: MessageType
  ) extends BaseSerializer

}
