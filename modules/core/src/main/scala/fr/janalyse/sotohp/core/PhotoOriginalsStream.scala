package fr.janalyse.sotohp.core

import zio.*
import zio.ZIO.*
import zio.stream.*

import java.io.{File, IOException}
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path, Paths}
import java.time.{Instant, OffsetDateTime, ZoneId}
import com.drew.metadata.Metadata
import fr.janalyse.sotohp.model.{PhotoMetaData, *}
import fr.janalyse.sotohp.model.DegreeMinuteSeconds.*
import fr.janalyse.sotohp.model.DecimalDegrees.*

import scala.jdk.CollectionConverters.*
import PhotoOperations.*
import fr.janalyse.sotohp.model.*
import java.util.UUID

object PhotoOriginalsStream {

  def searchPredicate(includeMaskRegex: Option[IncludeMaskRegex], ignoreMaskRegex: Option[IgnoreMaskRegex])(path: Path, attrs: BasicFileAttributes): Boolean = {
    attrs.isRegularFile &&
    (ignoreMaskRegex.isEmpty || ignoreMaskRegex.get.findFirstIn(path.toString).isEmpty) &&
    (includeMaskRegex.isEmpty || includeMaskRegex.get.findFirstIn(path.toString).isDefined)
  }

  def findFromSearchRoot(
    searchRoot: PhotoSearchRoot
  ): ZStream[Any, Throwable, (PhotoSearchRoot, PhotoPath)] = {
    searchRoot match {
      case searchPhotoFileRoot: PhotoSearchFileRoot =>
        import searchPhotoFileRoot.{baseDirectory, includeMask, ignoreMask}
        val foundRelativeFilesJavaStream = Files.find(baseDirectory, 10, searchPredicate(includeMask, ignoreMask))
        val foundFileStream              = ZStream
          .fromJavaStream(foundRelativeFilesJavaStream)
          .map(photoPath => searchRoot -> photoPath)
        foundFileStream
    }
  }

  def fileStream(searchRoots: List[PhotoSearchRoot]): ZStream[Any, Throwable, (PhotoSearchRoot, PhotoPath)] = {
    val foundFilesStreams = Chunk.fromIterable(searchRoots).map(searchRoot => findFromSearchRoot(searchRoot))
    val foundFilesStream  = ZStream.concatAll(foundFilesStreams)
    foundFilesStream
  }

  def makePhotoSource(baseDirectory: BaseDirectoryPath, photoPath: PhotoPath): Task[PhotoSource] = {
    for {
      fileSize         <- attemptBlockingIO(photoPath.toFile.length())
                            .logError(s"Unable to read file size of $photoPath")
      fileLastModified <- attemptBlockingIO(photoPath.toFile.lastModified())
                            .mapAttempt(Instant.ofEpochMilli)
                            .map(_.atZone(ZoneId.systemDefault()))
                            .map(_.toOffsetDateTime)
                            .logError(s"Unable to get file last modified of $photoPath")
      // fileHash         <- attemptBlockingIO(HashOperations.fileDigest(filePath))
      //                      .logError(s"Unable to compute file hash of $filePath")
    } yield PhotoSource(
      baseDirectory = baseDirectory,
      photoPath = photoPath,
      size = fileSize,
      // hash = PhotoHash(fileHash), // Computer later asynchronously
      hash = None,
      lastModified = fileLastModified
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

  def computePhotoCategory(searchRoot: PhotoSearchRoot, photoPath: PhotoPath): Option[PhotoCategory] = {
    searchRoot match {
      case searchFileRoot: PhotoSearchFileRoot =>
        val category = Option(photoPath.getParent).map { parentDir =>
          val text =
            parentDir.toString
              .replaceAll(searchFileRoot.baseDirectory.toString, "")
              .replaceAll("^/", "")
              .trim
          PhotoCategory(text)
        }
        category.filter(_.text.size > 0)
    }
  }

  def makePhoto(searchRoot: PhotoSearchRoot, photoPath: PhotoPath): Task[Photo] = {
    searchRoot match {
      case searchFileRoot: PhotoSearchFileRoot =>
        for {
          metadata      <- readMetadata(photoPath)
          geopoint       = extractGeoPoint(metadata)
          photoSource   <- makePhotoSource(searchFileRoot.baseDirectory, photoPath)
          photoMetaData  = makePhotoMetaData(metadata)
          photoId        = computePhotoId(photoSource, photoMetaData)
          photoTimestamp = computePhotoTimestamp(photoSource, photoMetaData)
          photoCategory  = computePhotoCategory(searchRoot, photoPath)
        } yield {
          Photo(
            id = PhotoId(photoId),
            ownerId = searchFileRoot.photoOwnerId,
            timestamp = photoTimestamp,
            source = photoSource,
            metaData = Some(photoMetaData),
            category = photoCategory
          )
        }
    }
  }

  def photoOriginalStream(searchRoots: List[PhotoSearchRoot]): ZStream[Any, Throwable, Photo] = {
    fileStream(searchRoots)
      .mapZIOParUnordered(1)((searchRoot, foundFile) => makePhoto(searchRoot, foundFile))
      .tapError(err => logError(s"error on stream : ${err.getMessage}"))
  }

}
