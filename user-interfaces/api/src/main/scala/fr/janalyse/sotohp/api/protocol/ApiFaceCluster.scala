package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.FaceId
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

/** One cluster of visually similar faces: its id, how many faces it holds, a representative face
  * to show as the cover, and how many of its faces are already identified by a human
  * (`confirmedCount == size` once every face in the cluster has a name).
  */
case class ApiFaceCluster(
  clusterId: Int,
  size: Long,
  coverFaceId: Option[FaceId],
  confirmedCount: Long
)

object ApiFaceCluster {
  given JsonValueCodec[ApiFaceCluster]                = JsonCodecMaker.make
  given apiFaceClusterSchema: Schema[ApiFaceCluster] = Schema.derived[ApiFaceCluster].name(Schema.SName("FaceCluster"))
}
