package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.model.*
import wvlet.airframe.ulid.ULID
import zio.json.internal.{RetractReader, Write}
import zio.json.{DeriveJsonCodec, JsonCodec, JsonDecoder, JsonEncoder, JsonError}

import java.nio.file.Path
import scala.util.matching.Regex

package object json {

  given originalIdCodec: JsonCodec[OriginalId] = new JsonCodec(
    (a: OriginalId, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.asString, indent, out),
    (trace: List[JsonError], in: RetractReader) => OriginalId(JsonDecoder.uuid.unsafeDecode(trace, in))
  )

  given storeIdCodec: JsonCodec[StoreId] = new JsonCodec(
    (a: StoreId, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.asString, indent, out),
    (trace: List[JsonError], in: RetractReader) => StoreId(JsonDecoder.uuid.unsafeDecode(trace, in))
  )

  given eventIdCodec: JsonCodec[EventId] = new JsonCodec(
    (a: EventId, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.asString, indent, out),
    (trace: List[JsonError], in: RetractReader) => EventId(JsonDecoder.uuid.unsafeDecode(trace, in))
  )

  given basicDirectoryPathCodec: JsonCodec[BaseDirectoryPath] = new JsonCodec(
    (a: BaseDirectoryPath, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.path.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => BaseDirectoryPath(Path.of(JsonDecoder.string.unsafeDecode(trace, in)))
  )

  given originalPathCodec: JsonCodec[OriginalPath] = new JsonCodec(
    (a: OriginalPath, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.path.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => OriginalPath(Path.of(JsonDecoder.string.unsafeDecode(trace, in)))
  )

  given detectedFacePathCodec: JsonCodec[DetectedFacePath] = new JsonCodec(
    (a: DetectedFacePath, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.path.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => DetectedFacePath(Path.of(JsonDecoder.string.unsafeDecode(trace, in)))
  )

  given normalizedPathCodec: JsonCodec[NormalizedPath] = new JsonCodec(
    (a: NormalizedPath, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.path.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => NormalizedPath(Path.of(JsonDecoder.string.unsafeDecode(trace, in)))
  )

  given eventMediaDirectoryCodec: JsonCodec[EventMediaDirectory] = new JsonCodec(
    (a: EventMediaDirectory, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.path.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => EventMediaDirectory(Path.of(JsonDecoder.string.unsafeDecode(trace, in)))
  )

  given ownerIdCodec: JsonCodec[OwnerId] = new JsonCodec(
    (a: OwnerId, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.asString, indent, out),
    (trace: List[JsonError], in: RetractReader) => OwnerId(ULID(JsonDecoder.string.unsafeDecode(trace, in)))
  )

  given faceIdCodec: JsonCodec[FaceId] = new JsonCodec(
    (a: FaceId, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.asString, indent, out),
    (trace: List[JsonError], in: RetractReader) => FaceId(ULID(JsonDecoder.string.unsafeDecode(trace, in)))
  )

  given personIdCodec: JsonCodec[PersonId] = new JsonCodec(
    (a: PersonId, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.asString, indent, out),
    (trace: List[JsonError], in: RetractReader) => PersonId(ULID(JsonDecoder.string.unsafeDecode(trace, in)))
  )

  given mediaAccessKeyCodec: JsonCodec[MediaAccessKey] = new JsonCodec(
    (a: MediaAccessKey, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.asString, indent, out),
    (trace: List[JsonError], in: RetractReader) => MediaAccessKey(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given originalHashCodec: JsonCodec[OriginalHash] = new JsonCodec(
    (a: OriginalHash, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => OriginalHash(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given fileSizeCodec: JsonCodec[FileSize] = new JsonCodec(
    (a: FileSize, indent: Option[Int], out: Write) => JsonEncoder.long.unsafeEncode(a.value, indent, out),
    (trace: List[JsonError], in: RetractReader) => FileSize(JsonDecoder.long.unsafeDecode(trace, in))
  )

  given widthCodec: JsonCodec[Width] = new JsonCodec(
    (a: Width, indent: Option[Int], out: Write) => JsonEncoder.int.unsafeEncode(a.value, indent, out),
    (trace: List[JsonError], in: RetractReader) => Width(JsonDecoder.int.unsafeDecode(trace, in))
  )

  given heightCodec: JsonCodec[Height] = new JsonCodec(
    (a: Height, indent: Option[Int], out: Write) => JsonEncoder.int.unsafeEncode(a.value, indent, out),
    (trace: List[JsonError], in: RetractReader) => Height(JsonDecoder.int.unsafeDecode(trace, in))
  )

  given latitudeDecimalDegreesCodec: JsonCodec[LatitudeDecimalDegrees] = new JsonCodec(
    (a: LatitudeDecimalDegrees, indent: Option[Int], out: Write) => JsonEncoder.double.unsafeEncode(a.doubleValue, indent, out),
    (trace: List[JsonError], in: RetractReader) => LatitudeDecimalDegrees(JsonDecoder.double.unsafeDecode(trace, in))
  )

  given longitudeDecimalDegreesCodec: JsonCodec[LongitudeDecimalDegrees] = new JsonCodec(
    (a: LongitudeDecimalDegrees, indent: Option[Int], out: Write) => JsonEncoder.double.unsafeEncode(a.doubleValue, indent, out),
    (trace: List[JsonError], in: RetractReader) => LongitudeDecimalDegrees(JsonDecoder.double.unsafeDecode(trace, in))
  )

  given altitudeMeanSeaLevelCodec: JsonCodec[AltitudeMeanSeaLevel] = new JsonCodec(
    (a: AltitudeMeanSeaLevel, indent: Option[Int], out: Write) => JsonEncoder.double.unsafeEncode(a.value, indent, out),
    (trace: List[JsonError], in: RetractReader) => AltitudeMeanSeaLevel(JsonDecoder.double.unsafeDecode(trace, in))
  )

  given apertureCodec: JsonCodec[Aperture] = new JsonCodec(
    (a: Aperture, indent: Option[Int], out: Write) => JsonEncoder.double.unsafeEncode(a.selected, indent, out),
    (trace: List[JsonError], in: RetractReader) => Aperture(JsonDecoder.double.unsafeDecode(trace, in))
  )

  given isoCodec: JsonCodec[ISO] = new JsonCodec(
    (a: ISO, indent: Option[Int], out: Write) => JsonEncoder.double.unsafeEncode(a.selected, indent, out),
    (trace: List[JsonError], in: RetractReader) => ISO(JsonDecoder.double.unsafeDecode(trace, in))
  )

  given focalLengthCodec: JsonCodec[FocalLength] = new JsonCodec(
    (a: FocalLength, indent: Option[Int], out: Write) => JsonEncoder.double.unsafeEncode(a.selected, indent, out),
    (trace: List[JsonError], in: RetractReader) => FocalLength(JsonDecoder.double.unsafeDecode(trace, in))
  )

  given fileLastModifiedCodec: JsonCodec[FileLastModified] = new JsonCodec(
    (a: FileLastModified, indent: Option[Int], out: Write) => JsonEncoder.offsetDateTime.unsafeEncode(a.offsetDateTime, indent, out),
    (trace: List[JsonError], in: RetractReader) => FileLastModified(JsonDecoder.offsetDateTime.unsafeDecode(trace, in))
  )

  given shootDateTimeCodec: JsonCodec[ShootDateTime] = new JsonCodec(
    (a: ShootDateTime, indent: Option[Int], out: Write) => JsonEncoder.offsetDateTime.unsafeEncode(a.offsetDateTime, indent, out),
    (trace: List[JsonError], in: RetractReader) => ShootDateTime(JsonDecoder.offsetDateTime.unsafeDecode(trace, in))
  )

  given birthDateCodec: JsonCodec[BirthDate] = new JsonCodec(
    (a: BirthDate, indent: Option[Int], out: Write) => JsonEncoder.offsetDateTime.unsafeEncode(a.offsetDateTime, indent, out),
    (trace: List[JsonError], in: RetractReader) => BirthDate(JsonDecoder.offsetDateTime.unsafeDecode(trace, in))
  )

  given addedOnCodec: JsonCodec[AddedOn] = new JsonCodec(
    (a: AddedOn, indent: Option[Int], out: Write) => JsonEncoder.offsetDateTime.unsafeEncode(a.offsetDateTime, indent, out),
    (trace: List[JsonError], in: RetractReader) => AddedOn(JsonDecoder.offsetDateTime.unsafeDecode(trace, in))
  )

  given lastCheckedCodec: JsonCodec[LastChecked] = new JsonCodec(
    (a: LastChecked, indent: Option[Int], out: Write) => JsonEncoder.offsetDateTime.unsafeEncode(a.offsetDateTime, indent, out),
    (trace: List[JsonError], in: RetractReader) => LastChecked(JsonDecoder.offsetDateTime.unsafeDecode(trace, in))
  )

  given lastSynchronizedCodec: JsonCodec[LastSynchronized] = new JsonCodec(
    (a: LastSynchronized, indent: Option[Int], out: Write) => JsonEncoder.offsetDateTime.unsafeEncode(a.offsetDateTime, indent, out),
    (trace: List[JsonError], in: RetractReader) => LastSynchronized(JsonDecoder.offsetDateTime.unsafeDecode(trace, in))
  )

  given cameraNameCodec: JsonCodec[CameraName] = new JsonCodec(
    (a: CameraName, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => CameraName(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given artistInfoCodec: JsonCodec[ArtistInfo] = new JsonCodec(
    (a: ArtistInfo, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => ArtistInfo(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given eventNameCodec: JsonCodec[EventName] = new JsonCodec(
    (a: EventName, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => EventName(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given storeNameCodec: JsonCodec[StoreName] = new JsonCodec(
    (a: StoreName, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => StoreName(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given firstNameCodec: JsonCodec[FirstName] = new JsonCodec(
    (a: FirstName, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => FirstName(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given lastNameCodec: JsonCodec[LastName] = new JsonCodec(
    (a: LastName, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => LastName(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given eventDescriptionCodec: JsonCodec[EventDescription] = new JsonCodec(
    (a: EventDescription, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => EventDescription(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given mediaDescriptionCodec: JsonCodec[MediaDescription] = new JsonCodec(
    (a: MediaDescription, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => MediaDescription(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given personDescriptionCodec: JsonCodec[PersonDescription] = new JsonCodec(
    (a: PersonDescription, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => PersonDescription(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given orientationCodec: JsonCodec[Orientation] = new JsonCodec(
    (a: Orientation, indent: Option[Int], out: Write) => JsonEncoder.int.unsafeEncode(a.ordinal, indent, out),
    (trace: List[JsonError], in: RetractReader) => Orientation.fromOrdinal(JsonDecoder.int.unsafeDecode(trace, in))
  )

  given mediaKindCodec: JsonCodec[MediaKind] = new JsonCodec(
    (a: MediaKind, indent: Option[Int], out: Write) => JsonEncoder.int.unsafeEncode(a.ordinal, indent, out),
    (trace: List[JsonError], in: RetractReader) => MediaKind.fromOrdinal(JsonDecoder.int.unsafeDecode(trace, in))
  )

  given keywordCodec: JsonCodec[Keyword] = new JsonCodec(
    (a: Keyword, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => Keyword(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given starredCodec: JsonCodec[Starred] = new JsonCodec(
    (a: Starred, indent: Option[Int], out: Write) => JsonEncoder.boolean.unsafeEncode(a.value, indent, out),
    (trace: List[JsonError], in: RetractReader) => Starred(JsonDecoder.boolean.unsafeDecode(trace, in))
  )

  given includeMaskCodec: JsonCodec[IncludeMask] = new JsonCodec(
    (a: IncludeMask, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.regex.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => IncludeMask(JsonDecoder.string.unsafeDecode(trace, in).r)
  )

  given ignoreMaskCodec: JsonCodec[IgnoreMask] = new JsonCodec(
    (a: IgnoreMask, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.regex.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => IgnoreMask(JsonDecoder.string.unsafeDecode(trace, in).r)
  )

}
