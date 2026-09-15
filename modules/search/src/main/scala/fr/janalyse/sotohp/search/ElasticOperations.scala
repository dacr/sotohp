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
  import com.sksamuel.elastic4s.requests.searches.queries.matches.MultiMatchQueryBuilderType
  import com.sksamuel.elastic4s.requests.searches.queries.Query
  import com.sksamuel.elastic4s.analysis.{Analysis, CustomAnalyzer}
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

  /** Index-wide default analyzer: standard tokenizer + lowercase + `asciifolding` (é/è/ê -> e, ñ ->
    * n, ...). Applied automatically to every dynamically-mapped text field (no per-field mapping
    * needed - ES falls back to the index's "default" analyzer for both indexing and search whenever
    * a field doesn't declare its own), so an accent is folded away on both sides alike: what lands in
    * the term dictionary is already accent-free, and so is whatever `searchMediaIds` sends as a query
    * term. That symmetry matters - folding only one side (the query, e.g. via a Scala-side
    * accent-stripping step applied to the *query* text alone) leaves the other side's accents
    * intact, and ES's fuzzy `prefixLength` then requires an exact character match over the first N
    * characters: an accent that falls within that prefix (e.g. "déchets" -> "dé" as the first two
    * characters) makes the query's folded "de" and the index's accented "dé" mismatch right there,
    * blocking the match outright regardless of the edit-distance budget - no accent-only fix at the
    * query layer alone can close that gap. Folding at index time removes the asymmetry entirely: a
    * search for "déchets" or "dechets" both normalize down to the same "dechets" term as whatever
    * was indexed from an accented caption, with no edit distance spent on accents at all. `.keyword`
    * sub-fields (used for anything needing the raw exact value) are untouched - keyword fields don't
    * go through an analyzer.
    *
    * Only takes effect on indices created *after* this was added - existing monthly indices keep
    * whatever mapping they were dynamically given before. Run `make run-search-reindex` once after
    * deploying this to rebuild every index (and republish every media) under the new analyzer.
    */
  private val defaultAnalysis = Analysis(
    CustomAnalyzer(name = "default", tokenizer = "standard", tokenFilters = List("lowercase", "asciifolding"))
  )

  /** Creates `indexName` with [[defaultAnalysis]] if it doesn't already exist - called before
    * indexing into a name for the first time, since an analyzer can only be set at index-creation
    * time (changing it on an existing index needs a reindex, which is exactly what
    * `make run-search-reindex` does). Two callers racing to create the same brand-new index is
    * harmless: the loser's `resource_already_exists_exception` is treated as success.
    */
  private def ensureIndexExists(indexName: String): Task[Unit] =
    client.execute(createIndex(indexName).analysis(defaultAnalysis)).flatMap { response =>
      val alreadyExists = response.isError && response.error.`type` == "resource_already_exists_exception"
      ZIO.cond(response.isSuccess || alreadyExists, (), response.error.asException)
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

  /** Free-text search across the text-bearing fields of every `${indexPrefix}-*` index. Returns
    * the matching document ids (which are the `originalId`s), ranked by relevance then most recent
    * first, capped at `size`. Only ids are fetched back - the API re-reads each media from LMDB.
    *
    * Each whitespace-separated word becomes its own `should` clause (`minimumShouldMatch(1)`, so
    * at least one has to hit), matched with `best_fields` across every searched field: different
    * words may land in different fields (e.g. "beer" in detectedObjects and "brieuc" in
    * identifiedPersons) and still count. Because ES sums the score of every clause a document
    * satisfies, a photo matching *every* word ranks above one matching only some of them, which in
    * turn still surfaces instead of vanishing - `must` (AND) used to drop the whole search to zero
    * hits the moment no single photo satisfied every word at once (e.g. "masque japon" found
    * nothing: the mask photo's caption says "japonais", and separately some Japan-trip photo's
    * reverse-geocoded country is "Japon", but no one photo had both literal words).
    *
    * Each word is actually a `should` of two clauses, not one:
    *   - a fuzzy `multi_match` for typo tolerance (unchanged from before, see below)
    *   - for words of 5+ letters, a `phrase_prefix` `multi_match`: does any indexed term *start
    *     with* this word. A short/root word ("japon") is a prefix of a longer one ("japonais"), not
    *     a typo of it - no realistic edit-distance budget bridges a 5- to an 8-letter word, but a
    *     prefix match closes that gap directly, on its own terms. Gated to 5+ letters so short or
    *     common words don't expand into noise; below that, only the fuzzy clause applies. Note this
    *     is a pure character-prefix test, with no morphology behind it, so it still occasionally
    *     pairs up two unrelated words that happen to share their first 5 letters (e.g. "boite" is a
    *     literal prefix of "boiteux", box vs limping) - a real fix would need a French-stemming
    *     analyzer (see below), 5 letters is just a cheap way to make the coincidence rarer.
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
    *   - accents never touch this edit-distance budget at all: the index's default analyzer folds
    *     them away at index time (see `defaultAnalysis`), and multi_match analyzes the query text
    *     with that same analyzer before comparing, so "ménuires"/"menuires"/"Menuires" all become
    *     the identical accent-free term on both sides - no fuzziness spent bridging an accent, and
    *     no risk of an accent landing inside `prefixLength`'s exact-match window and blocking the
    *     match outright (which is what a query-side-only accent fold used to do here for a word
    *     like "déchets", accented within its own indexed caption - see `defaultAnalysis` for the
    *     full story).
    * Exact matches still score highest. Per-field boosts (see `searchFields`) skew relevance
    * within that: `bag` is down-weighted since it describes the whole album rather than any one
    * photo in it.
    *
    * Each word is also expanded through `FrenchSynonyms` before the should-clauses above are built
    * - "bagnole" additionally searches "voiture"/"auto"/"automobile", "piaf" additionally searches
    * "oiseau", etc (see `synonyms_fr.txt`). This is query-side only: a synonym is just another
    * `wordQuery` should-clause alongside the original word, so it gets the exact same fuzzy/prefix
    * treatment and doesn't touch the index - editing the synonyms file needs a redeploy, never a
    * reindex.
    */
  def searchMediaIds(indexPrefix: String, queryString: String, size: Int): Task[List[String]] = {
    val words = queryString.trim.split("\\s+").iterator.filter(_.nonEmpty).toList

    def wordQuery(word: String): Query = {
      val fuzzy = multiMatchQuery(word)
        .fields(searchFields)
        .lenient(true)
        .fuzziness(1) //
        .prefixLength(2)
      if (word.length < 5) fuzzy
      else {
        val prefix = multiMatchQuery(word)
          .fields(searchFields)
          .lenient(true)
          .matchType(MultiMatchQueryBuilderType.PHRASE_PREFIX)
        boolQuery().should(fuzzy, prefix).minimumShouldMatch(1)
      }
    }

    def wordWithSynonymsQuery(word: String): Query = {
      val synonyms = FrenchSynonyms.expand(word)
      if (synonyms.isEmpty) wordQuery(word)
      else boolQuery().should((word :: synonyms.toList).map(wordQuery)).minimumShouldMatch(1)
    }

    val query =
      if (words.isEmpty) matchAllQuery()
      else boolQuery().should(words.map(wordWithSynonymsQuery)).minimumShouldMatch(1)
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
    val indexNames     = documents.map(document => indexNameFromTimestamp(indexPrefix, timestampExtractor(document))).distinct
    val responseEffect =
      ZIO.foreachDiscard(indexNames)(ensureIndexExists) *>
        client.execute {
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
