package com.dounine.jb.router.controller

import java.util.concurrent.TimeUnit

import akka.actor.typed.ActorSystem
import akka.cluster.{Cluster, MemberStatus}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.dounine.jb.tools.json.BaseRouter

class HealthRouter(system: ActorSystem[_]) extends BaseRouter {
  val cluster: Cluster = Cluster.get(system)

  val routeFragment: Route =
    get {
      path("ready") {
        TimeUnit.SECONDS.sleep(20)
        ok
      } ~ path("alive") {
        if (cluster.selfMember.status == MemberStatus.Up || cluster.selfMember.status == MemberStatus.WeaklyUp)
          ok
        else
          complete(StatusCodes.NotFound)
      }
    }
}
