package fr.janalyse.sotohp.search

import zio.*
import fr.janalyse.sotohp.model.{Media, MediaAccessKey, State}
import fr.janalyse.sotohp.search.model.MediaBag
import fr.janalyse.sotohp.search.sao.SaoMedia

import java.time.OffsetDateTime

case class SearchServiceIssue(message: String, throwables: Seq[Throwable])

trait SearchService {
  def publish(medias: Chunk[MediaBag]): IO[SearchServiceIssue, Chunk[MediaBag]]
  def unpublish(media: Media): IO[SearchServiceIssue, Unit]
}

object SearchService {
  def publish(medias: Chunk[MediaBag]): ZIO[SearchService, SearchServiceIssue, Chunk[MediaBag]] = ZIO.serviceWithZIO(_.publish(medias))
  def unpublish(media: Media): ZIO[SearchService, SearchServiceIssue, Unit]                     = ZIO.serviceWithZIO(_.unpublish(media))

  val live = ZLayer.fromZIO(
    for {
      config            <- SearchServiceConfig.config
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
  override def publish(bags: Chunk[MediaBag]): IO[SearchServiceIssue, Chunk[MediaBag]] = {
    elasticOperations
      .upsert(config.indexPrefix, bags.map(bag => SaoMedia.fromMedia(bag)))(_.timestamp, _.id)
      .when(config.enabled)
      .logError("couldn't upsert some or all photos from the given chunk of photos")
      .mapError(errs => SearchServiceIssue(s"Couldn't upsert", errs))
      .as(bags)
  }

  override def unpublish(media: Media): IO[SearchServiceIssue, Unit] = {
    elasticOperations
      .delete(config.indexPrefix, media.accessKey.asString, media.timestamp)
      .when(config.enabled)
      .map(_ => ())
      .logError(s"Couldn't unpublish ${media.accessKey.asString} ${media.timestamp}")
      .mapError(err => SearchServiceIssue(s"Couldn't unpublish ${media.accessKey.asString} ${media.timestamp}", err :: Nil))
  }
}
