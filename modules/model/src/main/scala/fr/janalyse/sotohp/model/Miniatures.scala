package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class Miniatures(
  sources: List[MiniatureSource],
  lastUpdated: OffsetDateTime
)
