package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.Original
import wvlet.airframe.ulid.ULID

type FaceId = ULID
object FaceId {
  def apply(value: ULID): FaceId = value
  extension (faceId: FaceId) {
    def code: ULID = faceId
  }
}

case class DetectedFace(
  faceId: FaceId,
  box: BoundingBox
)

case class OriginalFaces(
  original: Original,
  successful: Boolean,
  faces: List[DetectedFace]
)
