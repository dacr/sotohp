package fr.janalyse.sotohp.api

import fr.janalyse.sotohp.model.{Media, MediaAccessKey, Orientation}
import zio.json.*
import zio.json.internal.{RetractReader, Write}
import sttp.tapir.Schema
import sttp.tapir.SchemaType.SString

import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.model.{DetectedFacePath, FaceId, NormalizedPath, PersonDescription, PersonEmail, PersonId}

import java.net.URL

package object protocol {

  // Primitive schemas we’ll reuse
  private inline def strAs[T]: Schema[T]            = Schema.string.as[T]
  private inline def uuidAs[T]: Schema[T]           = Schema.schemaForUUID.as[T]
  private inline def longAs[T]: Schema[T]           = Schema.schemaForLong.as[T]
  private inline def intAs[T]: Schema[T]            = Schema.schemaForInt.as[T]
  private inline def doubleAs[T]: Schema[T]         = Schema.schemaForDouble.as[T]
  private inline def boolAs[T]: Schema[T]           = Schema.schemaForBoolean.as[T]
  private inline def offsetDateTimeAs[T]: Schema[T] = Schema.schemaForOffsetDateTime.as[T]
  private inline def enumerationAs[T]: Schema[T]    = Schema.derivedEnumeration[T].defaultStringBased

  // UUID-based wrappers
  given Schema[OriginalId] = uuidAs
  given Schema[StoreId]    = uuidAs
  given Schema[EventId]    = uuidAs

  // ULID- / String-based wrappers
  given Schema[OwnerId]  = strAs
  given Schema[FaceId]   = strAs
  given Schema[PersonId] = strAs

  // String to String wrappers
  given Schema[MediaAccessKey] = strAs
  given Schema[OriginalHash]   = strAs

  // Path-based wrappers (string JSON representation)
  given Schema[BaseDirectoryPath]   = strAs
  given Schema[OriginalPath]        = strAs
  given Schema[DetectedFacePath]    = strAs
  given Schema[NormalizedPath]      = strAs
  given Schema[EventMediaDirectory] = strAs

  // Numbers
  given Schema[FileSize]                = longAs
  given Schema[Width]                   = intAs
  given Schema[Height]                  = intAs
  given Schema[LatitudeDecimalDegrees]  = doubleAs
  given Schema[LongitudeDecimalDegrees] = doubleAs
  given Schema[AltitudeMeanSeaLevel]    = doubleAs
  given Schema[Aperture]                = doubleAs
  given Schema[ISO]                     = doubleAs
  given Schema[FocalLength]             = doubleAs

  // Date/time wrappers
  given Schema[FileLastModified] = offsetDateTimeAs
  given Schema[ShootDateTime]    = offsetDateTimeAs
  given Schema[BirthDate]        = offsetDateTimeAs
  given Schema[AddedOn]          = offsetDateTimeAs
  given Schema[LastChecked]      = offsetDateTimeAs
  given Schema[LastSynchronized] = offsetDateTimeAs

  // Simple string wrappers
  given Schema[CameraName]        = strAs
  given Schema[ArtistInfo]        = strAs
  given Schema[EventName]         = strAs
  given Schema[StoreName]         = strAs
  given Schema[FirstName]         = strAs
  given Schema[LastName]          = strAs
  given Schema[EventDescription]  = strAs
  given Schema[MediaDescription]  = strAs
  given Schema[PersonDescription] = strAs
  given Schema[PersonEmail]       = strAs
  given Schema[Keyword]           = strAs
  given Schema[URL]               = strAs

  // Booleans
  given Schema[Starred] = boolAs

  // Regex wrappers -> document as string
  given Schema[IncludeMask] = strAs
  given Schema[IgnoreMask]  = strAs

  // Enums
  given Schema[Orientation] = enumerationAs
  given Schema[MediaKind]   = enumerationAs
}
