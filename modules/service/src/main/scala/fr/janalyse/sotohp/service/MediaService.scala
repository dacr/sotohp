package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.core.CoreIssue
import zio.*
import zio.stream.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.model.{KeywordRules, SynchronizeAction, SynchronizeStatus}
import zio.lmdb.LMDB

import java.net.URL
import java.time.{Instant, OffsetDateTime}
import java.util.regex.Pattern

type MediaTuple = (key: MediaAccessKey, media: Media)

// Minimal projection used for the map tab: avoids the Original + Bags
// joins and trims the wire payload to just what a Leaflet marker needs.
case class MediaLocation(
  accessKey: MediaAccessKey,
  latitude: LatitudeDecimalDegrees,
  longitude: LongitudeDecimalDegrees,
  shootDateTime: Option[ShootDateTime],
  starred: Starred,
  bagId: Option[BagId]
)

// One entry of the mosaic seek table: the media sitting at absolute offset `offset` when medias
// are enumerated newest-first. `accessKey` is a valid `mediaStream` start key (inclusive), so a
// client can turn "I need the items at offset N" into a single streaming call.
case class MediaTimelineAnchor(
  offset: Long,
  accessKey: MediaAccessKey,
  timestamp: Instant
)

// Exact media count plus one anchor every `step` medias, newest-first.
case class MediaTimeline(
  total: Long,
  step: Int,
  anchors: List[MediaTimelineAnchor]
)

trait MediaService {

  // -------------------------------------------------------------------------------------------------------------------
  def mediaFind(nearKey: MediaAccessKey): IO[ServiceIssue, Option[MediaTuple]]
  def mediaSearch(keywordsFilter: Set[Keyword]): Stream[ServiceStreamIssue, MediaTuple]

  def mediaList(): Stream[ServiceStreamIssue, MediaTuple]
  def mediaLocationList(): Stream[ServiceStreamIssue, MediaLocation]
  def mediaFirst(): IO[ServiceIssue, Option[MediaTuple]]
  def mediaPrevious(nearKey: MediaAccessKey): IO[ServiceIssue, Option[MediaTuple]]
  def mediaNext(nearKey: MediaAccessKey): IO[ServiceIssue, Option[MediaTuple]]
  def mediaLast(): IO[ServiceIssue, Option[MediaTuple]]
  def mediaStream(fromKey: MediaAccessKey, backward: Boolean, limit: Int, inclusive: Boolean): Stream[ServiceStreamIssue, MediaTuple]
  def mediaTimeline(step: Int): IO[ServiceIssue, MediaTimeline]
  def mediaGet(key: MediaAccessKey): IO[ServiceIssue, Option[MediaTuple]]
  def mediaGet(id: OriginalId): IO[ServiceIssue, Option[MediaTuple]]
  def mediaGetAt(index: Long): IO[ServiceIssue, Option[MediaTuple]]
  def mediaMaxPosition(): IO[ServiceIssue, Option[Long]]

  def mediaUpdate(
    key: MediaAccessKey, // current media key
    updatedMedia: Media  // can contain the new media key to use
  ): IO[ServiceIssue, Option[MediaTuple]]

  // -------------------------------------------------------------------------------------------------------------------
  def mediaNormalizedRead(key: MediaAccessKey): Stream[ServiceStreamIssue, Byte]
  def mediaOriginalRead(key: MediaAccessKey): Stream[ServiceStreamIssue, Byte]
  def mediaMiniatureRead(key: MediaAccessKey): Stream[ServiceStreamIssue, Byte]

  // -------------------------------------------------------------------------------------------------------------------
  def stateList(): Stream[ServiceStreamIssue, State]
  def stateGet(originalId: OriginalId): IO[ServiceIssue, Option[State]]
  def stateDelete(originalId: OriginalId): IO[ServiceIssue, Unit]
  def stateUpsert(originalId: OriginalId, state: State): IO[ServiceIssue, State]

  // -------------------------------------------------------------------------------------------------------------------
  def originalClassifications(originalId: OriginalId): IO[ServiceIssue, Option[OriginalClassifications]]
  def originalFaces(originalId: OriginalId): IO[ServiceIssue, Option[OriginalFaces]]
  def originalFacesFeatures(originalId: OriginalId): IO[ServiceIssue, Option[OriginalFaceFeatures]]

  /** Detects face bounding boxes on the given original at the given rotation - no Face records,
    * no faceId allocation, no file or database writes. Used to check which rotation the currently
    * stored faces for an original actually match.
    */
  def facesDetectPreview(originalId: OriginalId, rotationDegrees: Int): IO[ServiceIssue, List[BoundingBox]]

  /** Remaps every currently stored face of the given original from one effective rotation to another :
    * bounding boxes are transformed with an exact (lossless) 90°-multiple rotation, cached face crops are
    * re-extracted from the newly-rotated image, and face features are recomputed - all in place, preserving
    * faceId, identifiedPersonId and every other identity field. Used whenever a media's effective orientation
    * changes (nothing is deleted or re-detected, so manually-added faces and person identifications survive).
    */
  def facesRemapForRotation(originalId: OriginalId, fromRotationDegrees: Int, toRotationDegrees: Int): IO[ServiceIssue, Option[OriginalFaceFeatures]]

