package fr.janalyse.sotohp.processor.config

import fr.janalyse.sotohp.core.ConfigInvalid
import zio.*
import zio.config.*
import zio.config.magnolia.*

/** Configuration of the face features (face embedding) computation.
  *
  * Two engines :
  *   - "djl" : the historical in-process DJL/PyTorch model, fed with the unaligned detector box.
  *   - "localai" : a dedicated face recognition model (InsightFace ArcFace, SCRFD detector) served
  *     by a LocalAI server over HTTP (`POST /v1/face/embed`). Each face is sent as a crop, with
  *     some context around the detector box, cut from the full-resolution original ; LocalAI
  *     re-detects the face inside it and aligns it on its 5 landmarks before embedding, which is
  *     what makes the vector robust to head roll, tilt and resolution.
  *
  * The two engines produce vectors of different, incompatible spaces (of the same 512 dimension
  * for ArcFace, so nothing structural tells them apart) : every stored vector is tagged with the
  * engine/model that computed it, and consumers only ever compare vectors carrying the tag of the
  * currently configured engine.
  *
  * @param engine         "djl" or "localai"
  * @param baseUrl        LocalAI server base URL (no trailing slash)
  * @param model          LocalAI face model name, e.g. "face-detect-buffalo-l", "insightface-buffalo-l"
  * @param timeoutSeconds per-request timeout (a cold model load can be slow)
  * @param cropMargin     context added around the detector box on each side, as a fraction of the
  *                       box size, so the server's own detector sees a whole face to align
  * @param minFaceSize    faces whose box longest side is below this many pixels of the full
  *                       resolution image are not embedded (too little identity signal)
  */
case class FaceFeaturesConfig(
  engine: String,
  baseUrl: String,
  model: String,
  timeoutSeconds: Int,
  cropMargin: Double,
  minFaceSize: Int
)

object FaceFeaturesConfig {
  val djlEngine     = "djl"
  val localaiEngine = "localai"

  private val derivedConfig =
    deriveConfig[FaceFeaturesConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "processors", "face-features")

  val config =
    ZIO
      .config(derivedConfig)
      .mapError(err => ConfigInvalid("Couldn't build FaceFeaturesConfig", err))
}
