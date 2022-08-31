package fr.janalyse.sotohp.core

import zio.*
import zio.json.*

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.{ExifDirectoryBase, ExifIFD0Directory, ExifSubIFDDirectory}
import com.fasterxml.uuid.Generators

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import java.util.UUID
import java.nio.file.{Files, Path, Paths}
import scala.util.Try

case class Photo(
  uuid: UUID,
  timestamp: OffsetDateTime,
  filePath: Path,
  fileSize: Long,
  fileHash: String,
  fileLastUpdated: OffsetDateTime,
  category: Option[String],
  shootDateTime: Option[OffsetDateTime],
  camera: Option[String],
  tags: Map[String, String],
  keywords: List[String],
  classifications: List[String],
  detectedObjects: List[String],
  place: Option[GeoPoint]
)

object Photo {
  given pathEncoder: JsonEncoder[Path] = JsonEncoder[String].contramap(p => p.toString)
  given pathDecoder: JsonDecoder[Path] = JsonDecoder[String].map(p => Path.of(p))
  given JsonCodec[Photo]               = DeriveJsonCodec.gen

  def makeTagKey(tag: com.drew.metadata.Tag): String = {
    val prefix = tag.getDirectoryName().trim.replaceAll("""\s+""", "")
    val name   = tag.getTagName().trim.replaceAll("""\s+""", "")
    val key    = s"$prefix$name"
    key.head.toLower + key.tail
  }

  def tagsToMap(tags: List[com.drew.metadata.Tag]): Map[String, String] = {
    tags
      .filterNot(_.getDescription == null)
      .map(tag => makeTagKey(tag) -> tag.getDescription)
      .toMap
  }

  def now = OffsetDateTime.now() // TODO : migrate to ZIO Clock.now

  def checkTimestampValid(ts: OffsetDateTime) = ts.isBefore(now)

  def computeTimestamp(mayBeShootDateTime: Option[OffsetDateTime], fileLastUpdated: OffsetDateTime): OffsetDateTime =
    mayBeShootDateTime.filter(checkTimestampValid).getOrElse(fileLastUpdated)

  def makePhoto(
    uuid: UUID,
    filePath: Path,
    fileSize: Long,
    fileHash: String,
    fileLastUpdated: Instant,
    category: Option[String],
    shootDateTime: Option[Instant],
    camera: Option[String],
    metaDataTags: List[com.drew.metadata.Tag],
    keywords: List[String],        // Extracted from category
    classifications: List[String], // Extracted from AI DJL
    detectedObjects: List[String]  // Extracted from AI DJL
  ): Photo = {
    val shootOffsetDateTime           = shootDateTime.map(_.atOffset(ZoneOffset.UTC))
    val fileLastUpdatedOffsetDateTime = fileLastUpdated.atOffset(ZoneOffset.UTC)
    val tags                          = tagsToMap(metaDataTags)
    Photo(
      uuid = uuid,
      timestamp = computeTimestamp(shootOffsetDateTime, fileLastUpdatedOffsetDateTime),
      filePath = filePath,
      fileSize = fileSize,
      fileHash = fileHash,
      fileLastUpdated = fileLastUpdatedOffsetDateTime,
      category = category,
      shootDateTime = shootOffsetDateTime,
      camera = camera,
      tags = tags,
      keywords = keywords,
      classifications = classifications,
      detectedObjects = detectedObjects,
      place = GeoPoint.computeGeoPoint(tags)
    )
  }

}
