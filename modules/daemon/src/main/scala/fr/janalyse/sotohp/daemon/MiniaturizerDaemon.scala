package fr.janalyse.sotohp.daemon

import zio.*
import zio.config.*
import java.io.File
import java.nio.file.Path
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.store.{PhotoStoreService, PhotoStoreIssue}

import net.coobird.thumbnailator.Thumbnails
import org.apache.commons.imaging.Imaging

case class MiniaturizeIssue(message: String, photoId: PhotoId, exception: Throwable)
case class MiniaturizeConfigIssue(message: String, exception: Throwable)

object MiniaturizerDaemon {

  val miniaturizerConfig =
    ZIO
      .config(MiniaturizerConfig.config)
      .mapError(th => MiniaturizeConfigIssue(s"Couldn't configure miniaturizer", th))

  def makeMiniatureFilePath(photo: Photo, size: Int, config: MiniaturizerConfig): IO[MiniaturizeIssue, Path] = {
    val target = s"${config.baseDirectory}/${photo.source.original.ownerId}/${photo.source.photoId}/$size.${config.format}"
    ZIO
      .attempt(Path.of(target))
      .mapError(th => MiniaturizeIssue(s"Invalid miniature destination file $target", photo.source.photoId, th))
  }

  private def miniaturizePhoto(photoId: PhotoId, referenceSize: Int, input: Path, output: Path, config: MiniaturizerConfig) = {
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
      .tap(_ => ZIO.logInfo(s"Miniaturize $input - ${photoId.id} - $referenceSize"))
      .mapError(th => MiniaturizeIssue(s"Couldn't generate miniature photo $input with reference size $referenceSize", photoId, th))
      .tapError(err => ZIO.logWarning(err.toString))
      .uninterruptible
  }

  def buildMiniature(photo: Photo, size: Int, config: MiniaturizerConfig, alreadyKnownMiniatures: Option[Miniatures]): IO[MiniaturizeIssue, MiniatureSource] = {
    for {
      input     <- ZIO
                     .attempt(photo.source.original.path.toAbsolutePath)
                     .mapError(th => MiniaturizeIssue(s"Couldn't build input path", photo.source.photoId, th))
      output    <- makeMiniatureFilePath(photo, size, config)
      _         <- ZIO
                     .attempt(output.getParent.toFile.mkdirs())
                     .mapError(th => MiniaturizeIssue(s"Couldn't target path", photo.source.photoId, th))
      _         <- miniaturizePhoto(photo.source.photoId, size, input, output, config)
                     .when(!output.toFile.exists())
      dimension <- ZIO
                     .from(alreadyKnownMiniatures.flatMap(m => m.sources.find(s => math.max(s.dimension.width, s.dimension.height) == size)).map(_.dimension))
                     .orElse(
                       ZIO
                         .attempt(Imaging.getImageSize(output.toFile))
                         .map(jdim => Dimension2D(width = jdim.getWidth.toInt, height = jdim.getHeight.toInt))
                     )
                     .mapError(th => MiniaturizeIssue(s"Couldn't get normalized photo size", photo.source.photoId, th))
    } yield MiniatureSource(path = output, dimension = dimension)
  }

  def upsertMiniaturesRecord(photo: Photo, miniaturesSources: List[MiniatureSource]) = for {
    currentDateTime <- Clock.currentDateTime
    miniatures       = Miniatures(sources = miniaturesSources, lastUpdated = currentDateTime)
    _               <- PhotoStoreService
                         .photoMiniaturesUpsert(photo.source.photoId, miniatures)
  } yield ()

  def buildMiniatures(photo: Photo, config: MiniaturizerConfig): ZIO[PhotoStoreService, PhotoStoreIssue | MiniaturizeIssue, Unit] = {
    for {
      alreadyKnownMiniatures <- PhotoStoreService.photoMiniaturesGet(photo.source.photoId)
      miniaturesSources      <- ZIO.foreach(config.referenceSizes)(size => buildMiniature(photo, size, config, alreadyKnownMiniatures))
      _                      <- upsertMiniaturesRecord(photo, miniaturesSources)
                                  .when(alreadyKnownMiniatures.map(_.sources).contains(miniaturesSources))
    } yield ()
  }

  type MiniaturizeIssues = StreamIOIssue | PhotoFileIssue | PhotoStoreIssue | NotFoundInStore | MiniaturizeIssue | MiniaturizeConfigIssue

  def miniaturize(photosStream: OriginalsStream.PhotoStream): ZIO[PhotoStoreService, MiniaturizeIssues, Unit] = {
    for {
      config <- miniaturizerConfig
      _      <- photosStream
                  .mapZIOParUnordered(4)(original => buildMiniatures(original, config).ignore)
                  .runDrain
    } yield ()
  }
}
