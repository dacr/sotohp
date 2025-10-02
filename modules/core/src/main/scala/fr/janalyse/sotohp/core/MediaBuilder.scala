package fr.janalyse.sotohp.core

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.{Metadata as DrewMetadata, Tag as DrewTag}
import com.drew.metadata.exif.{ExifDirectoryBase, ExifIFD0Directory, ExifSubIFDDirectory, GpsDirectory}
import com.drew.metadata.gif.GifImageDirectory
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.png.PngDirectory
import com.drew.metadata.bmp.BmpHeaderDirectory
import fr.janalyse.sotohp.model.*

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

  def buildDefaultMediaAccessKey(original: Original): MediaAccessKey = {
    //val timestamp = original.timestamp
    // TODO Migrate to something else as ULID are constrained to start from epoch ! 1947:07:01 15:00:00 +00:00 => -710154000000 for epoch millis !
    //val epoch     = if (timestamp.getYear >= 1970) Try(timestamp.toInstant.toEpochMilli).toOption.getOrElse(0L) else 0L
    //val ulid      = ULID.ofMillis(epoch)
    MediaAccessKey(original.timestamp, original.id.asUUID)
  }

  def buildEventAttachment(original: Original): Option[EventAttachment] = buildEventAttachment(original.store, original.mediaPath)

  def buildEventAttachment(store: Store, originalMediaPath: OriginalPath): Option[EventAttachment] = {
    val relativeDirectory = Option(originalMediaPath.parent).map { photoParentDir =>
      store.baseDirectory.path.relativize(photoParentDir)
    }

    relativeDirectory.map(dir => EventAttachment(store, EventMediaDirectory(dir))).filter(_.eventMediaDirectory.path.toString.nonEmpty)
  }

  def buildDefaultMediaEvent(original: Original): Option[Event] = buildDefaultMediaEvent(original.store, original.mediaPath, Some(original))

  def buildDefaultMediaEvent(store: Store, originalMediaPath: OriginalPath, mayBeOriginal: Option[Original]): Option[Event] = {
    val eventId         = EventId(UUID.randomUUID())
    val eventAttachment = buildEventAttachment(store, originalMediaPath)
    val eventName       = eventAttachment.map(_.eventMediaDirectory.toString)

    eventName
      .filter(_.nonEmpty)
      .map(name =>
        Event(
          id = eventId,
          attachment = eventAttachment,
          name = EventName(name),
          description = None,
          location = mayBeOriginal.flatMap(_.location),
          timestamp = mayBeOriginal.flatMap(_.cameraShootDateTime),
          originalId = mayBeOriginal.map(_.id),
          keywords = Set.empty
        )
      )
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
  ): Either[CoreIssue, Media] = Right {
    val mediaAccessKey = buildDefaultMediaAccessKey(original)
    Media(
      accessKey = mediaAccessKey,
      original = original,
      events = knownEvent.toList,
      description = None,
      starred = Starred(false),
      keywords = Set.empty,
      orientation = None,
      shootDateTime = None,
      userDefinedLocation = None,
      deductedLocation = None
    )
  }

}
