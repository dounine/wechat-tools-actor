package com.dounine.jb.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.{Http, HttpExt}
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Flow, Source}
import akka.util.ByteString
import com.dounine.jb.model.ChannelModel
import com.dounine.jb.service.ChannelStream.uuid
import com.dounine.jb.tools.akka.ProxySetting
import com.dounine.jb.tools.json.BaseRouter
import org.slf4j.LoggerFactory

import java.io.File
import scala.concurrent.Future

object ChannelStream extends BaseRouter {

  private val logger = LoggerFactory.getLogger(ChannelStream.getClass)

  def uuid(system: ActorSystem[_]): Source[String, NotUsed] = Source.future({
    implicit val ec = system.executionContext
    implicit val materializer = Materializer(system)
    val http: HttpExt = Http(system)
    val urlPath =
      "https://open.weixin.qq.com/connect/qrconnect?appid=wxd3b2f88404faa210&scope=snsapi_login&redirect_uri=https%3A%2F%2Fgame.weixin.qq.com%2Fcgi-bin%2Fminigame%2Fstatic%2Fchannel_side%2Flogin.html%3F&state=&login_type=jssdk&self_redirect=default&styletype=&sizetype=&bgcolor=&rst"
    http
      .singleRequest(
        request = HttpRequest(
          uri = s"$urlPath",
          method = HttpMethods.GET
        ),
        settings = ProxySetting.proxy(system)
      )
      .flatMap {
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
              .map(_.split("""src="""").last.split(""""""").head)
          } catch {
            case e => throw new Exception(e.getMessage)
          }
        case msg @ _ =>
          throw new Exception(s"请求失败 $msg")
      }
  })

  def downloadQrcodeFlow(
      system: ActorSystem[_]
  ): Flow[String, Either[Throwable, File], NotUsed] = {
    val http = Http(system)
    implicit val ec = system.executionContext
    implicit val materializer = Materializer(system)
    Flow[String].mapAsync(1) { qrcode =>
      {
        logger.info("qrcode path -> {}", qrcode)
        http
          .singleRequest(
            request = HttpRequest(
              uri = s"https://open.weixin.qq.com${qrcode}",
              method = HttpMethods.GET
            ),
            settings = ProxySetting.proxy(system)
          )
          .flatMap {
            case HttpResponse(_, _, entity, _) =>
              val file = File.createTempFile("qrcode", ".png")
              entity.dataBytes
                .runWith(FileIO.toPath(file.toPath))
                .map(_ => Right(file))
            case msg @ _ =>
              Future.successful(Left(new Exception(msg.toString())))
          }
      }
    }
  }

  type OauthSid = String
  def oauthSidQuery(
      system: ActorSystem[_]
  ): Flow[String, Either[Throwable, OauthSid], NotUsed] = {
    implicit val ec = system.executionContext
    implicit val materializer = Materializer(system)
    val http = Http(system)

    Flow[String]
      .mapAsync(1) { wxCode =>
        {
          logger.info("loginauth2 wxCode -> {}", wxCode)
          val urlPath =
            s"https://game.weixin.qq.com/cgi-bin/gamebizauthwap/loginoauth2?appid=wxd3b2f88404faa210&code=${wxCode}&needLogin=true&method=GET&abtest_cookie=&abt=&build_version=2020041712&QB&"
          http
            .singleRequest(
              request = HttpRequest(
                uri = s"$urlPath",
                method = HttpMethods.GET,
                headers = Array(
                  RawHeader(
                    "Referer",
                    s"https://game.weixin.qq.com/cgi-bin/minigame/static/channel_side/login.html?&code=${wxCode}&state="
                  )
                )
              ),
              settings = ProxySetting.proxy(system)
            )
            .flatMap {
              case response @ HttpResponse(_, _, entity, _) =>
                try {
                  Unmarshaller
                    .stringUnmarshaller(entity)
                    .map(convertTo[ChannelModel.ApiScanLoginAuthResponse])
                    .map(_.data.oauth_sid)
                    .map(sid => {
                      logger.info("oauthSidQuery sid -> {}", sid)
                      sid
                    })
                    .map(Right.apply)
                } catch {
                  case e => throw new Exception(e.getMessage)
                }
              case msg @ _ =>
                throw new Exception(s"请求失败 $msg")
            }
            .recover { case e =>
              Left(e)
            }
        }
      }
  }

  type WxCode = String
  def qrcodeStatusQuery(
      system: ActorSystem[_]
  ): Flow[String, Either[Throwable, WxCode], NotUsed] = {
    implicit val ec = system.executionContext
    implicit val materializer = Materializer(system)
    val http = Http(system)
    Flow[String]
      .map(_.split("/").last)
      .mapAsync(1) { uuid =>
        {
          println("statusQuery uuid -> {}", uuid)
          http
            .singleRequest(
              request = HttpRequest(
                uri =
                  s"https://lp.open.weixin.qq.com/connect/l/qrconnect?uuid=${uuid}&last=404&_=${System
                    .currentTimeMillis()}",
                method = HttpMethods.GET
              ),
              settings = ProxySetting.proxy(system)
            )
            .flatMap {
              case HttpResponse(_, _, entity, _) =>
                {
                  entity
                    .dataBytes
                    .runFold(ByteString.empty)(_++_)
                    .map(_.utf8String)
                    .map(html => {
                      println("statusQuery response")
                      if (html.contains("window.wx_errcode=405")) {
                        Right(html.split("window.wx_code=").last.split("'")(1))
                      } else {
                        throw new Exception("not login")
                      }
                    })
                }
              case msg @ _ =>
                logger.error(s"请求失败 $msg")
                throw new Exception(s"请求失败 $msg")
            }
            .recover { case e =>
              Left(e)
            }
        }
      }
  }

}
