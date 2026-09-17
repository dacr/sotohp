package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import fr.janalyse.sotohp.service.MediaServiceLive.given // brings `KeyCodec[FaceId]` into scope for `LMDBVectorIndex.create[FaceId]`
import zio.*
import zio.lmdb.LMDB
import zio.lmdb.vector.{HnswParams, LMDBVectorIndex, VectorMetric}

/** Groups every detected face into clusters of visually similar faces and stores the
  * assignments (readable back through `MediaService.faceClusterList` / `faceClusterMembers`,
  * and the `/api/faces/clusters` endpoints).
  *
  * Same shape as [[MediaFeaturesClustering]]: DBSCAN over the stored per-face feature vectors,
  * epsilon-neighbourhoods answered by an approximate (HNSW) index. Unlike identified persons
  * (a human decision) this is purely unsupervised - a "you might want to name these" grouping
  * of faces nobody has labelled yet, faces with no near neighbour are left unclustered ("noise",
  * clusterId `-1`).
  *
  * Args (all optional): `--radius=0.16` (max cosine distance between neighbours),
  * `--minPts=3` (neighbours, incl. self, to be a cluster core), `--neighbors=100`
  * (per-point candidate cap), `--ef=256` (HNSW search width).
  */
object FaceFeaturesClustering extends CommonsCLI {

  case class Params(radius: Double, minPts: Int, neighbors: Int, ef: Int)

  private def parse(args: Chunk[String]): Params = {
    def num(name: String, default: Double): Double =
      args.collectFirst { case s if s.startsWith(s"--$name=") => s.stripPrefix(s"--$name=") }.flatMap(_.toDoubleOption).getOrElse(default)
    Params(
      radius = num("radius", 0.16),
      minPts = num("minPts", 3).toInt.max(2),
      neighbors = num("neighbors", 100).toInt.max(8),
      ef = num("ef", 256).toInt.max(32)
    )
  }

  override def run =
    (for {
      args <- getArgs
      _    <- logic(parse(args))
    } yield ())
      .provideSome[ZIOAppArgs](
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  private val vectorIndexCollectionName = "faceFeaturesClusteringVectorIndexTmp"

  // Precompute every point's epsilon-neighbourhood as index lists, then run textbook DBSCAN
  // over them. `neighborsOf(i)` already includes `i` itself (the index returns it at distance ~0).
  private def dbscan(neighborsOf: Array[Array[Int]], minPts: Int): Array[Int] = {
    val n          = neighborsOf.length
    val labels     = Array.fill(n)(-2) // -2 = unvisited, -1 = noise, >=0 = cluster id
    var clusterId  = 0
    var i          = 0
    while (i < n) {
      if (labels(i) == -2) {
        val seeds = neighborsOf(i)
        if (seeds.length < minPts) {
          labels(i) = -1 // provisional noise; may be claimed as a border point below
        } else {
          labels(i) = clusterId
          val queue = scala.collection.mutable.Queue.from(seeds.iterator.filter(_ != i))
          while (queue.nonEmpty) {
            val j = queue.dequeue()
            if (labels(j) == -1) labels(j) = clusterId // border point
            if (labels(j) == -2) {
              labels(j) = clusterId
              val jn = neighborsOf(j)
              if (jn.length >= minPts) jn.foreach(queue.enqueue)
            }
          }
          clusterId += 1
        }
      }
      i += 1
    }
    labels
  }

  def logic(params: Params) = ZIO.logSpan("Cluster faces by feature vectors") {
    for {
      _           <- Console.printLine(s"parameters: radius=${params.radius} minPts=${params.minPts} neighbors=${params.neighbors} ef=${params.ef}")
      loadStart   <- Clock.nanoTime
      features    <- MediaService.faceFeaturesList().runCollect
      loadEnd     <- Clock.nanoTime
      _           <- ZIO.logInfo(s"${features.size} face feature vectors loaded in ${(loadEnd - loadStart) / 1000000000L}s")
      _           <- ZIO.fail(new RuntimeException("no face feature vectors - run FaceInference first")).when(features.isEmpty)
      dimension    = features.head.features.length
      indexById    = features.map(_.faceId).zipWithIndex.toMap
      assignments <- ZIO.acquireReleaseWith(
                       LMDB.collectionDrop(vectorIndexCollectionName).ignore *>
                         LMDBVectorIndex
                           .create[FaceId](vectorIndexCollectionName, dimension, VectorMetric.Cosine, failIfExists = false)
                           .orDieWith(err => new RuntimeException(err.toString))
                     )(index => LMDB.collectionDrop(index.collection.name).orDieWith(err => new RuntimeException(err.toString))) { index =>
                       for {
                         _            <- ZIO
                                           .foreachDiscard(features)(ff => index.insert(ff.faceId, ff.features))
                                           .orDieWith(err => new RuntimeException(err.toString))
                         _            <- index
                                           .buildApproximateIndex(HnswParams(m = 16, efConstruction = 100, efSearch = 64))
                                           .orDieWith(err => new RuntimeException(err.toString))
                                           .timed
                                           .flatMap((elapsed, _) => ZIO.logInfo(s"approximate (HNSW) index built in ${elapsed.toSeconds}s"))
                         neighborLists <- ZIO
                                            .foreachPar(features.toVector) { ff =>
                                              index
                                                .searchApproximate(ff.features, k = params.neighbors, ef = Some(params.ef))
                                                .orDieWith(err => new RuntimeException(err.toString))
                                                .map(hits => indexById(ff.faceId) -> hits.collect { case (id, dist) if dist <= params.radius => indexById(id) }.toArray)
                                            }
                                            .withParallelism(java.lang.Runtime.getRuntime.availableProcessors())
                         neighborsOf   = {
                                           val arr = Array.fill(features.size)(Array.emptyIntArray)
                                           neighborLists.foreach((i, ns) => arr(i) = ns)
                                           arr
                                         }
                         labels        = dbscan(neighborsOf, params.minPts)
                       } yield features.toVector.map(_.faceId).zip(labels).map((fid, label) => fid -> label)
                     }
      clustered    = assignments.count(_._2 >= 0)
      clusterCount = assignments.iterator.map(_._2).filter(_ >= 0).toSet.size
      _           <- Console.printLine(s"$clusterCount clusters covering $clustered faces, ${assignments.size - clustered} left unclustered")
      _           <- MediaService.faceClustersReplace(assignments)
      _           <- Console.printLine("face cluster assignments stored")
    } yield ()
  }

}
