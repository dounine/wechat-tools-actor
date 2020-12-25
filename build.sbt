val akkaVersion = "2.6.10"
val akkaHttpVersion = "10.2.1"
val json4sVersion = "3.7.0-M6"

resolvers += "Local Maven Repository" at "file://" + Path.userHome.absolutePath + "/.m2/repository"

lazy val app = (project in file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name := "wechat-tools-actor",
    organization := "com.dounine",
    maintainer := "102535481@qq.com",
    scalaVersion := "2.13.3",
    dockerExposedPorts := Seq(50001),
    dockerEntrypoint := Seq("/opt/docker/bin/bootstrap"),
    dockerBaseImage := "openjdk:11.0.8-slim",
    mainClass in (Compile, run) := Some("com.dounine.Bootstrap"),
    mappings in Universal ++= {
      import NativePackagerHelper._
      val confFile = buildEnv.value match {
        case BuildEnv.Stage        => "stage"
        case BuildEnv.Developement => "dev"
        case BuildEnv.Test         => "test"
        case BuildEnv.Production   => "prod"
      }
      contentOf("src/main/resources/" + confFile)
    },
    libraryDependencies ++= Seq(
      "de.heikoseeberger" %% "akka-http-circe" % "1.32.0",
      "ch.qos.logback" % "logback-classic" % "1.2.3",
      "com.github.pureconfig" %% "pureconfig" % "0.12.3",
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-metrics" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-query" % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
      "com.lightbend.akka" %% "akka-persistence-jdbc" % "4.0.0",
      "com.typesafe.akka" %% "akka-discovery" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-tools" % akkaVersion,
      "org.json4s" %% "json4s-jackson" % json4sVersion,
      "org.json4s" %% "json4s-native" % json4sVersion,
      "org.json4s" %% "json4s-ext" % json4sVersion,
      "net.lightbody.bmp" % "browsermob-proxy" % "2.1.5" pomOnly (),
      "org.seleniumhq.selenium" % "selenium-java" % "4.0.0-alpha-7",
      "org.apache.poi" % "poi" % "4.1.2",
      "org.apache.poi" % "poi-ooxml" % "4.1.2",
      "net.coobird" % "thumbnailator" % "0.4.13",
      "com.lightbend.akka.management" %% "akka-lease-kubernetes" % "1.0.9",
      "com.lightbend.akka.management" %% "akka-management-cluster-http" % "1.0.9",
      "com.lightbend.akka.discovery" %% "akka-discovery-kubernetes-api" % "1.0.9",
      "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % "1.0.9",
      "com.chuusai" %% "shapeless" % "2.3.3",
      "io.underscore" %% "slickless" % "0.3.6",
      "io.altoo" %% "akka-kryo-serialization" % "1.1.5",
      "com.typesafe.akka" %% "akka-http-spray-json" % "10.2.1",
      "de.heikoseeberger" %% "akka-http-circe" % "1.35.2",
      "commons-io" % "commons-io" % "2.8.0",
      "de.heikoseeberger" %% "akka-http-json4s" % "1.35.2",
      "com.esotericsoftware" % "kryo" % "5.0.0",
      "mysql" % "mysql-connector-java" % "8.0.22",
      "commons-codec" % "commons-codec" % "1.15",
      "commons-cli" % "commons-cli" % "1.4",
      "redis.clients" % "jedis" % "3.3.0",
      "com.typesafe.slick" %% "slick-hikaricp" % "3.3.3",
      "com.typesafe.slick" %% "slick-codegen" % "3.3.3",
      "io.spray" %% "spray-json" % "1.3.6",
      "com.pauldijou" %% "jwt-core" % "4.3.0",
      "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
      "com.typesafe.scala-logging" %% "scala-logging" % "3.9.2",
      "org.scalatest" %% "scalatest" % "3.1.2" % Test
    )
  )
