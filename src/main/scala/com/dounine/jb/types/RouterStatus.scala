package com.dounine.jb.types

object RouterStatus extends Enumeration {
  type RouterStatus = Value
  val ok: RouterStatus.Value = Value("ok")
  val fail: RouterStatus.Value = Value("fail")

}
