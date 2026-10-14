package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.{FaceFeaturesProcessor, LocalAIFaceFeaturesProcessor}
import fr.janalyse.sotohp.processor.config.FaceFeaturesConfig
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import fr.janalyse.sotohp.core.LogContext
import zio.lmdb.LMDB
import zio.lmdb.vector.VectorMetric

import java.awt.image.BufferedImage

/** Measures how well each face features engine resists what the photos throw at it - head roll, an
  * upside-down or sideways photo, low resolution - on faces of this very library. Read-only: nothing
  * is written to the database, and the engines are called directly (the stored vectors aren't used),
  * so both engines can be compared on the same faces whatever the configured one is.
  *
  * A sample of human-confirmed faces is taken (several per person). Each face is embedded as is -
  * its reference vector - then again after every synthetic transformation of its crop. For each
  * engine and transformation it reports:
  *   - embedded: the share of transformed faces the engine still produced a vector for (LocalAI's
  *     detector may no longer find a face ; the DJL model always answers, right or wrong)
  *   - drift: cosine distance between the transformed vector and the face's own reference vector
  *   - rank-1: the share of transformed faces whose nearest reference vector - among all the other
  *     sampled faces, its own excluded - belongs to the right person. A missing vector counts as a
  *     miss. This is the number that matters: drift only hurts when it crosses to someone else.
  *
  * The reference rows give the scale : typical distance between two photos of the same person
  * (genuine) and of two different people (impostor) - drift is small only relative to that gap.
  * Yaw (head turned left/right) can't be synthesized from a 2D crop ; the genuine distances and the
  * untransformed rank-1, which compare different photos of each person, are where it shows.
  *
  * Args (all optional): `--engines=djl,localai`, `--persons=40`, `--faces-per-person=4`,
  * `--min-face-size=80` (px of the longest side on the original, so the downscale steps are real
  * downscales), `--seed=42`.
  */
object FaceEmbeddingDrift extends CommonsCLI {

