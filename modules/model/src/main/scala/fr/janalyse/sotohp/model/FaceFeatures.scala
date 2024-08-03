package fr.janalyse.sotohp.model

import wvlet.airframe.ulid.ULID

case class FaceFeatures(
  photoId: PhotoId,
  someoneId: Option[SomeoneId],
  box: BoundingBox,
  features: Array[Float]
)
