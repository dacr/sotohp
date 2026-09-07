package fr.janalyse.sotohp.model

/** A whole-image feature vector (embedding) computed from a photo, used for visual
  * similarity search and clustering of similar photos.
  *
  * @param originalId
  *   the photo this vector describes
  * @param features
  *   the embedding, L2-normalised so cosine similarity is a plain dot product
  */
case class MediaFeatures(
  originalId: OriginalId,
  features: Array[Float]
)
