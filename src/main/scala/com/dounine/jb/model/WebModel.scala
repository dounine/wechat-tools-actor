package com.dounine.jb.model

object WebModel {

  final case class HbsessionResponse(
                                      status: String,
                                      err_code: Option[Int],
                                      err_msg: Option[String],
                                      ts: Long
                                    )


  final case class CancelResponseDataError(
                                            order_id: String,
                                            err_code: Int,
                                            err_msg: String
                                          )

  final case class CancelResponseData(
                                       errors: Array[CancelResponseDataError],
                                       successes: String
                                     )

  final case class CancelResponse(
                                   status: String,
                                   err_code: Option[Int],
                                   err_msg: Option[String],
                                   data: Option[CancelResponseData],
                                   ts: Long
                                 )

  final case class TriggerResponseData(
                                        order_id: Long
                                      )

  final case class TriggerResponse(
                                    status: String,
                                    err_code: Option[Int],
                                    err_msg: Option[String],
                                    data: Option[TriggerResponseData],
                                    ts: Long
                                  )

}
