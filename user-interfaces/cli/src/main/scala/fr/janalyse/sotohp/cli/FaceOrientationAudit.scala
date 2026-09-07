package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.FacesProcessor
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.lmdb.LMDB

import scala.io.AnsiColor.*

/*
 * Media whose rotation has been customized by the user (media.orientation differs from
 * original.orientation) may carry face data that was computed against the wrong rotation. Two
 * distinct things can be wrong, and this tool checks both :
 *
 * 1. THE BOXES. Faces detected before the user rotated the photo have boxes expressed in the
 *    original (EXIF) frame instead of the effective one. Rather than assuming, this tool asks the
 *    face detector : it runs detection at both rotations and matches each stored box against both
 *    sets of fresh detections by IoU. Whichever candidate the stored boxes agree with is taken as
 *    the frame they are currently in, and only then is an exact, lossless 90°-multiple geometric
 *    remap applied.
 *
 * 2. THE CROPS. A face added by hand on a rotated photo used to get its box from the frontend (in
 *    the effective frame, which is correct) but its cached crop cut from the un-rotated image - so
 *    the box points at a face while the thumbnail on disk shows some unrelated corner of the photo,
 *    and the features computed from that thumbnail are of that corner too. Detection cannot see
 *    this, because the boxes are perfectly fine; the crop file gives itself away by its size
 *    instead. A crop is `box x image dimensions` pixels, and the two frames of a 90°-multiple
 *    rotation have swapped dimensions, so predicting the crop size at the effective rotation and
 *    comparing it with the file on disk says which frame the crop was cut in - no model, no decode,
 *    just the JPEG header. The repair re-cuts the crop (and its features) at the stored box, which
 *    is left untouched.
 *
 * Nothing is ever deleted or re-detected, so manually-added faces and person identifications
 * survive both repairs. Default mode only reports what it finds; pass --fix to apply. Ambiguous
 * originals (stored boxes that agree with neither candidate more than the other) are always left
 * untouched and flagged for manual review. Pass --crops-only to skip the (slow, model-driven) box
 * stage and check crop geometry alone.
 */
object FaceOrientationAudit extends CommonsCLI {

