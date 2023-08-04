package fr.janalyse.sotohp.daemon

import zio.ZIO
import java.io.File
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.core.{OriginalsStream, PhotoOperations}
import fr.janalyse.sotohp.store.{PhotoStoreService, PhotoStoreIssue}
import OriginalsStream.StreamIOIssue
import PhotoOperations.{PhotoFileIssue, NotFoundInStore}
import net.coobird.thumbnailator.Thumbnails

object MiniaturizerDaemon {

  case class MiniaturizeIssue(message: String, photoId: PhotoId, exception: Throwable)

  def miniaturize(photo: Photo): ZIO[PhotoStoreService, PhotoStoreIssue | MiniaturizeIssue, Unit] = {
    val processing = ZIO.attemptBlockingIO {
      val input = photo.source.photoPath.toFile
      for (size <- List(64, 128, 256)) {
        val output = File(s"$size/${photo.id.uuid}")
        Thumbnails
          .of(input)
          .rotate(90)
          .size(size, size)
          .outputQuality(0.8)
          .keepAspectRatio(true)
          .toFile(output);
      }
    }
    processing.unit
      .mapError(exception => MiniaturizeIssue("Couldn't generate miniature", photo.id, exception))
  }

  type MiniaturizeIssues = StreamIOIssue | PhotoFileIssue | PhotoStoreIssue | NotFoundInStore
  def miniaturize(searchRoots: List[PhotoSearchRoot]): ZIO[PhotoStoreService, MiniaturizeIssues, Unit] = {
    OriginalsStream
      .photoStream(searchRoots)
      .runForeach(original => miniaturize(original))
  }
}