  /** Re-cuts every cached face crop of the given original from the image at the media's *effective*
    * rotation, at the boxes exactly as they are stored, and recomputes the features from those new
    * crops. Boxes are left untouched, and so are faceId, identifiedPersonId and every other field.
    *
    * The repair for a crop that was written against a different rotation than its box - which used
    * to happen when a face was added by hand on a photo the user had rotated, before faceCreate
    * learnt to honour the media's orientation. The stored box is taken as authoritative, since the
    * effective frame is the frame every stored geometry is defined in.
    */
  def facesRecropForEffectiveRotation(originalId: OriginalId): IO[ServiceIssue, Option[OriginalFaceFeatures]]
  def originalObjects(originalId: OriginalId): IO[ServiceIssue, Option[OriginalDetectedObjects]]
  def originalNormalized(originalId: OriginalId): IO[ServiceIssue, Option[OriginalNormalized]]
  def originalMiniatures(originalId: OriginalId): IO[ServiceIssue, Option[OriginalMiniatures]]

  /** Computes (once, then cached) the whole-image feature vector for a photo. */
  def originalMediaFeatures(originalId: OriginalId): IO[ServiceIssue, Option[OriginalMediaFeatures]]

  /** Recomputes the whole-image embedding and overwrites the stored one, where
    * `originalMediaFeatures` only ever computes a missing one. Needed whenever the input the vector
    * was derived from has changed - in practice when the media's effective rotation changed, since
    * the model is not rotation invariant.
    */
  def mediaFeaturesRecompute(originalId: OriginalId): IO[ServiceIssue, OriginalMediaFeatures]

  /** Generates (once, then cached) the image-to-text caption ("auto description") for a photo,
    * using a local Ollama vision model. A photo that already has a stored record - successful or
    * not - is left alone: one trip through the model is enough, and a caption the model failed to
    * produce is unlikely to appear on a retry. Use `originalCaptionRecompute` to try again.
    *
    * A fast no-op that stores nothing when the captioner is disabled
    * (`sotohp.processors.captioner.enabled`), so those photos are still picked up once it is on.
    */
  def originalCaption(originalId: OriginalId): IO[ServiceIssue, Option[OriginalCaption]]

  /** Re-runs captioning and overwrites the stored record, where `originalCaption` only fills a
    * missing one.
    */
  def originalCaptionRecompute(originalId: OriginalId): IO[ServiceIssue, OriginalCaption]

  /** Whether the model has already been run on this photo, caption or not. The cheapest possible
    * resume check for a batch job: a single key lookup, no record decoding, no joins.
    */
  def originalCaptionExists(originalId: OriginalId): IO[ServiceIssue, Boolean]

  /** The stored caption record for a photo, if the model has already been run on it - including a
    * record whose `status.successful` is false. Pure read: never triggers a model call, so a batch
    * job can tell "already attempted" from "never attempted" before spending seconds on a photo.
    */
  def originalCaptionGet(originalId: OriginalId): IO[ServiceIssue, Option[OriginalCaption]]

  /** The stored caption text for a photo, if any. Pure read - never triggers a model call. */
  def mediaCaptionGet(originalId: OriginalId): IO[ServiceIssue, Option[String]]

  /** Deduces (once, then cached on the media as `deductedPlace`) the textual place - town / region
    * / country - of a photo from its effective location, by offline reverse-geocoding. Yields the
    * effective place (`userDefinedPlace` orElse `deductedPlace`), or `None` when the media has no
    * usable location or nothing could be resolved.
    */
  def placeResolve(originalId: OriginalId): IO[ServiceIssue, Option[Place]]

  /** Re-runs the reverse-geocoding and overwrites `deductedPlace`, where `placeResolve` only ever
    * fills a missing one. Needed after a location change or a GeoNames dump refresh.
    */
  def placeRecompute(originalId: OriginalId): IO[ServiceIssue, Option[Place]]

  def originalFacesUpdate(originalId: OriginalId, facesIds: List[FaceId]): IO[ServiceIssue, Unit]

  // -------------------------------------------------------------------------------------------------------------------
  def faceList(): Stream[ServiceStreamIssue, Face]
  def faceCount(): IO[ServiceIssue, Long]
  def faceGet(faceId: FaceId): IO[ServiceIssue, Option[Face]]
  def faceExists(faceId: FaceId): IO[ServiceIssue, Boolean]
  def faceDelete(faceId: FaceId): IO[ServiceIssue, Unit]
  def faceCreate(faceId: Option[FaceId], originalId: OriginalId, box: BoundingBox): IO[ServiceIssue, Face]
  def faceUpdate(
    faceId: FaceId, // current face id
    face: Face      // may contain and updated id
  ): IO[ServiceIssue, Face]
  def faceRead(faceId: FaceId): Stream[ServiceStreamIssue, Byte]

