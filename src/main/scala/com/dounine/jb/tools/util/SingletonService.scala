package com.dounine.jb.tools.util

import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap

import akka.actor.typed.ActorSystem
import org.slf4j.{Logger, LoggerFactory}

object SingletonService {

  private val map: ConcurrentHashMap[Any, Any] = new ConcurrentHashMap[Any, Any]()
  private val logger: Logger = LoggerFactory.getLogger(SingletonService.getClass)

  def instance[T](clazz: Class[T], system: ActorSystem[_]): T = {
    if (!map.containsKey(clazz)) {
      logger.info("{} instance", clazz)
      val cls: Constructor[_] = clazz.getConstructors.apply(0)
      val obj: Any = cls.newInstance(Seq(system): _*)
      map.put(clazz, obj)
    }
    map.get(clazz).asInstanceOf[T]
  }
}
