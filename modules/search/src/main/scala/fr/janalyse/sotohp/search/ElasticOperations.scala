package fr.janalyse.sotohp.search

import zio.*
import zio.json.*
import zio.stream.*

import java.time.OffsetDateTime

case class ElasticOperations(config:SearchEngineConfig) {
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
    val elasticProperties = ElasticProperties(config.elasticUrl)

    val commonRequestConfigBuilder: RequestConfigCallback = (requestConfigBuilder: RequestConfig.Builder) =>
      requestConfigBuilder
        .setConnectTimeout(10000)
        .setRedirectsEnabled(true)
        .setSocketTimeout(10000)

    if (config.elasticPassword.isEmpty || config.elasticUsername.isEmpty)
      ElasticClient(JavaClient(elasticProperties, commonRequestConfigBuilder))
    else {
      lazy val provider = {
        val basicProvider = new BasicCredentialsProvider
        val credentials   = new UsernamePasswordCredentials(config.elasticUsername.get, config.elasticPassword.get)
        basicProvider.setCredentials(AuthScope.ANY, credentials)
        basicProvider
      }

      import org.apache.http.ssl.SSLContexts
      import org.apache.http.conn.ssl.TrustSelfSignedStrategy
      val sslContext = config.elasticUrlTrust match {
        case true  => SSLContexts.custom().loadTrustMaterial(TrustSelfSignedStrategy()).build()
        case false => SSLContexts.createDefault()
      }

      val httpClientConfigCallback: HttpClientConfigCallback =
        (httpClientBuilder: HttpAsyncClientBuilder) =>
          httpClientBuilder
            .setDefaultCredentialsProvider(provider)
            .setSSLContext(sslContext)
      // .setSSLHostnameVerifier(org.apache.http.conn.ssl.NoopHostnameVerifier.INSTANCE)

      ElasticClient(JavaClient(elasticProperties, commonRequestConfigBuilder, httpClientConfigCallback))
    }
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

  def fetchAll[T](indexName: String)(implicit decoder: JsonDecoder[T]) = {
    val result = for {
      response         <- client.execute(search(Index(indexName)).size(searchPageSize).scroll(scrollKeepAlive))
      scrollId         <- ZIO.fromOption(response.result.scrollId)
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
      failures  = response.result.failures.flatMap(_.error).map(_.toString)
      _        <- ZIO.log(s"${if (response.isSuccess) "Upserted" else "Failed to upsert"} ${documents.size} into elasticsearch")
      _        <- ZIO.cond(response.isSuccess, (), failures.mkString("\n"))
    } yield ()
    upsertEffect.timeout(timeout).retry(retrySchedule)
  }

}
