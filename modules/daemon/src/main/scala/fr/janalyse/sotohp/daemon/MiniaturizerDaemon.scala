package fr.janalyse.sotohp.daemon

import zio.*
import zio.ZIOAspect.*
import zio.config.*
import java.io.File
import java.nio.file.Path
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.store.{PhotoStoreService, PhotoStoreIssue}

import net.coobird.thumbnailator.Thumbnails
import org.apache.commons.imaging.Imaging

case class MiniaturizeIssue(message: String, exception: Throwable)
case class MiniaturizeConfigIssue(message: String, exception: Throwable)

object MiniaturizerDaemon {

  private val miniaturizerConfig =
    ZIO
      .config(MiniaturizerConfig.config)
      .mapError(th => MiniaturizeConfigIssue(s"Couldn't configure miniaturizer", th))

  private def makeMiniatureFilePath(photo: Photo, size: Int, config: MiniaturizerConfig): IO[MiniaturizeIssue, Path] = {
    val target = s"${config.baseDirectory}/${photo.source.original.ownerId}/${photo.source.photoId}/$size.${config.format}"
    ZIO
      .attempt(Path.of(target))
      .mapError(th => MiniaturizeIssue(s"Invalid miniature destination file $target", th))
  }

  private def miniaturizePhoto(referenceSize: Int, input: Path, output: Path, config: MiniaturizerConfig) = {
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
      .tap(_ => ZIO.logInfo(s"Miniaturize $referenceSize"))
      .mapError(th => MiniaturizeIssue(s"Couldn't generate miniature photo $input with reference size $referenceSize", th))
      .tapError(err => ZIO.logWarning(err.toString))
      .uninterruptible
      .ignore // Photo file may have internal issues
  }

  private def buildMiniature(photo: Photo, size: Int, config: MiniaturizerConfig): IO[MiniaturizeIssue, MiniatureSource] = {
    val alreadyKnownMiniatures = photo.miniatures
    for {
      input     <- ZIO
                     .from(photo.normalized.map(_.path)) // faster if normalized photo is already available
                     .orElse(
                       ZIO
                         .attempt(photo.source.original.path.toAbsolutePath)
                         .mapError(th => MiniaturizeIssue(s"Couldn't build input path from original photo", th))
                     )
      output    <- makeMiniatureFilePath(photo, size, config)
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
  }

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
  def miniaturize(photo: Photo): ZIO[PhotoStoreService, PhotoStoreIssue | MiniaturizeIssue | MiniaturizeConfigIssue, Photo] = {
    val logic = for {
      config            <- miniaturizerConfig
      miniaturesSources <- ZIO.foreach(config.referenceSizes)(size => buildMiniature(photo, size, config))
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
