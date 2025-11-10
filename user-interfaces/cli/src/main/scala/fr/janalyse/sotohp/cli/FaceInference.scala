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

  // -------------------------------------------------------------------------------------------------------------------
  def featuresForIdentifiedFaces(): ZIO[MediaService, Exception, Chunk[(DetectedFace, FaceFeatures)]] = {
    for {
      identifiedFaces <- MediaService.faceList().filter(_.identifiedPersonId.isDefined).runCollect
      featureByFace   <- ZIO.foreach(identifiedFaces) { detectedFace =>
                           MediaService.faceFeaturesGet(detectedFace.faceId).map(feature => feature.map(detectedFace -> _))
                         }
    } yield featureByFace.flatten
  }

  def featuresForUnknowFaces(): ZIO[MediaService, Exception, Chunk[(DetectedFace, FaceFeatures)]] = {
    for {
      identifiedFaces <- MediaService.faceList().filter(_.identifiedPersonId.isEmpty).runCollect
      featureByFace   <- ZIO.foreach(identifiedFaces) { detectedFace =>
                           MediaService.faceFeaturesGet(detectedFace.faceId).map(feature => feature.map(detectedFace -> _))
                         }
    } yield featureByFace.flatten
  }

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Infer person identification from faces features and already identified faces") {
    for {
      knownFaces     <- featuresForIdentifiedFaces()
      unknownFaces   <- featuresForUnknowFaces()
      alreadyInferred = unknownFaces.filter((face, _) => face.inferredIdentifiedPersonId.isDefined)
      // tocheck      = unknownFaces.filter((face, _) => face.originalId.asString == "a344f244-7ee5-5946-b37e-3b9c05f30f2c") // unknown people - 0.87 / 0.74
      // tocheck      = unknownFaces.filter((face, _) => face.originalId.asString == "98032e8e-b3c1-5a18-b046-cffe63249c47") // clo - 0.80
      // tocheck      = unknownFaces.filter((face, _) => face.originalId.asString == "148f95cf-9b4e-5d2c-a455-618b89534cf1") // chr - 0.62
      // tocheck       = unknownFaces.filter((face, _) => face.originalId.asString == "d4cd0c50-ec64-5475-a92c-d7b92ba40d52") // chr & agn - 0.57 / 0.51
      // tocheck       = unknownFaces.filter((face, _) => face.originalId.asString == "1a049ac1-b8ce-520e-80a2-ff633a76dd53") // bri & clo - 0.83 / 0.76
      tocheck         = unknownFaces
      _              <- Console.printLine(s"${knownFaces.size} known faces")
      _              <- Console.printLine(s"${unknownFaces.size} unknown faces with ${alreadyInferred.size} already inferred")
      _              <- ZIO.foreachDiscard(tocheck) { (face, faceFeatures) =>
                          val (knownFace, knownFaceFeature) = knownFaces.minBy((knownFace, knownFaceFeatures) => distance.d(faceFeatures.features, knownFaceFeatures.features))
                          val foundDistance                 = distance.d(faceFeatures.features, knownFaceFeature.features)
                          MediaService
                            .faceUpdate(
                              face.faceId,
                              face.copy(
                                inferredIdentifiedPersonId = knownFace.identifiedPersonId
                                  .filter(_ => foundDistance < 0.65)
                              )
                            )
                        }
    } yield ()
  }

}
