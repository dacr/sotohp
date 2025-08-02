package fr.janalyse.sotohp.processor

import zio.*
import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.Original
import fr.janalyse.sotohp.core.{ConfigInvalid, CoreIssue}
import fr.janalyse.sotohp.processor.config.{InternalDataConfig, MiniaturizerConfig, NormalizerConfig}

import java.awt.image.BufferedImage
import java.nio.file.Path

case class ProcessorIssue(message: String, exception: Throwable) extends Exception(message, exception) with CoreIssue

trait Processor {

  def getPhotoArtifactsCachePath(original: Original): IO[ConfigInvalid, Path] = {
    for {
      dataDir    <- InternalDataConfig.config.map(_.baseDirectory)
      storeDir    = original.store.id.asString
      originalDir = original.id.asString
      path        = Path.of(dataDir, storeDir, "originals-cache", originalDir)
    } yield path
  }

  def getNormalizedPhotoFilePath(original: Original): IO[ConfigInvalid, Path] = {
    for {
      format   <- NormalizerConfig.config.map(_.format)
      basePath <- getPhotoArtifactsCachePath(original)
      target    = s"normalized.$format"
      path      = basePath.resolve(target)
    } yield path
  }

  def getMiniaturePhotoFilePath(original: Original, size: Int): IO[ConfigInvalid, Path] = {
    for {
      format <- MiniaturizerConfig.config.map(_.format)
      basePath <- getPhotoArtifactsCachePath(original)
      target = s"miniature-$size.$format"
      path = basePath.resolve(target)
    } yield path
  }

  def getBestInputPhotoFile(original: Original): IO[CoreIssue, Path] = for {
    normalizedInput <- getNormalizedPhotoFilePath(original) // faster because lighter
    input           <- if (normalizedInput.toFile.exists()) ZIO.succeed(normalizedInput)
                       else
                         ZIO
                           .attempt(original.mediaPath.path.toAbsolutePath) // slower because original
                           .mapError(th => ProcessorIssue(s"Couldn't build input path for original photo", th))
  } yield input

  def loadBestInputPhoto(original: Original): IO[CoreIssue, BufferedImage] = {
    for {
      imagePath     <- getBestInputPhotoFile(original)
      bufferedImage <- ZIO
                         .attempt(BasicImaging.load(imagePath))
                         .mapError(th => ProcessorIssue(s"Couldn't load image $imagePath", th))
    } yield bufferedImage
  }

}
