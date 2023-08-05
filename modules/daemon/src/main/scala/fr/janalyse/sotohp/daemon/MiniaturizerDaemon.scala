package fr.janalyse.sotohp.daemon

import zio.*
import zio.config.*
import zio.config.typesafe.*
import zio.config.magnolia.*
import java.io.File
import java.nio.file.Path
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.store.{PhotoStoreService, PhotoStoreIssue}
import net.coobird.thumbnailator.Thumbnails

case class MiniaturizeIssue(message: String, photoId: PhotoId, exception: Throwable)
case class MiniaturizeConfigIssue(message: String, exception: Throwable)

object MiniaturizerDaemon {

  val miniaturizerConfig =
    ZIO
      .config(MiniaturizerConfig.config)
      .mapError(th => MiniaturizeConfigIssue(s"Couldn't configure miniaturizer", th))

  def makeMiniatureFilePath(photo: Photo, size: Int, config: MiniaturizerConfig): IO[MiniaturizeIssue, Path] = {
    val target = s"${config.baseDirectory}/${photo.source.ownerId.uuid}/${photo.id.uuid}/$size.${config.format}"
    ZIO
      .attempt(Path.of(target))
      .mapError(th => MiniaturizeIssue(s"Invalid miniature destination file $target", photo.id, th))
  }

  def buildMiniature(photo: Photo, size: Int, config: MiniaturizerConfig): IO[MiniaturizeIssue, Unit] = {
    for {
      input  <- ZIO
                  .attempt(photo.source.photoPath.toAbsolutePath)
                  .mapError(th => MiniaturizeIssue(s"Couldn't build input path", photo.id, th))
      output <- makeMiniatureFilePath(photo, size, config)
      _      <- ZIO
                  .attempt(output.getParent.toFile.mkdirs())
                  .mapError(th => MiniaturizeIssue(s"Couldn't target path", photo.id, th))
      _      <- ZIO
                  .logInfo(s"Build $size miniature for ${photo.id} - ${photo.source.photoPath}")
                  .when(!output.toFile.exists())
      _      <- ZIO
                  .attemptBlockingIO(
                    Thumbnails
                      .of(input.toFile)
                      .useExifOrientation(true)
                      .size(size, size)
                      .keepAspectRatio(true)
                      .outputQuality(config.quality)
                      .allowOverwrite(false)
                      .toFile(output.toFile)
                  )
                  .mapError(th => MiniaturizeIssue(s"Couldn't generate miniature with reference size $size", photo.id, th))
                  .when(!output.toFile.exists())
    } yield ()
  }

  def buildMiniatures(photo: Photo, config: MiniaturizerConfig): ZIO[PhotoStoreService, PhotoStoreIssue | MiniaturizeIssue, Unit] = {
    ZIO.foreachDiscard(config.referenceSizes)(size => buildMiniature(photo, size, config))
  }

  type MiniaturizeIssues = StreamIOIssue | PhotoFileIssue | PhotoStoreIssue | NotFoundInStore | MiniaturizeIssue | MiniaturizeConfigIssue

  def miniaturize(photosStream: OriginalsStream.PhotoStream): ZIO[PhotoStoreService, MiniaturizeIssues, Unit] = {
    for {
      config <- miniaturizerConfig
      _      <- photosStream.runForeach(original => buildMiniatures(original, config))
    } yield ()
  }
}
