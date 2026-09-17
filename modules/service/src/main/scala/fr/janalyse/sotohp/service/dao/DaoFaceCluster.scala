package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.FaceId
import fr.janalyse.sotohp.service
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.schema.LMDBSchema

/** A face's assignment to a visual-similarity cluster, keyed by `FaceId`.
  *
  * `clusterId >= 0` is a real cluster; `clusterId < 0` (see `DaoFaceCluster.noise`) means the
  * clustering left this face unclustered (a visual singleton). Rebuilt wholesale by the
  * `FaceFeaturesClustering` CLI.
  */
case class DaoFaceCluster(
  faceId: FaceId,
  clusterId: Int
) derives LMDBCodecJson, LMDBSchema

object DaoFaceCluster {
  val noise: Int = -1
}