  // -------------------------------------------------------------------------------------------------------------------
  def faceFeaturesList(): Stream[ServiceStreamIssue, FaceFeatures]
  def faceFeaturesGet(faceId: FaceId): IO[ServiceIssue, Option[FaceFeatures]]

  // -------------------------------------------------------------------------------------------------------------------
  /** Streams every stored whole-image feature vector (one per photo). */
  def mediaFeaturesList(): Stream[ServiceStreamIssue, MediaFeatures]

  /** The stored whole-image feature vector for a photo, if computed. */
  def mediaFeaturesGet(originalId: OriginalId): IO[ServiceIssue, Option[MediaFeatures]]

  /** The `count` photos most visually similar to the given one, best first, as
    * `(originalId, cosineSimilarity)` pairs (self excluded).
    */
  def mediaSimilar(originalId: OriginalId, count: Int): IO[ServiceIssue, List[(OriginalId, Double)]]

  // -------------------------------------------------------------------------------------------------------------------
  // Visual-similarity clusters (computed offline by the `MediaFeaturesClustering` CLI).

  /** Replaces every cluster assignment: clears the cluster collection then writes the
    * given `(originalId, clusterId)` pairs. `clusterId < 0` marks an unclustered photo.
    */
  def mediaClustersReplace(assignments: Iterable[(OriginalId, Int)]): IO[ServiceIssue, Unit]

  /** The cluster a photo belongs to, if any. */
  def mediaClusterOf(originalId: OriginalId): IO[ServiceIssue, Option[Int]]

  /** Every real cluster as `(clusterId, size)`, largest first (unclustered photos excluded). */
  def mediaClusterList(): IO[ServiceIssue, List[(Int, Long)]]

  /** The medias in one cluster. */
  def mediaClusterMembers(clusterId: Int): Stream[ServiceStreamIssue, MediaTuple]

  // -------------------------------------------------------------------------------------------------------------------
  def personList(): Stream[ServiceStreamIssue, Person]
  def personCount(): IO[ServiceIssue, Long]
  def personGet(personId: PersonId): IO[ServiceIssue, Option[Person]]
  def personExists(personId: PersonId): IO[ServiceIssue, Boolean]
  def personDelete(personId: PersonId): IO[ServiceIssue, Unit]
  def personCreate(
    id: Option[PersonId],
    firstName: FirstName,
    lastName: LastName,
    birthName: Option[BirthName],
    birthDate: Option[BirthDate],
    email: Option[PersonEmail],
    description: Option[PersonDescription]
  ): IO[ServiceIssue, Person]
  def personUpdate(
    personId: PersonId,
    firstName: FirstName,
    lastName: LastName,
    birthName: Option[BirthName],
    birthDate: Option[BirthDate],
    email: Option[PersonEmail],
    description: Option[PersonDescription],
    chosenFaceId: Option[FaceId]
  ): IO[ServiceIssue, Option[Person]]
  def personFaceList(personId: PersonId): Stream[ServiceStreamIssue, Face]

  // -------------------------------------------------------------------------------------------------------------------
  def originalList(): Stream[ServiceStreamIssue, Original]
  def originalCount(): IO[ServiceIssue, Long]
  def originalGet(originalId: OriginalId): IO[ServiceIssue, Option[Original]]
  def originalExists(originalId: OriginalId): IO[ServiceIssue, Boolean]
  def originalDelete(originalId: OriginalId): IO[ServiceIssue, Unit]
  def originalUpsert(providedOriginal: Original): IO[ServiceIssue, Original]

  // -------------------------------------------------------------------------------------------------------------------
  def bagList(): Stream[ServiceStreamIssue, Bag]
  def bagGet(bagId: BagId): IO[ServiceIssue, Option[Bag]]
  def bagDelete(bagId: BagId): IO[ServiceIssue, Unit]
  def bagUpdate(
    bagId: BagId,
    name: BagName,
    description: Option[BagDescription],
    location: Option[Location],
    timestamp: Option[ShootDateTime],
    coverOriginalId: Option[OriginalId],
    publishedOn: Option[URL],
    keywords: Set[Keyword]
  ): IO[ServiceIssue, Option[Bag]]

  // -------------------------------------------------------------------------------------------------------------------
  def portfolioList(): Stream[ServiceStreamIssue, Portfolio]
  def portfolioGet(portfolioId: PortfolioId): IO[ServiceIssue, Option[Portfolio]]
  def portfolioCreate(name: PortfolioName, description: Option[PortfolioDescription]): IO[ServiceIssue, Portfolio]
  def portfolioUpdate(portfolioId: PortfolioId, name: PortfolioName, description: Option[PortfolioDescription]): IO[ServiceIssue, Option[Portfolio]]
  def portfolioDelete(portfolioId: PortfolioId): IO[ServiceIssue, Unit]
  def portfolioAssetAdd(portfolioId: PortfolioId, asset: Asset): IO[ServiceIssue, Asset]
  def portfolioAssetUpdate(portfolioId: PortfolioId, oldAsset: Asset, newAsset: Asset): IO[ServiceIssue, Option[Asset]]
  def portfolioAssetRemove(portfolioId: PortfolioId, asset: Asset): IO[ServiceIssue, Boolean]

