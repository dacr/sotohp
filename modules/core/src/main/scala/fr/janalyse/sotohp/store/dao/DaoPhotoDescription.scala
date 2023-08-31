package fr.janalyse.sotohp.store.dao

import zio.json.JsonCodec

import java.time.OffsetDateTime

case class DaoPhotoDescription(
  text: Option[String],
  category: Option[String] = None,
  keywords: Option[Set[String]] = None
) derives JsonCodec
