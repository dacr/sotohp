package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import fr.janalyse.sotohp.service.MediaServiceLive.given // brings `KeyCodec[FaceId]` into scope, needed by `LMDBVectorIndex.create[FaceId]`
import zio.*
import zio.lmdb.LMDB
import zio.lmdb.vector.{HnswParams, LMDBVectorIndex, VectorMetric}

/*
 * Measures how good face inference actually is, using the faces a human has already confirmed as ground truth.
 *
 * Every confirmed face is a labelled example, so the decision rule can be scored leave-one-out: hide one face from
 * the gallery, ask the rule who it belongs to, and compare its answer with the person it really is. Doing that for
 * all of them turns "does this threshold look about right" into numbers, and lets a proposed rule be compared against
 * the one in use before it touches anybody's photos.
 *
 * Read-only: nothing here writes back to the database.
 */
object FaceInferenceEvaluate extends CommonsCLI {

  override def run =
    logic
      .provide(
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  // How many neighbors each query retrieves before any rule looks at them. Wide enough that a rule can score several
  // people against each other, rather than just the couple of faces the current one needs.
  val candidateCount = 50

  // Deliberately far above the production `efSearch`: the point of this run is to compare decision *rules*, so the
  // candidate lists should be as close to an exact scan as we can afford, leaving approximation out of the comparison.
  // How close that actually is gets measured separately, at the end.
  val evaluationEf = 256

  val searchParallelism = java.lang.Runtime.getRuntime.availableProcessors()

  // Exact searches are far slower than approximate ones, so the approximation check runs on a sample rather than the lot.
  val recallSampleSize = 2000

  // How many faces per person the stranger test uses. Capping it keeps the handful of very heavily photographed people
  // from dominating a measurement that is really about what happens to somebody new.
  val strangerFacesPerPerson = 30

  // Same values the production job uses, so the "current" line below reflects what is really running.
  val maxMatchDistance        = 0.16
  val maxIgnoredMatchDistance = 0.20

  // -------------------------------------------------------------------------------------------------------------------
  case class GalleryFace(faceId: FaceId, personId: PersonId, originalId: OriginalId, features: Array[Float])

  /** One retrieved neighbor, with the identity of the person it belongs to already resolved. */
  case class Candidate(personId: PersonId, originalId: OriginalId, distance: Double)

  /** A decision rule: given the neighbors of a face, name a person or abstain. */
  type Rule = Chunk[Candidate] => Option[PersonId]

  /** Plain nearest neighbor - whoever owns the single closest face, if it is near enough. The baseline everything else has to beat. */
  def nearestRule(threshold: Double): Rule =
    candidates => candidates.headOption.filter(_.distance <= threshold).map(_.personId)

  /** The rule `FaceInference` uses today: of the faces within the threshold, keep the two closest and accept only if they name the same person.
    *
    * Note what that does when only one face is within the threshold: the group is trivially unanimous, so it accepts on a single vote with no verification at all.
    */
  def topTwoAgreeRule(threshold: Double): Rule = { candidates =>
    val shortests = candidates.filter(_.distance <= threshold).take(2)
    shortests.groupBy(_.personId) match {
      case grouped if grouped.size == 1 => grouped.values.head.minByOption(_.distance).map(_.personId)
      case _                            => None
    }
  }

  /** Scores each *person* rather than each face - by the mean distance of their `facesPerPerson` closest faces - and accepts the winner only when the runner-up **person** is at least `margin` further away.
    *
    * Aggregating per person is what makes this insensitive to how many photos someone happens to have: a person with a thousand enrolled faces no longer crowds every other candidate out of the shortlist.
    */
  def personMarginRule(threshold: Double, margin: Double, facesPerPerson: Int): Rule = { candidates =>
    val ranked = candidates
      .groupBy(_.personId)
      .toVector
      .map((personId, theirs) => personId -> mean(theirs.map(_.distance).sorted.take(facesPerPerson)))
      .sortBy((_, distance) => distance)

    ranked.headOption.flatMap { (bestPerson, bestDistance) =>
      val runnerUpDistance = ranked.drop(1).headOption.map((_, distance) => distance).getOrElse(Double.MaxValue)
      Option.when(bestDistance <= threshold && (runnerUpDistance - bestDistance) >= margin)(bestPerson)
    }
  }

  def mean(values: Iterable[Double]): Double = if (values.isEmpty) Double.MaxValue else values.sum / values.size

  /** The rules to score against each other. Thresholds are varied *within* each rule family on purpose: without that control, a rule change bundled with a threshold change can take credit for what was really just a looser cutoff.
    */
  val rules: Vector[(String, Rule)] = Vector(
    "1-NN (t=0.16)"                          -> nearestRule(0.16),
    "1-NN (t=0.20)"                          -> nearestRule(0.20),
    "current: top-2 agree (t=0.16)"          -> topTwoAgreeRule(0.16),
    "current: top-2 agree (t=0.20)"          -> topTwoAgreeRule(0.20),
    "current: top-2 agree (t=0.24)"          -> topTwoAgreeRule(0.24),
    "person margin (t=0.16, m=0.02, best-1)" -> personMarginRule(0.16, 0.02, 1),
    "person margin (t=0.16, m=0.04, best-1)" -> personMarginRule(0.16, 0.04, 1),
    "person margin (t=0.20, m=0.02, best-1)" -> personMarginRule(0.20, 0.02, 1),
    "person margin (t=0.20, m=0.02, best-3)" -> personMarginRule(0.20, 0.02, 3),
    "person margin (t=0.20, m=0.04, best-3)" -> personMarginRule(0.20, 0.04, 3),
    "person margin (t=0.24, m=0.04, best-3)" -> personMarginRule(0.24, 0.04, 3),
    "person margin (t=0.24, m=0.06, best-3)" -> personMarginRule(0.24, 0.06, 3)
  )

  // -------------------------------------------------------------------------------------------------------------------
  case class Tally(decided: Int = 0, correct: Int = 0, abstained: Int = 0) {
    def wrong: Int = decided - correct

    def merge(other: Tally): Tally = Tally(decided + other.decided, correct + other.correct, abstained + other.abstained)

    def record(inferred: Option[PersonId], truth: PersonId): Tally = inferred match {
      case Some(person) if person == truth => copy(decided = decided + 1, correct = correct + 1)
      case Some(_)                         => copy(decided = decided + 1)
      case None                            => copy(abstained = abstained + 1)
    }

    /** For a face whose person was held out of the gallery entirely: abstaining is the only right answer, so every identification counts as a false positive. */
    def recordStranger(inferred: Option[PersonId]): Tally = inferred match {
      case Some(_) => copy(decided = decided + 1)
      case None    => copy(abstained = abstained + 1)
    }

    /** Of the calls it made, how many were right - the number that matters when a wrong answer is worse than no answer. */
    def precision: Double = if (decided == 0) 0d else correct.toDouble / decided

    /** Of all the faces it saw, how many did it identify correctly. */
    def recall(total: Int): Double = if (total == 0) 0d else correct.toDouble / total
  }

  case class Measurement(bucket: String, truth: PersonId, vetoed: Boolean, lenient: Vector[Option[PersonId]], strict: Vector[Option[PersonId]])

  /** Groups people by how many confirmed faces they have, which is the axis the current rule turns out to be most sensitive to. */
  def galleryBucket(size: Int): String =
    if (size <= 2) "1-2"
    else if (size <= 10) "3-10"
    else if (size <= 50) "11-50"
    else if (size <= 200) "51-200"
    else "200+"

  val buckets = Vector("1-2", "3-10", "11-50", "51-200", "200+")

  // -------------------------------------------------------------------------------------------------------------------
  def confirmedGallery(): ZIO[MediaService, Exception, Chunk[GalleryFace]] =
    for {
      confirmed <- MediaService.faceList().filter(_.identifiedPersonId.isDefined).runCollect
      gallery   <- ZIO.foreach(confirmed) { face =>
                     MediaService
                       .faceFeaturesGet(face.faceId)
                       .map(_.map(features => GalleryFace(face.faceId, face.identifiedPersonId.get, face.originalId, features.features)))
                   }
    } yield gallery.flatten

  def ignoredFeatures(): ZIO[MediaService, Exception, Chunk[Array[Float]]] =
    for {
      ignored  <- MediaService.faceList().filter(_.inferredIgnore.contains(true)).runCollect
      features <- ZIO.foreach(ignored)(face => MediaService.faceFeaturesGet(face.faceId).map(_.map(_.features)))
    } yield features.flatten

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Evaluate face inference against confirmed faces") {
    for {
      gallery       <- confirmedGallery()
      ignored       <- ignoredFeatures()
      byFaceId       = gallery.map(entry => entry.faceId -> entry).toMap
      facesPerPerson = gallery.groupBy(_.personId).map((personId, theirs) => personId -> theirs.size)
      dimension      = gallery.headOption.map(_.features.length).getOrElse(512)
      _             <- Console.printLine(s"${gallery.size} confirmed faces over ${facesPerPerson.size} people, ${ignored.size} ignored faces, $dimension-dimension features")

      evaluated                           <- withGalleryIndex(gallery, dimension) { index =>
                                               for {
                                                 measurements <- zio.stream.ZStream
                                                                   .fromChunk(gallery)
                                                                   .mapZIOParUnordered(searchParallelism)(subject => measure(index, subject, byFaceId, facesPerPerson, ignored))
                                                                   .runCollect
                                                                   .timed
                                                                   .tap((elapsed, _) => ZIO.logInfo(s"${gallery.size} leave-one-out queries in ${elapsed.toSeconds}s"))
                                                 recall       <- approximationRecall(index, gallery)
                                               } yield (measurements._2, recall)
                                             }
      (measurements, approximationQuality) = evaluated

      strangers                        <- strangerFalsePositives(gallery)
      (strangerTallies, strangersTried) = strangers

      _ <- report(measurements, gallery.size, approximationQuality, strangerTallies, strangersTried)
    } yield ()
  }

