package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import fr.janalyse.sotohp.core.LogContext
import zio.config.typesafe.*
import zio.lmdb.LMDB

import java.time.temporal.ChronoUnit.{MONTHS, YEARS}
import java.time.{Instant, OffsetDateTime}
import scala.io.AnsiColor.*

/*
 * This is a one-shot tool to fix original file path from absolute to relative to store path
 */
object FixOriginalPath extends CommonsCLI {

  override def run =
    logic
      .provide(
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  // -------------------------------------------------------------------------------------------------------------------
  def fixOriginalMediaPath(state: State) = {
    for {
      original         <- MediaService.originalGet(state.originalId).some
      currentMediaPath  = original.mediaPath
      relativeMediaPath = if (currentMediaPath.path.isAbsolute)
                            original.store.baseDirectory.path.relativize(currentMediaPath.path)
                          else currentMediaPath.path
      _                <- (ZIO.logInfo("Fix media path") @@ LogContext.original(original) @@ LogContext.kv("newMediaPath" -> relativeMediaPath))
                            .when(currentMediaPath.path.isAbsolute)
      _                <- MediaService
                            .originalUpsert(original.copy(mediaPath = OriginalPath(relativeMediaPath)))
                            .uninterruptible
                            .when(currentMediaPath.path.isAbsolute)
    } yield ()
  }

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Fix original media path from absolute to relative") {
    MediaService.stateList().runForeach(fixOriginalMediaPath)
  }

}
