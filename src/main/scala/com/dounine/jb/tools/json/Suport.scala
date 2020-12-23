package com.dounine.jb.tools.json

import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime}

import com.dounine.jb.types._
import org.json4s.JsonAST.{JField, JLong, JObject, JString}
import org.json4s.ext.EnumNameSerializer
import org.json4s.jackson.Serialization
import org.json4s.{CustomSerializer, DefaultFormats, Formats, jackson}

import scala.concurrent.duration.FiniteDuration

object LocalDateTimeSerializer extends CustomSerializer[LocalDateTime](_ => ( {
  case JString(str) =>
    LocalDateTime.parse(str, Suport.dateTimeFormatter)
}, {
  case date: LocalDateTime => JString(date.toString)
}))

object LocalDateSerializer extends CustomSerializer[LocalDate](_ => ( {
  case JString(str) =>
    LocalDate.parse(str)
}, {
  case date: LocalDate => JString(date.toString)
}))

object FiniteDurationSerializer extends CustomSerializer[FiniteDuration](_ => ( {
  case JObject(JField("unit", JString(unit)) :: JField("length", JLong(length)) :: Nil) =>
    FiniteDuration(length.toLong, unit)
}, {
  case time: FiniteDuration =>
    JObject(
      "unit" -> JString(time._2.name()),
      "length" -> JLong(time._1.toLong)
    )
}
))

object Suport {
  val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd[' ']['T'][HH:mm[:ss[.SSS]]][X]")
  val serialization: Serialization.type = jackson.Serialization
  val formats: Formats = DefaultFormats +
    new EnumNameSerializer(MessageType) +
    new EnumNameSerializer(RouterStatus) +
    new EnumNameSerializer(UserStatus) +
    LocalDateTimeSerializer +
    LocalDateSerializer +
    FiniteDurationSerializer
}
