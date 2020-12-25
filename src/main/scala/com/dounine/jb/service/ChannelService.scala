package com.dounine.jb.service

import java.io.File
import java.net.URLEncoder
import java.time.{LocalDate, ZoneOffset}

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.{OverflowStrategy, SystemMaterializer}
import akka.stream.scaladsl.FileIO
import com.dounine.jb.model.ChannelModel
import com.dounine.jb.store.UserTable
import com.dounine.jb.tools.akka.ProxySetting
import com.dounine.jb.tools.db.DataSource
import com.dounine.jb.tools.json.{BaseRouter, EnumMapper}
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc.MySQLProfile
import slick.lifted.TableQuery

import scala.concurrent.{ExecutionContextExecutor, Future}

class ChannelService(system: ActorSystem[_])
    extends BaseRouter
    with EnumMapper {

  private implicit val ec: ExecutionContextExecutor = system.executionContext
  private val logger: Logger = LoggerFactory.getLogger(classOf[ChannelService])
  private implicit val materializer = SystemMaterializer(system).materializer
  private val http: HttpExt = Http(system)

  def uuid(): Future[ChannelModel.ApiScanLoginResponse] = {
    val urlPath =
      "https://open.weixin.qq.com/connect/qrconnect?appid=wxd3b2f88404faa210&scope=snsapi_login&redirect_uri=https%3A%2F%2Fgame.weixin.qq.com%2Fcgi-bin%2Fminigame%2Fstatic%2Fchannel_side%2Flogin.html%3F&state=&login_type=jssdk&self_redirect=default&styletype=&sizetype=&bgcolor=&rst"

    val responseFuture = http
      .singleRequest(
        request = HttpRequest(
          uri = s"$urlPath",
          method = HttpMethods.GET
        ),
        settings = ProxySetting.proxy(system)
      )
    responseFuture.flatMap {
      case HttpResponse(_, headers, entity, _) =>
        try {
          Unmarshaller
            .stringUnmarshaller(entity)
            .map(html => {
              html
                .split("\n")
                .find(line => {
                  line.contains("""<img class="qrcode-image""")
                })
                .get
            })
            .map(uuid => {
              ChannelModel.ApiScanLoginResponse(
                uuid.split("""src="""").last.split(""""""").head
              )
            })
        } catch {
          case e => Future.failed(new Exception(e.getMessage))
        }
      case msg @ _ =>
        logger.error(s"请求失败 $msg")
        Future.failed(new Exception(s"请求失败 $msg"))
    }
  }

  def qrcode(uuid: String): Future[File] = {
    val urlPath = s"https://open.weixin.qq.com${uuid}"
    val responseFuture = http
      .singleRequest(
        request = HttpRequest(
          uri = s"$urlPath",
          method = HttpMethods.GET
        ),
        settings = ProxySetting.proxy(system)
      )
    responseFuture.flatMap {
      case HttpResponse(_, _, entity, _) =>
        val file = File.createTempFile("qrcode", ".png")
        entity.dataBytes
          .runWith(FileIO.toPath(file.toPath))
          .map(_ => file)
      case msg @ _ =>
        logger.error(s"请求失败 $msg")
        Future.failed(new Exception(s"请求失败 $msg"))
    }
  }

  def statusQuery(uuid: String): Future[ChannelModel.ApiScanStatusResponse] = {
    val urlPath =
      s"https://lp.open.weixin.qq.com/connect/l/qrconnect?uuid=${uuid.split('/').last}&last=404&_=${System.currentTimeMillis()}"
    val responseFuture = http
      .singleRequest(
        request = HttpRequest(
          uri = s"$urlPath",
          method = HttpMethods.GET
        ),
        settings = ProxySetting.proxy(system)
      )
    responseFuture.flatMap {
      case HttpResponse(_, _, entity, _) =>
        try {
          Unmarshaller
            .stringUnmarshaller(entity)
            .map(html => {
              logger.info("statusQuery response {}", html)
              html
            })
            .map(html => {
              if (html.contains("window.wx_errcode=405")) {
                ChannelModel.ApiScanStatusResponse(
                  true,
                  html.split("window.wx_code=").last.split("'")(1)
                )
              } else {
                ChannelModel.ApiScanStatusResponse(false, html)
              }
            })
        } catch {
          case e => Future.failed(new Exception(e.getMessage))
        }
      case msg @ _ =>
        logger.error(s"请求失败 $msg")
        Future.failed(new Exception(s"请求失败 $msg"))
    }
  }

  def loginauth2(
      code: String
  ): Future[ChannelModel.ApiScanLoginAuthResponse] = {
    val urlPath =
      s"https://game.weixin.qq.com/cgi-bin/gamebizauthwap/loginoauth2?appid=wxd3b2f88404faa210&code=${code}&needLogin=true&method=GET&abtest_cookie=&abt=&build_version=2020041712&QB&"

    val responseFuture = http
      .singleRequest(
        request = HttpRequest(
          uri = s"$urlPath",
          method = HttpMethods.GET,
          headers = Array(
            RawHeader(
              "Referer",
              s"https://game.weixin.qq.com/cgi-bin/minigame/static/channel_side/login.html?&code=${code}&state="
            )
          )
        ),
        settings = ProxySetting.proxy(system)
      )
    responseFuture.flatMap {
      case response @ HttpResponse(_, _, entity, _) =>
        try {
          Unmarshaller
            .stringUnmarshaller(entity)
            .map(item => {
              logger.info("loginauth2 response {}", item)
              item
            })
            .map(convertTo[ChannelModel.ApiScanLoginAuthResponse])
        } catch {
          case e => Future.failed(new Exception(e.getMessage))
        }

      case msg @ _ =>
        logger.error(s"请求失败 $msg")
        Future.failed(new Exception(s"请求失败 $msg"))
    }
  }

  def dataQuery(
      appid: String,
      day: Int,
      stat_type: Long,
      data_field_id: Long,
      name: String,
      out_group_id: String,
      out_channel_id: String,
      oauth_sid: String
  ): Future[ChannelModel.ApiScanGameDataResponse] = {
    val queryData = toJsonString(
      ChannelModel.ApiScanDataQuery(
        need_app_info = true,
        appid = appid,
        sequence_index_list = Array(
          ChannelModel.ApiScanQuerySequence(
            size_type = 24,
            stat_type = stat_type,
            data_field_id = data_field_id,
            requestType = "sequence",
            time_period = ChannelModel.ApiScanQueryTimePeriod(
              start_time = LocalDate
                .now(ZoneOffset.of("+8"))
                .minusDays(day)
                .atStartOfDay()
                .toEpochSecond(ZoneOffset.of("+8")),
              duration_seconds = 86400 * day
            ),
            filter_list = Array(
              ChannelModel.ApiScanQueryFilter(
                name = Option(URLEncoder.encode(name, "utf-8")),
                field_id = 5,
                value = out_channel_id
              ),
              ChannelModel.ApiScanQueryFilter(
                name = Option.empty,
                field_id = 4,
                value = out_group_id
              )
            )
          )
        )
      )
    )
    val replaceData = queryData.replaceAll(""""""", "%22")
    val urlPath =
      s"https://game.weixin.qq.com/cgi-bin/gamewxagchannelwap/getwxagstatcustomchannelsharedata?data=${replaceData}&needLogin=true&method=GET&abtest_cookie=&abt=&build_version=2020041712&QB&"

    val responseFuture = http
      .singleRequest(
        request = HttpRequest(
          uri = s"$urlPath",
          method = HttpMethods.GET,
          headers = Array(
            RawHeader(
              "referer",
              s"https://game.weixin.qq.com/cgi-bin/minigame/static/channel_side/index.html?appid=${appid}"
            ),
            RawHeader("Cookie", s"oauth_sid=${oauth_sid}")
          )
        ),
        settings = ProxySetting.proxy(system)
      )
    responseFuture.flatMap {
      case HttpResponse(_, _, entity, _) =>
        try {
          Unmarshaller
            .stringUnmarshaller(entity)
            .map(item => {
              item
            })
            .map(convertTo[ChannelModel.ApiScanGameDataResponse])
        } catch {
          case e => Future.failed(new Exception(e.getMessage))
        }
      case msg @ _ =>
        logger.error(s"请求失败 $msg")
        Future.failed(new Exception(s"请求失败 $msg"))
    }
  }

  def getsharepermuserinfo(
      appid: String,
      oauth_sid: String
  ): Future[ChannelModel.ApiScanGameUserInfo] = {
    val urlPath =
      s"https://game.weixin.qq.com/cgi-bin/gamewxagchannelwap/getsharepermuserinfo?appid=${appid}&needLogin=true&method=GET&abtest_cookie=&abt=&build_version=2020041712&QB&"

    val responseFuture = http
      .singleRequest(
        request = HttpRequest(
          uri = s"$urlPath",
          method = HttpMethods.GET,
          headers = Array(
            RawHeader(
              "referer",
              s"https://game.weixin.qq.com/cgi-bin/minigame/static/channel_side/index.html?appid=${appid}"
            ),
            RawHeader("Cookie", s"oauth_sid=${oauth_sid}")
          )
        ),
        settings = ProxySetting.proxy(system)
      )
    responseFuture.flatMap {
      case HttpResponse(_, _, entity, _) =>
        try {
          Unmarshaller
            .stringUnmarshaller(entity)
            .map(item => {
              item
            })
            .map(convertTo[ChannelModel.ApiScanGameUserInfo])
        } catch {
          case e => Future.failed(new Exception(e.getMessage))
        }

      case msg @ _ =>
        logger.error(s"请求失败 $msg")
        Future.failed(new Exception(s"请求失败 $msg"))
    }
  }
}
