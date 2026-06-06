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

//  def buildDefaultMediaAccessKey(original: Original): MediaAccessKey = {
//    MediaAccessKey(original.timestamp, original.id.asUUID)
//  }
//
//  def buildDefaultMediaAccessKey(original: Original, bag: Option[Bag]): MediaAccessKey = {
//    val timestamp =
//      original.cameraShootDateTime // 1. if camera shot date time is known
//        .orElse(bag.flatMap(_.timestamp)) // 2. if the bag timestamp is known (because a default bag already exists and has a timestamp)
//        .map(_.offsetDateTime)
//        .getOrElse(original.fileLastModified.offsetDateTime) // 3. default will go to file last modified date time
//    MediaAccessKey(timestamp, original.id.asUUID)
//  }

  def buildBagAttachment(original: Original): Option[BagAttachment] = buildBagAttachment(original.store, original.mediaPath)

  def buildBagAttachment(store: Store, originalMediaPath: OriginalPath): Option[BagAttachment] = {
    val relativeDirectory = Option(originalMediaPath.parent).filter(_ != null)

    relativeDirectory.map(dir => BagAttachment(store, BagMediaDirectory(dir))).filter(_.bagMediaDirectory.path.toString.nonEmpty)
  }

  def buildDefaultMediaBag(original: Original): Option[Bag] = buildDefaultMediaBag(original.store, original.mediaPath, Some(original))

  def buildDefaultMediaBag(store: Store, originalMediaPath: OriginalPath, mayBeOriginal: Option[Original]): Option[Bag] = {
    val bagId         = BagId(UUID.randomUUID())
    val bagAttachment = buildBagAttachment(store, originalMediaPath)
    val bagName       = bagAttachment.map(_.bagMediaDirectory.toString)

    for {
      attachment <- bagAttachment
      name       <- bagName.filter(_.nonEmpty)
    } yield Bag(
      id = bagId,
      attachment = attachment,
      name = BagName(name),
      description = None,
      location = mayBeOriginal.flatMap(_.location),
      timestamp = mayBeOriginal.flatMap(_.cameraShootDateTime),
      originalId = mayBeOriginal.map(_.id),
      publishedOn = None,
      keywords = Set.empty
    )
  }

  /** Generates a `Media` object from an `Original` object by computing its properties such as timestamp, media access key, bag, and media kind.
    *
    * @param original
    *   the `Original` object containing the base information about the media
    * @param knownBag
    *   bag to which this media belongs to
    * @return
    *   an `Either`, where the left side contains a `CoreIssue` if an error occurred during processing, and the right side contains a constructed `Media` object if successful
    */

  def mediaFromOriginal(
    original: Original,
    knownBag: Option[Bag]
  ): Either[CoreIssue, Media] = Right {
    //val mediaAccessKey = buildDefaultMediaAccessKey(original)
    Media(
      original = original,
      bag = knownBag,
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
