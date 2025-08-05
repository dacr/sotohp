package fr.janalyse.sotohp.processor

import zio.*
import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.Original
import fr.janalyse.sotohp.core.{ConfigInvalid, CoreIssue}
import fr.janalyse.sotohp.processor.config.{CacheDataConfig, MiniaturizerConfig, NormalizerConfig}
import fr.janalyse.sotohp.processor.model.FaceId

import java.awt.image.BufferedImage
import java.nio.file.Path

case class ProcessorIssue(message: String, exception: Throwable) extends Exception(message, exception) with CoreIssue

trait Processor extends AutoCloseable {

  def getProcessorDataOriginalsCachePath(original: Original): IO[ConfigInvalid, Path] = {
    for {
      dataDir    <- CacheDataConfig.config.map(_.directory)
      storeDir    = original.store.id.asString
      originalDir = original.id.asString
      path        = Path.of(dataDir, storeDir, "originals-cache", originalDir)
    } yield path
  }

  def getProcessorDataFacesCachePath(original: Original): IO[ConfigInvalid, Path] = {
    for {
      dataDir    <- CacheDataConfig.config.map(_.directory)
      storeDir    = original.store.id.asString
      originalDir = original.id.asString
      path        = Path.of(dataDir, storeDir, "faces-cache")
    } yield path
  }

  def getOriginalNormalizedFilePath(original: Original): IO[ConfigInvalid, Path] = {
    for {
      format   <- NormalizerConfig.config.map(_.format)
      basePath <- getProcessorDataOriginalsCachePath(original)
      target    = s"normalized.$format"
      path      = basePath.resolve(target)
    } yield path
  }

  def getOriginalFaceFilePath(original: Original, faceId: FaceId): IO[ConfigInvalid, Path] = {
    for {
      format   <- NormalizerConfig.config.map(_.format)
      basePath <- getProcessorDataFacesCachePath(original)
      target    = s"$faceId.$format"
      path      = basePath.resolve(target)
    } yield path
  }

  def getOriginalMiniatureFilePath(original: Original, size: Int): IO[ConfigInvalid, Path] = {
    for {
      format   <- MiniaturizerConfig.config.map(_.format)
      basePath <- getProcessorDataOriginalsCachePath(original)
      target    = s"miniature-$size.$format"
      path      = basePath.resolve(target)
    } yield path
  }

  def getOriginalMiniaturesFilePaths(original: Original): IO[ConfigInvalid, Map[Int, Path]] = {
    for {
      config   <- MiniaturizerConfig.config
      basePath <- getProcessorDataOriginalsCachePath(original)
      format    = config.format
      paths     = config.referenceSizes.map(size => size -> basePath.resolve(s"miniature-$size.$format"))
    } yield paths.toMap
  }

  def getOriginalBestInputFileForProcessors(original: Original): IO[CoreIssue, Path] = for {
    normalizedInput <- getOriginalNormalizedFilePath(original) // faster because lighter
    input           <- if (normalizedInput.toFile.exists()) ZIO.succeed(normalizedInput)
                       else
                         ZIO
                           .attempt(original.mediaPath.path.toAbsolutePath) // slower because original
                           .mapError(th => ProcessorIssue(s"Couldn't build input path for original photo", th))
  } yield input

  def loadOriginalBestInputFileForProcessors(original: Original): IO[CoreIssue, BufferedImage] = {
    for {
      imagePath     <- getOriginalBestInputFileForProcessors(original)
      bufferedImage <- ZIO
                         .attempt(BasicImaging.load(imagePath))
                         .mapError(th => ProcessorIssue(s"Couldn't load image $imagePath", th))
    } yield bufferedImage
  }

}
