package fr.janalyse.sotohp.api.protocol

import zio.json.*

case class ApiInfo(
  authors: List[String],
  version: String,
  message: String,
  photosCount: Long,
  videosCount: Long
)

object ApiInfo {
  given JsonCodec[ApiInfo] = DeriveJsonCodec.gen
}
