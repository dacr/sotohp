package fr.janalyse.sotohp.core

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.{ExifDirectoryBase, ExifIFD0Directory, ExifSubIFDDirectory, GpsDirectory}
import com.drew.metadata.gif.GifImageDirectory
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.png.PngDirectory
import fr.janalyse.sotohp.model.DecimalDegrees.*
import fr.janalyse.sotohp.model.DegreeMinuteSeconds.*
import fr.janalyse.sotohp.model.*
import zio.*
import zio.ZIO.*
import zio.stream.*
import zio.stream.ZPipeline.{splitLines, utf8Decode}

import java.io.{File, IOException}
import java.nio.charset.Charset
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path, Paths}
import java.time.*
import scala.Console.*
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.matching.Regex

object PhotoOperations {

  def readMetadata(filePath: Path): IO[IOException, Metadata] = {
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
    for {
      exif          <- Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
      shootDateTime <- Option(exif.getDate(ExifDirectoryBase.TAG_DATETIME))
    } yield shootDateTime.toInstant.atOffset(ZoneOffset.UTC)
  }

  def extractCameraName(metadata: Metadata): Option[String] = {
    for {
      exif       <- Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
      cameraName <- Option(exif.getString(ExifDirectoryBase.TAG_MODEL))
    } yield cameraName
  }

  def extractGeoPoint(metadata: Metadata): Option[GeoPoint] = {
    for {
      gps       <- Option(metadata.getFirstDirectoryOfType(classOf[GpsDirectory]))
      altitude  <- Option(gps.getDouble(GpsDirectory.TAG_ALTITUDE))
      latitude  <- Option(gps.getGeoLocation).map(_.getLatitude).map(LatitudeDecimalDegrees.apply)
      longitude <- Option(gps.getGeoLocation).map(_.getLongitude).map(LongitudeDecimalDegrees.apply)
    } yield {
      GeoPoint(
        latitude,
        longitude,
        altitude
      )
    }
  }

  def extractDimension(metadata: Metadata): Option[Dimension2D] = {
    lazy val jpgDir =
      Option(metadata.getFirstDirectoryOfType(classOf[JpegDirectory]))
        .map(dir => Dimension2D(dir.getImageWidth, dir.getImageHeight))
    lazy val pngDir =
      Option(metadata.getFirstDirectoryOfType(classOf[PngDirectory]))
        .map(dir => Dimension2D(dir.getInt(PngDirectory.TAG_IMAGE_WIDTH), dir.getInt(PngDirectory.TAG_IMAGE_HEIGHT)))
    lazy val gifDir =
      Option(metadata.getFirstDirectoryOfType(classOf[GifImageDirectory]))
        .map(dir => Dimension2D(dir.getInt(GifImageDirectory.TAG_WIDTH), dir.getInt(GifImageDirectory.TAG_HEIGHT)))

    jpgDir.orElse(pngDir).orElse(gifDir)
  }

  def extractOrientation(metadata: Metadata): Option[PhotoOrientation] = {
    val result = for {
      exif            <- Option(metadata.getFirstDirectoryOfType(classOf[ExifIFD0Directory]))
      orientationCode <- Option(exif.getInt(ExifDirectoryBase.TAG_ORIENTATION))
    } yield {
      PhotoOrientation.values.find(_.code == orientationCode)
    }
    result.flatten
  }

}
