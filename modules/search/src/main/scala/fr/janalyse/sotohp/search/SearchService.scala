package fr.janalyse.sotohp.search

import zio.*
import fr.janalyse.sotohp.media.model.{State, Media, MediaAccessKey}
import fr.janalyse.sotohp.search.sao.SaoMedia

import java.time.OffsetDateTime

case class SearchServiceIssue(message: String, throwables: Seq[Throwable])

trait SearchService {
  def publish(medias: Chunk[(State, Media)]): IO[SearchServiceIssue, Chunk[(State, Media)]]
  def unpublish(state: State, media: Media): IO[SearchServiceIssue, Unit]
}

object SearchService {
  def publish(medias: Chunk[(State, Media)]): ZIO[SearchService, SearchServiceIssue, Chunk[(State, Media)]] = ZIO.serviceWithZIO(_.publish(medias))
  def unpublish(state: State, media: Media): ZIO[SearchService, SearchServiceIssue, Unit]                   = ZIO.serviceWithZIO(_.unpublish(state, media))

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
  def publish(medias: Chunk[(State, Media)]): IO[SearchServiceIssue, Chunk[(State, Media)]] = {
    elasticOperations
      .upsert(config.indexPrefix, medias.map((state, media) => SaoMedia.fromMedia(state, media)))(_.timestamp, _.id)
      .when(config.enabled)
      .logError("couldn't upsert some or all photos from the given chunk of photos")
      .mapError(errs => SearchServiceIssue(s"Couldn't upsert", errs))
      .map(_ => medias)
  }

  override def unpublish(state: State, media: Media): IO[SearchServiceIssue, Unit] = {
    elasticOperations
      .delete(config.indexPrefix, state.mediaAccessKey.asString, media.timestamp)
      .when(config.enabled)
      .map(_ => ())
      .logError(s"Couldn't unpublish ${state.mediaAccessKey.asString} ${media.timestamp}")
      .mapError(err => SearchServiceIssue(s"Couldn't unpublish ${state.mediaAccessKey.asString} ${media.timestamp}", err :: Nil))
  }
}
