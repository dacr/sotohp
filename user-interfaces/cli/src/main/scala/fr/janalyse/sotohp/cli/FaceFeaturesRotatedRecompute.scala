package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.lmdb.LMDB

/*
 * One-shot tool to recompute face features for media whose rotation has been
 * customized by the user (media.orientation differs from original.orientation).
 * The face features are recomputed on the rotated face.
 */
object FaceFeaturesRotatedRecompute extends CommonsCLI {

  override def run =
    logic
      .provide(
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  private def hasUserCustomRotation(media: Media): Boolean =
    media.orientation.isDefined && media.orientation != media.original.orientation

  val logic = ZIO.logSpan("Recompute face features for user-rotated media") {
    for {
      _              <- ZIO.logInfo("Scanning media for user-customized rotation...")
      processedCount <- MediaService
                          .mediaList()
                          .filter(tuple => hasUserCustomRotation(tuple.media))
                          .mapZIO { tuple =>
                            val original  = tuple.media.original
                            val mediaRot  = tuple.media.orientation.map(_.rotationDegrees).getOrElse(0)
                            val origRot   = original.orientation.map(_.rotationDegrees).getOrElse(0)
                            ZIO.logInfo(s"Recomputing face features for ${original.id} (rotation $origRot° -> $mediaRot°)") *>
                              MediaService
                                .originalFacesFeaturesRecompute(tuple.media)
                                .tap {
                                  case Some(result) => ZIO.logInfo(s"Recomputed ${result.features.size} face features for ${original.id}")
                                  case None         => ZIO.logInfo(s"No faces to recompute for ${original.id}")
                                }
                                .ignoreLogged
                                .as(1)
                          }
                          .runSum
      _              <- ZIO.logInfo(s"Done - $processedCount media processed")
    } yield ()
  }

}
