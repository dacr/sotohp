package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.config.MiniaturizerConfig
import fr.janalyse.sotohp.processor.model.{OriginalMiniature, OriginalMiniatures}
import org.apache.commons.imaging.Imaging
import zio.*
import zio.ZIOAspect.*

import java.nio.file.Path

case class MiniaturizeIssue(message: String, exception: Throwable) extends Exception(message, exception) with CoreIssue

object MiniaturizeProcessor extends Processor {

  private def miniaturizePhoto(referenceSize: Int, input: Path, output: Path) = {
    for {
      config       <- MiniaturizerConfig.config
      newDimension <- ZIO
                        .attempt(
                          BasicImaging.reshapeImage(input, output, referenceSize, None, Some(config.quality))
                        )
                        .mapError(th => MiniaturizeIssue("Couldn't generate miniature photo", th))
                        .tap(_ => ZIO.logInfo("Miniaturize"))
                        .uninterruptible
                        .logError
    } yield newDimension
  }

  private def buildMiniature(original: Original, size: Int) = {
    for {
      input     <- getBestInputOriginalFile(original)
      output    <- getMiniaturePhotoFilePath(original, size)
      _         <- ZIO
                     .attempt(output.getParent.toFile.mkdirs())
                     .mapError(th => MiniaturizeIssue("Couldn't target path", th))
      dimension <- miniaturizePhoto(size, input, output)
                     @@ annotated("inputFile" -> input.toString)
    } yield OriginalMiniature(size = size, dimension = Dimension(Width(dimension.width), Height(dimension.height)))
  }

  // ===================================================================================================================

  /**
   * Generates a collection of miniatures for a given original object based on predefined reference sizes.
   *
   * @param original the original media object for which miniatures will be generated
   * @return a result encapsulating either a CoreIssue in case of an error or a collection of generated miniatures grouped by size
   */
  def miniaturize(original: Original): IO[CoreIssue, OriginalMiniatures] = {
    val logic = for {
      referencesSizes <- MiniaturizerConfig.config.map(_.referenceSizes)
      miniatures      <- ZIO.foreach(referencesSizes) { referenceSize =>
                           buildMiniature(original, referenceSize)
                             @@ annotated("referenceSize" -> referenceSize.toString)
                         }
    } yield OriginalMiniatures(original, miniatures.groupBy(_.size).view.mapValues(_.head).toMap)

    logic
      .logError(s"Miniaturization issue")
      @@ annotated("originalId" -> original.id.asString)
      @@ annotated("originalPath" -> original.mediaPath.toString)
  }

}
