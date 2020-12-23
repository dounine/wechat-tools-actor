package com.dounine.jb.service

import akka.actor.typed.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import com.dounine.jb.store.{AkkaPersistenerJournalTable, AkkaPersistenerSnapshotTable}
import com.dounine.jb.tools.db.DataSource
import com.dounine.jb.tools.json.BaseRouter
import slick.jdbc.MySQLProfile
import slick.jdbc.MySQLProfile.api._

import scala.concurrent.{ExecutionContextExecutor, Future}

class AkkaPersistenerService(system: ActorSystem[_]) extends BaseRouter {

  private implicit val ec: ExecutionContextExecutor = system.executionContext
  private val db: MySQLProfile.backend.DatabaseDef = DataSource.db
  private val journalDict: TableQuery[AkkaPersistenerJournalTable] = TableQuery[AkkaPersistenerJournalTable]
  private val snapshotDict: TableQuery[AkkaPersistenerSnapshotTable] = TableQuery[AkkaPersistenerSnapshotTable]


  def deleteAll(): Future[Int] = {
    db.run(DBIO.sequence(Seq(journalDict
      .filter(_.ordering >= 0L)
      .delete, snapshotDict.filter(_.sequence_number >= 0L).delete)))
      .map(_.sum)
  }

}
