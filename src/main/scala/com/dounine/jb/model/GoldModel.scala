package com.dounine.jb.model

object GoldModel {

  final case class UserInfo(
      nickName: String
  )
  final case class GoldResponse(
      userInfo: UserInfo
  )

  final case class GoldBalance(
      expire_balance: Long,
      total_balance: Long
  )
}
