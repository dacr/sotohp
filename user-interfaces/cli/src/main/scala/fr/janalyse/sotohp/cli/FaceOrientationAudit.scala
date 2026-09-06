package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.lmdb.LMDB

import scala.io.AnsiColor.*

/*
 * Media whose rotation has been customized by the user (media.orientation differs from
 * original.orientation) may carry face boxes/thumbnails that were computed against the wrong
 * rotation - either because they were detected before the user rotated the photo, or because a
 * face manually added afterward was cropped from the un-rotated image (see the faceCreate/rotate
 * bugs this tool exists to clean up after).
 *
 * Rather than assuming which rotation the currently stored faces are in, this tool asks the face
 * detector : it runs detection on both the original (EXIF) rotation and the effective
 * (user-customized) rotation, and matches each stored face box against both sets of fresh
 * detections by IoU. Whichever candidate the majority of stored boxes agree with is taken as the
 * box's *current* rotation, and only then is an exact, lossless 90°-multiple geometric remap
 * applied - faceId, identifiedPersonId and every other identity field are preserved untouched, and
 * nothing is deleted or re-detected, so manually-added faces and person identifications survive.
 *
 * Default mode only reports what it finds. Pass --fix to actually apply the remap to the
 * originals it could confidently classify as stuck on the original (pre-rotation) frame.
 * Ambiguous originals (stored boxes that don't clearly agree with either candidate) are always
 * left untouched and flagged for manual review.
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

  private case class Verdict(originalId: OriginalId, facesCount: Int, identifiedCount: Int, matchAtOriginal: Int, matchAtEffective: Int, originalRotation: Int, effectiveRotation: Int) {
    def classification: String =
      if (matchAtEffective > matchAtOriginal) "already-fixed"
      else if (matchAtOriginal > matchAtEffective) "stuck-at-original"
      else "ambiguous"
  }

  private def auditOne(media: Media): ZIO[MediaService, Nothing, Option[Verdict]] = {
    val original          = media.original
    val originalRotation  = original.orientation.map(_.rotationDegrees).getOrElse(0)
    val effectiveRotation = media.orientation.orElse(original.orientation).map(_.rotationDegrees).getOrElse(0)
    (for {
      storedFaces      <- MediaService.originalFaces(original.id).map(_.map(_.faces).getOrElse(Nil))
      result           <- if (storedFaces.isEmpty) ZIO.none
                           else
                             for {
                               detectedAtOriginal  <- MediaService.facesDetectPreview(original.id, originalRotation)
                               detectedAtEffective <- MediaService.facesDetectPreview(original.id, effectiveRotation)
                             } yield Some(
                               Verdict(
                                 originalId = original.id,
                                 facesCount = storedFaces.size,
                                 identifiedCount = storedFaces.count(_.identifiedPersonId.isDefined),
                                 matchAtOriginal = countMatches(storedFaces, detectedAtOriginal),
                                 matchAtEffective = countMatches(storedFaces, detectedAtEffective),
                                 originalRotation = originalRotation,
                                 effectiveRotation = effectiveRotation
                               )
                             )
    } yield result)
      .catchAll(err => ZIO.logWarning(s"Couldn't audit original ${original.id.asString} : $err").as(None))
  }

  private def report(v: Verdict): UIO[Unit] = {
    val (color, label) = v.classification match {
      case "already-fixed"     => (GREEN, "already correct, no action needed")
      case "stuck-at-original" => (YELLOW, "stuck at original rotation - would be fixed")
      case _                   => (RED, "ambiguous - needs manual review")
    }
    ZIO.logInfo(
      s"${color}${v.originalId.asString}$RESET  faces=${v.facesCount} identified=${v.identifiedCount}  " +
        s"match(original ${v.originalRotation}°)=${v.matchAtOriginal} match(effective ${v.effectiveRotation}°)=${v.matchAtEffective}  -> $label"
    )
  }

  val logic = ZIO.logSpan("Face orientation audit") {
    for {
      args           <- getArgs
      fixMode         = args.exists(a => a == "--fix" || a == "--execute" || a == "-f")
      _              <- ZIO.logInfo(if (fixMode) "Running in FIX mode - stuck originals will be remapped" else "Running in REPORT-ONLY mode (pass --fix to actually remap)")
      verdicts       <- MediaService
                          .mediaList()
                          .filter(tuple => hasUserCustomRotation(tuple.media))
                          .mapZIO(tuple => auditOne(tuple.media))
                          .collectSome
                          .mapZIO(v => report(v).as(v))
                          .runCollect
      byClass         = verdicts.groupBy(_.classification)
      stuck           = byClass.getOrElse("stuck-at-original", Chunk.empty)
      ambiguous       = byClass.getOrElse("ambiguous", Chunk.empty)
      alreadyFixed    = byClass.getOrElse("already-fixed", Chunk.empty)
      _              <- ZIO.logInfo(
                          s"Scanned ${verdicts.size} user-rotated originals with faces : " +
                            s"${alreadyFixed.size} already correct, ${stuck.size} stuck at the original rotation, ${ambiguous.size} ambiguous"
                        )
      _              <- ZIO
                          .foreachDiscard(stuck) { v =>
                            ZIO.logInfo(s"Remapping ${v.originalId.asString} : ${v.originalRotation}° -> ${v.effectiveRotation}°") *>
                              MediaService
                                .facesRemapForRotation(v.originalId, v.originalRotation, v.effectiveRotation)
                                .tapError(err => ZIO.logError(s"Failed to remap ${v.originalId.asString} : $err"))
                                .ignoreLogged
                          }
                          .when(fixMode)
      _              <- ZIO.logInfo(s"${ambiguous.size} originals need manual review (couldn't confidently tell which rotation their faces are in)").when(ambiguous.nonEmpty)
      _              <- ZIO.logInfo(if (fixMode) "Done - fixes applied to originals classified as stuck-at-original" else "Done - report only, nothing was changed (pass --fix to apply)")
    } yield ()
  }

}
