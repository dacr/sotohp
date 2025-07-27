package fr.janalyse.sotohp.media.core

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.bmp.BmpHeaderDirectory
import com.drew.metadata.exif.{ExifDirectoryBase, ExifIFD0Directory, ExifSubIFDDirectory, GpsDirectory}
import com.drew.metadata.gif.GifImageDirectory
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.png.PngDirectory
import com.drew.metadata.{Metadata as DrewMetadata, Tag as DrewTag}
import com.fasterxml.uuid.Generators
import fr.janalyse.sotohp.media.core.HashOperations
import fr.janalyse.sotohp.media.model.*
import wvlet.airframe.ulid.ULID

import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.time.{Instant, OffsetDateTime, ZoneId, ZoneOffset}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

object OriginalBuilder {
  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[OriginalBuilder.type])

  private val nameBaseUUIDGenerator = Generators.nameBasedGenerator()

  def readDrewMetadata(mediaPath: OriginalPath): Either[OriginalFileIssue, DrewMetadata] = {
    Try(ImageMetadataReader.readMetadata(mediaPath.file)) match {
      case Failure(exception) => Left(OriginalFileIssue(s"Couldn't read image meta data in file", mediaPath.path, exception))
      case Success(value)     => Right(value)
    }
  }

  def buildOriginalId(baseDirectory: BaseDirectoryPath, mediaPath: OriginalPath, ownerId: OwnerId): OriginalId = {
    // Using photo relative file path and owner id for photo identifier generation
    // as the same photo can be used within several directories or people
    val relativePath = baseDirectory.path.relativize(mediaPath.path)
    val key          = s"${ownerId}:$relativePath"
    val uuid         = nameBaseUUIDGenerator.generate(key)
    OriginalId(uuid)
  }

  def getOriginalFileSize(mediaPath: OriginalPath): Either[OriginalFileIssue, FileSize] = {
    Try(mediaPath.file.length()) match {
      case Failure(exception) => Left(OriginalFileIssue(s"Unable to get file size", mediaPath.path, exception))
      case Success(value)     => Right(FileSize(value))
    }
  }

  def getOriginalFileLastModified(mediaPath: OriginalPath): Either[OriginalFileIssue, OffsetDateTime] = {
    val tried = Try(mediaPath.file.lastModified())
      .map(Instant.ofEpochMilli)
      .flatMap(instant => Try(instant.atZone(ZoneId.systemDefault()).toOffsetDateTime))
    tried match {
      case Failure(exception) => Left(OriginalFileIssue(s"Unable to get file last modified", mediaPath.path, exception))
      case Success(value)     => Right(value)
    }
  }

  def computeOriginalHash(mediaPath: OriginalPath): Either[OriginalIssue, OriginalHash] = {
    HashOperations.fileDigest(mediaPath.path) match {
      case Left(error)  => Left(OriginalFileIssue(s"Unable to compute file hash", mediaPath.path, error))
      case Right(value) => Right(OriginalHash(value))
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

  private def extractExif(metadata: DrewMetadata)    = {
    Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
  }
  private def extractExifSub(metadata: DrewMetadata) = {
    Option(metadata.getFirstDirectoryOfType(classOf[ExifSubIFDDirectory]))
  }

  private val exifDateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss [XXX][XX][X]")

  def parseExifDateTimeFormat(spec: String, timeZoneOffsetSpec: String): OffsetDateTime = {
    OffsetDateTime.parse(s"$spec $timeZoneOffsetSpec", exifDateTimeFormat)
  }

  def extractShootDateTime(metadata: DrewMetadata): Option[ShootDateTime] = {
    val result = Try {
      for {
        exif              <- extractExif(metadata)
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
      exif       <- extractExif(metadata)
      if exif.containsTag(tagName)
      cameraName <- Option(exif.getString(tagName))
    } yield CameraName(cameraName)
  }

  def extractArtistInfo(metadata: DrewMetadata): Option[ArtistInfo] = {
    val tagName = ExifDirectoryBase.TAG_ARTIST
    for {
      exif   <- extractExif(metadata)
      if exif.containsTag(tagName)
      artist <- Option(exif.getString(tagName))
    } yield ArtistInfo(artist)
  }

  def extractAperture(metadata: DrewMetadata): Option[Aperture] = {
    val tagName = ExifDirectoryBase.TAG_APERTURE
    for {
      exif     <- extractExifSub(metadata)
      if exif.containsTag(tagName)
      aperture <- Option(exif.getDouble(tagName))
    } yield Aperture(aperture)
  }

  def extractExposureTime(metadata: DrewMetadata): Option[ExposureTime] = {
    val tagName = ExifDirectoryBase.TAG_EXPOSURE_TIME // TAG_SHUTTER_SPEED
    for {
      exif         <- extractExifSub(metadata)
      if exif.containsTag(tagName)
      exposureTime <- Option(exif.getRational(tagName)) // TODO Use Rational
    } yield ExposureTime(exposureTime.getNumerator, exposureTime.getDenominator)
  }

  def extractFocalLength(metadata: DrewMetadata): Option[FocalLength] = {
    val tagName = ExifDirectoryBase.TAG_FOCAL_LENGTH
    for {
      exif        <- extractExifSub(metadata)
      if exif.containsTag(tagName)
      focalLength <- Option(exif.getDouble(tagName))
    } yield FocalLength(focalLength)
  }

  def extractISO(metadata: DrewMetadata): Option[ISO] = {
    val tagName = ExifDirectoryBase.TAG_ISO_EQUIVALENT
    for {
      exif <- extractExifSub(metadata)
      if exif.containsTag(tagName)
      iso  <- Option(exif.getDouble(tagName))
    } yield ISO(iso)
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
        altitude = altitude.map(AltitudeMeanSeaLevel.apply)
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
        .flatMap(dir => buildDimension2D(Width(dir.getImageWidth), Height(dir.getImageHeight)))
    lazy val dimensionFromPng  =
      Option(metadata.getFirstDirectoryOfType(classOf[PngDirectory]))
        .flatMap(dir => buildDimension2D(Width(dir.getInt(PngDirectory.TAG_IMAGE_WIDTH)), Height(dir.getInt(PngDirectory.TAG_IMAGE_HEIGHT))))
    lazy val dimensionFromGif  =
      Option(metadata.getFirstDirectoryOfType(classOf[GifImageDirectory]))
        .flatMap(dir => buildDimension2D(Width(dir.getInt(GifImageDirectory.TAG_WIDTH)), Height(dir.getInt(GifImageDirectory.TAG_HEIGHT))))
    lazy val dimensionFromBmp  =
      Option(metadata.getFirstDirectoryOfType(classOf[BmpHeaderDirectory]))
        .flatMap(dir => buildDimension2D(Width(dir.getInt(BmpHeaderDirectory.TAG_IMAGE_WIDTH)), Height(dir.getInt(BmpHeaderDirectory.TAG_IMAGE_HEIGHT))))
    lazy val dimensionFromExif =
      extractExif(metadata)
        .flatMap(dir => buildDimension2D(Width(dir.getInt(ExifDirectoryBase.TAG_EXIF_IMAGE_WIDTH)), Height(dir.getInt(ExifDirectoryBase.TAG_EXIF_IMAGE_HEIGHT))))

    dimensionFromJpeg
      .orElse(dimensionFromPng)
      .orElse(dimensionFromGif)
      .orElse(dimensionFromBmp)
      .orElse(dimensionFromExif) // Remember that exif dimensions declaration may lie if the image have been altered
  }

  def extractOrientation(metadata: DrewMetadata): Option[Orientation] = {
    val tagName = ExifDirectoryBase.TAG_ORIENTATION
    val result  = for {
      exif            <- extractExif(metadata)
      if exif.containsTag(tagName)
      orientationCode <- Option(exif.getInt(tagName))
    } yield {
      Orientation.values.find(_.code == orientationCode)
    }
    result.flatten
  }

  def now() = OffsetDateTime.now(ZoneOffset.UTC)

  private val VideoExtensionsRE = """(?i)^(mp4|mov|avi|mkv|wmv|mpg|mpeg)$""".r
  private val PhotoExtensionsRE = """(?i)^(jpg|jpeg|png|gif|bmp|tif|tiff|ico|heif|heic)$""".r

  def computeMediaKind(mediaPath: OriginalPath): Either[OriginalIssue, MediaKind] = {
    val ext = mediaPath.extension
    ext match {
      case VideoExtensionsRE(_) => Right(MediaKind.Video)
      case PhotoExtensionsRE(_) => Right(MediaKind.Photo)
      case _                    => Left(OriginalFileIssue(s"Unsupported file extension $ext", mediaPath.path, new Exception("Unsupported file extension")))
    }
  }

  /** Generates an `Original` object from a media file and its associated metadata.
    *
    * @param baseDirectory
    *   the base directory path where the original media file resides
    * @param mediaPath
    *   the absolute path to the media file
    * @param ownerId
    *   the owner id of the media file
    * @param knownFileHash
    *   optionally provides an already computed hash for this original file
    * @return
    *   an `Either` containing either a `MediaIssue` if an error occurred during processing, or an `Original` object if successfully generated
    */
  def originalFromFile(
    store: Store,
    mediaPath: OriginalPath
  ): Either[OriginalIssue, Original] = {
    for {
      fileSize         <- getOriginalFileSize(mediaPath)
      fileLastModified <- getOriginalFileLastModified(mediaPath)
      kind             <- computeMediaKind(mediaPath)
      drewMetadata     <- readDrewMetadata(mediaPath)
      shootDateTime     = extractShootDateTime(drewMetadata)
      cameraName        = extractCameraName(drewMetadata)
      artistInfo        = extractArtistInfo(drewMetadata)
      // genericTags       = extractGenericTags(drewMetadata)
      dimension         = extractDimension(drewMetadata)
      orientation       = extractOrientation(drewMetadata)
      aperture          = extractAperture(drewMetadata)
      shutterSpeed      = extractExposureTime(drewMetadata)
      iso               = extractISO(drewMetadata)
      focalLength       = extractFocalLength(drewMetadata)
      location          = extractLocation(drewMetadata)
      originalId        = buildOriginalId(store.baseDirectory, mediaPath, store.ownerId)
    } yield Original(
      id = originalId,
      store = store,
      mediaPath = mediaPath,
      fileSize = fileSize,
      fileLastModified = FileLastModified(fileLastModified),
      kind = kind,
      cameraShootDateTime = shootDateTime,
      cameraName = cameraName,
      artistInfo = artistInfo,
      dimension = dimension,
      orientation = orientation,
      location = location,
      aperture = aperture,
      exposureTime = shutterSpeed,
      iso = iso,
      focalLength = focalLength
    )
  }
}
