package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

import java.time.temporal.ChronoUnit.{MONTHS, YEARS}
import java.time.{Instant, OffsetDateTime}
import scala.io.AnsiColor.*

/*
 * This is a one-shot tool to fix original file path from absolute to relative to store path
 */
object MigrateFaces extends CommonsCLI {

  override def run =
    logic
      .provide(
        configProviderLayer >>> LMDB.live,
        configProviderLayer >>> SearchService.live,
        MediaService.live,
        Scope.default,
        configProviderLayer
      )

  // -------------------------------------------------------------------------------------------------------------------
//  def migrateFaces(original: Original) = {
//    for {
//      originalFaces <- MediaService.originalFaces(original.id)
//      facesCount     = originalFaces.map(_.faces.size).getOrElse(0)
//      _             <- ZIO
//                         .logInfo(s"Fix original faces ${original.mediaPath.path} -> $facesCount")
//                         .when(facesCount > 0)
//      _             <- ZIO.foreachDiscard(originalFaces.map(_.faces).getOrElse(Nil)) { face =>
//                         MediaService
//                           .faceUpdate(face.faceId, face.copy(originalId = Some(original.id)))
//                           .whenZIO(MediaService.faceExists(face.faceId).negate)
//                       }
//      _             <- MediaService
//                         .originalFacesUpdate(original.id, originalFaces.map(_.faces.map(_.faceId)).getOrElse(Nil))
//    } yield ()
//  }

  // -------------------------------------------------------------------------------------------------------------------
//  def rewriteFacesToCleanupRemoveField(original: Original) = {
//    for {
//      originalFaces <- MediaService.originalFaces(original.id)
//      facesCount     = originalFaces.map(_.faces.size).getOrElse(0)
//      _             <- ZIO
//                         .logInfo(s"Fix original faces ${original.mediaPath.path} -> $facesCount")
//      _             <- MediaService
//                         .originalFacesUpdate(original.id, originalFaces.map(_.faces.map(_.faceId)).getOrElse(Nil))
//    } yield ()
//  }
  // -------------------------------------------------------------------------------------------------------------------
  def refreshDetectedFaceTimestampField(media: Media) = {
    for {
      originalFaces <- MediaService.originalFaces(media.original.id)
      timestamp      = media.timestamp
      _             <- ZIO
                         .foreachDiscard(originalFaces.map(_.faces).getOrElse(Nil)) { detectedFace =>
                           MediaService
                             .faceUpdate(detectedFace.faceId, detectedFace.copy(timestamp = media.timestamp))
                             .when(detectedFace.timestamp != media.timestamp)
                         }
    } yield ()
  }

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Fix original media path from absolute to relative") {
    // MediaService.originalList().runForeach(rewriteFacesToCleanupRemoveField)
    MediaService.mediaList().runForeach(refreshDetectedFaceTimestampField)
  }

}