  // -------------------------------------------------------------------------------------------------------------------
  def ownerList(): Stream[ServiceIssue, Owner]
  def ownerGet(ownerId: OwnerId): IO[ServiceIssue, Option[Owner]]
  def ownerDelete(ownerId: OwnerId): IO[ServiceIssue, Unit]
  def ownerCreate(
    providedOwnerId: Option[OwnerId], // If not provided, it will be chosen automatically
    firstName: FirstName,
    lastName: LastName,
    birthDate: Option[BirthDate]
  ): IO[ServiceIssue, Owner]
  def ownerUpdate(
    ownerId: OwnerId,
    firstName: FirstName,
    lastName: LastName,
    birthDate: Option[BirthDate],
    coverOriginalId: Option[OriginalId]
  ): IO[ServiceIssue, Option[Owner]]

  // -------------------------------------------------------------------------------------------------------------------
  def storeList(): Stream[ServiceIssue, Store]
  def storeGet(storeId: StoreId): IO[ServiceIssue, Option[Store]]
  def storeDelete(storeId: StoreId): IO[ServiceIssue, Unit]
  def storeCreate(
    providedStoreId: Option[StoreId], // If not provided, it will be chosen automatically
    name: Option[StoreName],
    ownerId: OwnerId,
    baseDirectory: BaseDirectoryPath,
    includeMask: Option[IncludeMask],
    ignoreMask: Option[IgnoreMask]
  ): IO[ServiceIssue, Store]
  def storeUpdate(
    storeId: StoreId,
    name: Option[StoreName],
    baseDirectory: BaseDirectoryPath,
    includeMask: Option[IncludeMask],
    ignoreMask: Option[IgnoreMask]
  ): IO[ServiceIssue, Option[Store]]

  // -------------------------------------------------------------------------------------------------------------------
  def synchronizeStart(addedThoseLastDays: Option[Int]): IO[ServiceIssue, Unit]
  def synchronizeWait(): IO[ServiceIssue, Unit]
  def synchronizeStop(): IO[ServiceIssue, Unit]
  def synchronizeStatus(): IO[ServiceIssue, SynchronizeStatus]

  /** Rebuilds the LMDB indexes (medias, originals, detectedFaces, positional). Does NOT touch the
    * search engine - use [[searchReindexAll]] for that.
    */
  def reindexAll(): IO[ServiceIssue, Unit]

  /** Re-publishes every stored media to the search engine, in batches. Needed after a `SaoMedia`
    * schema change or a bulk enrichment backfill (e.g. `placeResolve`), since the normal
    * synchronization only ever publishes never-synced medias. Returns the number published.
    */
  def searchReindexAll(): IO[ServiceIssue, Long]

  /** Re-publishes a single media's `SaoMedia` document to the search engine, rebuilt from what is
    * currently stored. Lets a long enrichment backfill (captions, places, ...) keep the index in
    * step as it goes, instead of leaving the whole collection stale until a `searchReindexAll`.
    *
    * A no-op when search is disabled. Unlike the internal best-effort re-publish that follows a
    * user edit, this one surfaces failures so a batch job can count and report them.
    */
  def searchPublish(originalId: OriginalId): IO[ServiceIssue, Unit]

  // -------------------------------------------------------------------------------------------------------------------
  def keywordSentenceToKeywords(storeId: StoreId, sentence: String): IO[ServiceIssue, Set[Keyword]]

  def keywordList(storeId: StoreId): IO[ServiceIssue, Map[Keyword, Int]]
  def keywordDelete(storeId: StoreId, keyword: Keyword): IO[ServiceIssue, Unit]

  def keywordRulesList(): IO[ServiceIssue, Chunk[KeywordRules]]
  def keywordRulesGet(storeId: StoreId): IO[ServiceIssue, Option[KeywordRules]]
  def keywordRulesUpsert(storeId: StoreId, rules: KeywordRules): IO[ServiceIssue, Unit]
  def keywordRulesDelete(storeId: StoreId): IO[ServiceIssue, Unit]
}

// =====================================================================================================================

object MediaService {

  val live: ZLayer[LMDB & SearchService, LMDBIssues | CoreIssue, MediaService] = ZLayer.fromZIO {
    for {
      lmdb             <- ZIO.service[LMDB]
      searchService    <- ZIO.service[SearchService]
      mediaServiceLive <- MediaServiceLive.setup(lmdb, searchService).logError("MediaServiceLive setup error")
      _                <- ZIO.logInfo(s"MediaServiceLive layer ready")
    } yield mediaServiceLive
  }

  // -------------------------------------------------------------------------------------------------------------------

  def mediaFind(nearKey: MediaAccessKey): ZIO[MediaService, ServiceIssue, Option[MediaTuple]] = ZIO.serviceWithZIO(_.mediaFind(nearKey))

  def mediaSearch(keywordsFilter: Set[Keyword]): ZStream[MediaService, ServiceStreamIssue, MediaTuple] = ZStream.serviceWithStream(_.mediaSearch(keywordsFilter))

  def mediaList(): ZStream[MediaService, ServiceStreamIssue, MediaTuple] = ZStream.serviceWithStream(_.mediaList())

