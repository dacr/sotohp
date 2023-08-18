package fr.janalyse.sotohp.daemon

import zio.*
import zio.ZIOAspect.*
import zio.config.*

import java.io.File
import java.nio.file.Path
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.store.{PhotoStoreIssue, PhotoStoreService}
import fr.janalyse.sotohp.config.*
import net.coobird.thumbnailator.Thumbnails
import org.apache.commons.imaging.Imaging

case class MiniaturizeIssue(message: String, exception: Throwable)

object MiniaturizerDaemon extends ProcessorCommon {

  def makeMiniatureFilePath(photo: Photo, size: Int, config: SotohpConfig): Path = {
    val basePath = makePhotoInternalDataPath(photo, config)
    val format   = config.miniaturizer.format
    val target   = s"miniature-$size.$format"
    basePath.resolve(target)
  }

  private def miniaturizePhoto(referenceSize: Int, input: Path, output: Path, config: SotohpConfig) = {
    ZIO
      .attemptBlockingIO(
        Thumbnails
          .of(input.toFile)
          .useExifOrientation(true)
          .size(referenceSize, referenceSize)
          .keepAspectRatio(true)
          .outputQuality(config.miniaturizer.quality)
          .allowOverwrite(false)
          .toFile(output.toFile)
      )
      .tap(_ => ZIO.logInfo(s"Miniaturize $referenceSize"))
      .mapError(th => MiniaturizeIssue(s"Couldn't generate miniature photo $input with reference size $referenceSize", th))
      .tapError(err => ZIO.logWarning(err.toString))
      .uninterruptible
      .ignore // Photo file may have internal issues
  }

  private def buildMiniature(photo: Photo, size: Int, config: SotohpConfig): IO[MiniaturizeIssue, MiniatureSource] = {
    val alreadyKnownMiniatures = photo.miniatures
    for {
      input     <- ZIO
                     .from(photo.normalized.map(_.path)) // faster if normalized photo is already available
                     .orElse(
                       ZIO
                         .attempt(photo.source.original.path.toAbsolutePath)
                         .mapError(th => MiniaturizeIssue(s"Couldn't build input path from original photo", th))
                     )
      output    <- ZIO
                     .attempt(makeMiniatureFilePath(photo, size, config))
                     .mapError(th => MiniaturizeIssue(s"Invalid miniature destination file", th))
      _         <- ZIO
                     .attempt(output.getParent.toFile.mkdirs())
                     .mapError(th => MiniaturizeIssue(s"Couldn't target path", th))
      _         <- miniaturizePhoto(size, input, output, config)
                     .when(!output.toFile.exists())
      dimension <- ZIO
                     .from(alreadyKnownMiniatures.flatMap(m => m.sources.find(_.size == size)).map(_.dimension))
                     .orElse(
                       ZIO
                         .attempt(Imaging.getImageSize(output.toFile))
                         .map(jdim => Dimension2D(width = jdim.getWidth.toInt, height = jdim.getHeight.toInt))
                     )
                     .mapError(th => MiniaturizeIssue(s"Couldn't get normalized photo size", th))
    } yield MiniatureSource(path = output, dimension = dimension)
  } @@ annotated("size" -> size.toString)

  private def upsertMiniaturesRecordIfNeeded(photo: Photo, miniaturesSources: List[MiniatureSource]) = for {
    currentDateTime       <- Clock.currentDateTime
    alreadyKnownMiniatures = photo.miniatures
    updatedMiniatures      = Miniatures(sources = miniaturesSources, lastUpdated = currentDateTime)
    upsertNeeded           = !alreadyKnownMiniatures.map(_.sources).contains(miniaturesSources)
    _                     <- PhotoStoreService
                               .photoMiniaturesUpsert(photo.source.photoId, updatedMiniatures)
                               .when(upsertNeeded)
    miniatures             = if (upsertNeeded) updatedMiniatures else alreadyKnownMiniatures.get
  } yield miniatures

  // ===================================================================================================================

  /** generates photo miniatures
    * @param photo
    * @return
    *   photo with updated miniatures field if some changes have occurred
    */
  def miniaturize(photo: Photo): ZIO[PhotoStoreService, PhotoStoreIssue | MiniaturizeIssue | SotohpConfigIssue, Photo] = {
    val logic = for {
      config            <- sotophConfig
      miniaturesSources <- ZIO.foreach(config.miniaturizer.referenceSizes)(size => buildMiniature(photo, size, config))
      miniatures        <- upsertMiniaturesRecordIfNeeded(photo, miniaturesSources)
    } yield photo.copy(miniatures = Some(miniatures))

    logic
      .logError(s"Miniaturization issue")
      .option
      .someOrElse(photo)
      @@ annotated("photoId" -> photo.source.photoId.toString())
      @@ annotated("photoPath" -> photo.source.original.path.toString)
  }

}
