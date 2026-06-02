package fr.janalyse.sotohp.service

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.model.*
import wvlet.airframe.ulid.ULID

import java.nio.file.Path

package object json {

  private def codec[T](
    enc: (T, JsonWriter) => Unit,
    dec: JsonReader => T
  ): JsonValueCodec[T] = new JsonValueCodec[T] {
    override def nullValue: T                               = null.asInstanceOf[T]
    override def encodeValue(x: T, out: JsonWriter): Unit   = enc(x, out)
    override def decodeValue(in: JsonReader, default: T): T = dec(in)
  }

  given originalIdCodec: JsonValueCodec[OriginalId] = codec(
    (x, out) => out.writeVal(x.asUUID),
    in => OriginalId(in.readUUID(null))
  )

  given storeIdCodec: JsonValueCodec[StoreId] = codec(
    (x, out) => out.writeVal(x.asUUID),
    in => StoreId(in.readUUID(null))
  )

  given eventIdCodec: JsonValueCodec[EventId] = codec(
    (x, out) => out.writeVal(x.asUUID),
    in => EventId(in.readUUID(null))
  )

  given portfolioIdCodec: JsonValueCodec[PortfolioId] = codec(
    (x, out) => out.writeVal(x.asUUID),
    in => PortfolioId(in.readUUID(null))
  )

  given portfolioNameCodec: JsonValueCodec[PortfolioName] = codec(
    (x, out) => out.writeVal(x.text),
    in => PortfolioName(in.readString(null))
  )

  given portfolioDescriptionCodec: JsonValueCodec[PortfolioDescription] = codec(
    (x, out) => out.writeVal(x.text),
    in => PortfolioDescription(in.readString(null))
  )

  given assetDescriptionCodec: JsonValueCodec[AssetDescription] = codec(
    (x, out) => out.writeVal(x.text),
    in => AssetDescription(in.readString(null))
  )

  given basicDirectoryPathCodec: JsonValueCodec[BaseDirectoryPath] = codec(
    (x, out) => out.writeVal(x.path.toString),
    in => BaseDirectoryPath(Path.of(in.readString(null)))
  )

  given originalPathCodec: JsonValueCodec[OriginalPath] = codec(
    (x, out) => out.writeVal(x.path.toString),
    in => OriginalPath(Path.of(in.readString(null)))
  )

  given detectedFacePathCodec: JsonValueCodec[FacePath] = codec(
    (x, out) => out.writeVal(x.path.toString),
    in => FacePath(Path.of(in.readString(null)))
  )

  given normalizedPathCodec: JsonValueCodec[NormalizedPath] = codec(
    (x, out) => out.writeVal(x.path.toString),
    in => NormalizedPath(Path.of(in.readString(null)))
  )

  given eventMediaDirectoryCodec: JsonValueCodec[EventMediaDirectory] = codec(
    (x, out) => out.writeVal(x.path.toString),
    in => EventMediaDirectory(Path.of(in.readString(null)))
  )

  given ownerIdCodec: JsonValueCodec[OwnerId] = codec(
    (x, out) => out.writeVal(x.asString),
    in => OwnerId(ULID(in.readString(null)))
  )

  given faceIdCodec: JsonValueCodec[FaceId] = codec(
    (x, out) => out.writeVal(x.asString),
    in => FaceId(ULID(in.readString(null)))
  )

  given personIdCodec: JsonValueCodec[PersonId] = codec(
    (x, out) => out.writeVal(x.asString),
    in => PersonId(ULID(in.readString(null)))
  )

  given mediaAccessKeyCodec: JsonValueCodec[MediaAccessKey] = codec(
    (x, out) => out.writeVal(x.asString),
    in => MediaAccessKey(in.readString(null))
  )

  given originalHashCodec: JsonValueCodec[OriginalHash] = codec(
    (x, out) => out.writeVal(x.toString),
    in => OriginalHash(in.readString(null))
  )

  given fileSizeCodec: JsonValueCodec[FileSize] = codec(
    (x, out) => out.writeVal(x.value),
    in => FileSize(in.readLong())
  )

  given widthCodec: JsonValueCodec[Width] = codec(
    (x, out) => out.writeVal(x.value),
    in => Width(in.readInt())
  )

  given heightCodec: JsonValueCodec[Height] = codec(
    (x, out) => out.writeVal(x.value),
    in => Height(in.readInt())
  )

  given latitudeDecimalDegreesCodec: JsonValueCodec[LatitudeDecimalDegrees] = codec(
    (x, out) => out.writeVal(x.doubleValue),
    in => LatitudeDecimalDegrees(in.readDouble())
  )

  given longitudeDecimalDegreesCodec: JsonValueCodec[LongitudeDecimalDegrees] = codec(
    (x, out) => out.writeVal(x.doubleValue),
    in => LongitudeDecimalDegrees(in.readDouble())
  )

  given altitudeMeanSeaLevelCodec: JsonValueCodec[AltitudeMeanSeaLevel] = codec(
    (x, out) => out.writeVal(x.value),
    in => AltitudeMeanSeaLevel(in.readDouble())
  )

  given apertureCodec: JsonValueCodec[Aperture] = codec(
    (x, out) => out.writeVal(x.selected),
    in => Aperture(in.readDouble())
  )

  given isoCodec: JsonValueCodec[ISO] = codec(
    (x, out) => out.writeVal(x.selected),
    in => ISO(in.readDouble())
  )

  given focalLengthCodec: JsonValueCodec[FocalLength] = codec(
    (x, out) => out.writeVal(x.selected),
    in => FocalLength(in.readDouble())
  )

  given fileLastModifiedCodec: JsonValueCodec[FileLastModified] = codec(
    (x, out) => out.writeVal(x.offsetDateTime),
    in => FileLastModified(in.readOffsetDateTime(null))
  )

  given shootDateTimeCodec: JsonValueCodec[ShootDateTime] = codec(
    (x, out) => out.writeVal(x.offsetDateTime),
    in => ShootDateTime(in.readOffsetDateTime(null))
  )

  given birthDateCodec: JsonValueCodec[BirthDate] = codec(
    (x, out) => out.writeVal(x.offsetDateTime),
    in => BirthDate(in.readOffsetDateTime(null))
  )

  given addedOnCodec: JsonValueCodec[AddedOn] = codec(
    (x, out) => out.writeVal(x.offsetDateTime),
    in => AddedOn(in.readOffsetDateTime(null))
  )

  given lastCheckedCodec: JsonValueCodec[LastChecked] = codec(
    (x, out) => out.writeVal(x.offsetDateTime),
    in => LastChecked(in.readOffsetDateTime(null))
  )

  given lastSynchronizedCodec: JsonValueCodec[LastSynchronized] = codec(
    (x, out) => out.writeVal(x.offsetDateTime),
    in => LastSynchronized(in.readOffsetDateTime(null))
  )

  given cameraNameCodec: JsonValueCodec[CameraName] = codec(
    (x, out) => out.writeVal(x.toString),
    in => CameraName(in.readString(null))
  )

  given artistInfoCodec: JsonValueCodec[ArtistInfo] = codec(
    (x, out) => out.writeVal(x.toString),
    in => ArtistInfo(in.readString(null))
  )

  given eventNameCodec: JsonValueCodec[EventName] = codec(
    (x, out) => out.writeVal(x.toString),
    in => EventName(in.readString(null))
  )

  given storeNameCodec: JsonValueCodec[StoreName] = codec(
    (x, out) => out.writeVal(x.toString),
    in => StoreName(in.readString(null))
  )

  given firstNameCodec: JsonValueCodec[FirstName] = codec(
    (x, out) => out.writeVal(x.toString),
    in => FirstName(in.readString(null))
  )

  given lastNameCodec: JsonValueCodec[LastName] = codec(
    (x, out) => out.writeVal(x.toString),
    in => LastName(in.readString(null))
  )

  given eventDescriptionCodec: JsonValueCodec[EventDescription] = codec(
    (x, out) => out.writeVal(x.toString),
    in => EventDescription(in.readString(null))
  )

  given mediaDescriptionCodec: JsonValueCodec[MediaDescription] = codec(
    (x, out) => out.writeVal(x.toString),
    in => MediaDescription(in.readString(null))
  )

  given personDescriptionCodec: JsonValueCodec[PersonDescription] = codec(
    (x, out) => out.writeVal(x.toString),
    in => PersonDescription(in.readString(null))
  )

  given personEmailCodec: JsonValueCodec[PersonEmail] = codec(
    (x, out) => out.writeVal(x.text),
    in => PersonEmail(in.readString(null))
  )

  given orientationCodec: JsonValueCodec[Orientation] = codec(
    (x, out) => out.writeVal(x.ordinal),
    in => Orientation.fromOrdinal(in.readInt())
  )

  given mediaKindCodec: JsonValueCodec[MediaKind] = codec(
    (x, out) => out.writeVal(x.ordinal),
    in => MediaKind.fromOrdinal(in.readInt())
  )

  given keywordCodec: JsonValueCodec[Keyword] = codec(
    (x, out) => out.writeVal(x.toString),
    in => Keyword(in.readString(null))
  )

  given starredCodec: JsonValueCodec[Starred] = codec(
    (x, out) => out.writeVal(x.value),
    in => Starred(in.readBoolean())
  )

  given includeMaskCodec: JsonValueCodec[IncludeMask] = codec(
    (x, out) => out.writeVal(x.regex.toString),
    in => IncludeMask(in.readString(null).r)
  )

  given ignoreMaskCodec: JsonValueCodec[IgnoreMask] = codec(
    (x, out) => out.writeVal(x.regex.toString),
    in => IgnoreMask(in.readString(null).r)
  )

}
