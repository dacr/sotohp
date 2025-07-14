package fr.janalyse.sotohp.media.core

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.{Metadata as DrewMetadata, Tag as DrewTag}
import com.drew.metadata.exif.{ExifDirectoryBase, ExifIFD0Directory, ExifSubIFDDirectory, GpsDirectory}
import com.drew.metadata.gif.GifImageDirectory
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.png.PngDirectory
import com.drew.metadata.bmp.BmpHeaderDirectory
import fr.janalyse.sotohp.media.model.DecimalDegrees.*
import fr.janalyse.sotohp.media.model.*
import fr.janalyse.sotohp.media.core.HashOperations

import java.nio.file.Path
import java.time.{Instant, OffsetDateTime, ZoneId, ZoneOffset}
import scala.jdk.CollectionConverters.*
import com.fasterxml.uuid.Generators
import wvlet.airframe.ulid.ULID

import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.util.{Failure, Success, Try}

object MediaBuilder {
  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[MediaBuilder.type])

  private def buildDefaultMediaAccessKey(timestamp: OffsetDateTime): MediaAccessKey = {
    val ulid = ULID.ofMillis(timestamp.toInstant.toEpochMilli)
    MediaAccessKey(ulid)
  }

  private def computeMediaTimestamp(original: Original): Either[OriginalFileIssue, OffsetDateTime] = {
    val sdt = original.cameraShootDateTime.filter(_.year >= 1990) // TODO - Add rule/config to control shootDataTime validity !
    sdt match {
      case Some(shootDateTime) => Right(shootDateTime.offsetDateTime)
      case _                   => Right(original.fileLastModified.offsetDateTime)
    }
  }

  def buildMediaEvent(original: Original): Option[Event] = buildMediaEvent(original.baseDirectory, original.mediaPath, original.ownerId)

  def buildMediaEvent(originalBaseDirectory: BaseDirectoryPath, originalMediaPath: OriginalPath, ownerId: OwnerId): Option[Event] = {
    val eventId        = EventId(UUID.randomUUID())
    val eventName      = Option(originalMediaPath.parent).map { photoParentDir =>
      originalBaseDirectory.path.relativize(photoParentDir).toString
    }
    val mediaDirectory = EventMediaDirectory(originalMediaPath.parent)
    eventName
      .filter(_.nonEmpty)
      .map(name => Event(id = eventId, ownerId = ownerId, mediaDirectory = mediaDirectory, name = EventName(name), description = None, keywords = Set.empty))
  }

  private val VideoExtensionsRE = """(?i)^(mp4|mov|avi|mkv|wmv|mpg|mpeg)$""".r
  private val PhotoExtensionsRE = """(?i)^(jpg|jpeg|png|gif|bmp|tif|tiff|ico|heif|heic)$""".r

  def computeMediaKind(original: Original): Either[CoreIssue, MediaKind] = {
    val ext = original.mediaPath.extension
    ext match {
      case VideoExtensionsRE(_) => Right(MediaKind.Video)
      case PhotoExtensionsRE(_) => Right(MediaKind.Photo)
      case _                    => Left(OriginalFileIssue(s"Unsupported file extension $ext", original.mediaPath.path, new Exception("Unsupported file extension")))
    }
  }

  /** Generates a `Media` object from an `Original` object by computing its properties such as timestamp, media access key, event, and media kind.
    *
    * @param original
    *   the `Original` object containing the base information about the media
    * @param knownEvent
    *   event to which this media belongs to
    * @return
    *   an `Either`, where the left side contains a `CoreIssue` if an error occurred during processing, and the right side contains a constructed `Media` object if successful
    */

  def mediaFromOriginal(
    original: Original,
    knownEvent: Option[Event]
  ): Either[CoreIssue, Media] = {
    for {
      timestamp     <- computeMediaTimestamp(original)
      mediaAccessKey = buildDefaultMediaAccessKey(timestamp)
      kind          <- computeMediaKind(original)
    } yield Media(
      accessKey = mediaAccessKey,
      kind = kind,
      original = original,
      event = knownEvent,
      description = None,
      starred = Starred(false),
      keywords = Set.empty,
      orientation = None,
      shootDateTime = None,
      location = None
    )
  }

}
