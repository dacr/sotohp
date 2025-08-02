package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.core.{ConfigInvalid, CoreIssue}
import fr.janalyse.sotohp.processor.config.NormalizerConfig
import fr.janalyse.sotohp.processor.model.OriginalNormalized
import org.apache.commons.imaging.Imaging
import zio.*
import zio.ZIOAspect.*

import java.nio.file.Path

case class NormalizeIssue(message: String, exception: Throwable) extends Exception(message, exception) with CoreIssue

object NormalizeProcessor extends Processor {

  private def makeOutputDirectories(output: Path) = {
    ZIO
      .attempt(output.getParent.toFile.mkdirs())
      .mapError(th => NormalizeIssue(s"Couldn't target path", th))
  }

  private def resizePhoto(input: Path, output: Path, orientation: Option[Orientation]) = {
    for {
      config       <- NormalizerConfig.config
      referenceSize = config.referenceSize
      newDimension <- ZIO
                        .attemptBlocking(
                          BasicImaging.reshapeImage(input, output, referenceSize, orientation.map(_.rotationDegrees), Some(config.quality))
                        )
                        .tap(_ => ZIO.logInfo(s"Normalize"))
                        .mapError(th => NormalizeIssue(s"Couldn't generate normalized photo $input with reference size $referenceSize", th))
                        .uninterruptible
    } yield newDimension
  }

  /** generates normalized photo
    *
    * @param original
    * @return
    *   photo with updated normalized field if some changes have occurred
    */
  def normalize(original: Original): IO[CoreIssue, OriginalNormalized] = {
    val logic = for {
      input     <- ZIO
                     .attempt(original.mediaPath.path.toAbsolutePath)
                     .mapError(th => NormalizeIssue(s"Couldn't build input path", th))
      output    <- getNormalizedPhotoFilePath(original)
      _         <- makeOutputDirectories(output)
      dimension <- resizePhoto(input, output, original.orientation)
    } yield OriginalNormalized(original, Dimension(Width(dimension.width), Height(dimension.height)))

    logic
      .logError(s"Normalization issue")
      @@ annotated("originalId" -> original.id.asString)
      @@ annotated("originalPath" -> original.mediaPath.toString)
  }

}
