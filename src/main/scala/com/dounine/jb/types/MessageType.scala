package com.dounine.jb.types

object MessageType extends Enumeration {
  type MessageType = Value

  val gold: MessageType.Value = Value("gold")
  val errorPush: MessageType.Value = Value("errorPush")

}
