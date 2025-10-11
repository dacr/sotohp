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
        MediaService.live,
        SearchService.live,
        Scope.default
      )

  val logic = ZIO.logSpan("Synchronize") {
    for {
      _     <- ZIO.logInfo("start photos synchronization and processing")
      _     <- MediaService.synchronizeStart(Some(42))
      _     <- MediaService.synchronizeWait()
      count <- MediaService.originalCount()
      _     <- ZIO.logInfo(s"$count photos synchronized")
      _     <- GoogleTakeoutTooling.logic
    } yield ()
  }
}
