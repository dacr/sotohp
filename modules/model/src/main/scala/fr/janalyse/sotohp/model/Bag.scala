package fr.janalyse.sotohp.model

import java.net.URL

case class BagAttachment(
  store: Store,
  bagMediaDirectory: BagMediaDirectory
)

case class Bag(
  id: BagId,
  attachment: BagAttachment,           // bag is always backed by a relative directory path within a given store
  name: BagName,
  description: Option[BagDescription],
  location: Option[Location],          // reference location for this bag
  timestamp: Option[ShootDateTime],    // reference date time for this bag,
  originalId: Option[OriginalId],      // reference/chosen original, which will be shown as the bag cover
  publishedOn: Option[URL],            // URL where this bag album has been published
  keywords: Set[Keyword]
)
