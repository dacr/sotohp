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
import scala.util.{Failure, Success, Try}

trait MediaIssue                                                                     extends Exception {
  val message: String
  val filePath: Path
}
case class MediaFileIssue(message: String, filePath: Path, throwable: Throwable)     extends Exception(message, throwable) with MediaIssue
case class MediaInternalIssue(message: String, filePath: Path, throwable: Throwable) extends Exception(message, throwable) with MediaIssue

class MediaOperations

object MediaOperations {
  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[MediaOperations.type])

  private val nameBaseUUIDGenerator = Generators.nameBasedGenerator()

  def readDrewMetadata(mediaPath: OriginalPath): Either[MediaFileIssue, DrewMetadata] = {
    Try(ImageMetadataReader.readMetadata(mediaPath.file)) match {
      case Failure(exception) => Left(MediaFileIssue(s"Couldn't read image meta data in file", mediaPath.path, exception))
      case Success(value)     => Right(value)
    }
  }

  def buildOriginalId(baseDirectory: BaseDirectoryPath, mediaPath: OriginalPath, owner: Owner): OriginalId = {
    // Using photo relative file path and owner id for photo identifier generation
    // as the same photo can be used within several directories or people
    val relativePath = baseDirectory.path.relativize(mediaPath.path)
    val key          = s"${owner.id}:$relativePath"
    val uuid         = nameBaseUUIDGenerator.generate(key)
    OriginalId(uuid)
  }

  def buildDefaultMediaAccessKey(timestamp: OffsetDateTime): MediaAccessKey = {
    val ulid = ULID.ofMillis(timestamp.toInstant.toEpochMilli)
    MediaAccessKey(ulid)
  }

  def buildMediaEvent(baseDirectory: BaseDirectoryPath, originalPath: OriginalPath): Option[Event] = {
    val eventName = Option(originalPath.parent).map { photoParentDir =>
      baseDirectory.path.relativize(photoParentDir).toString
    }
    eventName
      .filter(_.nonEmpty)
      .map(name => Event(name = EventName(name), description = None, keywords = Set.empty))
  }

  def getOriginalFileSize(mediaPath: OriginalPath): Either[MediaFileIssue, FileSize] = {
    Try(mediaPath.file.length()) match {
      case Failure(exception) => Left(MediaFileIssue(s"Unable to get file size", mediaPath.path, exception))
      case Success(value)     => Right(FileSize(value))
    }
  }

  def getOriginalFileLastModified(mediaPath: OriginalPath): Either[MediaFileIssue, OffsetDateTime] = {
    val tried = Try(mediaPath.file.lastModified())
      .map(Instant.ofEpochMilli)
      .flatMap(instant => Try(instant.atZone(ZoneId.systemDefault()).toOffsetDateTime))
    tried match {
      case Failure(exception) => Left(MediaFileIssue(s"Unable to get file last modified", mediaPath.path, exception))
      case Success(value)     => Right(value)
    }
  }

  def getOriginalFileHash(mediaPath: OriginalPath): Either[MediaIssue, FileHash] = {
    HashOperations.fileDigest(mediaPath.path) match {
      case Left(error)  => Left(MediaFileIssue(s"Unable to compute file hash", mediaPath.path, error))
      case Right(value) => Right(FileHash(value))
    }
  }

  def computeMediaTimestamp(original: Original): Either[MediaFileIssue, OffsetDateTime] = {
    val sdt = original.cameraShootDateTime.filter(_.year >= 1990) // TODO - Add rule/config to control shootDataTime validity !
    sdt match {
      case Some(shootDateTime) => Right(shootDateTime.offsetDateTime)
      case _                   => getOriginalFileLastModified(original.mediaPath)

    }
  }

  private def buildGenericTagKey(tag: com.drew.metadata.Tag): String = {
    val prefix = tag.getDirectoryName().trim.replaceAll("""[^a-zA-Z0-9]+""", "")
    val name   = tag.getTagName().trim.replaceAll("""[^a-zA-Z0-9]+""", "")
    s"${prefix}_$name"
  }

  private def metadataTagsToGenericTags(tags: List[com.drew.metadata.Tag]): Map[String, String] = {
    tags
      .filter(_.hasTagName)
      .filter(_.getDescription != null)
      .map(tag => buildGenericTagKey(tag) -> tag.getDescription)
      .toMap
  }

  private def extractGenericTags(metadata: DrewMetadata): Map[String, String] = {
    val metaDirectories = metadata.getDirectories.asScala
    val metaDataTags    = metaDirectories.flatMap(dir => dir.getTags.asScala).toList
    metadataTagsToGenericTags(metaDataTags)
  }

  private val exifDateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss [XXX][XX][X]")

  def parseExifDateTimeFormat(spec: String, timeZoneOffsetSpec: String): OffsetDateTime = {
    OffsetDateTime.parse(s"$spec $timeZoneOffsetSpec", exifDateTimeFormat)
  }

  def extractShootDateTime(metadata: DrewMetadata): Option[ShootDateTime] = {
    val result = Try {
      for {
        exif              <- Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
        if exif.containsTag(ExifDirectoryBase.TAG_DATETIME)
        exifSubIFD         = Option(metadata.getFirstDirectoryOfType(classOf[ExifSubIFDDirectory]))
        shootDateTimeRaw  <- Option(exif.getString(ExifDirectoryBase.TAG_DATETIME))
        shootZoneOffsetRaw = exifSubIFD
                               .flatMap(dir => Option(dir.getString(ExifDirectoryBase.TAG_TIME_ZONE_ORIGINAL)))
                               .getOrElse("+00:00")
        shootDateTime      = parseExifDateTimeFormat(shootDateTimeRaw, shootZoneOffsetRaw)
      } yield shootDateTime.toInstant.atOffset(ZoneOffset.UTC)
    }
    result match {
      case Success(found) =>
        found.map(ShootDateTime.apply)
      case Failure(err)   =>
        logger.warn("Couldn't process exif date time format", err)
        None
    }
  }

  def extractCameraName(metadata: DrewMetadata): Option[CameraName] = {
    val tagName = ExifDirectoryBase.TAG_MODEL
    for {
      exif       <- Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
      if exif.containsTag(tagName)
      cameraName <- Option(exif.getString(tagName))
    } yield CameraName(cameraName)
  }

  def extractLocation(metadata: DrewMetadata): Option[Location] = {
    val tagName = GpsDirectory.TAG_ALTITUDE
    for {
      gps       <- Option(metadata.getFirstDirectoryOfType(classOf[GpsDirectory]))
      altitude   = if (gps.containsTag(tagName)) Option(gps.getDouble(tagName)) else None
      latitude  <- Option(gps.getGeoLocation).map(_.getLatitude).map(LatitudeDecimalDegrees.apply)
      longitude <- Option(gps.getGeoLocation).map(_.getLongitude).map(LongitudeDecimalDegrees.apply)
    } yield {
      Location(
        latitude = latitude,
        longitude = longitude,
        altitude = altitude
      )
    }
  }

  def buildDimension2D(getWidth: => Width, getHeight: => Height): Option[Dimension] = {
    val result = for {
      width  <- Try(getWidth)
      height <- Try(getHeight)
    } yield Dimension(width, height)
    result.toOption
  }

  def extractDimension(metadata: DrewMetadata): Option[Dimension] = {
    lazy val dimensionFromJpeg =
      Option(metadata.getFirstDirectoryOfType(classOf[JpegDirectory]))
        .flatMap(dir => buildDimension2D(dir.getImageWidth, dir.getImageHeight))
    lazy val dimensionFromPng  =
      Option(metadata.getFirstDirectoryOfType(classOf[PngDirectory]))
        .flatMap(dir => buildDimension2D(dir.getInt(PngDirectory.TAG_IMAGE_WIDTH), dir.getInt(PngDirectory.TAG_IMAGE_HEIGHT)))
    lazy val dimensionFromGif  =
      Option(metadata.getFirstDirectoryOfType(classOf[GifImageDirectory]))
        .flatMap(dir => buildDimension2D(dir.getInt(GifImageDirectory.TAG_WIDTH), dir.getInt(GifImageDirectory.TAG_HEIGHT)))
    lazy val dimensionFromBmp  =
      Option(metadata.getFirstDirectoryOfType(classOf[BmpHeaderDirectory]))
        .flatMap(dir => buildDimension2D(dir.getInt(BmpHeaderDirectory.TAG_IMAGE_WIDTH), dir.getInt(BmpHeaderDirectory.TAG_IMAGE_HEIGHT)))
    lazy val dimensionFromExif =
      Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
        .flatMap(dir => buildDimension2D(dir.getInt(ExifDirectoryBase.TAG_EXIF_IMAGE_WIDTH), dir.getInt(ExifDirectoryBase.TAG_EXIF_IMAGE_HEIGHT)))

    dimensionFromJpeg
      .orElse(dimensionFromPng)
      .orElse(dimensionFromGif)
      .orElse(dimensionFromBmp)
      .orElse(dimensionFromExif) // Remember that exif dimensions declaration may lie if the image have been altered
  }

  def extractOrientation(metadata: DrewMetadata): Option[Orientation] = {
    val tagName = ExifDirectoryBase.TAG_ORIENTATION
    val result  = for {
      exif            <- Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
      if exif.containsTag(tagName)
      orientationCode <- Option(exif.getInt(tagName))
    } yield {
      Orientation.values.find(_.code == orientationCode)
    }
    result.flatten
  }

  def mediaFileToOriginal(
    baseDirectory: BaseDirectoryPath,
    mediaPath: OriginalPath,
    owner: Owner,
    mediaCache: MediaCache = MediaNoCache
  ): Either[MediaIssue, Original] = {
    for {
      cachedOriginal   <- mediaCache.originalGet(baseDirectory, mediaPath, owner) // TODO enhance to check for coherency
      fileSize         <- getOriginalFileSize(mediaPath)
      fileLastModified <- getOriginalFileLastModified(mediaPath)
      fileHash         <- if (cachedOriginal.isDefined) Right(cachedOriginal.get.fileHash) else getOriginalFileHash(mediaPath)
      drewMetadata     <- readDrewMetadata(mediaPath)
      shootDateTime     = extractShootDateTime(drewMetadata)
      cameraName        = extractCameraName(drewMetadata)
      // genericTags       = extractGenericTags(drewMetadata)
      dimension         = extractDimension(drewMetadata)
      orientation       = extractOrientation(drewMetadata)
      location          = extractLocation(drewMetadata)
      originalId        = buildOriginalId(baseDirectory, mediaPath, owner)
      original          = Original(
                            id = originalId,
                            baseDirectory = baseDirectory,
                            mediaPath = mediaPath,
                            ownerId = owner.id,
                            fileHash = fileHash,
                            fileSize = fileSize,
                            fileLastModified = FileLastModified(fileLastModified),
                            cameraShootDateTime = shootDateTime,
                            cameraName = cameraName,
                            dimension = dimension,
                            orientation = orientation,
                            location = location
                          )
    } yield {
      mediaCache.originalUpdate(original)
      original
    }
  }

  private val VideoExtensionsRE = """(?i)^(mp4|mov|avi|mkv|wmv|mpg|mpeg)$""".r
  private val PhotoExtensionsRE = """(?i)^(jpg|jpeg|png|gif|bmp|dib|tiff|ico|heif|heic)$""".r

  def computeMediaKind(original: Original): Either[MediaIssue, MediaKind] = {
    val ext = original.mediaPath.extension
    ext match {
      case VideoExtensionsRE(_) => Right(MediaKind.Video)
      case PhotoExtensionsRE(_) => Right(MediaKind.Photo)
      case _                    => Left(MediaFileIssue(s"Unsupported file extension $ext", original.mediaPath.path, new Exception("Unsupported file extension")))
    }
  }

  def originalToDefaultMedia(original: Original): Either[MediaIssue, Media] = {
    for {
      timestamp     <- computeMediaTimestamp(original)
      mediaAccessKey = buildDefaultMediaAccessKey(timestamp)
      event          = buildMediaEvent(original.baseDirectory, original.mediaPath)
      kind          <- computeMediaKind(original)
    } yield Media(
      accessKey = mediaAccessKey,
      kind = kind,
      original = original,
      event = event,
      description = None,
      starred = false,
      keywords = Set.empty,
      orientation = None,
      shootDateTime = None,
      location = None
    )
  }

}
