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

object StoreEventFix extends CommonsCLI {

  override def run =
    logic
      .provide(
        configProviderLayer >>> LMDB.live,
        configProviderLayer >>> SearchService.live,
        MediaService.live,
        Scope.default,
        configProviderLayer
      )

  def fixEvent(event: Event) = {
    // will be slow but I don't care as it is a one-shot fix
    for {
      medias       <- MediaService.mediaList().filter(_.events.exists(_.id == event.id)).runCollect
      selectedMedia = medias.find(_.original.hasLocation).orElse(medias.headOption)
      _            <- MediaService
                        .eventUpdate(
                          eventId = event.id,
                          name = event.name,
                          description = event.description,
                          location = selectedMedia.flatMap(_.original.location),
                          timestamp = selectedMedia.flatMap(_.original.cameraShootDateTime),
                          coverOriginalId = selectedMedia.map(_.original.id),
                          keywords = event.keywords
                        )
                        .when(selectedMedia.isDefined)
      _            <- ZIO.logInfo(s"fixing event ${event.id} ${event.name}").when(selectedMedia.isDefined)
    } yield ()
  }

  val logic = ZIO.logSpan("fix missing information in already existing events") {
    val eventsStream = MediaService.eventList()
    for {
      events <- eventsStream
                  .filter(_.attachment.isDefined)
                  .filter(_.originalId.isEmpty)
                  .runCollect
      _      <- ZIO.foreachDiscard(events)(fixEvent)
    } yield ()
  }
}
