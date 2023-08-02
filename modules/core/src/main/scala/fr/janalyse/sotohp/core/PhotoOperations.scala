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
import zio.ZIO.*

import java.io.IOException
import java.nio.file.Path
import java.time.{OffsetDateTime, ZoneOffset, Instant, ZoneId}
import scala.jdk.CollectionConverters.*
import com.fasterxml.uuid.Generators

import fr.janalyse.sotohp.store.PhotoStoreService

import scala.util.Try

object PhotoOperations {

  private val nameBaseUUIDGenerator = Generators.nameBasedGenerator()

  def computePhotoId(photoPath: PhotoPath, photoOwnerId: PhotoOwnerId): PhotoId = {
    // Using photo file path and owner id for photo identifier generation
    // as the same photo can be used within several directories or people
    val key  = s"$photoOwnerId:$photoPath"
    val uuid = nameBaseUUIDGenerator.generate(key)
    PhotoId(uuid)
  }

  def computePhotoCategory(baseDirectory: BaseDirectoryPath, photoPath: PhotoPath): Option[PhotoCategory] = {
    val category = Option(photoPath.getParent).map { parentDir =>
      val text =
        parentDir.toString
          .replaceAll(baseDirectory.toString, "")
          .replaceAll("^/", "")
          .replaceAll("/$", "")
          .trim
      PhotoCategory(text)
    }
    category.filter(_.text.size > 0)
  }

  def computePhotoTimestamp(photoSource: PhotoSource, photoMetaData: PhotoMetaData): OffsetDateTime = {
    photoMetaData.shootDateTime match {
      case Some(shootDateTime) => shootDateTime
      case _                   => photoSource.fileLastModified
    }
  }

  def readDrewMetadata(filePath: Path): IO[IOException, Metadata] = {
    attemptBlockingIO(ImageMetadataReader.readMetadata(filePath.toFile))
      .tapError(th => ZIO.logWarning(s"Couldn't read image meta data in file $filePath : ${th.getMessage}"))
  }

  private def makeGenericTagKey(tag: com.drew.metadata.Tag): String = {
    val prefix = tag.getDirectoryName().trim.replaceAll("""[^a-zA-Z0-9]+""", "")
    val name   = tag.getTagName().trim.replaceAll("""[^a-zA-Z0-9]+""", "")
    s"${prefix}_$name"
  }

