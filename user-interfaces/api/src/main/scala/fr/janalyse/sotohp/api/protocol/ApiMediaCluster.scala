package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.MediaAccessKey
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

/** One cluster of visually similar photos: its id, how many photos it holds, and a
  * representative photo to show as the cover.
  */
case class ApiMediaCluster(
  clusterId: Int,
  size: Long,
  coverAccessKey: Option[MediaAccessKey]
)

object ApiMediaCluster {
  given JsonValueCodec[ApiMediaCluster]                = JsonCodecMaker.make
  given apiMediaClusterSchema: Schema[ApiMediaCluster] = Schema.derived[ApiMediaCluster].name(Schema.SName("MediaCluster"))
}
