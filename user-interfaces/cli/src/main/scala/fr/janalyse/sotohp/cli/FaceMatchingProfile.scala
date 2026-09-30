package fr.janalyse.sotohp.cli

import zio.Chunk

/** The cosine distances face inference and clustering decide with. They only mean something in the
  * space of one embedding model : a distance that says "same person" for one model says nothing for
  * another, so every model gets its own profile.
  *
  * @param maxMatchDistance           a person is inferred only when their closest faces are within this
  * @param maxIgnoredMatchDistance    a face this close to a face the user marked as ignored is never inferred
  * @param minRunnerUpMargin          the winning person must be at least this much closer than the runner-up
  * @param bootstrapMaxMatchDistance  `maxMatchDistance` for a winner with very few confirmed faces
  * @param bootstrapMinRunnerUpMargin `minRunnerUpMargin` for a winner with very few confirmed faces
  * @param clusteringRadius           DBSCAN neighbourhood radius of the face clustering
  */
case class FaceMatchingProfile(
  name: String,
  maxMatchDistance: Double,
  maxIgnoredMatchDistance: Double,
  minRunnerUpMargin: Double,
  bootstrapMaxMatchDistance: Double,
  bootstrapMinRunnerUpMargin: Double,
  clusteringRadius: Double
) {

  /** Any of the values overridden from the command line : `--max-distance=`, `--ignored-distance=`,
    * `--margin=`, `--bootstrap-max-distance=`, `--bootstrap-margin=`, `--radius=`.
    */
  def withOverrides(args: Chunk[String]): FaceMatchingProfile = {
    def num(name: String): Option[Double] =
      args.collectFirst { case s if s.startsWith(s"--$name=") => s.stripPrefix(s"--$name=") }.flatMap(_.toDoubleOption)
    val overridden = copy(
      maxMatchDistance = num("max-distance").getOrElse(maxMatchDistance),
      maxIgnoredMatchDistance = num("ignored-distance").getOrElse(maxIgnoredMatchDistance),
      minRunnerUpMargin = num("margin").getOrElse(minRunnerUpMargin),
      bootstrapMaxMatchDistance = num("bootstrap-max-distance").getOrElse(bootstrapMaxMatchDistance),
      bootstrapMinRunnerUpMargin = num("bootstrap-margin").getOrElse(bootstrapMinRunnerUpMargin),
      clusteringRadius = num("radius").getOrElse(clusteringRadius)
    )
    if (overridden == this) this else overridden.copy(name = s"$name, overridden")
  }

  override def toString: String =
    f"$name (match<=$maxMatchDistance%.3f, margin>=$minRunnerUpMargin%.3f, ignored<=$maxIgnoredMatchDistance%.3f, " +
      f"bootstrap match<=$bootstrapMaxMatchDistance%.3f margin>=$bootstrapMinRunnerUpMargin%.3f, clustering radius=$clusteringRadius%.3f)"
}

object FaceMatchingProfile {

  /** The historical DJL model - the values `FaceInferenceEvaluate` tuned, see `FaceInference`. */
  val legacy: FaceMatchingProfile = FaceMatchingProfile(
    name = "historical DJL model",
    maxMatchDistance = 0.20,
    maxIgnoredMatchDistance = 0.15,
    minRunnerUpMargin = 0.06,
    bootstrapMaxMatchDistance = 0.32,
    bootstrapMinRunnerUpMargin = 0.03,
    clusteringRadius = 0.16
  )

  /** Face recognition served by LocalAI - tuned for InsightFace buffalo_l (ArcFace R50, 512-d, faces aligned on
    * their 5 landmarks), with `FaceInferenceEvaluate` over 79077 confirmed faces of 364 people (2026-09-30).
    *
    * The rule is the same person-margin one as for the historical model, only the distances change : this space is
    * far wider (two photos of one person 0.3-0.6 apart, two people 0.9-1.1). Precision stays at 99.7-99.9% whatever
    * the values, so what they trade is recall against strangers - faces of people not in the library wrongly given a
    * name, measured on 5862 faces whose whole person was held out of the gallery :
    *
    * {{{
    *   rule (match <= t, runner-up margin >= m)   recall   strangers named
    *   historical model, its own tuning             76.0%    2.36%
    *   t=0.324 m=0.05                               82.2%    1.21%
    *   t=0.36  m=0.05   <- chosen                   88.5%    1.67%   (precision 99.70%)
    *   t=0.36  m=0.10                               86.6%    1.57%
    *   t=0.396 m=0.05                               92.3%    2.59%
    *   t=0.55  m=0.10   (first provisional guess)   94.1%    6.87%
    * }}}
    *
    * 0.36/0.05 is the knee : below, every recall point costs little ; above, strangers climb four times faster than
    * recall - and most unconfirmed faces of a street-photo heavy library belong to strangers. At this threshold the
    * margin barely filters anymore (0.05 -> 0.10 loses 1.9 recall points to save 0.1 stranger point).
    *
    * Bootstrap (a winner with <= 2 confirmed faces) at 0.44 : recall on people with 2 confirmed faces 30% -> 50%,
    * precision 90% -> 94%, stranger rate unchanged. The ignored-face veto at 0.35 suppresses 0.59% of correct
    * identifications (1.90% at 0.45).
    *
    * Clustering radius, from `FaceFeaturesClustering --radii=...` over 175611 vectors (DBSCAN, minPts=3) - purity is
    * the share of clustered confirmed faces whose cluster is in majority their own person :
    *
    * {{{
    *   radius   purity   clustered   largest cluster
    *   0.26     99.80%     69676        10747
    *   0.28     99.77%     79123        11844
    *   0.30     89.60%     87774        20174   <- two big groups chain together
    *   0.45     50.92%    130646        68379   (first provisional guess)
    * }}}
    *
    * DBSCAN chains neighbour to neighbour, so its radius sits well below the inference threshold. 0.27 keeps a margin
    * from the collapse just below 0.30, which new photos could bridge. minPts=5 pushes the collapse past 0.30 but
    * leaves people with fewer than 5 photos unclustered - the very faces clusters exist to suggest names for.
    */
  val localai: FaceMatchingProfile = FaceMatchingProfile(
    name = "LocalAI face recognition",
    maxMatchDistance = 0.36,
    maxIgnoredMatchDistance = 0.35,
    minRunnerUpMargin = 0.05,
    bootstrapMaxMatchDistance = 0.44,
    bootstrapMinRunnerUpMargin = 0.05,
    clusteringRadius = 0.27
  )

  def forModel(model: Option[String]): FaceMatchingProfile =
    model match {
      case None                                    => legacy
      case Some(tag) if tag.startsWith("localai:") => localai.copy(name = tag)
      case Some(tag)                               => localai.copy(name = s"$tag (unknown model, LocalAI values)")
    }
}
