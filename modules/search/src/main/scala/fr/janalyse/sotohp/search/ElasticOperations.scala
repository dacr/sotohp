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

  private val searchFields = Seq(
    "description",
    "keywords",
    "bag",
    "classifications",
    "detectedObjects",
    "identifiedPersons",
    "camera",
    "placeStreet",
    "placeTown",
    "placeRegion",
    "placeCountry",
    "placeCountryCode",
    "filePath"
  )

  /** Free-text search across the text-bearing fields of every `${indexPrefix}-*` index. Returns
    * the matching document ids (which are the `originalId`s), ranked by relevance then most recent
    * first, capped at `size`. Only ids are fetched back - the API re-reads each media from LMDB.
    *
    * Each whitespace-separated word becomes its own `must` clause, matched with `best_fields`
    * across every searched field: so every word has to hit *somewhere*, but different words may
    * land in different fields (e.g. "beer" in detectedObjects and "brieuc" in identifiedPersons).
    * A plain multi_match with `operator=and` would instead require all words in the *same* field
    * and miss that. `fuzziness("AUTO")` (1 edit for 3-5 char words, 2 for longer) + `prefixLength(1)`
    * give typo / accent / singular-plural tolerance while keeping exact matches scored highest.
    */
  def searchMediaIds(indexPrefix: String, queryString: String, size: Int): Task[List[String]] = {
    val words = queryString.trim.split("\\s+").iterator.filter(_.nonEmpty).toList
    val query =
      if (words.isEmpty) matchAllQuery()
      else
        boolQuery().must(
          words.map(word =>
            multiMatchQuery(word)
              .fields(searchFields*)
              .lenient(true)
              .fuzziness("AUTO")
              .prefixLength(1)
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
      _        <- ZIO.log(s"${if (response.isSuccess) "Upserted" else "Failed to upsert"} ${documents.size} into elasticsearch")
      _        <- ZIO.cond(response.isSuccess, (), failures.map(err => Exception(err)))
    } yield ()
    upsertEffect.timeout(timeout).retry(retrySchedule)
  }

}
