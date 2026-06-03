package fr.janalyse.sotohp.model

case class Asset(
  originalId: OriginalId,
  selectedBox: Option[BoundingBox],
  description: Option[AssetDescription]
)
