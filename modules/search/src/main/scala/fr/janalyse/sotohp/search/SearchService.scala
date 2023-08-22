package fr.janalyse.sotohp.search

import zio.*
import fr.janalyse.sotohp.model.Photo
import fr.janalyse.sotohp.search.dao.SaoPhoto

case class SearchServiceIssue(message: String, throwables: Seq[Throwable])

trait SearchService {
  def publish(photos: Chunk[Photo]): IO[SearchServiceIssue, Chunk[Photo]]
}

object SearchService {
  def publish(photos: Chunk[Photo]): ZIO[SearchService, SearchServiceIssue, Chunk[Photo]] = ZIO.serviceWithZIO(_.publish(photos))

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
      .tapError(err => ZIO.logError(err.toString))
      .mapError(errs => SearchServiceIssue(s"Couldn't upsert", errs))
      .map(_ => photos)
  }
}