  /** Leave-one-**person**-out: the measurement the face-level evaluation structurally cannot make.
    *
    * Every face scored above belongs to somebody who is in the gallery, so those numbers say nothing about the failure that a looser threshold most invites - a face of a person who was never enrolled being confidently handed someone else's name.
    * Here the subject's entire person is removed from the gallery, which makes them a stranger: the only correct answer is to abstain, and *any* identification is a false positive.
    *
    * The candidate lists are built by scanning the gallery directly rather than through the index, because a filtered nearest-neighbor query isn't something the index can answer: for a person with thousands of enrolled faces, the nearest few hundred
    * neighbors are all their own, and the strangers only start below that.
    */
  def strangerFalsePositives(gallery: Chunk[GalleryFace]): ZIO[Any, Nothing, (Vector[Tally], Int)] = {
    val galleryArray = gallery.toArray
    // Capped per person so that the handful of very heavily photographed people don't drown out everybody else: the
    // question here is "what happens when a new face shows up", which is a per-person question.
    val sample       = Chunk.fromIterable(gallery.groupBy(_.personId).values.flatMap(_.take(strangerFacesPerPerson)))

    for {
      timed               <- zio.stream.ZStream
                               .fromChunk(sample)
                               .mapZIOParUnordered(searchParallelism)(subject => ZIO.succeed(rules.map((_, rule) => rule(nearestExcludingPerson(galleryArray, subject, candidateCount)))))
                               .runCollect
                               .timed
      (elapsed, decisions) = timed
      _                   <- ZIO.logInfo(s"${sample.size} stranger queries (whole person held out) in ${elapsed.toSeconds}s")
      tallies              = rules.indices.map(r => decisions.foldLeft(Tally())((tally, perRule) => tally.recordStranger(perRule(r)))).toVector
    } yield (tallies, sample.size)
  }

