package fr.janalyse.sotohp.model

/** A face embedding.
  *
  * @param model
  *   the engine/model that computed `features` ; vectors computed by different models live in
  *   different spaces and must never be compared. `None` is the historical DJL model.
  */
case class FaceFeatures(
  faceId: FaceId,
  features: Array[Float],
  model: Option[String] = None
)
