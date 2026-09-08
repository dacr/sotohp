package fr.janalyse.sotohp.service.dao

import zio.lmdb.json.LMDBCodecJson
import zio.lmdb.schema.LMDBSchema

/** Textual place of a photo, stored inline on [[DaoMedia]] (mirrors [[DaoLocation]]).
  *
  * All fields are plain `String`, so `derives LMDBCodecJson, LMDBSchema` needs no extra
  * `service.json` / `service.dao` package givens, and Chimney maps `Place` <-> `DaoPlace`
  * structurally (no explicit `Transformer` given, same as `DaoLocation`).
  */
case class DaoPlace(
  street: Option[String],
  town: Option[String],
  region: Option[String],
  country: Option[String],
  countryCode: Option[String]
) derives LMDBCodecJson, LMDBSchema
