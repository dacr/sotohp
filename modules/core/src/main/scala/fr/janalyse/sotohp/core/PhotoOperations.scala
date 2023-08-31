package fr.janalyse.sotohp.core

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.{ExifDirectoryBase, ExifIFD0Directory, GpsDirectory}
import com.drew.metadata.gif.GifImageDirectory
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.png.PngDirectory
import com.drew.metadata.bmp.BmpHeaderDirectory
import fr.janalyse.sotohp.model.DecimalDegrees.*
import fr.janalyse.sotohp.model.*
import zio.*
import zio.ZIOAspect.*

import java.nio.file.Path
import java.time.{Instant, OffsetDateTime, ZoneId, ZoneOffset}
import scala.jdk.CollectionConverters.*
import com.fasterxml.uuid.Generators
import fr.janalyse.sotohp.store.{PhotoStoreIssue, PhotoStoreService}
import wvlet.airframe.ulid.ULID

import scala.util.Try

case class NotFoundInStore(message: String, id: String)

case class PhotoFileIssue(message: String, filePath: Path, throwable: Throwable)

object PhotoOperations {

  private val nameBaseUUIDGenerator = Generators.nameBasedGenerator()

  def readDrewMetadata(filePath: Path): IO[PhotoFileIssue, Metadata] = {
    ZIO
      .attemptBlockingIO(ImageMetadataReader.readMetadata(filePath.toFile))
      .mapError(exception => PhotoFileIssue(s"Couldn't read image meta data in file", filePath, exception))
  }

  def buildOriginalId(original: Original): OriginalId = {
    // Using photo relative file path and owner id for photo identifier generation
    // as the same photo can be used within several directories or people
    import original.*
    val relativePath = baseDirectory.relativize(path)
    val key          = s"$ownerId:$relativePath"
    val uuid         = nameBaseUUIDGenerator.generate(key)
    OriginalId(uuid)
  }

  def buildPhotoId(timestamp: OffsetDateTime): PhotoId = {
    val ulid = ULID.ofMillis(timestamp.toInstant.toEpochMilli)
    PhotoId(ulid)
  }

  def buildPhotoCategory(baseDirectory: BaseDirectoryPath, photoPath: PhotoPath): Option[PhotoCategory] = {
    val category = Option(photoPath.getParent).map { photoParentDir =>
      val text = baseDirectory.relativize(photoParentDir).toString
      PhotoCategory(text)
    }
    category.filter(_.text.size > 0)
  }

  def getOriginalFileLastModified(original: Original): IO[PhotoFileIssue, OffsetDateTime] = {
    ZIO
      .attemptBlockingIO(original.path.toFile.lastModified())
      .mapAttempt(Instant.ofEpochMilli)
      .mapAttempt(_.atZone(ZoneId.systemDefault()).toOffsetDateTime)
      .mapError(exception => PhotoFileIssue(s"Unable to get file last modified", original.path, exception))
  }

