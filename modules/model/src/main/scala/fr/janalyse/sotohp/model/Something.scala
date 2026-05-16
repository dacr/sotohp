package fr.janalyse.sotohp.model

import fr.janalyse.sotohp.model.BoundingBox

case class Something(
  name: String,
  probability: Double,
  box: BoundingBox
)
