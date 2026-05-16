package fr.janalyse.sotohp.model

import java.time.Instant

case class Asset(
  originalId: OriginalId,
  selectedBox: Option[BoundingBox]
)

case class Portfolio(
  name: String,
  description: String,
  created: Instant,
  updated: Instant,
  assets: List[Asset]
)