  def mediaLocationList(): ZStream[MediaService, ServiceStreamIssue, MediaLocation] = ZStream.serviceWithStream(_.mediaLocationList())

  def mediaFirst(): ZIO[MediaService, ServiceIssue, Option[MediaTuple]] = ZIO.serviceWithZIO(_.mediaFirst())

  def mediaPrevious(nearKey: MediaAccessKey): ZIO[MediaService, ServiceIssue, Option[MediaTuple]] = ZIO.serviceWithZIO(_.mediaPrevious(nearKey))

  def mediaNext(nearKey: MediaAccessKey): ZIO[MediaService, ServiceIssue, Option[MediaTuple]] = ZIO.serviceWithZIO(_.mediaNext(nearKey))

  def mediaLast(): ZIO[MediaService, ServiceIssue, Option[MediaTuple]] = ZIO.serviceWithZIO(_.mediaLast())

  def mediaStream(fromKey: MediaAccessKey, backward: Boolean, limit: Int, inclusive: Boolean = false): ZStream[MediaService, ServiceStreamIssue, MediaTuple] =
    ZStream.serviceWithStream(_.mediaStream(fromKey, backward, limit, inclusive))

  def mediaTimeline(step: Int): ZIO[MediaService, ServiceIssue, MediaTimeline] = ZIO.serviceWithZIO(_.mediaTimeline(step))

  def mediaGet(key: MediaAccessKey): ZIO[MediaService, ServiceIssue, Option[MediaTuple]] = ZIO.serviceWithZIO(_.mediaGet(key))

  def mediaGet(id: OriginalId): ZIO[MediaService, ServiceIssue, Option[MediaTuple]] = ZIO.serviceWithZIO(_.mediaGet(id))

  def mediaGetAt(index: Long): ZIO[MediaService, ServiceIssue, Option[MediaTuple]] = ZIO.serviceWithZIO(_.mediaGetAt(index))

  def mediaMaxPosition(): ZIO[MediaService, ServiceIssue, Option[Long]] = ZIO.serviceWithZIO(_.mediaMaxPosition())

  def mediaUpdate(
    key: MediaAccessKey,
    updatedMedia: Media
  ): ZIO[MediaService, ServiceIssue, Option[MediaTuple]] =
    ZIO.serviceWithZIO(_.mediaUpdate(key, updatedMedia))

  // -------------------------------------------------------------------------------------------------------------------
  def stateList(): ZStream[MediaService, ServiceStreamIssue, State]                             = ZStream.serviceWithStream(_.stateList())
  def stateGet(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[State]]          = ZIO.serviceWithZIO(_.stateGet(originalId))
  def stateDelete(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Unit]                = ZIO.serviceWithZIO(_.stateDelete(originalId))
  def stateUpsert(originalId: OriginalId, state: State): ZIO[MediaService, ServiceIssue, State] = ZIO.serviceWithZIO(_.stateUpsert(originalId, state))

  // -------------------------------------------------------------------------------------------------------------------
  def originalClassifications(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[OriginalClassifications]] = ZIO.serviceWithZIO(_.originalClassifications(originalId))
  def originalFaces(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[OriginalFaces]]                     = ZIO.serviceWithZIO(_.originalFaces(originalId))
  def originalFacesFeatures(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[OriginalFaceFeatures]]      = ZIO.serviceWithZIO(_.originalFacesFeatures(originalId))
  def facesDetectPreview(originalId: OriginalId, rotationDegrees: Int): ZIO[MediaService, ServiceIssue, List[BoundingBox]] = ZIO.serviceWithZIO(_.facesDetectPreview(originalId, rotationDegrees))
  def facesRemapForRotation(originalId: OriginalId, fromRotationDegrees: Int, toRotationDegrees: Int): ZIO[MediaService, ServiceIssue, Option[OriginalFaceFeatures]] =
    ZIO.serviceWithZIO(_.facesRemapForRotation(originalId, fromRotationDegrees, toRotationDegrees))
  def facesRecropForEffectiveRotation(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[OriginalFaceFeatures]]                                          =
    ZIO.serviceWithZIO(_.facesRecropForEffectiveRotation(originalId))
  def originalObjects(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[OriginalDetectedObjects]]         = ZIO.serviceWithZIO(_.originalObjects(originalId))
  def originalNormalized(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[OriginalNormalized]]           = ZIO.serviceWithZIO(_.originalNormalized(originalId))
  def originalMiniatures(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[OriginalMiniatures]]           = ZIO.serviceWithZIO(_.originalMiniatures(originalId))
  def originalMediaFeatures(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[OriginalMediaFeatures]]     = ZIO.serviceWithZIO(_.originalMediaFeatures(originalId))
  def mediaFeaturesRecompute(originalId: OriginalId): ZIO[MediaService, ServiceIssue, OriginalMediaFeatures]            = ZIO.serviceWithZIO(_.mediaFeaturesRecompute(originalId))
  def originalCaption(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[OriginalCaption]]                 = ZIO.serviceWithZIO(_.originalCaption(originalId))
  def originalCaptionRecompute(originalId: OriginalId): ZIO[MediaService, ServiceIssue, OriginalCaption]                = ZIO.serviceWithZIO(_.originalCaptionRecompute(originalId))
  def originalCaptionGet(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[OriginalCaption]]              = ZIO.serviceWithZIO(_.originalCaptionGet(originalId))
  def originalCaptionExists(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Boolean]                          = ZIO.serviceWithZIO(_.originalCaptionExists(originalId))
  def mediaCaptionGet(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[String]]                         = ZIO.serviceWithZIO(_.mediaCaptionGet(originalId))
  def placeResolve(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[Place]]                             = ZIO.serviceWithZIO(_.placeResolve(originalId))
  def placeRecompute(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[Place]]                           = ZIO.serviceWithZIO(_.placeRecompute(originalId))

