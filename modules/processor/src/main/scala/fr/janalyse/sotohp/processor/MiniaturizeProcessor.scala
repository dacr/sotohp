package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.config.*
import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.store.PhotoStoreService
import org.apache.commons.imaging.Imaging
import zio.*
import zio.ZIOAspect.*

import java.nio.file.Path

case class MiniaturizeIssue(message: String, exception: Throwable) extends Exception(message, exception)

object MiniaturizeProcessor extends Processor {

  private def miniaturizePhoto(referenceSize: Int, input: Path, output: Path) = {
    for {
      config <- SotohpConfig.zioConfig
      _      <- ZIO
                  .attempt(
                    //        Thumbnails
                    //          .of(input.toFile)
                    //          .useExifOrientation(true)
                    //          .size(referenceSize, referenceSize)
                    //          .keepAspectRatio(true)
                    //          .outputQuality(config.miniaturizer.quality)
                    //          .allowOverwrite(false)
                    //          .toFile(output.toFile)
                    BasicImaging.reshapeImage(input, output, referenceSize, None, Some(config.normalizer.quality))
                  )
                  .mapError(th => MiniaturizeIssue("Couldn't generate miniature photo", th))
                  .tap(_ => ZIO.logInfo("Miniaturize"))
                  .uninterruptible
                  .ignoreLogged // Photo file may have internal issues
    } yield ()
  }

  private def buildMiniature(photo: Photo, size: Int) = {
    val alreadyKnownMiniatures = photo.miniatures
    for {
      input     <- getBestInputPhotoFile(photo)
      output    <- PhotoOperations.getMiniaturePhotoFilePath(photo.source, size)
      _         <- ZIO
                     .attempt(output.getParent.toFile.mkdirs())
                     .mapError(th => MiniaturizeIssue("Couldn't target path", th))
      _         <- miniaturizePhoto(size, input, output)
                     .when(!output.toFile.exists())
                     @@ annotated("inputFile" -> input.toString)
      dimension <- ZIO
                     .from(alreadyKnownMiniatures.flatMap(m => m.sources.find(_.size == size)).map(_.dimension)) // faster
                     .orElse(
                       ZIO
                         .attempt(Imaging.getImageSize(output.toFile))                                           // slower
                         .map(jdim => Dimension2D(width = jdim.getWidth.toInt, height = jdim.getHeight.toInt))
                     )
                     .mapError(th => MiniaturizeIssue("Couldn't get normalized photo size", th))
    } yield MiniatureSource(size = size, dimension = dimension)
  }

  private def upsertMiniaturesRecordIfNeeded(photo: Photo, miniaturesSources: List[MiniatureSource]) = {
    val alreadyKnownMiniatures = photo.miniatures
    val updatedMiniatures      = Miniatures(sources = miniaturesSources)
    val upsertNeeded           = !alreadyKnownMiniatures.contains(updatedMiniatures)
    for {
      _         <- PhotoStoreService
                     .photoMiniaturesUpsert(photo.source.photoId, updatedMiniatures)
                     .when(upsertNeeded)
      miniatures = if (upsertNeeded) updatedMiniatures else alreadyKnownMiniatures.get
    } yield miniatures
  }

  // ===================================================================================================================

  /** generates photo miniatures
    * @param photo
    * @return
    *   photo with updated miniatures field if some changes have occurred
    */
  def miniaturize(photo: Photo): RIO[PhotoStoreService, Photo] = {
    val logic = for {
      referencesSizes   <- SotohpConfig.zioConfig.map(_.miniaturizer.referenceSizes)
      miniaturesSources <- ZIO.foreach(referencesSizes) { referenceSize =>
                             buildMiniature(photo, referenceSize)
                               @@ annotated("referenceSize" -> referenceSize.toString)
                           }
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
