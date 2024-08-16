package fr.janalyse.sotohp.processor

import zio.*
import fr.janalyse.sotohp.config.*
import fr.janalyse.sotohp.core.PhotoOperations
import fr.janalyse.sotohp.model.Photo

import java.awt.image.BufferedImage
import java.nio.file.Path

case class ProcessorIssue(message: String, exception: Throwable) extends Exception(message, exception)

trait Processor {

  val sotophConfig =
    ZIO
      .config(SotohpConfig.config)
      .mapError(th => SotohpConfigIssue(s"Couldn't get configuration", th))

  def getBestInputPhotoFile(photo: Photo): IO[ProcessorIssue | SotohpConfigIssue, Path] = for {
    normalizedInput <- PhotoOperations.getNormalizedPhotoFilePath(photo.source) // faster because lighter
    input           <- if (normalizedInput.toFile.exists()) ZIO.succeed(normalizedInput)
                       else
                         ZIO
                           .attempt(photo.source.original.path.toAbsolutePath) // slower because original
                           .mapError(th => ProcessorIssue(s"Couldn't build input path for original photo", th))
  } yield input

  def loadBestInputPhoto(photo: Photo): IO[ProcessorIssue | SotohpConfigIssue, BufferedImage] = {
    for {
      imagePath     <- getBestInputPhotoFile(photo)
      bufferedImage <- ZIO
                         .attempt(BasicImaging.load(imagePath))
                         .mapError(th => ProcessorIssue(s"Couldn't load image $imagePath", th))
    } yield bufferedImage
  }
}
