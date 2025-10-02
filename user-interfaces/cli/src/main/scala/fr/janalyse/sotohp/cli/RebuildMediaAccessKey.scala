package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

import java.time.temporal.ChronoUnit.{MONTHS, YEARS}
import java.time.{Instant, OffsetDateTime}
import scala.io.AnsiColor.*

object RebuildMediaAccessKey extends CommonsCLI {

  override def run =
    logic
      .provide(
        configProviderLayer >>> LMDB.live,
        configProviderLayer >>> SearchService.live,
        MediaService.live,
        Scope.default,
        configProviderLayer
      )

  // -------------------------------------------------------------------------------------------------------------------
  def rebuildAccessKey(state: State) = {
    for {
      media       <- MediaService.mediaGet(state.mediaAccessKey).some
      currentKey   = media.accessKey
      rebuiltKey   = media.rebuildAccessKey
      updatedState = state.copy(mediaAccessKey = rebuiltKey)
      updatedMedia = media.copy(accessKey = rebuiltKey)
      _           <- ZIO
                       .logInfo(s"rebuild access key for ${media.original.mediaPath.path} from ${currentKey} to ${rebuiltKey}")
                       .when(currentKey != rebuiltKey)
      _           <- (MediaService.stateUpsert(state.originalId, updatedState) *> MediaService.mediaUpdate(currentKey, updatedMedia)).uninterruptible
                       .when(currentKey != rebuiltKey)
    } yield ()
  }

  val rebuildFromStates =
    for {
      states <- MediaService.stateList().runCollect
      _      <- ZIO.foreach(states)(rebuildAccessKey)
    } yield ()

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Rebuild media access keys") {
    rebuildFromStates
  }

}