  /** The `wanted` closest gallery faces that do **not** belong to the subject's person, by exact scan. */
  def nearestExcludingPerson(gallery: Array[GalleryFace], subject: GalleryFace, wanted: Int): Chunk[Candidate] = {
    val bestDistance = Array.fill(wanted)(Double.MaxValue)
    val bestIndex    = Array.fill(wanted)(-1)

    var i = 0
    while (i < gallery.length) {
      val other = gallery(i)
      if (other.personId != subject.personId) {
        val distance = VectorMetric.Cosine.distance(subject.features, other.features)
        if (distance < bestDistance(wanted - 1)) {
          var slot = wanted - 1
          while (slot > 0 && bestDistance(slot - 1) > distance) {
            bestDistance(slot) = bestDistance(slot - 1)
            bestIndex(slot) = bestIndex(slot - 1)
            slot -= 1
          }
          bestDistance(slot) = distance
          bestIndex(slot) = i
        }
      }
      i += 1
    }

    Chunk.fromIterable(
      (0 until wanted)
        .filter(slot => bestIndex(slot) >= 0)
        .map(slot => Candidate(gallery(bestIndex(slot)).personId, gallery(bestIndex(slot)).originalId, bestDistance(slot)))
    )
  }

  /** Checks how much the approximate index actually costs in recall on this data, at the settings the production job runs with - the question the synthetic tests in `zio-lmdb-vector` can only answer for synthetic vectors. */
  def approximationRecall(index: LMDBVectorIndex[FaceId], gallery: Chunk[GalleryFace]): ZIO[Any, Nothing, Double] = {
    val wanted = 8 // what `FaceInference` retrieves
    val step   = math.max(1, gallery.size / recallSampleSize)
    val sample = Chunk.fromIterable(0 until gallery.size by step).map(gallery.apply)
    ZIO
      .foreach(sample) { subject =>
        for {
          exact  <- index.searchNearest(subject.features, k = wanted + 1).orDieWith(err => new RuntimeException(err.toString))
          approx <- index.searchApproximate(subject.features, k = wanted + 1).orDieWith(err => new RuntimeException(err.toString))
        } yield {
          val truth = exact.map((faceId, _) => faceId).filter(_ != subject.faceId).take(wanted).toSet
          val found = approx.map((faceId, _) => faceId).filter(_ != subject.faceId).take(wanted).toSet
          if (truth.isEmpty) 1d else truth.intersect(found).size.toDouble / truth.size
        }
      }
      .map(recalls => if (recalls.isEmpty) 1d else recalls.sum / recalls.size)
      .timed
      .tap((elapsed, _) => ZIO.logInfo(s"approximation check over ${sample.size} sampled faces in ${elapsed.toSeconds}s"))
      .map((_, recall) => recall)
  }

