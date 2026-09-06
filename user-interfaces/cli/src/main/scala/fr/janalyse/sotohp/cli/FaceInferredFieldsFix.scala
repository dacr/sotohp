package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.lmdb.LMDB

/*
 * One-shot dataset fix : identifying a face used to only set `identifiedPersonId` and leave the
 * inference bookkeeping (inferredIdentifiedPersonId / confidence / timestamp / ignore) in place.
 * Those leftovers stay invisible in the UI - every view gates on `identifiedPersonId` first - but
 * they are not harmless :
 *   - removing the identification resurrects the old, possibly wrong, guess in the review queue,
 *   - a stale `inferredIgnore` keeps the face acting as a negative example, vetoing the inference
 *     of other faces of the very person it belongs to (see FaceInference.featuresForIgnoredFaces).
 *
 * MediaService.faceUpdate now clears them on every write; this walks the already stored faces and
 * applies the same rule to them.
 */
object FaceInferredFieldsFix extends CommonsCLI {

  override def run =
    logic
      .provide(
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  private case class Stats(scanned: Long = 0, identified: Long = 0, cleaned: Long = 0)

  val logic = ZIO.logSpan("Clear the inference leftovers of identified faces") {
    for {
      _     <- ZIO.logInfo("scanning faces...")
      stats <- MediaService
                 .faceList()
                 .runFoldZIO(Stats()) { (stats, face) =>
                   val identified = face.identifiedPersonId.isDefined
                   val toClean    = identified && face.hasInferredIdentification
                   MediaService
                     .faceUpdate(face.faceId, face.withoutInferredIdentification)
                     .when(toClean)
                     .as(
                       Stats(
                         scanned = stats.scanned + 1,
                         identified = stats.identified + (if (identified) 1 else 0),
                         cleaned = stats.cleaned + (if (toClean) 1 else 0)
                       )
                     )
                 }
      _     <- ZIO.logInfo(s"${stats.scanned} faces scanned, ${stats.identified} identified, ${stats.cleaned} cleaned up")
    } yield ()
  }
}
