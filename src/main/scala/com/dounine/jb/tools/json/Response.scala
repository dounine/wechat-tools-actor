package com.dounine.jb.tools.json

import com.dounine.jb.types.RouterStatus
import com.dounine.jb.types.RouterStatus.RouterStatus

object Response {

  case class Data(
      data: Option[Any] = Option.empty[Any],
      code: RouterStatus = RouterStatus.ok
  )

  case class Ok(
      code: RouterStatus = RouterStatus.ok
  )

  case class Fail(
      msg: Option[String] = Option.empty[String],
      code: RouterStatus = RouterStatus.fail
  )

}