  /** Runs one leave-one-out query and applies every rule to its result, twice: once with only the face itself removed, and once with every face from the same photo removed too. */
  def measure(
    index: LMDBVectorIndex[FaceId],
    subject: GalleryFace,
    byFaceId: Map[FaceId, GalleryFace],
    facesPerPerson: Map[PersonId, Int],
    ignored: Chunk[Array[Float]]
  ): ZIO[Any, Nothing, Measurement] =
    index
      .searchApproximate(subject.features, k = candidateCount + 1, ef = Some(evaluationEf))
      .orDieWith(err => new RuntimeException(err.toString))
      .map { found =>
        val candidates = found
          .filter((faceId, _) => faceId != subject.faceId) // leave-one-out: the subject is always its own nearest neighbor
          .flatMap((faceId, distance) => byFaceId.get(faceId).map(entry => Candidate(entry.personId, entry.originalId, distance)))

        // Faces from the same photo are often near-duplicates (bursts, re-imports) and make the task look easier than
        // it is, so the rules are also scored with the subject's whole photo held out.
        val withoutSamePhoto = candidates.filter(_.originalId != subject.originalId)

        val vetoed = ignored.exists(features => VectorMetric.Cosine.distance(subject.features, features) <= maxIgnoredMatchDistance)

        Measurement(
          bucket = galleryBucket(facesPerPerson.getOrElse(subject.personId, 0)),
          truth = subject.personId,
          vetoed = vetoed,
          lenient = rules.map((_, rule) => rule(candidates)),
          strict = rules.map((_, rule) => rule(withoutSamePhoto))
        )
      }

  /** Builds a throwaway vector index over the gallery, warmed for exact search and graph-indexed for approximate search, and drops it again afterwards. */
  def withGalleryIndex[R, A](gallery: Chunk[GalleryFace], dimension: Int)(use: LMDBVectorIndex[FaceId] => ZIO[R, Nothing, A]): ZIO[R & LMDB, Nothing, A] = {
    val collectionName = "faceInferenceEvaluationVectorIndexTmp"
    ZIO.acquireReleaseWith(
      (LMDB.collectionDrop(collectionName).ignore *>
        LMDBVectorIndex
          .create[FaceId](collectionName, dimension, VectorMetric.Cosine, failIfExists = false)
          .tap(index => ZIO.foreachDiscard(gallery)(entry => index.insert(entry.faceId, entry.features)))
          .tap(index => index.warm()) // the exact reference used by the approximation check at the end
          .tap(index =>
            index
              .buildApproximateIndex(HnswParams(m = 16, efConstruction = 100, efSearch = 64))
              .timed
              .flatMap((elapsed, _) => ZIO.logInfo(s"approximate index over ${gallery.size} confirmed faces built in ${elapsed.toSeconds}s"))
          )).orDieWith(err => new RuntimeException(err.toString))
    )(index => LMDB.collectionDrop(index.collection.name).orDieWith(err => new RuntimeException(err.toString)))(use)
  }

