package fr.janalyse.sotohp.processor

import zio.*
import fr.janalyse.sotohp.config.*
import fr.janalyse.sotohp.model.Photo
import java.nio.file.Path

case class ProcessorIssue(message: String, exception: Throwable)

trait Processor {

  val sotophConfig =
    ZIO
      .config(SotohpConfig.config)
      .mapError(th => SotohpConfigIssue(s"Couldn't get configuration", th))

  def makePhotoInternalDataPath(photo: Photo, config: SotohpConfig): Path = {
    val dataDir = config.internalData.baseDirectory
    val ownerId = photo.source.original.ownerId
    val photoId = photo.source.photoId
    Path.of(dataDir, "artifacts", ownerId.toString, photoId.toString)
  }

  def internalDataRelativize(output: Path, config: SotohpConfig): Path = {
    val dataDirPath = Path.of(config.internalData.baseDirectory)
    dataDirPath.relativize(output)
  }

  def getBestInputPhotoFile(photo: Photo): IO[ProcessorIssue | SotohpConfigIssue, Path] = for {
    config          <- sotophConfig
    normalizedInput <- ZIO
                         .attempt(NormalizeProcessor.makeNormalizedFilePath(photo, config)) // faster because lighter
                         .mapError(th => ProcessorIssue(s"Couldn't build input path for normalized photo", th))
    input           <- if (normalizedInput.toFile.exists()) ZIO.succeed(normalizedInput)
                       else
                         ZIO
                           .attempt(photo.source.original.path.toAbsolutePath) // slower because original
                           .mapError(th => ProcessorIssue(s"Couldn't build input path for original photo", th))
  } yield input

}
