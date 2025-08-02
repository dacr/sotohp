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

  override def close(): Unit = {
  }

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

  /**
   * Normalizes the given original media file by resizing it and saving the normalized version.
   *
   * @param original The original media file to be normalized, containing metadata and file information such as its path, size, orientation, etc.
   * @return An effect that can produce either a `CoreIssue` if an error occurs during the normalization process,
   *         or an `OriginalNormalized` containing the normalized file and its new dimensions.
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