  override def run =
    logic
      .provideSome[ZIOAppArgs](
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  case class Params(engines: List[String], persons: Int, facesPerPerson: Int, minFaceSize: Int, seed: Long)

  private def parse(args: Chunk[String]): Params = {
    def value(name: String): Option[String] = args.collectFirst { case s if s.startsWith(s"--$name=") => s.stripPrefix(s"--$name=") }
    Params(
      engines = value("engines").map(_.split(",").toList.map(_.trim).filter(_.nonEmpty)).getOrElse(List(FaceFeaturesConfig.djlEngine, FaceFeaturesConfig.localaiEngine)),
      persons = value("persons").flatMap(_.toIntOption).getOrElse(40).max(2),
      facesPerPerson = value("faces-per-person").flatMap(_.toIntOption).getOrElse(4).max(2),
      minFaceSize = value("min-face-size").flatMap(_.toIntOption).getOrElse(80),
      seed = value("seed").flatMap(_.toLongOption).getOrElse(42L)
    )
  }

  /** A synthetic transformation of a face crop ; `faceSize` is the longest side of the face box in the crop, in px. */
  case class Transformation(name: String, apply: (BufferedImage, Double) => BufferedImage)

  private def rolled(degrees: Double) = Transformation(f"roll ${degrees}%+.0f°", (crop, _) => BasicImaging.rotate(crop, degrees))

  private def downscaled(targetFaceSize: Int) = Transformation(
    s"face at ${targetFaceSize}px",
    { (crop, faceSize) =>
      val factor = targetFaceSize / faceSize
      if (factor >= 1d) crop
      else BasicImaging.resize(crop, math.max(1, (crop.getWidth * factor).toInt), math.max(1, (crop.getHeight * factor).toInt))
    }
  )

  val identity        = Transformation("reference (as is)", (crop, _) => crop)
  val transformations = List(
    identity,
    rolled(15),
    rolled(-15),
    rolled(30),
    rolled(-30),
    rolled(90),
    rolled(180),
    downscaled(96),
    downscaled(64),
    downscaled(40)
  )

  case class Sampled(face: Face, personId: PersonId, image: BufferedImage)

  // -------------------------------------------------------------------------------------------------------------------
  def sample(params: Params): ZIO[MediaService, Exception, List[Sampled]] =
    for {
      confirmed <- MediaService.faceList().filter(_.identifiedPersonId.isDefined).runCollect
      random     = new scala.util.Random(params.seed)
      picked     = random
                     .shuffle(confirmed.groupBy(_.identifiedPersonId.get).toList.sortBy((personId, _) => personId.toString))
                     .map((personId, faces) => personId -> random.shuffle(faces.toList))
      loaded    <- ZIO.foldLeft(picked)(List.empty[List[Sampled]]) { (selected, person) =>
                     val (personId, faces) = person
                     if (selected.size >= params.persons) ZIO.succeed(selected)
                     else
                       // walk this person's faces until enough big-enough ones are loaded
                       ZIO
                         .foldLeft(faces)(List.empty[Sampled]) { (theirs, face) =>
                           if (theirs.size >= params.facesPerPerson) ZIO.succeed(theirs)
                           else loadFace(face, params.minFaceSize).map(mayBe => theirs ++ mayBe.map((framed, image) => Sampled(framed, personId, image)))
                         }
                         .map(theirs => if (theirs.size >= 2) selected :+ theirs else selected)
                   }
    } yield loaded.flatten

  /** A window of the full resolution original at the media's effective rotation - the frame stored boxes are
    * expressed in - around the face (one box size of context on each side, more than any engine cuts), with the
    * face re-framed into it ; or nothing when the face is too small or the image can't be read. Only the window is
    * kept, not the whole photo : a sample of full resolution photos wouldn't fit in memory.
    */
  private def loadFace(face: Face, minFaceSize: Int): ZIO[MediaService, Nothing, Option[(Face, BufferedImage)]] =
    (for {
      original <- MediaService.originalGet(face.originalId).someOrFail(new RuntimeException(s"no original ${face.originalId}"))
      media    <- MediaService.mediaGet(face.originalId)
      rotation  = media.flatMap(_.media.orientation).orElse(original.orientation).map(_.rotationDegrees).getOrElse(0)
      image    <- ZIO.attemptBlocking(BasicImaging.rotate(BasicImaging.load(original.absoluteMediaPath), rotation.toDouble))
    } yield Option.when(faceSize(face, image) >= minFaceSize)(framed(face, image)))
      .catchAll(err => (ZIO.logWarning("Skipping face") @@ LogContext.face(face.faceId) @@ LogContext.kv("error" -> err)).as(None))

  private def framed(face: Face, image: BufferedImage): (Face, BufferedImage) = {
    val (left, top, width, height) = LocalAIFaceFeaturesProcessor.marginRectangle(face.box, image.getWidth, image.getHeight, 1.0d)
    val window                     = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB) // a copy, not a view on the photo's raster
    val graphics                   = window.createGraphics()
    try graphics.drawImage(image.getSubimage(left, top, width, height), 0, 0, null)
    finally graphics.dispose()
    val box = BoundingBox(
      x = XAxis((face.box.x.value * image.getWidth - left) / width),
      y = YAxis((face.box.y.value * image.getHeight - top) / height),
      width = BoxWidth(face.box.width.value * image.getWidth / width),
      height = BoxHeight(face.box.height.value * image.getHeight / height)
    )
    (face.copy(box = box), window)
  }

  private def faceSize(face: Face, image: BufferedImage): Double =
    math.max(face.box.width.value * image.getWidth, face.box.height.value * image.getHeight)

  // -------------------------------------------------------------------------------------------------------------------
  /** Per sampled face, per transformation, the vector if any. */
  type Vectors = Vector[Vector[Option[Array[Float]]]]

  def embedAll(processor: FaceFeaturesProcessor, sampled: List[Sampled]): ZIO[Any, Nothing, Vectors] =
    ZIO
      .foreach(sampled.toVector) { entry =>
        (for {
          crop     <- processor.faceCrop(entry.face, entry.image)
          // the face box's longest side, in crop pixels - the crop is cut at the image's resolution
          size      = faceSize(entry.face, entry.image)
          vectors  <- ZIO.foreach(transformations.toVector) { transformation =>
                        ZIO
                          .attemptBlocking(transformation.apply(crop, size))
                          .flatMap(processor.embedCrop)
                          .catchAll(err => (ZIO.logWarning("Face transformation failed") @@ LogContext.face(entry.face.faceId) @@ LogContext.kv("transformation" -> transformation.name, "error" -> err)).as(None))
                      }
        } yield vectors).catchAll(err => (ZIO.logWarning("Face embedding failed") @@ LogContext.face(entry.face.faceId) @@ LogContext.kv("error" -> err)).as(Vector.fill(transformations.size)(None)))
      }

