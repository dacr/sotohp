package fr.janalyse.sotohp.daemon

import zio.*
import zio.config.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.store.{PhotoStoreService, PhotoStoreIssue}

import java.nio.file.Path
import org.apache.commons.imaging.Imaging
import net.coobird.thumbnailator.Thumbnails

case class NormalizeIssue(message: String, photoId: PhotoId, exception: Throwable)
case class NormalizeConfigIssue(message: String, exception: Throwable)

object NormalizerDaemon {
  val normalizerConfig =
    ZIO
      .config(NormalizerConfig.config)
      .mapError(th => NormalizeConfigIssue(s"Couldn't configure normalizer", th))

  def makeNormalizedFilePath(photo: Photo, config: NormalizerConfig): IO[NormalizeIssue, Path] = {
    val target = s"${config.baseDirectory}/${photo.source.ownerId.uuid}/${photo.id.uuid}.${config.format}"
    ZIO
      .attempt(Path.of(target))
      .mapError(th => NormalizeIssue(s"Invalid normalized destination file $target", photo.id, th))
  }

  private def resizePhoto(photoId: PhotoId, input: Path, output: Path, config: NormalizerConfig) = {
    import config.referenceSize
    ZIO
      .attemptBlockingIO(
        Thumbnails
          .of(input.toFile)
          .useExifOrientation(true)
          .size(referenceSize, referenceSize)
          .keepAspectRatio(true)
          .outputQuality(config.quality)
          .allowOverwrite(false)
          .toFile(output.toFile)
      )
      .tap(_ => ZIO.logInfo(s"Normalize $input - ${photoId.uuid}"))
      .mapError(th => NormalizeIssue(s"Couldn't generate normalized photo with reference size ${config.referenceSize}", photoId, th))
      .uninterruptible
      .when(!output.toFile.exists())
  }

  def buildNormalizedPhoto(photo: Photo, config: NormalizerConfig): ZIO[PhotoStoreService, NormalizeIssue | PhotoStoreIssue, NormalizedPhoto] = {
    for {
      input           <- ZIO
                           .attempt(photo.source.photoPath.toAbsolutePath)
                           .mapError(th => NormalizeIssue(s"Couldn't build input path", photo.id, th))
      output          <- makeNormalizedFilePath(photo, config)
      _               <- ZIO
                           .attempt(output.getParent.toFile.mkdirs())
                           .mapError(th => NormalizeIssue(s"Couldn't target path", photo.id, th))
      _               <- resizePhoto(photo.id, input, output, config)
      dimension       <- ZIO
                           .attempt(Imaging.getImageSize(output.toFile))
                           .mapError(th => NormalizeIssue(s"Couldn't get normalized photo size", photo.id, th))
      currentDateTime <- Clock.currentDateTime
      normalizedPhoto  = NormalizedPhoto(
                           path = output,
                           dimension = Dimension2D(width = dimension.getWidth.toInt, height = dimension.getHeight.toInt),
                           lastUpdated = currentDateTime
                         )
      _               <- PhotoStoreService
                           .photoNormalizedUpsert(photo.id, normalizedPhoto)
                           .whenZIO(PhotoStoreService.photoNormalizedContains(photo.id).map(!_))
    } yield normalizedPhoto
  }

  type NormalizeIssues = StreamIOIssue | PhotoFileIssue | PhotoStoreIssue | NotFoundInStore | NormalizeIssue | NormalizeConfigIssue

  def normalize(photosStream: OriginalsStream.PhotoStream): ZIO[PhotoStoreService, NormalizeIssues, Unit] = {
    for {
      config <- normalizerConfig
      _      <- photosStream
                  .mapZIOParUnordered(4)(original => buildNormalizedPhoto(original, config))
                  .runDrain
    } yield ()
  }
}