  private def metadataTagsToGenericTags(tags: List[com.drew.metadata.Tag]): Map[String, String] = {
    tags
      .filter(_.hasTagName)
      .filter(_.getDescription != null)
      .map(tag => makeGenericTagKey(tag) -> tag.getDescription)
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
      if gps.containsTag(tagName)
      altitude  <- Option(gps.getDouble(tagName))
      latitude  <- Option(gps.getGeoLocation).map(_.getLatitude).map(LatitudeDecimalDegrees.apply)
      longitude <- Option(gps.getGeoLocation).map(_.getLongitude).map(LongitudeDecimalDegrees.apply)
    } yield {
      PhotoPlace(
        latitude,
        longitude,
        altitude
      )
    }
  }

  private def makeDimension2D(getWidth: => Width, getHeight: => Height): Option[Dimension2D] = {
    val result = for {
      width  <- Try(getWidth)
      height <- Try(getHeight)
    } yield Dimension2D(width, height)
    result.toOption
  }

  def extractDimension(metadata: Metadata): Option[Dimension2D] = {
    lazy val dimensionFromJpeg =
      Option(metadata.getFirstDirectoryOfType(classOf[JpegDirectory]))
        .flatMap(dir => makeDimension2D(dir.getImageWidth, dir.getImageHeight))
    lazy val dimensionFromPng  =
      Option(metadata.getFirstDirectoryOfType(classOf[PngDirectory]))
        .flatMap(dir => makeDimension2D(dir.getInt(PngDirectory.TAG_IMAGE_WIDTH), dir.getInt(PngDirectory.TAG_IMAGE_HEIGHT)))
    lazy val dimensionFromGif  =
      Option(metadata.getFirstDirectoryOfType(classOf[GifImageDirectory]))
        .flatMap(dir => makeDimension2D(dir.getInt(GifImageDirectory.TAG_WIDTH), dir.getInt(GifImageDirectory.TAG_HEIGHT)))
    lazy val dimensionFromBmp  =
      Option(metadata.getFirstDirectoryOfType(classOf[BmpHeaderDirectory]))
        .flatMap(dir => makeDimension2D(dir.getInt(BmpHeaderDirectory.TAG_IMAGE_WIDTH), dir.getInt(BmpHeaderDirectory.TAG_IMAGE_HEIGHT)))
    lazy val dimensionFromExif =
      Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
        .flatMap(dir => makeDimension2D(dir.getInt(ExifDirectoryBase.TAG_EXIF_IMAGE_WIDTH), dir.getInt(ExifDirectoryBase.TAG_EXIF_IMAGE_HEIGHT)))

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

  def makePhotoSource(baseDirectory: BaseDirectoryPath, photoPath: PhotoPath, photoOwnerId: PhotoOwnerId): Task[PhotoSource] = {
    for {
      fileSize         <- attemptBlockingIO(photoPath.toFile.length())
                            .logError(s"Unable to read file size of $photoPath")
      fileLastModified <- attemptBlockingIO(photoPath.toFile.lastModified())
                            .mapAttempt(Instant.ofEpochMilli)
                            .map(_.atZone(ZoneId.systemDefault()))
                            .map(_.toOffsetDateTime)
                            .logError(s"Unable to get file last modified of $photoPath")
      fileHash         <- attemptBlockingIO(HashOperations.fileDigest(photoPath))
                            .logError(s"Unable to compute file hash of $photoPath")
    } yield PhotoSource(
      ownerId = photoOwnerId,
      baseDirectory = baseDirectory,
      photoPath = photoPath,
      fileSize = fileSize,
      fileHash = PhotoHash(fileHash),
      fileLastModified = fileLastModified
    )
  }

  def makePhotoMetaData(metadata: Metadata): PhotoMetaData = {
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

  def makePhotoFromFile(photoId: PhotoId, baseDirectory: BaseDirectoryPath, photoPath: PhotoPath, photoOwnerId: PhotoOwnerId): ZIO[PhotoStoreService, Throwable, Photo] = {
    for {
      _               <- logInfo(s"New photo found $photoPath $photoId")
      drewMetadata    <- readDrewMetadata(photoPath)
      photoMetaData    = makePhotoMetaData(drewMetadata)
      photoSource     <- makePhotoSource(baseDirectory, photoPath, photoOwnerId)
      foundPlace       = extractPlace(drewMetadata)
      photoTimestamp   = computePhotoTimestamp(photoSource, photoMetaData)
      photoCategory    = computePhotoCategory(baseDirectory, photoPath)
      _               <- PhotoStoreService.photoMetaDataUpsert(photoId, photoMetaData).mapError(err => Exception(s"TODO - temporary exception $err"))                           // TODO enhance error management
      _               <- PhotoStoreService.photoSourceUpsert(photoId, photoSource).mapError(err => Exception(s"TODO - temporary exception $err"))                               // TODO enhance error management
      _               <- foreachDiscard(foundPlace)(place => PhotoStoreService.photoPlaceUpsert(photoId, place)).mapError(err => Exception(s"TODO - temporary exception $err")) // TODO enhance error management
      currentDateTime <- Clock.currentDateTime
      state            = PhotoState(
                           photoId = photoId,
                           photoHash = photoSource.fileHash,
                           lastSynchronized = currentDateTime,
                           lastUpdated = currentDateTime,
                           firstSeen = currentDateTime
                         )
      _               <- PhotoStoreService.photoStateUpsert(photoId, state).mapError(err => Exception(s"TODO - temporary exception $err"))                                      // TODO enhance error management
    } yield {
      Photo(
        id = photoId,
        timestamp = photoTimestamp,
        source = photoSource,
        metaData = Some(photoMetaData),
        place = foundPlace,
        category = photoCategory
      )
    }
  }

  def makePhotoFromStore(photoId: PhotoId, baseDirectory: BaseDirectoryPath, photoPath: PhotoPath, photoOwnerId: PhotoOwnerId): ZIO[PhotoStoreService, Throwable, Photo] = {
    val result = for {
//      state           <- PhotoStoreService.photoStateGet(photoId).some
//      currentDateTime <- Clock.currentDateTime
//      updatedState     = state.copy(lastSynchronized = currentDateTime)
//      _               <- PhotoStoreService.photoStateUpsert(photoId, updatedState)
      photoMetaData <- PhotoStoreService.photoMetaDataGet(photoId).some
      photoSource   <- PhotoStoreService.photoSourceGet(photoId).some
      photoPlace    <- PhotoStoreService.photoPlaceGet(photoId)
      photoTimestamp = computePhotoTimestamp(photoSource, photoMetaData)
      photoCategory  = computePhotoCategory(baseDirectory, photoPath)
    } yield {
      Photo(
        id = photoId,
        timestamp = photoTimestamp,
        source = photoSource,
        metaData = Some(photoMetaData),
        place = photoPlace,
        category = photoCategory
      )
    }
    result.mapError(err => Exception(s"TODO - temporary exception $err")) // TODO enhance error management
  }

  def makePhoto(baseDirectory: BaseDirectoryPath, photoPath: PhotoPath, photoOwnerId: PhotoOwnerId): ZIO[PhotoStoreService, Throwable, Photo] = {
    val photoId = computePhotoId(photoPath, photoOwnerId)
    makePhotoFromStore(photoId, baseDirectory, photoPath, photoOwnerId)
      .orElse(makePhotoFromFile(photoId, baseDirectory, photoPath, photoOwnerId))
  }
}
