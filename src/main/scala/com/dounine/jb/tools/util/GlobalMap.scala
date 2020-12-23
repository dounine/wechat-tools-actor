package com.dounine.jb.tools.util

import java.util.concurrent.ConcurrentHashMap

object GlobalMap {

  private val maps: ConcurrentHashMap[String, Any] = new ConcurrentHashMap[String, Any]()

  def set(key: String, value: Any): Unit = {
    maps.put(key, value)
  }

  def get[T](key: String): T = {
    maps.get(key).asInstanceOf[T]
  }

}
