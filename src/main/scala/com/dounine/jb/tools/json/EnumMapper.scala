package com.dounine.jb.tools.json

import java.sql.{Date, Timestamp}
import java.time.{LocalDate, LocalDateTime}

import com.dounine.jb.types.{UserStatus}
import com.dounine.jb.types.UserStatus.UserStatus
import slick.ast.BaseTypedType
import slick.jdbc.JdbcType
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.duration.FiniteDuration

trait EnumMapper extends BaseRouter {

  val localDateTime2timestamp
      : JdbcType[LocalDateTime] with BaseTypedType[LocalDateTime] =
    MappedColumnType.base[LocalDateTime, Timestamp](
      { instant =>
        if (instant == null) null else Timestamp.valueOf(instant)
      },
      { timestamp =>
        if (timestamp == null) null else timestamp.toLocalDateTime
      }
    )

  implicit val finiteDuration2String
      : JdbcType[FiniteDuration] with BaseTypedType[FiniteDuration] =
    MappedColumnType.base[FiniteDuration, String](
      e => e.toString,
      s => {
        val spl: Array[String] = s.split(" ")
        FiniteDuration(spl.head.toLong, spl.last)
      }
    )

  val localDate2timestamp: JdbcType[LocalDate] with BaseTypedType[LocalDate] =
    MappedColumnType.base[LocalDate, Date](
      { instant =>
        if (instant == null) null else Date.valueOf(instant)
      },
      { d =>
        if (d == null) null else d.toLocalDate
      }
    )

  implicit val userStatusMapper
      : JdbcType[UserStatus] with BaseTypedType[UserStatus] =
    MappedColumnType.base[UserStatus, String](
      e => e.toString,
      s => UserStatus.withName(s)
    )

}