  // -------------------------------------------------------------------------------------------------------------------
  def distance(a: Array[Float], b: Array[Float]): Double = VectorMetric.Cosine.distance(a, b)

  def quantile(values: Seq[Double], q: Double): Option[Double] =
    if (values.isEmpty) None
    else {
      val sorted = values.sorted
      Some(sorted(math.min(sorted.size - 1, math.max(0, math.ceil(q * sorted.size).toInt - 1))))
    }

  def fmt(value: Option[Double]): String = value.map(v => f"$v%.3f").getOrElse("-")
  def pct(ratio: Double): String         = f"${ratio * 100}%.1f%%"

  def report(engine: String, sampled: List[Sampled], vectors: Vectors): ZIO[Any, java.io.IOException, Unit] = {
    val persons    = sampled.map(_.personId).toVector
    val references = vectors.map(_.head) // identity transformation first
    val refIdx     = references.indices.filter(i => references(i).isDefined)

    val (genuine, impostor) = {
      val pairs = for { i <- refIdx; j <- refIdx if i < j } yield (persons(i) == persons(j), distance(references(i).get, references(j).get))
      (pairs.collect { case (true, d) => d }, pairs.collect { case (false, d) => d })
    }

    def rank1(vector: Array[Float], self: Int): Boolean =
      refIdx.filter(_ != self).minByOption(j => distance(vector, references(j).get)).exists(j => persons(j) == persons(self))

    val rows = transformations.indices.map { t =>
      val produced = vectors.indices.flatMap(i => vectors(i)(t).map(i -> _))
      val drifts   = produced.flatMap((i, vector) => references(i).map(reference => distance(vector, reference)))
      val correct  = produced.count((i, vector) => rank1(vector, i))
      (transformations(t).name, produced.size, drifts, correct)
    }

    for {
      _ <- Console.printLine("")
      _ <- Console.printLine(s"=== $engine - ${sampled.size} faces of ${persons.distinct.size} people ===")
      _ <- Console.printLine(
             s"genuine distance (same person, other photo): median ${fmt(quantile(genuine, 0.5))}, p90 ${fmt(quantile(genuine, 0.9))} - " +
               s"impostor distance (other people): p10 ${fmt(quantile(impostor, 0.1))}, median ${fmt(quantile(impostor, 0.5))}"
           )
      _ <- Console.printLine(f"${"transformation"}%-20s ${"embedded"}%9s ${"drift med"}%10s ${"drift p90"}%10s ${"rank-1"}%8s")
      _ <- ZIO.foreachDiscard(rows) { (name, produced, drifts, correct) =>
             Console.printLine(
               f"$name%-20s ${pct(produced.toDouble / sampled.size)}%9s ${fmt(quantile(drifts, 0.5))}%10s ${fmt(quantile(drifts, 0.9))}%10s ${pct(correct.toDouble / sampled.size)}%8s"
             )
           }
    } yield ()
  }

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Face embedding drift") {
    for {
      args    <- getArgs
      params   = parse(args)
      _       <- Console.printLine(s"engines ${params.engines.mkString(", ")} - ${params.persons} people x ${params.facesPerPerson} faces, faces >= ${params.minFaceSize}px, seed ${params.seed}")
      sampled <- sample(params)
      _       <- Console.printLine(s"${sampled.size} faces of ${sampled.map(_.personId).distinct.size} people loaded")
      _       <- ZIO.fail(new RuntimeException("not enough confirmed faces to sample")).when(sampled.map(_.personId).distinct.size < 2)
      _       <- ZIO.foreachDiscard(params.engines) { engine =>
                   ZIO.acquireReleaseWith(FaceFeaturesProcessor.allocate(engine))(processor => ZIO.succeed(processor.close())) { processor =>
                     embedAll(processor, sampled).timed.flatMap { (elapsed, vectors) =>
                       (ZIO.logInfo("Embeddings computed") @@ LogContext.kv("engine" -> engine, "count" -> sampled.size * transformations.size, "elapsedSeconds" -> elapsed.toSeconds)) *>
                         report(engine, sampled, vectors)
                     }
                   }
                 }
    } yield ()
  }
}
