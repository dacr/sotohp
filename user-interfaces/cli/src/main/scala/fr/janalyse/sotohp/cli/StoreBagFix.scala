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

object StoreBagFix extends CommonsCLI {

  override def run =
    logic
      .provide(
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  def fixBag(bag: Bag) = {
    // will be slow but I don't care as it is a one-shot fix
    for {
      medias       <- MediaService
                        .mediaList()
                        .filter(_.media.bag.exists(_.id == bag.id))
                        .runCollect
      selectedMedia = medias.find(_.media.original.hasLocation).orElse(medias.headOption)
      _            <- MediaService
                        .bagUpdate(
                          bagId = bag.id,
                          name = bag.name,
                          description = bag.description,
                          location = selectedMedia.flatMap(_.media.original.location),
                          timestamp = selectedMedia.flatMap(_.media.original.cameraShootDateTime),
                          coverOriginalId = selectedMedia.map(_.media.original.id),
                          publishedOn = bag.publishedOn,
                          keywords = bag.keywords
                        )
                        .when(selectedMedia.isDefined)
      _            <- ZIO.logInfo(s"fixing bag ${bag.id} ${bag.name}").when(selectedMedia.isDefined)
    } yield ()
  }

  val logic = ZIO.logSpan("fix missing information in already existing bags") {
    val bagsStream = MediaService.bagList()
    for {
      bags <- bagsStream
                .filter(_.originalId.isEmpty)
                .runCollect
      _    <- ZIO.foreachDiscard(bags)(fixBag)
    } yield ()
  }
}