  def computePhotoTimestamp(original: Original, photoMetaData: PhotoMetaData): IO[PhotoFileIssue, OffsetDateTime] = {
    val sdt = photoMetaData.shootDateTime.filter(_.getYear >= 1990) // TODO - Add rule/config to control shootDataTime validity !
    sdt match {
      case Some(shootDateTime) => ZIO.succeed(shootDateTime)
      case _                   => getOriginalFileLastModified(original)

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

  def extractGenericTags(metadata: Metadata): Map[String, String] = {
    val metaDirectories = metadata.getDirectories.asScala
    val metaDataTags    = metaDirectories.flatMap(dir => dir.getTags.asScala).toList
    metadataTagsToGenericTags(metaDataTags)
  }

  def extractShootDateTime(metadata: Metadata): Option[OffsetDateTime] = {
    val tagName = ExifDirectoryBase.TAG_DATETIME
    for {
      exif          <- Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
      if exif.containsTag(tagName)
      shootDateTime <- Option(exif.getDate(tagName))
    } yield shootDateTime.toInstant.atOffset(ZoneOffset.UTC)
  }

  def extractCameraName(metadata: Metadata): Option[String] = {
    val tagName = ExifDirectoryBase.TAG_MODEL
    for {
      exif       <- Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
      if exif.containsTag(tagName)
      cameraName <- Option(exif.getString(tagName))
    } yield cameraName
  }

  def extractPlace(metadata: Metadata): Option[PhotoPlace] = {
    val tagName = GpsDirectory.TAG_ALTITUDE
    for {
      gps       <- Option(metadata.getFirstDirectoryOfType(classOf[GpsDirectory]))
      altitude   = if (gps.containsTag(tagName)) Option(gps.getDouble(tagName)) else None
      latitude  <- Option(gps.getGeoLocation).map(_.getLatitude).map(LatitudeDecimalDegrees.apply)
      longitude <- Option(gps.getGeoLocation).map(_.getLongitude).map(LongitudeDecimalDegrees.apply)
    } yield {
      PhotoPlace(
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        deducted = false // Real GPS meta data
      )
    }
  }

  private def buildDimension2D(getWidth: => Width, getHeight: => Height): Option[Dimension2D] = {
    val result = for {
      width  <- Try(getWidth)
      height <- Try(getHeight)
    } yield Dimension2D(width, height)
    result.toOption
  }

  def extractDimension(metadata: Metadata): Option[Dimension2D] = {
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

  def extractOrientation(metadata: Metadata): Option[PhotoOrientation] = {
    val tagName = ExifDirectoryBase.TAG_ORIENTATION
    val result  = for {
      exif            <- Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
      if exif.containsTag(tagName)
      orientationCode <- Option(exif.getInt(tagName))
    } yield {
      PhotoOrientation.values.find(_.code == orientationCode)
    }
    result.flatten
  }

  def buildPhotoSource(photoId: PhotoId, original: Original): ZIO[PhotoStoreService, PhotoFileIssue, PhotoSource] = {
    for {
      fileSize         <- ZIO
                            .attemptBlockingIO(original.path.toFile.length())
                            .mapError(exception => PhotoFileIssue(s"Unable to read file size", original.path, exception))
      fileLastModified <- getOriginalFileLastModified(original)
      fileHash         <- PhotoStoreService
                            .photoStateGet(photoId)
                            .map(r => r.map(_.photoHash.code))
                            .some
                            .orElse(
                              ZIO
                                .attemptBlockingIO(HashOperations.fileDigest(original.path))
                                .mapError(exception => PhotoFileIssue(s"Unable to compute file hash", original.path, exception))
                            )
    } yield PhotoSource(
      photoId = photoId,
      original = original,
      fileSize = fileSize,
      fileHash = PhotoHash(fileHash),
      fileLastModified = fileLastModified
    )
  }

  def buildPhotoMetaData(metadata: Metadata): PhotoMetaData = {
    val shootDateTime = extractShootDateTime(metadata)
    val cameraName    = extractCameraName(metadata)
    val genericTags   = extractGenericTags(metadata)
    val dimension     = extractDimension(metadata)
    val orientation   = extractOrientation(metadata)
    PhotoMetaData(
      dimension = dimension,
      shootDateTime = shootDateTime,
      orientation = orientation,
      cameraName = cameraName,
      tags = genericTags
    )
  }

  private def setupPhotoInitialState(originalId: OriginalId, photo: Photo): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = {
    for {
      currentDateTime <- Clock.currentDateTime
      state            = PhotoState(
                           photoId = photo.source.photoId,
                           originalId = originalId,
                           photoHash = photo.source.fileHash,
                           photoOwnerId = photo.source.original.ownerId,
                           photoTimestamp = photo.timestamp,
                           firstSeen = currentDateTime,
                           lastSeen = currentDateTime
                         )
      _               <- PhotoStoreService.photoStateUpsert(photo.source.photoId, state)
    } yield ()
  }

  private def updateStateLastSeen(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue | NotFoundInStore, Unit] = {
    for {
      state           <- PhotoStoreService
                           .photoStateGet(photoId)
                           .some
                           .mapError(someError => someError.getOrElse(NotFoundInStore("no photo state in store", photoId.toString)))
      currentDateTime <- Clock.currentDateTime
      updatedState     = state.copy(lastSeen = currentDateTime)
      _               <- PhotoStoreService.photoStateUpsert(photoId, updatedState)
    } yield ()
  }

  private def makePhotoFromFile(original: Original): ZIO[PhotoStoreService, PhotoStoreIssue | PhotoFileIssue, Photo] = {
    val originalId = buildOriginalId(original)
    for {
      drewMetadata    <- readDrewMetadata(original.path)
      photoMetaData    = buildPhotoMetaData(drewMetadata)
      photoTimestamp  <- computePhotoTimestamp(original, photoMetaData)
      photoId          = buildPhotoId(photoTimestamp)
      photoSource     <- buildPhotoSource(photoId, original)
      foundPlace       = extractPlace(drewMetadata)
      category         = buildPhotoCategory(original.baseDirectory, original.path)
      photoDescription = PhotoDescription(category = category)
      _               <- PhotoStoreService.photoSourceUpsert(originalId, photoSource)
      _               <- PhotoStoreService.photoMetaDataUpsert(photoId, photoMetaData) // TODO LMDB add a transaction feature to avoid leaving partial data...
      _               <- PhotoStoreService.photoDescriptionUpsert(photoId, photoDescription)
      _               <- ZIO.foreachDiscard(foundPlace)(place => PhotoStoreService.photoPlaceUpsert(photoId, place))
      photo            = Photo(
                           timestamp = photoTimestamp,
                           source = photoSource,
                           metaData = Some(photoMetaData),
                           place = foundPlace,
                           description = Some(photoDescription)
                         )
      _               <- setupPhotoInitialState(originalId, photo)
      _               <- ZIO.logInfo(s"New photo found $photoTimestamp - ${photoMetaData.shootDateTime}")
    } yield photo
  }

  private def makePhotoFromStore(
    original: Original
  ): ZIO[PhotoStoreService, PhotoStoreIssue | PhotoFileIssue | NotFoundInStore, Photo] = {
    import original.*
    val originalId = buildOriginalId(original)
    val logic      = for {
      photoSource      <- PhotoStoreService
                            .photoSourceGet(originalId)
                            .some
                            .mapError(someError => someError.getOrElse(NotFoundInStore("no source in store", originalId.toString)))
      photoId           = photoSource.photoId
      photoMetaData    <- PhotoStoreService
                            .photoMetaDataGet(photoId)
                            .some
                            .mapError(someError => someError.getOrElse(NotFoundInStore("no meta data in store", photoId.toString)))
      photoPlace       <- PhotoStoreService.photoPlaceGet(photoId)
      miniatures       <- PhotoStoreService.photoMiniaturesGet(photoId)
      normalizedPhoto  <- PhotoStoreService.photoNormalizedGet(photoId)
      photoTimestamp   <- computePhotoTimestamp(original, photoMetaData)
      photoDescription <- PhotoStoreService.photoDescriptionGet(photoId)
      _                <- updateStateLastSeen(photoId)
    } yield {
      Photo(
        timestamp = photoTimestamp,
        source = photoSource,
        metaData = Some(photoMetaData),
        place = photoPlace,
        description = photoDescription,
        miniatures = miniatures,
        normalized = normalizedPhoto
      )
    }

    logic @@ annotated("originalId" -> originalId.toString)
  }

  def makePhoto(original: Original): ZIO[PhotoStoreService, PhotoStoreIssue | PhotoFileIssue | NotFoundInStore, Photo] = {
    val makeIt = for {
      photo <- makePhotoFromStore(original)
                 .tapError(err =>
                   ZIO
                     .logWarning(err.toString)
                     .when(!err.isInstanceOf[NotFoundInStore]) // NotFound so it is a new photo to be computed from file
                 )
                 .orElse(makePhotoFromFile(original))
    } yield photo

    makeIt.uninterruptible
      @@ annotated("originalPath" -> original.path.toString)
      @@ annotated("ownerId" -> original.ownerId.toString)
  }

  def makePhotoFromStoredState(state: PhotoState): ZIO[PhotoStoreService, PhotoStoreIssue | NotFoundInStore, Photo] = {
    val photoId    = state.photoId
    val originalId = state.originalId
    for {
      photoSource     <- PhotoStoreService.photoSourceGet(originalId).someOrFail(NotFoundInStore("Photo source not found", state.photoId.toString()))
      photoMetaData   <- PhotoStoreService.photoMetaDataGet(photoId)
      photoPlace      <- PhotoStoreService.photoPlaceGet(photoId)
      description     <- PhotoStoreService.photoDescriptionGet(photoId)
      miniatures      <- PhotoStoreService.photoMiniaturesGet(photoId)
      normalizedPhoto <- PhotoStoreService.photoNormalizedGet(photoId)
      classifications <- PhotoStoreService.photoClassificationsGet(photoId)
      objects         <- PhotoStoreService.photoObjectsGet(photoId)
      faces           <- PhotoStoreService.photoFacesGet(photoId)
    } yield {
      Photo(
        timestamp = state.photoTimestamp,
        source = photoSource,
        metaData = photoMetaData,
        place = photoPlace,
        description = description,
        miniatures = miniatures,
        normalized = normalizedPhoto,
        foundClassifications = classifications,
        foundObjects = objects,
        foundFaces = faces
      )
    }
  }
}
