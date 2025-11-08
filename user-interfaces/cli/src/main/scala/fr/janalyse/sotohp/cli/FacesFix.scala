package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.{FacesDetectionIssue, NormalizeProcessor}
import fr.janalyse.sotohp.processor.model.DetectedFace
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
object FacesFix extends CommonsCLI {

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
  def cacheFaceImage(
    face: DetectedFace,
    originalImage: BufferedImage
  ): IO[FacesDetectionIssue, Unit] = {
    val x           = (face.box.x.value * originalImage.getWidth).toInt
    val y           = (face.box.y.value * originalImage.getHeight).toInt
    val width       = (face.box.width.value * originalImage.getWidth).toInt
    val height      = (face.box.height.value * originalImage.getHeight).toInt
    val fixedX      = if (x < 0) 0 else x
    val fixedY      = if (y < 0) 0 else y
    val fixedWidth  = if (fixedX + width < originalImage.getWidth()) width else originalImage.getWidth - fixedX
    val fixedHeight = if (fixedY + height < originalImage.getHeight()) height else originalImage.getHeight - fixedY

    ZIO
      .attempt(originalImage.getSubimage(fixedX, fixedY, fixedWidth, fixedHeight))
      .mapError(err => FacesDetectionIssue(s"Couldn't extract face from image [$fixedX, $fixedY, $fixedWidth, $fixedHeight]", err))
      .flatMap(buff =>
        ZIO
          .attempt(BasicImaging.save(face.path.path, buff))
          .mapError(err => FacesDetectionIssue(s"Couldn't save selected face from image [$fixedX, $fixedY, $fixedWidth, $fixedHeight]", err))
          .logError("Faces caching issue")
      )
  }

  // -------------------------------------------------------------------------------------------------------------------
  def facesRotateRefresh(original: Original, originalFaces: List[DetectedFace], rotation: Int) = {
    for {
      originalBufferedImage <- ZIO.attemptBlocking(BasicImaging.load(original.absoluteMediaPath))
      originalBufferedImage <- ZIO
                                 .attemptBlocking(
                                   BasicImaging.rotate(
                                     originalBufferedImage,
                                     rotation
                                   )
                                 )
      _                     <- ZIO.foreachDiscard(originalFaces) { face =>
                                 cacheFaceImage(face, originalBufferedImage)
                               }
    } yield ()
  }

  def facesRotationFix(media: Media) = {
    for {
      original      <- MediaService.originalGet(media.original.id).some
      originalFaces <- MediaService.originalFaces(original.id).map(_.map(_.faces).getOrElse(Nil))
      rotation       = original.orientation.map(_.rotationDegrees).getOrElse(0)
      _             <- facesRotateRefresh(original, originalFaces, rotation)
                         .when(originalFaces.nonEmpty && rotation != 0)
    } yield ()
  }

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Fix rotation for faces extracted from originals") {
    MediaService.mediaList().runForeach(facesRotationFix)
  }

}
