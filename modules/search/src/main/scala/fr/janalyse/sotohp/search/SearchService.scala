package fr.janalyse.sotohp.search

import zio.*
import fr.janalyse.sotohp.model.{Photo, PhotoId}
import fr.janalyse.sotohp.search.dao.SaoPhoto

import java.time.OffsetDateTime

case class SearchServiceIssue(message: String, throwables: Seq[Throwable])

trait SearchService {
  def publish(photos: Chunk[Photo]): IO[SearchServiceIssue, Chunk[Photo]]
  def unpublish(photoId: PhotoId, photoTimestamp: OffsetDateTime): IO[SearchServiceIssue, Unit]
}

object SearchService {
  def publish(photos: Chunk[Photo]): ZIO[SearchService, SearchServiceIssue, Chunk[Photo]]                       = ZIO.serviceWithZIO(_.publish(photos))
  def unpublish(photoId: PhotoId, photoTimestamp: OffsetDateTime): ZIO[SearchService, SearchServiceIssue, Unit] = ZIO.serviceWithZIO(_.unpublish(photoId, photoTimestamp))

  val live = ZLayer.fromZIO(
    for {
      config            <- ZIO
                             .config(SearchServiceConfig.config)
                             .tapError(err => ZIO.logError(err.toString))
                             .mapError(th => SearchServiceIssue(s"Couldn't get search engine configuration", Nil))
      _                 <- ZIO.logInfo(config.toString)
      elasticOperations <- ZIO
                             .attempt(ElasticOperations(config))
                             .tapError(err => ZIO.logError(err.toString))
                             .mapError(th => SearchServiceIssue(s"Couldn't initialize search engine configuration", Nil))
      _                 <- ZIO.logInfo("SearchEngine layer ready")
    } yield SearchServiceLive(elasticOperations, config)
  )
}

class SearchServiceLive(elasticOperations: ElasticOperations, config: SearchServiceConfig) extends SearchService {
  def publish(photos: Chunk[Photo]): IO[SearchServiceIssue, Chunk[Photo]] = {
    elasticOperations
      .upsert(config.indexPrefix, photos.map(p => SaoPhoto.fromPhoto(p)))(_.timestamp, _.id)
      .when(config.enabled)
      .logError("couldń't upsert some or all photos from the given chunk of photos")
      .mapError(errs => SearchServiceIssue(s"Couldn't upsert", errs))
      .map(_ => photos)
  }

  override def unpublish(photoId: PhotoId, photoTimestamp: OffsetDateTime): IO[SearchServiceIssue, Unit] = {
    elasticOperations
      .delete(config.indexPrefix, photoId.id.toString, photoTimestamp)
      .when(config.enabled)
      .map(_ => ())
      .logError(s"Couldn't unpublish $photoId $photoTimestamp")
      .mapError(err => SearchServiceIssue(s"Couldn't unpublish $photoId $photoTimestamp", err :: Nil))
  }
}
