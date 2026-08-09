package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.{MediaService, MediaTuple, ServiceIssue}
import wvlet.airframe.ulid.ULID
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB
import zio.stream.ZStream

import java.time.temporal.ChronoUnit.{MONTHS, YEARS}
import java.time.{Instant, OffsetDateTime}
import scala.io.AnsiColor.*

/*
 * Search for photos containing the specified people.
 *
 * Each argument identifies a person, either by:
 *   - its raw PersonId (a ULID), or
 *   - its name using the "Firstname:Lastname" form (case-insensitive), which is
 *     resolved to the corresponding PersonId.
 *
 * Options:
 *   - `--check` / `--dry-run` / `-n`   only report the matching media, without copying anything.
 *   - `--contains` / `--any`           keep any photo containing all the given people (default: only
 *                                      photos where the given people are the sole people in the frame).
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

  def matchesPeople(peopleToLookFor: Set[PersonId], containsMode: Boolean)(mediaTuple: MediaTuple): ZIO[MediaService, ServiceIssue, Boolean] = {
    MediaService
      .originalFaces(mediaTuple.media.original.id)
      .map(_.map(_.faces).getOrElse(Nil))
      .map { faces =>
        val allIdentifiedPersonId = faces.flatMap(_.identifiedPersonId).toSet
        if (containsMode)
          peopleToLookFor.subsetOf(allIdentifiedPersonId) // photo contains all the given people (anyone else is allowed)
        else
          //faces.size == allIdentifiedPersonId.size &&           // All face are identified
          allIdentifiedPersonId.size == peopleToLookFor.size && // only the right number of people
          allIdentifiedPersonId.forall(personId => peopleToLookFor.contains(personId)) // found all given people and no one else
      }
  }

  def searchForPeople(peopleToLookFor: Set[PersonId], containsMode: Boolean): ZStream[MediaService, Exception, MediaTuple] = {
    MediaService
      .mediaList()
      .filterZIO(mediaTuple => matchesPeople(peopleToLookFor, containsMode)(mediaTuple))
      .tap(mediaTuple => ZIO.logInfo(s"Found media with specified people : ${mediaTuple.media.original.mediaPath.path}"))
  }

  def FindPeopleAndCheck(peopleToLookFor: Set[PersonId], containsMode: Boolean) = {
    searchForPeople(peopleToLookFor, containsMode).runCount
      .flatMap(count => ZIO.logInfo(s"Found $count media matching the specified people (check only, nothing copied)"))
  }

  /** Sanitize a bag name so it can be used as a file name prefix : lower-cased, and every character which is not a letter or a digit is replaced by a minus. */
  def sanitizeForFileName(input: String): String =
    input.toLowerCase.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "")

  def FindPeopleAndCopy(peopleToLookFor: Set[PersonId], containsMode: Boolean) = {
    val timestamp = java.time.format.DateTimeFormatter
      .ofPattern("yyyyMMdd'T'HHmmss")
      .format(java.time.LocalDateTime.now())
    val targetDir = java.nio.file.Paths.get("out", s"searched-people-results-$timestamp")
    ZIO.attemptBlocking(java.nio.file.Files.createDirectories(targetDir)) *>
      searchForPeople(peopleToLookFor, containsMode)
        .foreach(mediaTuple =>
          ZIO.attemptBlocking {
            val source        = mediaTuple.media.original.absoluteMediaPath
            val bagPrefix      = mediaTuple.media.bag.map(bag => s"${sanitizeForFileName(bag.name.text)}-").getOrElse("")
            val destination   = targetDir.resolve(s"$bagPrefix${source.getFileName}")
            java.nio.file.Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
          }
        )
  }

  // -------------------------------------------------------------------------------------------------------------------

  /** Resolve a "Firstname:Lastname" argument to a PersonId by looking it up in the known people. */
  def resolvePersonByName(firstName: String, lastName: String): ZIO[MediaService, Exception, PersonId] = {
    val wantedFirst = firstName.trim.toLowerCase
    val wantedLast  = lastName.trim.toLowerCase
    MediaService
      .personList()
      .filter(person => person.firstName.text.toLowerCase == wantedFirst && person.lastName.text.toLowerCase == wantedLast)
      .runCollect
      .flatMap {
        case matches if matches.isEmpty     =>
          ZIO.fail(new IllegalArgumentException(s"No person found with name $firstName $lastName"))
        case matches if matches.size > 1    =>
          ZIO.fail(new IllegalArgumentException(s"Ambiguous name $firstName $lastName matches ${matches.size} people: ${matches.map(_.id).mkString(", ")}"))
        case matches                        =>
          ZIO.succeed(matches.head.id)
      }
  }

  /** Turn a single CLI argument into a PersonId, accepting either a raw ULID or a "Firstname:Lastname" name. */
  def resolvePersonArg(arg: String): ZIO[MediaService, Exception, PersonId] = {
    arg.split(":", 2) match {
      case Array(firstName, lastName) => resolvePersonByName(firstName, lastName)
      case _                          => ZIO.attempt(PersonId(ULID(arg.trim.toUpperCase))).refineToOrDie[Exception]
    }
  }

  private val checkOnlyFlags = Set("--check", "--dry-run", "-n")
  private val containsFlags   = Set("--contains", "--any")

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Search for some people in photos") {
    for {
      allGivenArgs        <- getArgs
      (flags, personArgs)  = allGivenArgs.partition(arg => arg.startsWith("-"))
      checkOnly            = flags.exists(flag => checkOnlyFlags.contains(flag))
      containsMode         = flags.exists(flag => containsFlags.contains(flag))
      allGivenPersonId    <- ZIO.foreach(personArgs)(resolvePersonArg)
      peopleToLookFor      = allGivenPersonId.toSet
      _                   <- ZIO.when(checkOnly)(FindPeopleAndCheck(peopleToLookFor, containsMode))
      _                   <- ZIO.unless(checkOnly)(FindPeopleAndCopy(peopleToLookFor, containsMode))
    } yield ()
  }

}
