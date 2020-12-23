package com.dounine.jb.tools.util

import java.time.{Duration, Instant, LocalDateTime, ZoneId}

object TimeUtil {

  def displayTime(begin: LocalDateTime, end: LocalDateTime): String = {
    val ds: Duration = Duration.between(begin, end)
    if (ds.toDays > 0) {
      s"${ds.toDays} 天"
    } else if (ds.toHours > 0) {
      s"${ds.toHours} 小时"
    } else if (ds.toMinutes > 0) {
      s"${ds.toMinutes} 分钟"
    } else if (ds.getSeconds > 0) {
      s"${ds.getSeconds} 秒"
    } else {
      s"${ds.toMillis} 毫秒"
    }
  }

  implicit class LongToLDT(time: Long) {

    def toLocalDateTime: LocalDateTime = {
      Instant.ofEpochMilli(time)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime
    }
  }

}
