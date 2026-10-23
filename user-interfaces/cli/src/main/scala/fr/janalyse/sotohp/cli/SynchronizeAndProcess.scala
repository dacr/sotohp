package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.{ClassificationProcessor, FaceFeaturesProcessor, FacesProcessor, MiniaturizeProcessor, NormalizeProcessor, ObjectsDetectionProcessor}
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import fr.janalyse.sotohp.service.model.SynchronizeAction.Start
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

import java.nio.file.Files
import java.util.Comparator

object SynchronizeAndProcess extends CommonsCLI {

  override def run =
    logic
      .provide(
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  // every 30s while it runs - the remaining time is known once the scan is done, while processing the new medias
  val progress = MediaService
    .synchronizeStatus()
    .flatMap(status =>
      ZIO.logInfo("Synchronization progress") @@ LogContext.kv(
        "phase"     -> status.phase,
        "checked"   -> status.checkedCount,
        "processed" -> s"${status.processedCount}/${status.toProcessCount}",
        "remaining" -> status.remainingSeconds.map(s => Duration.fromSeconds(s).render).getOrElse("-"),
        "problems"  -> status.problemCount
      )
    )
    .repeat(Schedule.spaced(30.seconds))
    .delay(30.seconds)

  val summary = MediaService
    .synchronizeStatus()
    .flatMap(status =>
      ZIO.foreachDiscard(status.lastRun)(run =>
        ZIO.logInfo("Synchronization done") @@ LogContext.kv(
          "outcome"   -> run.outcome,
          "checked"   -> run.checkedCount,
          "processed" -> s"${run.processedCount}/${run.toProcessCount}",
          "problems"  -> run.problemCount,
          "failure"   -> run.failure.getOrElse("-")
        )
      ) *> ZIO.foreachDiscard(status.problems.reverse)(problem => ZIO.logWarning(problem.message) @@ LogContext.kv("path" -> problem.path.getOrElse("-")))
    )

  val logic = ZIO.logSpan("Synchronize") {
    for {
      _     <- ZIO.logInfo("start photos synchronization and processing")
      _     <- MediaService.synchronizeStart(Some(42))
      _     <- MediaService.synchronizeWait().raceFirst(progress *> ZIO.never)
      _     <- summary
      count <- MediaService.originalCount()
      _     <- (ZIO.logInfo("Photos synchronized") @@ LogContext.kv("count" -> count))
      _     <- GoogleTakeoutTooling.logic
    } yield ()
  }
}
