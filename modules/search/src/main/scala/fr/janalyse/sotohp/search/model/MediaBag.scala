package fr.janalyse.sotohp.search.model

import fr.janalyse.sotohp.model.{Media, Person, State}
import fr.janalyse.sotohp.processor.model.{OriginalClassifications, OriginalDetectedObjects, OriginalFaces, OriginalMiniatures, OriginalNormalized}

case class MediaBag(
  media: Media,
  state: State,
  processedClassifications: Option[OriginalClassifications],
  processedObjects: Option[OriginalDetectedObjects],
  processedFaces: Option[OriginalFaces],
  processedMiniatures: Option[OriginalMiniatures],
  processedNormalized: Option[OriginalNormalized],
  persons: List[Person] // the distinct people positively identified on the media's faces
)
