package fr.janalyse.sotohp

import java.time.OffsetDateTime
import java.util.{Locale, UUID}
import wvlet.airframe.ulid.ULID
import java.time.OffsetDateTime
import scala.util.matching.Regex
import java.io.File
import java.nio.file.Path
import java.time.OffsetDateTime
import scala.annotation.targetName
import scala.math.{pow, sqrt}
import scala.util.{Failure, Success, Try}

package object model {

  // -------------------------------------------------------------------------------------------------------------------
  opaque type OwnerId = ULID

  object OwnerId {
    def apply(id: ULID): OwnerId        = id
    @deprecated
    def fromString(id: String): OwnerId = OwnerId(ULID(id)) // TODO unsafe

    extension (ownerId: OwnerId) {
      def asString: String = ownerId.toString
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // use a default encoding that optimize speed to access medias using a default time based order
  opaque type MediaAccessKey = (ownerId: OwnerId, ulid: ULID)

  object MediaAccessKey {
    private val sep      = "/"
    private val ulidSize = 26

    def apply(ownerId: OwnerId, ulid: ULID): MediaAccessKey = (ownerId, ulid)

    def apply(input: String): MediaAccessKey = {
      if (input.length < (ulidSize + 1)) throw new IllegalArgumentException(s"given MediaAccessKey input ($input) is invalid")
      else {
        val ownerIdStr = input.substring(0, ulidSize)
        val ulidStr    = input.substring(ulidSize + 1)
        (OwnerId(ULID(ownerIdStr)), ULID(ulidStr))
      }
    }

    extension (mediaAccessKey: MediaAccessKey) {
      def asString: String = s"${mediaAccessKey.ownerId.toString}$sep${mediaAccessKey.ulid.toString}" // natural ordering by (owner / timestamp)
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type MediaDescription = String

  object MediaDescription {
    def apply(description: String): MediaDescription = description

    extension (mediaDescription: MediaDescription) {
      def text: String = mediaDescription
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type EventId = UUID

  object EventId {
    def apply(id: UUID): EventId = id

    extension (eventId: EventId) {
      def asString: String = eventId.toString
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type EventName = String

  object EventName {
    def apply(name: String): EventName = name

    extension (eventName: EventName) {
      def text: String = eventName
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type EventDescription = String

  object EventDescription {
    def apply(description: String): EventDescription = description

    extension (eventDescription: EventDescription) {
      def text: String = eventDescription
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type EventMediaDirectory = Path

  object EventMediaDirectory {
    def apply(path: Path): EventMediaDirectory = path

    extension (eventMediaDirectory: EventMediaDirectory) {
      def path: Path = eventMediaDirectory
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type Starred = Boolean

  object Starred {
    def apply(starred: Boolean): Starred = starred

    extension (starred: Starred) {
      def value: Boolean = starred
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  enum MediaKind(code: Int) {
    case Photo extends MediaKind(0)
    case Video extends MediaKind(1)
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type OriginalId = UUID

  object OriginalId {
    def apply(id: UUID): OriginalId = id

    extension (originalId: OriginalId) {
      def asString: String = originalId.toString
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type CameraName = String

  object CameraName {
    def apply(name: String): CameraName = name

    extension (cameraName: CameraName) {
      def text: String = cameraName
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type BaseDirectoryPath = Path

  object BaseDirectoryPath {
    def apply(path: Path): BaseDirectoryPath = path

    extension (baseDirPath: BaseDirectoryPath) {
      def path: Path = baseDirPath
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type OriginalPath = Path

  object OriginalPath {
    def apply(path: Path): OriginalPath = path

    extension (originalPath: OriginalPath) {
      def parent: Path      = originalPath.getParent
      def file: File        = originalPath.toFile
      def path: Path        = originalPath
      def fileName: String  = originalPath.getFileName.toString
      def extension: String = originalPath.getFileName.toString.split("\\.").last
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type ShootDateTime = OffsetDateTime

  object ShootDateTime {
    def apply(timeStamp: OffsetDateTime): ShootDateTime = timeStamp

    extension (shootDateTime: ShootDateTime) {
      def year: Int                      = shootDateTime.getYear
      def offsetDateTime: OffsetDateTime = shootDateTime
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type FileSize = Long

  object FileSize {
    def apply(size: Long): FileSize = size

    extension (fileSize: FileSize) {
      def value: Long = fileSize
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type FileLastModified = OffsetDateTime

  object FileLastModified {
    def apply(timeStamp: OffsetDateTime): FileLastModified = timeStamp

    extension (fileLastModified: FileLastModified) {
      def offsetDateTime: OffsetDateTime = fileLastModified
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type OriginalHash = String

  object OriginalHash {
    def apply(hash: String): OriginalHash = hash

    extension (originalHash: OriginalHash) {
      def code: String = originalHash
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type FirstName = String

  object FirstName {
    def apply(name: String): FirstName = name
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type LastName = String

  object LastName {
    def apply(name: String): LastName = name
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type BirthDate = OffsetDateTime

  object BirthDate {
    def apply(date: OffsetDateTime): BirthDate = date

    extension (birthDate: BirthDate) {
      def offsetDateTime: OffsetDateTime = birthDate
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type LastChecked = OffsetDateTime

  object LastChecked {
    def apply(date: OffsetDateTime): LastChecked = date

    extension (lastChecked: LastChecked) {
      def offsetDateTime: OffsetDateTime = lastChecked
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type LastSynchronized = OffsetDateTime

  object LastSynchronized {
    def apply(date: OffsetDateTime): LastSynchronized = date

    extension (lastSynchronized: LastSynchronized) {
      def offsetDateTime: OffsetDateTime = lastSynchronized
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type AddedOn = OffsetDateTime

  object AddedOn {
    def apply(timeStamp: OffsetDateTime): AddedOn = timeStamp

    extension (addedOn: AddedOn) {
      def offsetDateTime: OffsetDateTime = addedOn
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type Keyword = String
  object Keyword {
    def apply(keyword: String): Keyword = keyword
    extension (keyword: Keyword) {
      def text: String = keyword
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type StoreId = UUID

  object StoreId {
    def apply(id: UUID): StoreId        = id
    @deprecated
    def fromString(id: String): StoreId = StoreId(UUID.fromString(id)) // TODO unsafe

    extension (storeId: StoreId) {
      def asString: String = storeId.toString
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type IncludeMask = Regex

  object IncludeMask {
    def apply(regex: Regex): IncludeMask      = regex
    @deprecated
    def fromString(mask: String): IncludeMask = apply(mask.r) // TODO unsafe

    extension (includeMask: IncludeMask) {
      def isIncluded(path: String): Boolean = includeMask.findFirstIn(path).isDefined
      def regex: Regex                      = includeMask
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type IgnoreMask = Regex

  object IgnoreMask {
    def apply(regex: Regex): IgnoreMask      = regex
    @deprecated
    def fromString(mask: String): IgnoreMask = mask.r

    extension (ignoreMaskRegex: IgnoreMask) {
      def isIgnored(path: String): Boolean = ignoreMaskRegex.findFirstIn(path).isDefined
      def regex: Regex                     = ignoreMaskRegex
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type AltitudeMeanSeaLevel = Double // https://en.wikipedia.org/wiki/Sea_level

  object AltitudeMeanSeaLevel {
    def apply(value: Double): AltitudeMeanSeaLevel = value

    extension (alt: AltitudeMeanSeaLevel) {
      def value: Double = alt
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type LatitudeDecimalDegrees = Double // https://en.wikipedia.org/wiki/Decimal_degrees

  object LatitudeDecimalDegrees {
    def apply(value: Double): LatitudeDecimalDegrees = value

    extension (dd: LatitudeDecimalDegrees) {
      def toDegreeMinuteSeconds: LatitudeDegreeMinuteSeconds = ??? // https://en.wikipedia.org/wiki/Decimal_degrees

      def doubleValue: Double = dd
      def toRadians: Double   = dd * Math.PI / 180d
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type LongitudeDecimalDegrees = Double // https://en.wikipedia.org/wiki/Decimal_degrees

  object LongitudeDecimalDegrees {
    def apply(value: Double): LongitudeDecimalDegrees = value

    extension (dd: LongitudeDecimalDegrees) {
      def toDegreeMinuteSeconds: LongitudeDegreeMinuteSeconds = ??? // https://en.wikipedia.org/wiki/Decimal_degrees

      def doubleValue: Double = dd
      def toRadians: Double   = dd * Math.PI / 180d
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type LatitudeDegreeMinuteSeconds = String // https://en.wikipedia.org/wiki/Decimal_degrees

  object LatitudeDegreeMinuteSeconds {
    def fromSpec(dmsFullSpec: String): Try[LatitudeDegreeMinuteSeconds] = {
      if (!DegreeMinuteSeconds.latitudeDmsRE.matches(dmsFullSpec))
        Failure(IllegalArgumentException(s"given DegreeMinuteSeconds latitude spec ($dmsFullSpec) is invalid"))
      else Success(DegreeMinuteSeconds.normalize(dmsFullSpec))
    }

    def fromSpec(dmsSpec: String, dmsRef: String): Try[LatitudeDegreeMinuteSeconds] = {
      fromSpec(s"$dmsSpec $dmsRef")
    }

    extension (dms: LatitudeDegreeMinuteSeconds) {
      def toDecimalDegrees: LatitudeDecimalDegrees = dms match {
        case DegreeMinuteSeconds.latitudeDmsRE(d, m, s, ref) =>
          LatitudeDecimalDegrees(DegreeMinuteSeconds.convert(d, m, s, ref))
      }
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type LongitudeDegreeMinuteSeconds = String // https://en.wikipedia.org/wiki/Decimal_degrees

  object LongitudeDegreeMinuteSeconds {
    def fromSpec(dmsFullSpec: String): Try[LongitudeDegreeMinuteSeconds] = {
      if (!DegreeMinuteSeconds.longitudeDmsRE.matches(dmsFullSpec))
        Failure(IllegalArgumentException(s"given DegreeMinuteSeconds longitude spec ($dmsFullSpec) is invalid"))
      else Success(DegreeMinuteSeconds.normalize(dmsFullSpec))
    }

    def fromSpec(dmsSpec: String, dmsRef: String): Try[LongitudeDegreeMinuteSeconds] = {
      fromSpec(s"$dmsSpec $dmsRef")
    }

    extension (dms: LongitudeDegreeMinuteSeconds) {
      def toDecimalDegrees: LongitudeDecimalDegrees = dms match {
        case DegreeMinuteSeconds.longitudeDmsRE(d, m, s, ref) =>
          LongitudeDecimalDegrees(DegreeMinuteSeconds.convert(d, m, s, ref))
      }
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  object DegreeMinuteSeconds {
    // TODO also support those notations "45/1 20/1 43377720/1000000 N" (latitude) and "6/1 37/1 1979399/1000000 E" (longitude) see com.drewnoakes::metadata-extractor implementation
    val latitudeDmsRE: Regex  = """([-+]?\d+)°\s*([-+]?\d+)['′]\s*([-+]?\d+(?:[.,]\d+)?)(?:(?:")|(?:'')|(?:′′)|(?:″))\s+([NS])""".r
    val longitudeDmsRE: Regex = """([-+]?\d+)°\s*([-+]?\d+)['′]\s*([-+]?\d+(?:[.,]\d+)?)(?:(?:")|(?:'')|(?:′′)|(?:″))\s+([EW])""".r

    def normalize(dmsFullSpec: String): String = {
      dmsFullSpec.trim
        .replaceAll("[,]", ".")
    }

    def convert(d: String, m: String, s: String, ref: String) = {
      val dd =
        d.toDouble +
          m.toDouble / 60d +
          s.toDouble / 3600d
      if ("NE".contains(ref.toUpperCase)) dd else -dd
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type ArtistInfo = String
  object ArtistInfo {
    def apply(artist: String): ArtistInfo = artist
    extension (artistInfo: ArtistInfo) {
      def artist: String = artistInfo
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type Aperture = Double
  object Aperture {
    def apply(aperture: Double): Aperture = aperture
    extension (aperture: Aperture) {
      def selected: Double = aperture
      def sexy: String     = "F%.1f".formatLocal(Locale.US, pow(sqrt(2), aperture))
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type ExposureTime = (numerator: Long, denominator: Long)
  object ExposureTime {
    def apply(numerator: Long, denominator: Long): ExposureTime = (numerator, denominator)
    extension (exposureTime: ExposureTime) {
      def numerator: Long   = exposureTime.numerator
      def denominator: Long = exposureTime.denominator
      def selected: Double  = exposureTime.numerator.toDouble / exposureTime.denominator.toDouble
      def sexy: String      = "%d/%d s".formatLocal(Locale.US, exposureTime.numerator, exposureTime.denominator)
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type FocalLength = Double
  object FocalLength {
    def apply(focalLength: Double): FocalLength = focalLength
    extension (focalLength: FocalLength) {
      def selected: Double = focalLength
      def sexy: String     = "%.1f mm".formatLocal(Locale.US, focalLength)
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type ISO = Double
  object ISO {
    def apply(iso: Double): ISO = iso
    extension (iso: ISO) {
      def selected: Double = iso
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type Width = Int

  object Width {
    def apply(width: Int): Width = width

    extension (width: Width) {
      def value: Int = width
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
  opaque type Height = Int

  object Height {
    def apply(height: Int): Height = height

    extension (height: Height) {
      def value: Int = height
    }
  }

  // -------------------------------------------------------------------------------------------------------------------
}
