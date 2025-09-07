package fr.janalyse.sotohp.api.protocol

import zio.json.JsonCodec

case class ApiExposureTime(
  numerator: Long,
  denominator: Long
) derives JsonCodec
