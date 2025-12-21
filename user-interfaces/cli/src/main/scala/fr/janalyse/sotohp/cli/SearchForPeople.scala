package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.processor.model.{FaceId, PersonId}
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.{MediaService, ServiceIssue}
import wvlet.airframe.ulid.ULID
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB
import zio.stream.ZStream

import java.time.temporal.ChronoUnit.{MONTHS, YEARS}
import java.time.{Instant, OffsetDateTime}
import scala.io.AnsiColor.*

/*
 * Search for photos with only specified people in it
 */
object SearchForPeople extends CommonsCLI {

  override def run =
    logic
      .provideSome[ZIOAppArgs](
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default,
      )

  // -------------------------------------------------------------------------------------------------------------------

  def areAllIn(peopleToLookFor: Set[PersonId])(media: Media): ZIO[MediaService, ServiceIssue, Boolean] = {
    MediaService
      .originalFaces(media.original.id)
      .map(_.map(_.faces).getOrElse(Nil))
      .map { faces =>
        val allIdentifiedPersonId = faces.flatMap(_.identifiedPersonId).toSet
        //faces.size == allIdentifiedPersonId.size &&           // All face are identified
        allIdentifiedPersonId.size == peopleToLookFor.size && // only the right number of people
        allIdentifiedPersonId.forall(personId => peopleToLookFor.contains(personId)) // found all given people
      }
  }

  def searchForPeople(peopleToLookFor: Set[PersonId]): ZStream[MediaService, Exception, Media] = {
    MediaService
      .mediaList()
      .filterZIO(media => areAllIn(peopleToLookFor)(media))
      .tap(media => ZIO.logInfo(s"Found media with specified people : ${media.original.mediaPath.path}"))
  }

  def FindPeopleAndCopy(peopleToLookFor: Set[PersonId]) = {
    val timestamp = java.time.format.DateTimeFormatter
      .ofPattern("yyyyMMdd'T'HHmmss")
      .format(java.time.LocalDateTime.now())
    val targetDir = java.nio.file.Paths.get("out", s"searched-people-results-$timestamp")
    ZIO.attemptBlocking(java.nio.file.Files.createDirectories(targetDir)) *>
      searchForPeople(peopleToLookFor)
        .foreach(media =>
          ZIO.attemptBlocking {
            val source      = media.original.absoluteMediaPath
            val destination = targetDir.resolve(source.getFileName)
            java.nio.file.Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
          }
        )
  }

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Search for some people in photos and no one else") {
    for {
      allGivenRawId    <- getArgs
      allGivenPersonId <- ZIO.foreach(allGivenRawId)(rid => ZIO.attempt(PersonId(ULID(rid.toUpperCase))))
      _                <- FindPeopleAndCopy(allGivenPersonId.toSet)
    } yield ()
  }

}
