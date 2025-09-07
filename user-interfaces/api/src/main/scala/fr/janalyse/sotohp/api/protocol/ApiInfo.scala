package fr.janalyse.sotohp.api.protocol

import zio.json.*

case class ApiInfo(
  authors: List[String],
  version: String,
  message: String,
  originalsCount: Long
) derives JsonCodec