  def originalFacesUpdate(originalId: OriginalId, facesIds: List[FaceId]): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.originalFacesUpdate(originalId, facesIds))

  // -------------------------------------------------------------------------------------------------------------------
  def faceList(): ZStream[MediaService, ServiceStreamIssue, Face]               = ZStream.serviceWithStream(_.faceList())
  def faceCount(): ZIO[MediaService, ServiceIssue, Long]                        = ZIO.serviceWithZIO(_.faceCount())
  def faceGet(faceId: FaceId): ZIO[MediaService, ServiceIssue, Option[Face]]    = ZIO.serviceWithZIO(_.faceGet(faceId))
  def faceExists(faceId: FaceId): ZIO[MediaService, ServiceIssue, Boolean]      = ZIO.serviceWithZIO(_.faceExists(faceId))
  def faceDelete(faceId: FaceId): ZIO[MediaService, ServiceIssue, Unit]         = ZIO.serviceWithZIO(_.faceDelete(faceId))
  def faceCreate(
    faceId: Option[FaceId],
    originalId: OriginalId,
    box: BoundingBox
  ): ZIO[MediaService, ServiceIssue, Face]                                      = ZIO.serviceWithZIO(_.faceCreate(faceId, originalId, box))
  def faceUpdate(
    faceId: FaceId, // current face id
    face: Face      // may contain and updated id
  ): ZIO[MediaService, ServiceIssue, Face]                                      = ZIO.serviceWithZIO(_.faceUpdate(faceId, face))
  def faceRead(faceId: FaceId): ZStream[MediaService, ServiceStreamIssue, Byte] = ZStream.serviceWithStream(_.faceRead(faceId))

  // -------------------------------------------------------------------------------------------------------------------
  def faceFeaturesList(): ZStream[MediaService, ServiceStreamIssue, FaceFeatures]            = ZStream.serviceWithStream(_.faceFeaturesList())
  def faceFeaturesGet(faceId: FaceId): ZIO[MediaService, ServiceIssue, Option[FaceFeatures]] = ZIO.serviceWithZIO(_.faceFeaturesGet(faceId))

  // -------------------------------------------------------------------------------------------------------------------
  def mediaFeaturesList(): ZStream[MediaService, ServiceStreamIssue, MediaFeatures]                       = ZStream.serviceWithStream(_.mediaFeaturesList())
  def mediaFeaturesGet(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[MediaFeatures]]    = ZIO.serviceWithZIO(_.mediaFeaturesGet(originalId))
  def mediaSimilar(originalId: OriginalId, count: Int): ZIO[MediaService, ServiceIssue, List[(OriginalId, Double)]] = ZIO.serviceWithZIO(_.mediaSimilar(originalId, count))

  def mediaClustersReplace(assignments: Iterable[(OriginalId, Int)]): ZIO[MediaService, ServiceIssue, Unit]  = ZIO.serviceWithZIO(_.mediaClustersReplace(assignments))
  def mediaClusterOf(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[Int]]                   = ZIO.serviceWithZIO(_.mediaClusterOf(originalId))
  def mediaClusterList(): ZIO[MediaService, ServiceIssue, List[(Int, Long)]]                                 = ZIO.serviceWithZIO(_.mediaClusterList())
  def mediaClusterMembers(clusterId: Int): ZStream[MediaService, ServiceStreamIssue, MediaTuple]             = ZStream.serviceWithStream(_.mediaClusterMembers(clusterId))

  // -------------------------------------------------------------------------------------------------------------------
  def personList(): ZStream[MediaService, ServiceStreamIssue, Person]                     = ZStream.serviceWithStream(_.personList())
  def personCount(): ZIO[MediaService, ServiceIssue, Long]                                = ZIO.serviceWithZIO(_.personCount())
  def personGet(personId: PersonId): ZIO[MediaService, ServiceIssue, Option[Person]]      = ZIO.serviceWithZIO(_.personGet(personId))
  def personExists(personId: PersonId): ZIO[MediaService, ServiceIssue, Boolean]          = ZIO.serviceWithZIO(_.personExists(personId))
  def personDelete(personId: PersonId): ZIO[MediaService, ServiceIssue, Unit]             = ZIO.serviceWithZIO(_.personDelete(personId))
  def personCreate(
    id: Option[PersonId],
    firstName: FirstName,
    lastName: LastName,
    birthName: Option[BirthName],
    birthDate: Option[BirthDate],
    email: Option[PersonEmail],
    description: Option[PersonDescription]
  ): ZIO[MediaService, ServiceIssue, Person]                                              = ZIO.serviceWithZIO(_.personCreate(id, firstName, lastName, birthName, birthDate, email, description))
  def personUpdate(
    personId: PersonId,
    firstName: FirstName,
    lastName: LastName,
    birthName: Option[BirthName],
    birthDate: Option[BirthDate],
    email: Option[PersonEmail],
    description: Option[PersonDescription],
    chosenFaceId: Option[FaceId]
  ): ZIO[MediaService, ServiceIssue, Option[Person]]                                      = ZIO.serviceWithZIO(_.personUpdate(personId, firstName, lastName, birthName, birthDate, email, description, chosenFaceId))
  def personFaceList(personId: PersonId): ZStream[MediaService, ServiceStreamIssue, Face] = ZStream.serviceWithStream(_.personFaceList(personId))

  // -------------------------------------------------------------------------------------------------------------------
  def originalList(): ZStream[MediaService, ServiceStreamIssue, Original]                    = ZStream.serviceWithStream(_.originalList())
  def originalCount(): ZIO[MediaService, ServiceIssue, Long]                                 = ZIO.serviceWithZIO(_.originalCount())
  def originalGet(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[Original]] = ZIO.serviceWithZIO(_.originalGet(originalId))
  def originalExists(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Boolean]       = ZIO.serviceWithZIO(_.originalExists(originalId))
  def originalDelete(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Unit]          = ZIO.serviceWithZIO(_.originalDelete(originalId))
  def originalUpsert(providedOriginal: Original): ZIO[MediaService, ServiceIssue, Original]  = ZIO.serviceWithZIO(_.originalUpsert(providedOriginal))

  // -------------------------------------------------------------------------------------------------------------------

  def mediaNormalizedRead(key: MediaAccessKey): ZStream[MediaService, ServiceStreamIssue, Byte] = ZStream.serviceWithStream(_.mediaNormalizedRead(key))

  def mediaOriginalRead(key: MediaAccessKey): ZStream[MediaService, ServiceStreamIssue, Byte] = ZStream.serviceWithStream(_.mediaOriginalRead(key))

  def mediaMiniatureRead(key: MediaAccessKey): ZStream[MediaService, ServiceStreamIssue, Byte] = ZStream.serviceWithStream(_.mediaMiniatureRead(key))

  // -------------------------------------------------------------------------------------------------------------------

  def bagList(): ZStream[MediaService, ServiceStreamIssue, Bag] = ZStream.serviceWithStream(_.bagList())

  def bagGet(bagId: BagId): ZIO[MediaService, ServiceIssue, Option[Bag]] = ZIO.serviceWithZIO(_.bagGet(bagId))

  def bagDelete(bagId: BagId): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.bagDelete(bagId))

  def bagUpdate(
    bagId: BagId,
    name: BagName,
    description: Option[BagDescription],
    location: Option[Location],
    timestamp: Option[ShootDateTime],
    coverOriginalId: Option[OriginalId],
    publishedOn: Option[URL],
    keywords: Set[Keyword]
  ): ZIO[MediaService, ServiceIssue, Option[Bag]] =
    ZIO.serviceWithZIO(_.bagUpdate(bagId, name, description, location, timestamp, coverOriginalId, publishedOn, keywords))

  // -------------------------------------------------------------------------------------------------------------------

  def portfolioList(): ZStream[MediaService, ServiceStreamIssue, Portfolio]                                = ZStream.serviceWithStream(_.portfolioList())
  def portfolioGet(portfolioId: PortfolioId): ZIO[MediaService, ServiceIssue, Option[Portfolio]]           = ZIO.serviceWithZIO(_.portfolioGet(portfolioId))
  def portfolioCreate(name: PortfolioName, description: Option[PortfolioDescription]): ZIO[MediaService, ServiceIssue, Portfolio] =
    ZIO.serviceWithZIO(_.portfolioCreate(name, description))
  def portfolioUpdate(portfolioId: PortfolioId, name: PortfolioName, description: Option[PortfolioDescription]): ZIO[MediaService, ServiceIssue, Option[Portfolio]] =
    ZIO.serviceWithZIO(_.portfolioUpdate(portfolioId, name, description))
  def portfolioDelete(portfolioId: PortfolioId): ZIO[MediaService, ServiceIssue, Unit]                     = ZIO.serviceWithZIO(_.portfolioDelete(portfolioId))
  def portfolioAssetAdd(portfolioId: PortfolioId, asset: Asset): ZIO[MediaService, ServiceIssue, Asset]      = ZIO.serviceWithZIO(_.portfolioAssetAdd(portfolioId, asset))
  def portfolioAssetUpdate(portfolioId: PortfolioId, oldAsset: Asset, newAsset: Asset): ZIO[MediaService, ServiceIssue, Option[Asset]] = ZIO.serviceWithZIO(_.portfolioAssetUpdate(portfolioId, oldAsset, newAsset))
  def portfolioAssetRemove(portfolioId: PortfolioId, asset: Asset): ZIO[MediaService, ServiceIssue, Boolean] = ZIO.serviceWithZIO(_.portfolioAssetRemove(portfolioId, asset))

  // -------------------------------------------------------------------------------------------------------------------

  def ownerList(): ZStream[MediaService, ServiceIssue, Owner] = ZStream.serviceWithStream(_.ownerList())

  def ownerGet(ownerId: OwnerId): ZIO[MediaService, ServiceIssue, Option[Owner]] = ZIO.serviceWithZIO(_.ownerGet(ownerId))

  def ownerDelete(ownerId: OwnerId): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.ownerDelete(ownerId))

  def ownerCreate(providedOwnerId: Option[OwnerId], firstName: FirstName, lastName: LastName, birthDate: Option[BirthDate]): ZIO[MediaService, ServiceIssue, Owner] = ZIO.serviceWithZIO(_.ownerCreate(providedOwnerId, firstName, lastName, birthDate))

  def ownerUpdate(ownerId: OwnerId, firstName: FirstName, lastName: LastName, birthDate: Option[BirthDate], coverOriginalId: Option[OriginalId]): ZIO[MediaService, ServiceIssue, Option[Owner]] =
    ZIO.serviceWithZIO(_.ownerUpdate(ownerId, firstName, lastName, birthDate, coverOriginalId))

  // -------------------------------------------------------------------------------------------------------------------

  def storeList(): ZStream[MediaService, ServiceIssue, Store] = ZStream.serviceWithStream(_.storeList())

  def storeGet(storeId: StoreId): ZIO[MediaService, ServiceIssue, Option[Store]] = ZIO.serviceWithZIO(_.storeGet(storeId))

  def storeDelete(storeId: StoreId): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.storeDelete(storeId))

  def storeCreate(providedStoreId: Option[StoreId], name: Option[StoreName], ownerId: OwnerId, baseDirectory: BaseDirectoryPath, includeMask: Option[IncludeMask], ignoreMask: Option[IgnoreMask]): ZIO[MediaService, ServiceIssue, Store] =
    ZIO.serviceWithZIO(_.storeCreate(providedStoreId, name, ownerId, baseDirectory, includeMask, ignoreMask))

  def storeUpdate(storeId: StoreId, name: Option[StoreName], baseDirectory: BaseDirectoryPath, includeMask: Option[IncludeMask], ignoreMask: Option[IgnoreMask]): ZIO[MediaService, ServiceIssue, Option[Store]] =
    ZIO.serviceWithZIO(_.storeUpdate(storeId, name, baseDirectory, includeMask, ignoreMask))

  // -------------------------------------------------------------------------------------------------------------------

  def synchronizeStart(addedThoseLastDays: Option[Int]): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.synchronizeStart(addedThoseLastDays))
  def synchronizeWait(): ZIO[MediaService, ServiceIssue, Unit]                                 = ZIO.serviceWithZIO(_.synchronizeWait())
  def synchronizeStop(): ZIO[MediaService, ServiceIssue, Unit]                                 = ZIO.serviceWithZIO(_.synchronizeStop())
  def synchronizeStatus(): ZIO[MediaService, ServiceIssue, SynchronizeStatus]                  = ZIO.serviceWithZIO(_.synchronizeStatus())
  def reindexAll(): ZIO[MediaService, ServiceIssue, Unit]                                      = ZIO.serviceWithZIO(_.reindexAll())
  def searchReindexAll(): ZIO[MediaService, ServiceIssue, Long]                                = ZIO.serviceWithZIO(_.searchReindexAll())
  def searchPublish(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Unit]             = ZIO.serviceWithZIO(_.searchPublish(originalId))

  // -------------------------------------------------------------------------------------------------------------------
  def keywordSentenceToKeywords(storeId: StoreId, sentence: String): ZIO[MediaService, ServiceIssue, Set[Keyword]] = ZIO.serviceWithZIO(_.keywordSentenceToKeywords(storeId, sentence))

  def keywordList(storeId: StoreId): ZIO[MediaService, ServiceIssue, Map[Keyword, Int]]        = ZIO.serviceWithZIO(_.keywordList(storeId))
  def keywordDelete(storeId: StoreId, keyword: Keyword): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.keywordDelete(storeId, keyword))

  def keywordRulesList(): ZIO[MediaService, ServiceIssue, Chunk[KeywordRules]]                         = ZIO.serviceWithZIO(_.keywordRulesList())
  def keywordRulesGet(storeId: StoreId): ZIO[MediaService, ServiceIssue, Option[KeywordRules]]         = ZIO.serviceWithZIO(_.keywordRulesGet(storeId))
  def keywordRulesUpsert(storeId: StoreId, rules: KeywordRules): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.keywordRulesUpsert(storeId, rules))
  def keywordRulesDelete(storeId: StoreId): ZIO[MediaService, ServiceIssue, Unit]                      = ZIO.serviceWithZIO(_.keywordRulesDelete(storeId))

}
