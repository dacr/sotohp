package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

import java.time.OffsetDateTime

case class DaoPhotoDescription(
  text: Option[String],
  event: Option[String] = None,
  keywords: Option[Set[String]] = None
) derives LMDBCodecJson