  override def run =
    logic
      .provideSome[ZIOAppArgs](
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  private val iouMatchThreshold = 0.5d

  private def hasUserCustomRotation(media: Media): Boolean = {
    val originalRotation  = media.original.orientation.map(_.rotationDegrees).getOrElse(0)
    val effectiveRotation = media.orientation.orElse(media.original.orientation).map(_.rotationDegrees).getOrElse(0)
    media.orientation.isDefined && originalRotation != effectiveRotation
  }

  private def iou(a: BoundingBox, b: BoundingBox): Double = {
    val ax1 = a.x.value
    val ay1 = a.y.value
    val ax2 = a.x.value + a.width.value
    val ay2 = a.y.value + a.height.value
    val bx1 = b.x.value
    val by1 = b.y.value
    val bx2 = b.x.value + b.width.value
    val by2 = b.y.value + b.height.value

    val interW = math.max(0d, math.min(ax2, bx2) - math.max(ax1, bx1))
    val interH = math.max(0d, math.min(ay2, by2) - math.max(ay1, by1))
    val inter  = interW * interH
    val union  = a.width.value * a.height.value + b.width.value * b.height.value - inter
    if (union <= 0d) 0d else inter / union
  }

  /** For each stored face, its best IoU against any freshly detected box - i.e. how well the
    * stored (unmodified) box coincides with a real detection in that candidate frame.
    */
  private def countMatches(storedFaces: List[Face], detected: List[BoundingBox]): Int =
    storedFaces.count(stored => detected.exists(box => iou(stored.box, box) >= iouMatchThreshold))

  /** The size `extractThenCacheFaceImageFromOriginal` would give this face's crop, had it been cut
    * from the original rotated by `rotationDegrees`.
    */
  private def expectedCropSize(box: BoundingBox, dimension: Dimension, rotationDegrees: Int): (Int, Int) = {
    val rawWidth        = dimension.width.value
    val rawHeight       = dimension.height.value
    val swapped         = rotationDegrees == 90 || rotationDegrees == 270
    val width           = if (swapped) rawHeight else rawWidth
    val height          = if (swapped) rawWidth else rawHeight
    val (_, _, cw, ch)  = FacesProcessor.croppedRectangle(box, width, height)
    (cw, ch)
  }

  /** Whether this face's cached crop was cut in the effective frame. None when it cannot be told :
    * no known original dimension, an unreadable crop file, or - the interesting case - a rotation
    * difference of 180°, where both frames have the very same dimensions and the size carries no
    * information at all.
    */
  private def cropIsAtEffectiveRotation(face: Face, dimension: Option[Dimension], originalRotation: Int, effectiveRotation: Int): Option[Boolean] = {
    val undecidable = (effectiveRotation - originalRotation + 360) % 360 == 180
    for {
      dim    <- dimension
      if !undecidable
      onDisk <- BasicImaging.sizeOf(face.path.path)
    } yield onDisk == expectedCropSize(face.box, dim, effectiveRotation)
  }

  private enum BoxFrame {
    case StuckAtOriginal, AtEffective, NoDetections, Ambiguous
  }

  private case class Verdict(
    originalId: OriginalId,
    facesCount: Int,
    identifiedCount: Int,
    matchAtOriginal: Int,
    matchAtEffective: Int,
    staleCrops: Int,
    undecidableCrops: Int,
    originalRotation: Int,
    effectiveRotation: Int,
    boxesChecked: Boolean
  ) {
    def boxFrame: BoxFrame =
      if (!boxesChecked) BoxFrame.AtEffective
      else if (matchAtEffective > matchAtOriginal) BoxFrame.AtEffective
      else if (matchAtOriginal > matchAtEffective) BoxFrame.StuckAtOriginal
      else if (matchAtOriginal == 0) BoxFrame.NoDetections
      else BoxFrame.Ambiguous

    /** What to do about this original, in priority order : a stuck box frame subsumes everything
      * (remapping re-cuts the crops on the way), and a doubtful box frame vetoes touching the crops
      * at all, since re-cutting them assumes the box is right.
      */
    def classification: String = boxFrame match {
      case BoxFrame.StuckAtOriginal            => "stuck-at-original"
      case BoxFrame.Ambiguous                  => "ambiguous"
      case _ if staleCrops > 0                 => "stale-crops"
      case _                                   => "already-correct"
    }
  }

  private def auditOne(media: Media, checkBoxes: Boolean): ZIO[MediaService, Nothing, Option[Verdict]] = {
    val original          = media.original
    val originalRotation  = original.orientation.map(_.rotationDegrees).getOrElse(0)
    val effectiveRotation = media.orientation.orElse(original.orientation).map(_.rotationDegrees).getOrElse(0)
    (for {
      storedFaces <- MediaService.originalFaces(original.id).map(_.map(_.faces).getOrElse(Nil))
      result      <- if (storedFaces.isEmpty) ZIO.none
                     else
                       for {
                         cropChecks          <- ZIO.attemptBlocking(
                                                  storedFaces.map(face => cropIsAtEffectiveRotation(face, original.dimension, originalRotation, effectiveRotation))
                                                )
                         detectedAtOriginal  <- MediaService.facesDetectPreview(original.id, originalRotation).when(checkBoxes).map(_.getOrElse(Nil))
                         detectedAtEffective <- MediaService.facesDetectPreview(original.id, effectiveRotation).when(checkBoxes).map(_.getOrElse(Nil))
                       } yield Some(
                         Verdict(
                           originalId = original.id,
                           facesCount = storedFaces.size,
                           identifiedCount = storedFaces.count(_.identifiedPersonId.isDefined),
                           matchAtOriginal = countMatches(storedFaces, detectedAtOriginal),
                           matchAtEffective = countMatches(storedFaces, detectedAtEffective),
                           staleCrops = cropChecks.count(_.contains(false)),
                           undecidableCrops = cropChecks.count(_.isEmpty),
                           originalRotation = originalRotation,
                           effectiveRotation = effectiveRotation,
                           boxesChecked = checkBoxes
                         )
                       )
    } yield result)
      .catchAll(err => ZIO.logWarning(s"Couldn't audit original ${original.id.asString} : $err").as(None))
  }

  private def report(v: Verdict): UIO[Unit] = {
    val (color, label) = v.classification match {
      case "already-correct"   => (GREEN, "already correct, no action needed")
      case "stuck-at-original" => (YELLOW, "boxes stuck at original rotation - would be remapped")
      case "stale-crops"       => (YELLOW, s"boxes fine but ${v.staleCrops}/${v.facesCount} crops cut at the wrong rotation - would be re-cropped")
      case _                   => (RED, "ambiguous - needs manual review")
    }
    val boxes          = if (v.boxesChecked) s"match(original ${v.originalRotation}°)=${v.matchAtOriginal} match(effective ${v.effectiveRotation}°)=${v.matchAtEffective}  " else ""
    val undecidable    = if (v.undecidableCrops > 0) s"undecidable-crops=${v.undecidableCrops}  " else ""
    ZIO.logInfo(
      s"${color}${v.originalId.asString}$RESET  faces=${v.facesCount} identified=${v.identifiedCount}  " +
        boxes + undecidable + s"-> $label"
    )
  }

  private def applyFix(v: Verdict): ZIO[MediaService, Nothing, Unit] = v.classification match {
    case "stuck-at-original" =>
      ZIO.logInfo(s"Remapping ${v.originalId.asString} : ${v.originalRotation}° -> ${v.effectiveRotation}°") *>
        MediaService
          .facesRemapForRotation(v.originalId, v.originalRotation, v.effectiveRotation)
          .tapError(err => ZIO.logError(s"Failed to remap ${v.originalId.asString} : $err"))
          .ignoreLogged
    case "stale-crops"       =>
      ZIO.logInfo(s"Re-cropping ${v.originalId.asString} at ${v.effectiveRotation}° (${v.staleCrops} stale crop(s), boxes untouched)") *>
        MediaService
          .facesRecropForEffectiveRotation(v.originalId)
          .tapError(err => ZIO.logError(s"Failed to re-crop ${v.originalId.asString} : $err"))
          .ignoreLogged
    case _                   => ZIO.unit
  }

  val logic = ZIO.logSpan("Face orientation audit") {
    for {
      args             <- getArgs
      fixMode           = args.exists(a => a == "--fix" || a == "--execute" || a == "-f")
      checkBoxes        = !args.contains("--crops-only")
      _                <- ZIO.logInfo(if (fixMode) "Running in FIX mode - affected originals will be repaired" else "Running in REPORT-ONLY mode (pass --fix to actually repair)")
      _                <- ZIO.logInfo("Checking crop geometry only - box frames are assumed correct (--crops-only)").when(!checkBoxes)
      verdicts         <- MediaService
                            .mediaList()
                            .filter(tuple => hasUserCustomRotation(tuple.media))
                            .mapZIO(tuple => auditOne(tuple.media, checkBoxes))
                            .collectSome
                            .mapZIO(v => report(v).as(v))
                            .runCollect
      byClass           = verdicts.groupBy(_.classification)
      stuck             = byClass.getOrElse("stuck-at-original", Chunk.empty)
      staleCrops        = byClass.getOrElse("stale-crops", Chunk.empty)
      ambiguous         = byClass.getOrElse("ambiguous", Chunk.empty)
      alreadyCorrect    = byClass.getOrElse("already-correct", Chunk.empty)
      undecidable       = verdicts.map(_.undecidableCrops).sum
      _                <- ZIO.logInfo(
                            s"Scanned ${verdicts.size} user-rotated originals with faces : " +
                              s"${alreadyCorrect.size} already correct, ${stuck.size} with boxes stuck at the original rotation, " +
                              s"${staleCrops.size} with crops cut at the wrong rotation (${staleCrops.map(_.staleCrops).sum} faces), ${ambiguous.size} ambiguous"
                          )
      _                <- ZIO
                            .logInfo(s"$undecidable crop(s) could not be checked - a 180° rotation leaves the crop size unchanged, so nothing can be told from it")
                            .when(undecidable > 0)
      _                <- ZIO.foreachDiscard(stuck ++ staleCrops)(applyFix).when(fixMode)
      _                <- ZIO.logInfo(s"${ambiguous.size} originals need manual review (couldn't confidently tell which rotation their boxes are in)").when(ambiguous.nonEmpty)
      _                <- ZIO.logInfo(if (fixMode) "Done - repairs applied" else "Done - report only, nothing was changed (pass --fix to apply)")
    } yield ()
  }

}