  // -------------------------------------------------------------------------------------------------------------------
  def report(measurements: Chunk[Measurement], total: Int, approximationQuality: Double, strangerTallies: Vector[Tally], strangersTried: Int): ZIO[Any, java.io.IOException, Unit] = {
    val lenient = rules.indices.map(r => measurements.foldLeft(Tally())((tally, m) => tally.record(m.lenient(r), m.truth))).toVector
    val strict  = rules.indices.map(r => measurements.foldLeft(Tally())((tally, m) => tally.record(m.strict(r), m.truth))).toVector

    val currentRule = rules.indexWhere((name, _) => name == "current: top-2 agree (t=0.16)")
    val marginRule  = rules.indexWhere((name, _) => name == "person margin (t=0.20, m=0.02, best-3)")

    val byBucket = buckets.map { bucket =>
      val slice = measurements.filter(_.bucket == bucket)
      bucket -> (
        slice.size,
        slice.foldLeft(Tally())((tally, m) => tally.record(m.lenient(currentRule), m.truth)),
        slice.foldLeft(Tally())((tally, m) => tally.record(m.lenient(marginRule), m.truth))
      )
    }

    val vetoed = measurements.count(_.vetoed)

    for {
      _ <- Console.printLine("")
      _ <- Console.printLine(s"=== Leave-one-out over $total confirmed faces (k=$candidateCount candidates, ef=$evaluationEf) ===")
      _ <- Console.printLine("")
      _ <- Console.printLine(f"${"rule"}%-40s ${"decided"}%9s ${"correct"}%9s ${"wrong"}%7s ${"abstain"}%9s ${"precision"}%10s ${"recall"}%8s")
      _ <- ZIO.foreachDiscard(rules.indices)(r => Console.printLine(tallyRow(rules(r)._1, lenient(r), total)))
      _ <- Console.printLine("")
      _ <- Console.printLine("--- same photo held out too (no burst / re-import leakage) ---")
      _ <- ZIO.foreachDiscard(rules.indices)(r => Console.printLine(tallyRow(rules(r)._1, strict(r), total)))
      _ <- Console.printLine("")
      _ <- Console.printLine(s"--- strangers: $strangersTried faces whose person was held out of the gallery entirely (abstaining is the only right answer) ---")
      _ <- Console.printLine(f"${"rule"}%-40s ${"identified"}%11s ${"abstained"}%10s ${"false positive rate"}%20s")
      _ <- ZIO.foreachDiscard(rules.indices) { r =>
             val tally = strangerTallies(r)
             Console.printLine(f"${rules(r)._1}%-40s ${tally.decided}%11d ${tally.abstained}%10d ${percent(tally.decided.toDouble / strangersTried)}%20s")
           }
      _ <- Console.printLine("")
      _ <- Console.printLine(s"--- by number of confirmed faces the true person has: '${rules(currentRule)._1}' vs '${rules(marginRule)._1}' ---")
      _ <- Console.printLine(f"${"gallery size"}%-14s ${"faces"}%8s ${"current prec"}%13s ${"current rec"}%12s ${"margin prec"}%12s ${"margin rec"}%11s")
      _ <- ZIO.foreachDiscard(byBucket) { (bucket, counts) =>
             val (size, current, margin) = counts
             Console.printLine(f"$bucket%-14s $size%8d ${percent(current.precision)}%13s ${percent(current.recall(size))}%12s ${percent(margin.precision)}%12s ${percent(margin.recall(size))}%11s")
           }
      _ <- Console.printLine("")
      _ <- Console.printLine(f"ignore-veto: $vetoed%d of $total%d confirmed faces (${percent(vetoed.toDouble / total)}%s) sit within $maxIgnoredMatchDistance%.2f of an ignored face, and would have their identification suppressed")
      _ <- Console.printLine(f"approximate index: recall@8 of ${percent(approximationQuality)}%s against an exact scan, at the production efSearch - the share of true neighbors the graph actually returns")
    } yield ()
  }

  def tallyRow(name: String, tally: Tally, total: Int): String =
    f"$name%-40s ${tally.decided}%9d ${tally.correct}%9d ${tally.wrong}%7d ${tally.abstained}%9d ${percent(tally.precision)}%10s ${percent(tally.recall(total))}%8s"

  def percent(ratio: Double): String = f"${ratio * 100}%.2f%%"
}
