package fr.janalyse.sotohp.model

case class Portfolio(
  id: PortfolioId,
  name: PortfolioName,
  description: Option[PortfolioDescription],
  assets: List[Asset]
)
