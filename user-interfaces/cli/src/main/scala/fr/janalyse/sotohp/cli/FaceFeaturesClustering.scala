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
  * Args (all optional): `--radius=` (max cosine distance between neighbours, defaults to the configured
  * model's `FaceMatchingProfile.clusteringRadius` - 0.16 for the historical model),
  * `--minPts=3` (neighbours, incl. self, to be a cluster core), `--neighbors=100`
  * (per-point candidate cap), `--ef=256` (HNSW search width), `--dry-run` (report only, store nothing),
  * `--radii=0.35,0.4,0.45` (compare several radii in one run : report only, nothing stored).
  *
  * Every run reports the clustering quality against the faces a human has confirmed : purity (different
  * people merged into one cluster lower it) and cohesion (one person split across clusters lowers it).
  */
object FaceFeaturesClustering extends CommonsCLI {

  case class Params(radius: Double, minPts: Int, neighbors: Int, ef: Int, radii: Option[List[Double]], dryRun: Boolean)

  private def parse(args: Chunk[String], defaultRadius: Double): Params = {
    def num(name: String, default: Double): Double =
      args.collectFirst { case s if s.startsWith(s"--$name=") => s.stripPrefix(s"--$name=") }.flatMap(_.toDoubleOption).getOrElse(default)
    Params(
      radius = num("radius", defaultRadius),
      minPts = num("minPts", 3).toInt.max(2),
      neighbors = num("neighbors", 100).toInt.max(8),
      ef = num("ef", 256).toInt.max(32),
      radii = args
        .collectFirst { case s if s.startsWith("--radii=") => s.stripPrefix("--radii=").split(",").toList.flatMap(_.trim.toDoubleOption) }
        .filter(_.nonEmpty),
      dryRun = args.contains("--dry-run")
    )
  }

  override def run =
    (for {
      args  <- getArgs
      model <- MediaService.faceFeaturesModel()
      _     <- Console.printLine(s"face features model: ${model.getOrElse("historical DJL model")}")
      _     <- logic(parse(args, FaceMatchingProfile.forModel(model).clusteringRadius))
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
    val radii = params.radii.getOrElse(List(params.radius)).sorted
    for {
      _           <- Console.printLine(s"parameters: radius=${radii.mkString(",")} minPts=${params.minPts} neighbors=${params.neighbors} ef=${params.ef}${if (params.dryRun) " (dry run, nothing stored)" else ""}")
      loadStart   <- Clock.nanoTime
      features    <- MediaService.faceFeaturesListCurrent().runCollect
      confirmed   <- MediaService.faceList().filter(_.identifiedPersonId.isDefined).runCollect.map(_.map(face => face.faceId -> face.identifiedPersonId.get).toMap)
      loadEnd     <- Clock.nanoTime
      _           <- ZIO.logInfo(s"${features.size} face feature vectors loaded in ${(loadEnd - loadStart) / 1000000000L}s")
      _           <- ZIO.fail(new RuntimeException("no face feature vectors computed by the configured model - run make run-compute-face-features first")).when(features.isEmpty)
      dimension    = features.head.features.length
      indexById    = features.map(_.faceId).zipWithIndex.toMap
      faceIds      = features.toVector.map(_.faceId)
      // every neighbour within the largest radius, with its distance : smaller radii only filter these
      neighbours  <- ZIO.acquireReleaseWith(
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
                                                .map(hits => indexById(ff.faceId) -> hits.collect { case (id, dist) if dist <= radii.last => (indexById(id), dist) }.toArray)
                                            }
                                            .withParallelism(java.lang.Runtime.getRuntime.availableProcessors())
                       } yield {
                         val arr = Array.fill(features.size)(Array.empty[(Int, Double)])
                         neighborLists.foreach((i, ns) => arr(i) = ns)
                         arr
                       }
                     }
      _           <- Console.printLine(f"${"radius"}%7s ${"clusters"}%9s ${"clustered"}%10s ${"largest"}%8s ${"purity"}%8s ${"cohesion"}%9s ${"confirmed clustered"}%20s")
      results      = radii.map { radius =>
                       val labels  = dbscan(neighbours.map(_.collect { case (j, dist) if dist <= radius => j }), params.minPts)
                       radius -> labels
                     }
      _           <- ZIO.foreachDiscard(results)((radius, labels) => Console.printLine(qualityRow(radius, faceIds, labels, confirmed)))
      _           <- Console.printLine(
                       "purity: share of clustered confirmed faces whose cluster's majority is their own person (low = different people merged)\n" +
                         "cohesion: share of a person's clustered confirmed faces sitting in that person's main cluster (low = people split up)"
                     )
      _           <- ZIO
                       .unless(params.dryRun || results.size != 1) {
                         val assignments = faceIds.zip(results.head._2)
                         MediaService.faceClustersReplace(assignments) *> Console.printLine("face cluster assignments stored")
                       }
    } yield ()
  }

  /** One line of clustering quality, measured against the faces a human has confirmed. */
  private def qualityRow(radius: Double, faceIds: Vector[FaceId], labels: Array[Int], confirmed: Map[FaceId, PersonId]): String = {
    val clusterSizes = labels.iterator.filter(_ >= 0).toSeq.groupBy(identity).view.mapValues(_.size).toMap
    // (cluster, person) of every confirmed face that got a cluster
    val labelled     = faceIds.indices.flatMap(i => confirmed.get(faceIds(i)).map(person => (labels(i), person)))
    val inClusters   = labelled.filter(_._1 >= 0)
    val purity       = inClusters.groupBy(_._1).values.map(members => members.groupBy(_._2).values.map(_.size).max).sum.toDouble / math.max(1, inClusters.size)
    val cohesion     = inClusters.groupBy(_._2).values.map(members => members.groupBy(_._1).values.map(_.size).max).sum.toDouble / math.max(1, inClusters.size)
    val clustered    = labels.count(_ >= 0)
    f"$radius%7.3f ${clusterSizes.size}%9d ${clustered}%10d ${clusterSizes.values.maxOption.getOrElse(0)}%8d ${purity * 100}%7.2f%% ${cohesion * 100}%8.2f%% ${s"${inClusters.size}/${labelled.size}"}%20s"
  }

}
