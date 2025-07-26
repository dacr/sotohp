package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.media.model.*
import wvlet.airframe.ulid.ULID
import zio.json.internal.{RetractReader, Write}
import zio.json.{DeriveJsonCodec, JsonCodec, JsonDecoder, JsonEncoder, JsonError}

import java.nio.file.Path
import scala.util.matching.Regex

package object dao {

  given JsonCodec[OriginalId] = new JsonCodec(
    (a: OriginalId, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.asString, indent, out),
    (trace: List[JsonError], in: RetractReader) => OriginalId(JsonDecoder.uuid.unsafeDecode(trace, in))
  )

  given JsonCodec[StoreId] = new JsonCodec(
    (a: StoreId, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.asString, indent, out),
    (trace: List[JsonError], in: RetractReader) => StoreId(JsonDecoder.uuid.unsafeDecode(trace, in))
  )

  given JsonCodec[EventId] = new JsonCodec(
    (a: EventId, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.asString, indent, out),
    (trace: List[JsonError], in: RetractReader) => EventId(JsonDecoder.uuid.unsafeDecode(trace, in))
  )

  given JsonCodec[BaseDirectoryPath] = new JsonCodec(
    (a: BaseDirectoryPath, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.path.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => BaseDirectoryPath(Path.of(JsonDecoder.string.unsafeDecode(trace, in)))
  )

  given JsonCodec[OriginalPath] = new JsonCodec(
    (a: OriginalPath, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.path.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => OriginalPath(Path.of(JsonDecoder.string.unsafeDecode(trace, in)))
  )

  given JsonCodec[EventMediaDirectory] = new JsonCodec(
    (a: EventMediaDirectory, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.path.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => EventMediaDirectory(Path.of(JsonDecoder.string.unsafeDecode(trace, in)))
  )

  given JsonCodec[OwnerId] = new JsonCodec(
    (a: OwnerId, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.asString, indent, out),
    (trace: List[JsonError], in: RetractReader) => OwnerId(ULID(JsonDecoder.string.unsafeDecode(trace, in)))
  )

  given JsonCodec[MediaAccessKey] = new JsonCodec(
    (a: MediaAccessKey, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.asString, indent, out),
    (trace: List[JsonError], in: RetractReader) => MediaAccessKey(ULID(JsonDecoder.string.unsafeDecode(trace, in)))
  )

  given JsonCodec[OriginalHash] = new JsonCodec(
    (a: OriginalHash, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => OriginalHash(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given JsonCodec[FileSize] = new JsonCodec(
    (a: FileSize, indent: Option[Int], out: Write) => JsonEncoder.long.unsafeEncode(a.value, indent, out),
    (trace: List[JsonError], in: RetractReader) => FileSize(JsonDecoder.long.unsafeDecode(trace, in))
  )

  given JsonCodec[Width] = new JsonCodec(
    (a: Width, indent: Option[Int], out: Write) => JsonEncoder.int.unsafeEncode(a.value, indent, out),
    (trace: List[JsonError], in: RetractReader) => Width(JsonDecoder.int.unsafeDecode(trace, in))
  )

  given JsonCodec[Height] = new JsonCodec(
    (a: Height, indent: Option[Int], out: Write) => JsonEncoder.int.unsafeEncode(a.value, indent, out),
    (trace: List[JsonError], in: RetractReader) => Height(JsonDecoder.int.unsafeDecode(trace, in))
  )

  given JsonCodec[LatitudeDecimalDegrees] = new JsonCodec(
    (a: LatitudeDecimalDegrees, indent: Option[Int], out: Write) => JsonEncoder.double.unsafeEncode(a.doubleValue, indent, out),
    (trace: List[JsonError], in: RetractReader) => LatitudeDecimalDegrees(JsonDecoder.double.unsafeDecode(trace, in))
  )

  given JsonCodec[LongitudeDecimalDegrees] = new JsonCodec(
    (a: LongitudeDecimalDegrees, indent: Option[Int], out: Write) => JsonEncoder.double.unsafeEncode(a.doubleValue, indent, out),
    (trace: List[JsonError], in: RetractReader) => LongitudeDecimalDegrees(JsonDecoder.double.unsafeDecode(trace, in))
  )

  given JsonCodec[AltitudeMeanSeaLevel] = new JsonCodec(
    (a: AltitudeMeanSeaLevel, indent: Option[Int], out: Write) => JsonEncoder.double.unsafeEncode(a.value, indent, out),
    (trace: List[JsonError], in: RetractReader) => AltitudeMeanSeaLevel(JsonDecoder.double.unsafeDecode(trace, in))
  )

  given JsonCodec[Aperture] = new JsonCodec(
    (a: Aperture, indent: Option[Int], out: Write) => JsonEncoder.double.unsafeEncode(a.selected, indent, out),
    (trace: List[JsonError], in: RetractReader) => Aperture(JsonDecoder.double.unsafeDecode(trace, in))
  )

  given JsonCodec[ISO] = new JsonCodec(
    (a: ISO, indent: Option[Int], out: Write) => JsonEncoder.double.unsafeEncode(a.selected, indent, out),
    (trace: List[JsonError], in: RetractReader) => ISO(JsonDecoder.double.unsafeDecode(trace, in))
  )

  case class EncodedExposureTime(numerator: Long, denominator: Long)
  given encodedExposureTimeCodec: JsonCodec[EncodedExposureTime] = DeriveJsonCodec.gen[EncodedExposureTime]

  given JsonCodec[ExposureTime] = new JsonCodec(
    encodedExposureTimeCodec.encoder.contramap(a => EncodedExposureTime(a.numerator, a.denominator)),
    encodedExposureTimeCodec.decoder.map(a => ExposureTime(a.numerator, a.denominator))
  )

  given JsonCodec[FocalLength] = new JsonCodec(
    (a: FocalLength, indent: Option[Int], out: Write) => JsonEncoder.double.unsafeEncode(a.selected, indent, out),
    (trace: List[JsonError], in: RetractReader) => FocalLength(JsonDecoder.double.unsafeDecode(trace, in))
  )

  given JsonCodec[FileLastModified] = new JsonCodec(
    (a: FileLastModified, indent: Option[Int], out: Write) => JsonEncoder.offsetDateTime.unsafeEncode(a.offsetDateTime, indent, out),
    (trace: List[JsonError], in: RetractReader) => FileLastModified(JsonDecoder.offsetDateTime.unsafeDecode(trace, in))
  )

  given JsonCodec[ShootDateTime] = new JsonCodec(
    (a: ShootDateTime, indent: Option[Int], out: Write) => JsonEncoder.offsetDateTime.unsafeEncode(a.offsetDateTime, indent, out),
    (trace: List[JsonError], in: RetractReader) => ShootDateTime(JsonDecoder.offsetDateTime.unsafeDecode(trace, in))
  )

  given JsonCodec[BirthDate] = new JsonCodec(
    (a: BirthDate, indent: Option[Int], out: Write) => JsonEncoder.offsetDateTime.unsafeEncode(a.offsetDateTime, indent, out),
    (trace: List[JsonError], in: RetractReader) => BirthDate(JsonDecoder.offsetDateTime.unsafeDecode(trace, in))
  )

  given JsonCodec[AddedOn] = new JsonCodec(
    (a: AddedOn, indent: Option[Int], out: Write) => JsonEncoder.offsetDateTime.unsafeEncode(a.offsetDateTime, indent, out),
    (trace: List[JsonError], in: RetractReader) => AddedOn(JsonDecoder.offsetDateTime.unsafeDecode(trace, in))
  )

  given JsonCodec[LastChecked] = new JsonCodec(
    (a: LastChecked, indent: Option[Int], out: Write) => JsonEncoder.offsetDateTime.unsafeEncode(a.offsetDateTime, indent, out),
    (trace: List[JsonError], in: RetractReader) => LastChecked(JsonDecoder.offsetDateTime.unsafeDecode(trace, in))
  )

  given JsonCodec[LastSynchronized] = new JsonCodec(
    (a: LastSynchronized, indent: Option[Int], out: Write) => JsonEncoder.offsetDateTime.unsafeEncode(a.offsetDateTime, indent, out),
    (trace: List[JsonError], in: RetractReader) => LastSynchronized(JsonDecoder.offsetDateTime.unsafeDecode(trace, in))
  )

  given JsonCodec[CameraName] = new JsonCodec(
    (a: CameraName, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => CameraName(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given JsonCodec[ArtistInfo] = new JsonCodec(
    (a: ArtistInfo, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => ArtistInfo(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given JsonCodec[EventName] = new JsonCodec(
    (a: EventName, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => EventName(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given JsonCodec[FirstName] = new JsonCodec(
    (a: FirstName, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => FirstName(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given JsonCodec[LastName] = new JsonCodec(
    (a: LastName, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => LastName(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given JsonCodec[EventDescription] = new JsonCodec(
    (a: EventDescription, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => EventDescription(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given JsonCodec[MediaDescription] = new JsonCodec(
    (a: MediaDescription, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => MediaDescription(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given JsonCodec[Orientation] = new JsonCodec(
    (a: Orientation, indent: Option[Int], out: Write) => JsonEncoder.int.unsafeEncode(a.ordinal, indent, out),
    (trace: List[JsonError], in: RetractReader) => Orientation.fromOrdinal(JsonDecoder.int.unsafeDecode(trace, in))
  )

  given JsonCodec[MediaKind] = new JsonCodec(
    (a: MediaKind, indent: Option[Int], out: Write) => JsonEncoder.int.unsafeEncode(a.ordinal, indent, out),
    (trace: List[JsonError], in: RetractReader) => MediaKind.fromOrdinal(JsonDecoder.int.unsafeDecode(trace, in))
  )

  given JsonCodec[Keyword] = new JsonCodec(
    (a: Keyword, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => Keyword(JsonDecoder.string.unsafeDecode(trace, in))
  )

  given JsonCodec[Starred] = new JsonCodec(
    (a: Starred, indent: Option[Int], out: Write) => JsonEncoder.boolean.unsafeEncode(a.value, indent, out),
    (trace: List[JsonError], in: RetractReader) => Starred(JsonDecoder.boolean.unsafeDecode(trace, in))
  )

  given JsonCodec[IncludeMask] = new JsonCodec(
    (a: IncludeMask, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.regex.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => IncludeMask(JsonDecoder.string.unsafeDecode(trace, in).r)
  )

  given JsonCodec[IgnoreMask] = new JsonCodec(
    (a: IgnoreMask, indent: Option[Int], out: Write) => JsonEncoder.string.unsafeEncode(a.regex.toString, indent, out),
    (trace: List[JsonError], in: RetractReader) => IgnoreMask(JsonDecoder.string.unsafeDecode(trace, in).r)
  )

}
