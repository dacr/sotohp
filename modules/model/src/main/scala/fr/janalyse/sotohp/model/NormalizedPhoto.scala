package fr.janalyse.sotohp.model

import java.nio.file.Path
import java.time.OffsetDateTime

case class NormalizedPhoto(
  path: Path,
  dimension: Dimension2D,
  lastUpdated: OffsetDateTime
)
