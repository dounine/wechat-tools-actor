package com.dounine.jb.tools.plugin

import slick.jdbc.MySQLProfile.api._
import slick.lifted.{Shape, TupleShape}

case class SlickUpdate[E, U, C[_]](query: Query[E, U, C], maybeUpdate: Option[SlickUpdate.Update[E]]) {
  def update[F, G, T](projection: E => F, maybeT: Option[T])(implicit shape: Shape[_ <: FlatShapeLevel, F, T, G]): SlickUpdate[E, U, C] =
    maybeT match {
      case Some(t) =>
        val u2: SlickUpdate.UpdateImpl[E, F, G, T] = SlickUpdate.UpdateImpl(projection, t)
        maybeUpdate match {
          case Some(u1) =>
            implicit val tupleShape: Shape[_ <: FlatShapeLevel, (u1.F, u2.F), (u1.T, u2.T), (u1.G, u2.G)] =
              new TupleShape(u1.shape, u2.shape)
            val tupleUpdate: SlickUpdate.UpdateImpl[E, (u1.F, F), (u1.G, G), (u1.T, T)] = SlickUpdate.UpdateImpl((e: E) => (u1.projection(e), u2.projection(e)), (u1.newValue, u2.newValue))
            SlickUpdate(query, Some(tupleUpdate))
          case None =>
            SlickUpdate(query, Some(u2))
        }
      case None =>
        this
    }

  def run(): DBIO[Int] =
    maybeUpdate match {
      case Some(update) =>
        import update.shape
        query.map(update.projection).update(update.newValue)
      case None => DBIO.successful(0)
    }
}

object SlickUpdate {

  def apply[E, U, C[_]](query: Query[E, U, C]): SlickUpdate[E, U, C] = SlickUpdate(query, None)

  trait Update[E] {
    type F
    type G
    type T

    implicit def shape: Shape[_ <: FlatShapeLevel, F, T, G]

    def projection: E => F

    def newValue: T
  }

  case class UpdateImpl[E, F0, G0, T0](projection: E => F0, newValue: T0)(implicit val shape: Shape[_ <: FlatShapeLevel, F0, T0, G0]) extends Update[E] {
    type F = F0
    type G = G0
    type T = T0
  }

}



