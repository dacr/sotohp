package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import zio.lmdb.json.JValue
import zio.lmdb.json.JValue.{MapV, StringV}
import zio.lmdb.schema.SchemaShape

import scala.collection.immutable.ListMap

/** `SchemaShape` givens for the opaque newtypes that appear as leaf fields of the `Dao*` value
  * classes.
  *
  * `derives LMDBSchema` reduces to `summonInline[SchemaShape[T]].shape`, whose `Mirror`-based
  * product derivation recurses into every field type. An opaque type (`opaque type OriginalId =
  * UUID`, …) has no `Mirror` and no built-in `SchemaShape`, so the recursive summon fails — and,
  * because it fails *inside* an inline given, the compiler blames the enclosing class with the
  * misleading "derived … does not match type SchemaShape[DaoX]" rather than naming the field.
  * These givens close that gap for the whole dao layer.
  *
  * Each shape mirrors the wire representation chosen by the matching `JsonValueCodec` given in
  * [[fr.janalyse.sotohp.service.json]]; the two lists must stay in lockstep — a newtype encoded as
  * a UUID is described as a uuid string, one encoded as an ordinal as an integer, and so on.
  */

private def shapeOf[T](jv: JValue): SchemaShape[T] = new SchemaShape[T] { val shape: JValue = jv }

private val stringShape: JValue   = SchemaShape[String].shape          // {"type":"string"}
private val integerShape: JValue  = SchemaShape[Int].shape             // {"type":"integer"} (also Long)
private val numberShape: JValue   = SchemaShape[Double].shape          // {"type":"number"}
private val booleanShape: JValue  = SchemaShape[Boolean].shape         // {"type":"boolean"}
private val uuidShape: JValue     = SchemaShape[java.util.UUID].shape  // {"type":"string","format":"uuid"}
private val dateTimeShape: JValue = MapV(ListMap("type" -> StringV("string"), "format" -> StringV("date-time")))

// ── UUID-backed identifiers ──────────────────────────────────────────────────────────────────
given SchemaShape[OriginalId]  = shapeOf(uuidShape)
given SchemaShape[StoreId]     = shapeOf(uuidShape)
given SchemaShape[BagId]       = shapeOf(uuidShape)
given SchemaShape[PortfolioId] = shapeOf(uuidShape)

// ── String-backed names / descriptions / paths / hashes / ULIDs / regexes ────────────────────
given SchemaShape[PortfolioName]        = shapeOf(stringShape)
given SchemaShape[PortfolioDescription] = shapeOf(stringShape)
given SchemaShape[AssetDescription]     = shapeOf(stringShape)
given SchemaShape[BaseDirectoryPath]    = shapeOf(stringShape)
given SchemaShape[OriginalPath]         = shapeOf(stringShape)
given SchemaShape[FacePath]             = shapeOf(stringShape)
given SchemaShape[NormalizedPath]       = shapeOf(stringShape)
given SchemaShape[BagMediaDirectory]    = shapeOf(stringShape)
given SchemaShape[OwnerId]              = shapeOf(stringShape)
given SchemaShape[FaceId]               = shapeOf(stringShape)
given SchemaShape[PersonId]             = shapeOf(stringShape)
given SchemaShape[MediaAccessKey]       = shapeOf(stringShape)
given SchemaShape[OriginalHash]         = shapeOf(stringShape)
given SchemaShape[CameraName]           = shapeOf(stringShape)
given SchemaShape[ArtistInfo]           = shapeOf(stringShape)
given SchemaShape[BagName]              = shapeOf(stringShape)
given SchemaShape[StoreName]            = shapeOf(stringShape)
given SchemaShape[FirstName]            = shapeOf(stringShape)
given SchemaShape[LastName]             = shapeOf(stringShape)
given SchemaShape[BagDescription]       = shapeOf(stringShape)
given SchemaShape[MediaDescription]     = shapeOf(stringShape)
given SchemaShape[PersonDescription]    = shapeOf(stringShape)
given SchemaShape[PersonEmail]          = shapeOf(stringShape)
given SchemaShape[Keyword]              = shapeOf(stringShape)
given SchemaShape[IncludeMask]          = shapeOf(stringShape)
given SchemaShape[IgnoreMask]           = shapeOf(stringShape)

// ── Integer-backed (raw integers and enum ordinals) ──────────────────────────────────────────
given SchemaShape[FileSize]    = shapeOf(integerShape)
given SchemaShape[Width]       = shapeOf(integerShape)
given SchemaShape[Height]      = shapeOf(integerShape)
given SchemaShape[Orientation] = shapeOf(integerShape) // codec writes the ordinal, not a oneOf
given SchemaShape[MediaKind]   = shapeOf(integerShape) // codec writes the ordinal, not a oneOf

// ── Number-backed (doubles) ──────────────────────────────────────────────────────────────────
given SchemaShape[LatitudeDecimalDegrees]  = shapeOf(numberShape)
given SchemaShape[LongitudeDecimalDegrees] = shapeOf(numberShape)
given SchemaShape[AltitudeMeanSeaLevel]    = shapeOf(numberShape)
given SchemaShape[Aperture]                = shapeOf(numberShape)
given SchemaShape[ISO]                     = shapeOf(numberShape)
given SchemaShape[FocalLength]             = shapeOf(numberShape)

// ── Boolean-backed ───────────────────────────────────────────────────────────────────────────
given SchemaShape[Starred] = shapeOf(booleanShape)

// ── Date-time-backed (OffsetDateTime; no built-in SchemaShape) ───────────────────────────────
given SchemaShape[FileLastModified] = shapeOf(dateTimeShape)
given SchemaShape[ShootDateTime]    = shapeOf(dateTimeShape)
given SchemaShape[BirthDate]        = shapeOf(dateTimeShape)
given SchemaShape[AddedOn]          = shapeOf(dateTimeShape)
given SchemaShape[LastChecked]      = shapeOf(dateTimeShape)
given SchemaShape[LastSynchronized] = shapeOf(dateTimeShape)
