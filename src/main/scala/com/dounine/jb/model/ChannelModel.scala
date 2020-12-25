package com.dounine.jb.model

import akka.http.scaladsl.model.HttpHeader

object ChannelModel {

  case class ApiScanLoginResponse(
      uuid: String
  )

  case class ApiScanStatusResponse(
      status: Boolean,
      wx_code: String
  )

  case class ApiScanLoginAuthResponse(
      errcode: Int,
      errmsg: String,
      data: ApiScanLoginAuthData
  )

  case class ApiScanUserInfoResponse(
      errcode: Int,
      errmsg: String,
      data: ApiScanLoginAuthData
  )

  case class ApiScanAppInfo(
      appname: String,
      icon_url: String
  )

  case class ApiScanCUserInfo(
      head_img_url: String,
      nickname: String
  )

  case class StatItem(
      stat_type: Long,
      data_field_id: Long
  )

  case class PermItem(
      out_group_id: String,
      out_channel_id: String,
      channel_name: String,
      stat_list: List[StatItem]
  )

  case class ApiScanUserInfo(
      app_info: ApiScanAppInfo,
      user_info: ApiScanCUserInfo,
      share_perm_data: SharePermData
  )

  case class ApiScanGameUserInfo(
      errcode: Int,
      errmsg: String,
      data: ApiScanUserInfo
  )

  case class ApiScanGameDataResponse(
      errcode: Int,
      errmsg: String,
      data: ApiScanGameDataSequenceWrap
  )

  case class ApiScanDataQuery(
      need_app_info: Boolean,
      appid: String,
      sequence_index_list: Seq[ApiScanQuerySequence]
  )

  case class ApiScanQuerySequence(
      size_type: Int,
      stat_type: Long,
      data_field_id: Long,
      requestType: String,
      time_period: ApiScanQueryTimePeriod,
      filter_list: Seq[ApiScanQueryFilter]
  )

  case class ApiScanQueryTimePeriod(
      start_time: Long,
      duration_seconds: Long
  )

  case class ApiScanQueryFilter(
      name: Option[String],
      field_id: Int,
      value: String
  )

  case class ApiScanGameDataSequenceWrap(
      sequence_data_list: List[ApiScanGameDataSequence]
  )

  case class ApiScanGameDataSequence(
      point_list: List[ApiScanGameSequenceData]
  )

  case class ApiScanGameSequenceData(
      label: String,
      label_value: String,
      value: Option[Double]
  )

  case class ApiCSVData(
      name: String,
      appid: String,
      date: String,
      register: Boolean,
      value: Double
  )

  case class ApiExportData(
      appid: String,
      name: String,
      ccode: String,
      date: String,
      register: Double,
      active: Double
  )

  case class SharePermData(
      perm_list: List[PermItem]
  )

  case class ApiScanLoginAuthData(
      oauth_sid: String
  )

  case class LoginBaseResp(
      err_msg: String,
      ret: Int
  )

  case class LoginResponse(
      base_resp: LoginBaseResp,
      redirect_url: Option[String],
      headers: Option[Map[String, String]],
      httpHeaders: Option[Seq[HttpHeader]] = Option.empty,
      cookie: Option[String] = Option.empty
  )

  case class ScanQueryResponse(
      acct_size: Int,
      base_resp: LoginBaseResp,
      status: Int,
      user_category: Int,
      cookie: Option[String]
  )

  case class UpdateItem(
      username: String,
      status: String
  )

  case class UpdateStatus(
      `type`: String,
      list: List[UpdateItem]
  )

  case class DownloadRequest(
      list: List[ScanItem]
  )

  case class ScanItem(
      username: String,
      cookie: Option[String],
      token: Option[String] = Option.empty,
      info: Option[UserInfo] = Option.empty
  )

  case class ScanStart(
      `type`: String
  )

  case class ScanItemD(
      `type`: String,
      name: String
  )

  case class ScanFinish(
      `type`: String,
      path: String
  )

  case class ScanError(
      `type`: String,
      msg: String
  )

  case class ConfigScan(
      config: String
  )

  case class ConfigScan2(
      list: List[String]
  )

  case class ConfigScan2Data(
      path: String,
      uuid: String
  )

  case class ConfigAccount(
      username: String,
      password: String
  )

  case class ConfigScanData(
      username: String,
      scanPath: String,
      cookie: Option[String]
  )

  case class ScanMessage(
      data: List[ConfigScanData]
  )

  case class ScanMessage2(
      list: List[String],
      day: String,
      uuid: String
  )

  case class BizLoginResponse(
      base_resp: LoginBaseResp,
      redirect_url: String,
      cookie: Option[String]
  )

  case class IndexResponse(
      userInfo: UserInfo
  )

  case class UserInfo(
      openid: String,
      nickName: String,
      mediaTicket: String,
      appid: String,
      token: String,
      fakeId: Long,
      userRole: Int,
      iconUrl: String,
      userName: String,
      fakeIdBase64: String,
      wxverifyDate: String,
      wxverifyAnnualReviewBeginDate: String,
      wxverifyAnnualReviewEndDate: String
  )

  case class MpAdminLoginResponse(
      errcode: Int,
      errmsg: String,
      mp_session: Option[String]
  )

  case class PluginLoginInfo(
      third_url: String
  )

  case class PluginTokenResponse(
      ret: Int,
      plugin_login_info: PluginLoginInfo,
      pluginToken: Option[String]
  )

}
