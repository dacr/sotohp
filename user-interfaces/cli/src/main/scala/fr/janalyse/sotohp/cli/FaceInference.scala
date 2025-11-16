package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.model.{DetectedFace, FaceFeatures, FaceId}
import fr.janalyse.sotohp.processor.{FacesDetectionIssue, NormalizeProcessor}
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

import java.awt.image.BufferedImage
import java.time.temporal.ChronoUnit.{MONTHS, YEARS}
import java.time.{Instant, OffsetDateTime}
import scala.io.AnsiColor.*

/*
 * This is a one-shot tool to fix faces rotation from originals, which was not initially done
 */
object FaceInference extends CommonsCLI {

  override def run =
    logic
      .provide(
        configProviderLayer >>> LMDB.live,
        configProviderLayer >>> SearchService.live,
        MediaService.live,
        Scope.default,
        configProviderLayer
      )

  val distance = new smile.math.distance.EuclideanDistance()

  def fixFaceWithMissingFeatures(): ZIO[MediaService, Exception, Unit] = {
    MediaService
      .originalList()
      .mapZIO(original => MediaService.originalFacesFeatures(original.id).ignoreLogged)
      .runCollect
      .unit
  }

  // -------------------------------------------------------------------------------------------------------------------
  def featuresForIdentifiedFaces(): ZIO[MediaService, Exception, Chunk[(DetectedFace, FaceFeatures)]] = {
    for {
      identifiedFaces <- MediaService.faceList().filter(_.identifiedPersonId.isDefined).runCollect
      featureByFace   <- ZIO.foreach(identifiedFaces) { detectedFace =>
                           MediaService
                             .faceFeaturesGet(detectedFace.faceId)
                             .map(feature => feature.map(detectedFace -> _))
                         }
    } yield featureByFace.flatten
  }

  def featuresForUnknowFaces(): ZIO[MediaService, Exception, Chunk[(DetectedFace, FaceFeatures)]] = {
    for {
      identifiedFaces <- MediaService.faceList().filter(_.identifiedPersonId.isEmpty).runCollect
      featureByFace   <- ZIO.foreach(identifiedFaces) { detectedFace =>
                           for {
                             original  <- MediaService.originalGet(detectedFace.originalId)
                             dimension  = original.flatMap(_.dimension)
                             faceWidth  = dimension.map(_.width.value * detectedFace.box.width.value)
                             faceHeight = dimension.map(_.height.value * detectedFace.box.height.value)
                             enoughBig  = dimension.isEmpty || (faceWidth.getOrElse(0d) > 70d && faceHeight.getOrElse(0d) > 70d)
                             tuple     <- MediaService
                                            .faceFeaturesGet(detectedFace.faceId)
                                            .map(feature => feature.map(detectedFace -> _))
                           } yield tuple.filter(_ => enoughBig)
                         }
    } yield featureByFace.flatten
  }

  def identifyFace(knownFaces: Chunk[(DetectedFace, FaceFeatures)])(face: DetectedFace, faceFeatures: FaceFeatures): ZIO[MediaService, Exception, Unit] = {
    val (knownFace, knownFaceFeature) = knownFaces.minBy((knownFace, knownFaceFeatures) => distance.d(faceFeatures.features, knownFaceFeatures.features))
    val foundDistance                 = distance.d(faceFeatures.features, knownFaceFeature.features)
    MediaService
      .faceUpdate(
        face.faceId,
        face.copy(
          inferredIdentifiedPersonId = knownFace.identifiedPersonId
            .filter(_ => foundDistance < 0.625)
        )
      )
      .unit
  }

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Infer person identification from faces features and already identified faces") {
    for {
      _              <- fixFaceWithMissingFeatures()
      knownFaces     <- featuresForIdentifiedFaces()
      unknownFaces   <- featuresForUnknowFaces()
      alreadyInferred = unknownFaces.filter((face, _) => face.inferredIdentifiedPersonId.isDefined && face.identifiedPersonId.isEmpty)
      tocheck         = unknownFaces
      _              <- Console.printLine(s"${knownFaces.size} known faces")
      _              <- Console.printLine(s"${unknownFaces.size} unknown faces with ${alreadyInferred.size} inferred and unconfirmed")
      _              <- ZIO.foreachDiscard(tocheck) { (face, faceFeatures) => identifyFace(knownFaces)(face, faceFeatures) }
    } yield ()
  }

}
