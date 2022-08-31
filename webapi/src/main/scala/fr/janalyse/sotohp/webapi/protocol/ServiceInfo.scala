package fr.janalyse.sotohp.webapi.protocol

import zio.json.*

case class ServiceInfo(
  authors: List[String],
  version: String,
  message: String,
  photosCount: Long,
  videosCount: Long
)

object ServiceInfo {
  given JsonCodec[ServiceInfo] = DeriveJsonCodec.gen
}
