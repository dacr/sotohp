package fr.janalyse.sotohp.core

import zio.*
import zio.ZIO.*
import zio.stream.*

import scala.util.Try
import scala.util.matching.Regex
import zio.stream.ZPipeline.{splitLines, utf8Decode}

import java.io.{File, IOException}
import java.nio.charset.Charset
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path, Paths}
import java.time.{Instant, OffsetDateTime, ZoneId, ZoneOffset, ZonedDateTime}
import scala.Console.{BLUE, GREEN, RED, RESET, YELLOW}
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.{ExifDirectoryBase, ExifIFD0Directory, ExifSubIFDDirectory, GpsDirectory}
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.png.PngDirectory
import com.drew.metadata.gif.GifImageDirectory
import fr.janalyse.sotohp.model.{PhotoMetaData, *}
import fr.janalyse.sotohp.model.DegreeMinuteSeconds.*
import fr.janalyse.sotohp.model.DecimalDegrees.*
import com.fasterxml.uuid.Generators

import scala.jdk.CollectionConverters.*
import PhotoOperations.*
import fr.janalyse.sotohp.model.PhotoSource.PhotoFile
import java.util.UUID

object PhotoOriginalsStream {

  private val nameBaseUUIDGenerator = Generators.nameBasedGenerator()

  def computePhotoId(photoSource: PhotoSource, photoMetaData: PhotoMetaData): UUID = {
    // Using photo file path for identifier generation
    // as the same photo can be used within several directories
    photoSource match {
      case photoFile: PhotoSource.PhotoFile =>
        nameBaseUUIDGenerator.generate(photoFile.path)
    }
  }

  def computePhotoTimestamp(photoSource: PhotoSource, photoMetaData: PhotoMetaData): OffsetDateTime = {
    photoMetaData.shootDateTime match {
      // case Some(shootDateTime) if checkTimestampValid(shootDateTime) => shootDateTime
      case Some(shootDateTime) => shootDateTime
      case _                   =>
        photoSource match {
          case photoFile: PhotoSource.PhotoFile => photoFile.lastModified
        }
    }
  }

  def searchPredicate(includeMaskRegex: Option[Regex], ignoreMaskRegex: Option[Regex])(path: Path, attrs: BasicFileAttributes): Boolean = {
    attrs.isRegularFile &&
    (ignoreMaskRegex.isEmpty || ignoreMaskRegex.get.findFirstIn(path.toString).isEmpty) &&
    (includeMaskRegex.isEmpty || includeMaskRegex.get.findFirstIn(path.toString).isDefined)
  }

  def findFromSearchRoot(
    searchRoot: Path,
    includeMaskRegex: Option[Regex],
    ignoreMaskRegex: Option[Regex]
  ) = {
    val result = for {
      searchPath <- attempt(searchRoot)
      javaStream  = Files.find(searchPath, 10, searchPredicate(includeMaskRegex, ignoreMaskRegex))
      pathStream  = ZStream.fromJavaStream(javaStream).map(path => searchRoot -> path)
    } yield pathStream

    ZStream.unwrap(result)
  }

  def fileStream(searchRoots: List[String], includeMask: Option[String] = None, ignoreMask: Option[String] = None): ZStream[Any, Throwable, (Path, Path)] = {
    val result = for {
      _                 <- logInfo("photos inventory")
      includeMaskRegex  <- attempt(includeMask.map(_.r))
      ignoreMaskRegex   <- attempt(ignoreMask.map(_.r))
      searchRootsStreams = Chunk.fromIterable(searchRoots).map(searchRoot => findFromSearchRoot(Path.of(searchRoot), includeMaskRegex, ignoreMaskRegex))
      zCandidatesStream  = ZStream.concatAll(searchRootsStreams)
    } yield zCandidatesStream

    ZStream.unwrap(result)
  }

  def makePhotoSource(filePath: Path): Task[PhotoSource] = {
    for {
      fileSize         <- attemptBlockingIO(filePath.toFile.length())
                            .logError(s"Unable to read file size of $filePath")
      fileLastModified <- attemptBlockingIO(filePath.toFile.lastModified())
                            .mapAttempt(Instant.ofEpochMilli)
                            .map(_.atZone(ZoneId.systemDefault()))
                            .map(_.toOffsetDateTime)
                            .logError(s"Unable to get file last modified of $filePath")
      fileHash         <- attemptBlockingIO(HashOperations.fileDigest(filePath))
                            .logError(s"Unable to compute file hash of $filePath")
    } yield PhotoSource.PhotoFile(
      path = filePath.toString,
      size = fileSize,
      hash = PhotoHash(fileHash),
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

  def makePhoto(searchPath: Path, filePath: Path): Task[Photo] = {
    for {
      metadata      <- readMetadata(filePath)
      geopoint       = extractGeoPoint(metadata)
      photoSource   <- makePhotoSource(filePath)
      photoMetaData  = makePhotoMetaData(metadata)
      photoId        = computePhotoId(photoSource, photoMetaData)
      photoTimestamp = computePhotoTimestamp(photoSource, photoMetaData)
    } yield {
      Photo(
        id = PhotoId(photoId),
        ownerId = PhotoOwnerId(UUID.fromString("CAFECAFE-CAFE-CAFE-BABE-BABEBABE")),
        timestamp = photoTimestamp,
        source = photoSource,
        metaData = Some(photoMetaData)
      )
    }
  }

  def photoOriginalStream(searchRoots: List[String], includeMask: Option[String] = None, ignoreMask: Option[String] = None): ZStream[Any, Throwable, Photo] = {
    fileStream(searchRoots, includeMask, ignoreMask)
      .mapZIOParUnordered(1)((searchPath, path) => makePhoto(searchPath, path))
      .tapError(err => Console.printLine(err))
  }

}
