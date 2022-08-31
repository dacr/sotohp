package fr.janalyse.sotohp.core.store

import zio.*

trait PersistenceService {}

object PersistenceService {
  val live = ZLayer.succeed(new PersistenceService {})
}
