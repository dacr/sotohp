package fr.janalyse.sotohp.search

import zio.*
import zio.json.*
import zio.stream.*

import java.time.OffsetDateTime

case class ElasticOperations(config: SearchServiceConfig) {
  import com.sksamuel.elastic4s.zio.instances.*
  import com.sksamuel.elastic4s.ziojson.*
  import com.sksamuel.elastic4s.{ElasticClient, ElasticProperties}
  import com.sksamuel.elastic4s.ElasticDsl.*
  import com.sksamuel.elastic4s.Index
  import com.sksamuel.elastic4s.{ElasticClient, ElasticProperties}
  import com.sksamuel.elastic4s.http.JavaClient
  import com.sksamuel.elastic4s.ElasticDsl.*
  import com.sksamuel.elastic4s.requests.mappings.*
  import com.sksamuel.elastic4s.Response
  import com.sksamuel.elastic4s.requests.bulk.BulkResponse
  import com.sksamuel.elastic4s.requests.searches.SearchResponse
  import com.sksamuel.elastic4s.requests.searches.sort.SortOrder.Desc
  import org.elasticsearch.client.RestClientBuilder.{HttpClientConfigCallback, RequestConfigCallback}
  import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
  import org.apache.http.client.config.RequestConfig
  import org.apache.http.impl.client.BasicCredentialsProvider
  import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
  import scala.concurrent.duration.FiniteDuration
  import java.time.temporal.ChronoField
  import java.util.concurrent.TimeUnit
  import scala.util.Properties.{envOrNone, envOrElse}

  private val client = { // TODO rewrite to be fully effect based
    import scala.concurrent.ExecutionContext.Implicits.global
    val elasticProperties = ElasticProperties(config.elasticUrl)

    val commonRequestConfigBuilder: RequestConfigCallback = (requestConfigBuilder: RequestConfig.Builder) =>
      requestConfigBuilder
        .setConnectTimeout(10000)
        .setRedirectsEnabled(true)
        .setSocketTimeout(10000)

    val javaClient = if (config.elasticPassword.isEmpty || config.elasticUsername.isEmpty)
      JavaClient(elasticProperties, commonRequestConfigBuilder)
    else {
      lazy val provider = {
        val basicProvider = new BasicCredentialsProvider
        val credentials   = new UsernamePasswordCredentials(config.elasticUsername.get, config.elasticPassword.get)
        basicProvider.setCredentials(AuthScope.ANY, credentials)
        basicProvider
      }

      val httpClientConfigCallback: HttpClientConfigCallback =
        (httpClientBuilder: HttpAsyncClientBuilder) =>
          if (config.elasticUrlTrustSelfSigned) {
            import org.apache.http.ssl.SSLContexts
            import org.apache.http.conn.ssl.TrustSelfSignedStrategy
            val sslContext = SSLContexts.custom().loadTrustMaterial(TrustSelfSignedStrategy()).build()
            httpClientBuilder
              .setDefaultCredentialsProvider(provider)
              .setSSLContext(sslContext)
              .setSSLHostnameVerifier((hostname, session) => true)
          } else {
            httpClientBuilder
          }

      JavaClient(elasticProperties, commonRequestConfigBuilder, httpClientConfigCallback)
    }

    import com.sksamuel.elastic4s.{HttpClient, ElasticRequest, HttpResponse}
    val zioClient = new HttpClient[Task] {
      override def send(request: ElasticRequest): Task[HttpResponse] = ZIO.fromFuture(implicit ec => javaClient.send(request))
      override def close(): Task[Unit] = ZIO.fromFuture(implicit ec => javaClient.close())
    }

    ElasticClient(zioClient)
  }

  private val scrollKeepAlive = FiniteDuration(30, "seconds")
  private val timeout         = 20.seconds
  private val retrySchedule   = (Schedule.exponential(500.millis, 2).jittered && Schedule.recurs(5)).onDecision((state, out, decision) =>
    decision match {
      case Schedule.Decision.Done               => ZIO.logInfo("No more retry attempt !")
      case Schedule.Decision.Continue(interval) => ZIO.logInfo(s"Will retry at ${interval.start}")
    }
  )
  val upsertGrouping          = 50
  val searchPageSize          = 500
  val searchMaxResults        = 1000

  // ------------------------------------------------------

  private def indexNameFromTimestamp(indexPrefix: String, timestamp: OffsetDateTime): String = {
    val year  = timestamp.get(ChronoField.YEAR)
    val month = timestamp.get(ChronoField.MONTH_OF_YEAR)
    val day   = timestamp.get(ChronoField.DAY_OF_MONTH)
    val week  = timestamp.get(ChronoField.ALIGNED_WEEK_OF_YEAR)
    s"$indexPrefix-$year-$month"
  }

  // ------------------------------------------------------
  private def streamFromScroll(scrollId: String) = {
    ZStream.paginateChunkZIO(scrollId) { currentScrollId =>
      for {
        response    <- client.execute(searchScroll(currentScrollId).keepAlive(scrollKeepAlive))
        nextScrollId = response.result.scrollId
        results      = Chunk.fromArray(response.result.hits.hits.map(_.sourceAsString))
        _           <- ZIO.log(s"Got ${results.size} more documents")
      } yield results -> (if (results.size > 0) nextScrollId else None)
    }
  }

  // ------------------------------------------------------

  def delete[T](indexPrefix: String, document: T)(timestampExtractor: T => OffsetDateTime, idExtractor: T => String): Task[Unit] = {
    val indexName = indexNameFromTimestamp(indexPrefix, timestampExtractor(document))
    val id        = idExtractor(document)
    for {
      response <- client.execute(deleteById(indexName, id))
      _        <- ZIO.cond(response.isSuccess, (), response.error.asException)
    } yield ()
  }

  // ------------------------------------------------------

  def delete(indexPrefix: String, id: String, timestamp: OffsetDateTime): Task[Unit] = {
    val indexName = indexNameFromTimestamp(indexPrefix, timestamp)
    for {
      response <- client.execute(deleteById(indexName, id))
      _        <- ZIO.cond(response.isSuccess, (), response.error.asException)
    } yield ()
  }

  // ------------------------------------------------------

  /** Deletes every `${indexPrefix}-*` index. A full re-publish otherwise leaves stale documents
    * behind: a media whose timestamp changed lands in a different monthly index and orphans its
    * former copy. No-op when none match; unrelated indices are never touched.
    */
  def deleteIndexes(indexPrefix: String): Task[Int] = {
    for {
      listing <- client.execute(catIndices())
      names    = listing.result.map(_.index).filter(_.startsWith(s"$indexPrefix-")).toList
      _       <- ZIO
                   .foreachDiscard(names.grouped(100).toList) { batch =>
                     client.execute(deleteIndex(batch)).flatMap(response => ZIO.cond(response.isSuccess, (), response.error.asException))
                   }
                   .when(names.nonEmpty)
      _       <- ZIO.log(s"Deleted ${names.size} search indexes matching $indexPrefix-*")
    } yield names.size
  }

  // ------------------------------------------------------

  // The bag/album name is shared by every photo filed under it, so a match there says more about
  // the album than about this particular photo - e.g. one trip's bag name shouldn't make every
  // photo in it rank as high as a photo whose own caption/keywords/people actually match. Down-
  // weighted rather than dropped: it should still help find things, just not dominate the score.
  private val searchFields: Map[String, Double] = Map(
    "description"       -> 1.0,
    "autoDescription"   -> 1.0,
    "keywords"          -> 0.7,
    "bag"               -> 0.3,
    "classifications"   -> 0.8,
    "detectedObjects"   -> 0.9,
    "identifiedPersons" -> 1.0,
    "camera"            -> 0.2,
    "placeStreet"       -> 1.0,
    "placeTown"         -> 1.0,
    "placeRegion"       -> 1.0,
    "placeCountry"      -> 1.0,
    "placeCountryCode"  -> 1.0,
    "filePath"          -> 0.1
  )

  /** Strips diacritics (é -> e, ñ -> n, ...) via NFD decomposition + dropping the combining marks.
    * Applied to query words only - the index keeps whatever accents the source text had. This
    * takes accents out of the fuzzy-matching problem entirely (a folded "ménuires" either equals
    * the indexed "menuires" outright or is a plain accent-free typo away from it) rather than
    * relying on the edit-distance budget to bridge them, which would otherwise compete with the
    * budget genuine typos need.
    */
  private def foldDiacritics(word: String): String =
    java.text.Normalizer.normalize(word, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "")

  /** Free-text search across the text-bearing fields of every `${indexPrefix}-*` index. Returns
    * the matching document ids (which are the `originalId`s), ranked by relevance then most recent
    * first, capped at `size`. Only ids are fetched back - the API re-reads each media from LMDB.
    *
    * Each whitespace-separated word becomes its own `must` clause, matched with `best_fields`
    * across every searched field: so every word has to hit *somewhere*, but different words may
    * land in different fields (e.g. "beer" in detectedObjects and "brieuc" in identifiedPersons).
    * A plain multi_match with `operator=and` would instead require all words in the *same* field
    * and miss that.
    *
    * Typo tolerance is deliberately conservative - a captioned photo library turns up unrelated
    * words that are a couple of letters away from the query far more often than a hand-typed
    * search box does (e.g. a caption's "sangles" - straps - fuzzy-matching a search for
    * "sanglier" - wild boar - under the old `fuzziness("AUTO")`, which allowed 2 edits for 6+
    * char words):
    *   - `fuzziness(1)` caps every word at a single edit, replacing AUTO's 1-for-short/2-for-long
    *     scale, so a chance two-letter-away word never surfaces.
    *   - `prefixLength(2)` requires the first two characters to match exactly before that one edit
    *     applies, so an elision like "l'une" (which is otherwise just one inserted character away
    *     from "lune") is rejected at the second character instead of fuzzy-matching a real word.
    *   - `foldDiacritics` (above) keeps this strict budget from being spent on accents, so
    *     "ménuires" still finds the (unaccented, geocoded) "menuires" - see its own doc for why.
    * Exact matches still score highest. Per-field boosts (see `searchFields`) skew relevance
    * within that: `bag` is down-weighted since it describes the whole album rather than any one
    * photo in it.
    */
  def searchMediaIds(indexPrefix: String, queryString: String, size: Int): Task[List[String]] = {
    val words = queryString.trim.split("\\s+").iterator.filter(_.nonEmpty).map(foldDiacritics).toList
    val query =
      if (words.isEmpty) matchAllQuery()
      else
        boolQuery().must(
          words.map(word =>
            multiMatchQuery(word)
              .fields(searchFields)
              .lenient(true)
              .fuzziness(1) //
              .prefixLength(2)
          )
        )
    val request =
      search(s"$indexPrefix-*")
        .query(query)
        .sortBy(scoreSort(Desc), fieldSort("timestamp").order(Desc))
        // A single non-scrolled page; ES's default index.max_result_window (10k) is the ceiling.
        .size(size.max(0).min(searchMaxResults))
        .fetchSource(false)
    for {
      response <- client.execute(request)
      _        <- ZIO.cond(response.isSuccess, (), response.error.asException)
    } yield response.result.hits.hits.map(_.id).toList
  }

  // ------------------------------------------------------

  def fetchAll[T](indexName: String)(implicit decoder: JsonDecoder[T]) = {
    // TODO something is going wrong here, sometimes not all results are returned without error being returned
    // TODO deep pagination issue see https://www.elastic.co/guide/en/elasticsearch/reference/current/scroll-api.html
    val result = for {
      response         <- client.execute(search(Index(indexName)).size(searchPageSize).scroll(scrollKeepAlive))
      scrollId         <- ZIO.fromOption(response.result.scrollId).orElseFail(new Exception("No scrollId returned"))
      firstResults      = Chunk.fromArray(response.result.hits.hits.map(_.sourceAsString))
      _                <- ZIO.log(s"Got ${firstResults.size} first documents")
      nextResultsStream = streamFromScroll(scrollId)
    } yield ZStream.fromChunk(firstResults) ++ nextResultsStream

    ZStream.unwrap(result).map(_.fromJson[T]).absolve.mapError(err => Exception(err.toString))
  }

  // ------------------------------------------------------
  def upsert[T](indexPrefix: String, documents: Chunk[T])(timestampExtractor: T => OffsetDateTime, idExtractor: T => String)(implicit encoder: JsonEncoder[T]) = {
    val responseEffect = client.execute {
      bulk {
        for { document <- documents } yield {
          val indexName = indexNameFromTimestamp(indexPrefix, timestampExtractor(document))
          val id        = idExtractor(document)
          indexInto(indexName).id(id).doc(document)
        }
      }
    }
    val upsertEffect   = for {
      response <- responseEffect
                    .mapError(err => List(err))
      failures  = response.result.failures.flatMap(_.error).map(_.toString)
      _        <- ZIO.cond(response.isSuccess, (), failures.map(err => Exception(err)))
    } yield ()
    upsertEffect
      .timeout(timeout)
      .retry(retrySchedule)
      .logError(s"Couldn't upsert ${documents.size} document into elasticsearch")
  }

}
