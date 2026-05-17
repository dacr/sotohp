package fr.janalyse.sotohp.model

case class Asset(
  originalId: OriginalId,
  selectedBox: Option[BoundingBox],
  description: Option[AssetDescription]
)

case class Portfolio(
  id: PortfolioId,
  name: PortfolioName,
  description: Option[PortfolioDescription],
  assets: List[Asset]
)
