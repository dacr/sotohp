package fr.janalyse.sotohp.daemon

import fr.janalyse.sotohp.config.*
import zio.*
import zio.ZIOAspect.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.store.{PhotoStoreIssue, PhotoStoreService}

import java.nio.file.Path
import org.apache.commons.imaging.Imaging
import net.coobird.thumbnailator.Thumbnails

case class NormalizeIssue(message: String, exception: Throwable)

object NormalizerDaemon extends ProcessorCommon {

  def makeNormalizedFilePath(photo: Photo, config: SotohpConfig): Path = {
    val basePath = makePhotoInternalDataPath(photo, config)
    val format   = config.miniaturizer.format
    val target   = s"normalized.$format"
    basePath.resolve(target)
  }

  private def resizePhoto(input: Path, output: Path, config: SotohpConfig) = {
    import config.normalizer.referenceSize
    ZIO
      .attemptBlockingIO(
        Thumbnails
          .of(input.toFile)
          .useExifOrientation(true)
          .size(referenceSize, referenceSize)
          .keepAspectRatio(true)
          .outputQuality(config.normalizer.quality)
          .allowOverwrite(false)
          .toFile(output.toFile)
      )
      .tap(_ => ZIO.logInfo(s"Normalize"))
      .mapError(th => NormalizeIssue(s"Couldn't generate normalized photo $input with reference size $referenceSize", th))
      .tapError(err => ZIO.logWarning(err.toString))
      .uninterruptible
      .ignore // Photo file may have internal issues
  }

  private def upsertNormalizedPhotoIfNeeded(photo: Photo, output: Path, config: SotohpConfig) = {
    val currentNormalized = photo.normalized
    for {
      dimension             <- ZIO
                                 .from(photo.normalized.map(_.dimension))
                                 .orElse(
                                   ZIO
                                     .attempt(Imaging.getImageSize(output.toFile))
                                     .map(jdim => Dimension2D(width = jdim.getWidth.toInt, height = jdim.getHeight.toInt))
                                     .mapError(th => NormalizeIssue(s"Couldn't get normalized photo size", th))
                                 )
      currentDateTime       <- Clock.currentDateTime
      updatedNormalizedPhoto = NormalizedPhoto(
                                 path = output,
                                 dimension = dimension,
                                 lastUpdated = currentDateTime
                               )
      upsertNeeded           = currentNormalized.isEmpty
                                 || currentNormalized.get.path != updatedNormalizedPhoto.path
                                 || currentNormalized.get.dimension != updatedNormalizedPhoto.dimension
      _                     <- PhotoStoreService
                                 .photoNormalizedUpsert(photo.source.photoId, updatedNormalizedPhoto)
                                 .when(upsertNeeded)
      normalizedPhoto        = if (upsertNeeded) updatedNormalizedPhoto else photo.normalized.get
    } yield normalizedPhoto
  }

  private def makeOutputDirectories(output: Path) = {
    ZIO
      .attempt(output.getParent.toFile.mkdirs())
      .mapError(th => NormalizeIssue(s"Couldn't target path", th))
  }

  /** generates normalized photo
    *
    * @param photo
    * @return
    *   photo with updated normalized field if some changes have occurred
    */
  def normalize(photo: Photo): ZIO[PhotoStoreService, NormalizeIssue | PhotoStoreIssue | SotohpConfigIssue, Photo] = {
    val logic = for {
      config          <- sotophConfig
      input           <- ZIO
                           .attempt(photo.source.original.path.toAbsolutePath)
                           .mapError(th => NormalizeIssue(s"Couldn't build input path", th))
      output          <- ZIO
                           .attempt(makeNormalizedFilePath(photo, config))
                           .mapError(th => NormalizeIssue(s"Couldn't build destination path target", th))
      _               <- makeOutputDirectories(output)
      _               <- resizePhoto(input, output, config)
                           .when(!output.toFile.exists())
      normalizedPhoto <- upsertNormalizedPhotoIfNeeded(photo, output, config)
                           .logError("Couldn't upsert normalized photo in datastore")
    } yield photo.copy(normalized = Some(normalizedPhoto))

    logic
      .logError(s"Normalization issue")
      .option
      .someOrElse(photo)
      @@ annotated("photoId" -> photo.source.photoId.toString())
      @@ annotated("photoPath" -> photo.source.original.path.toString)

  }

}
