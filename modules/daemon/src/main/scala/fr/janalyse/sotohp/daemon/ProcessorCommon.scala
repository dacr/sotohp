package fr.janalyse.sotohp.daemon

import zio.*
import fr.janalyse.sotohp.config.*
import fr.janalyse.sotohp.model.Photo
import java.nio.file.Path

trait ProcessorCommon {

  def makePhotoInternalDataPath(photo: Photo, config: SotohpConfig): Path = {
    val dataDir = config.internalData.baseDirectory
    val ownerId = photo.source.original.ownerId
    val photoId = photo.source.photoId
    Path.of(dataDir, ownerId.toString, photoId.toString)
  }

  def internalDataRelativize(output: Path, config: SotohpConfig): Path = {
    val dataDirPath = Path.of(config.internalData.baseDirectory)
    dataDirPath.relativize(output)
  }

  val sotophConfig =
    ZIO
      .config(SotohpConfig.config)
      .mapError(th => SotohpConfigIssue(s"Couldn't get configuration", th))

}
