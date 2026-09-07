package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.OriginalId
import fr.janalyse.sotohp.service
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.schema.LMDBSchema

/** A photo's assignment to a visual-similarity cluster, keyed by `OriginalId`.
  *
  * `clusterId >= 0` is a real cluster; `clusterId < 0` (see `DaoMediaCluster.noise`)
  * means the clustering left this photo unclustered (a visual singleton). Rebuilt
  * wholesale by the `MediaFeaturesClustering` CLI.
  */
case class DaoMediaCluster(
  originalId: OriginalId,
  clusterId: Int
) derives LMDBCodecJson, LMDBSchema

object DaoMediaCluster {
  val noise: Int = -1
}
