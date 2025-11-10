package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.core.CoreIssue
import zio.*
import zio.stream.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.processor.model.{DetectedFace, FaceFeatures, FaceId, OriginalClassifications, OriginalDetectedObjects, OriginalFaceFeatures, OriginalFaces, OriginalMiniatures, OriginalNormalized, Person, PersonDescription, PersonId}
import fr.janalyse.sotohp.service.model.{KeywordRules, SynchronizeAction, SynchronizeStatus}
import zio.lmdb.LMDB

import java.net.URL
import java.time.OffsetDateTime
import java.util.regex.Pattern

trait MediaService {

  // -------------------------------------------------------------------------------------------------------------------
  def mediaFind(nearKey: MediaAccessKey): IO[ServiceIssue, Option[Media]]
  def mediaSearch(keywordsFilter: Set[Keyword]): Stream[ServiceStreamIssue, Media]

  def mediaList(): Stream[ServiceStreamIssue, Media]
  def mediaFirst(): IO[ServiceIssue, Option[Media]]
  def mediaPrevious(nearKey: MediaAccessKey): IO[ServiceIssue, Option[Media]]
  def mediaNext(nearKey: MediaAccessKey): IO[ServiceIssue, Option[Media]]
  def mediaLast(): IO[ServiceIssue, Option[Media]]
  def mediaGet(key: MediaAccessKey): IO[ServiceIssue, Option[Media]]
  def mediaGetAt(index: Long): IO[ServiceIssue, Option[Media]]

  def mediaUpdate(
    key: MediaAccessKey, // current media key
    updatedMedia: Media // can contain the new media key to use
  ): IO[ServiceIssue, Option[Media]]

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
  def originalObjects(originalId: OriginalId): IO[ServiceIssue, Option[OriginalDetectedObjects]]
  def originalNormalized(originalId: OriginalId): IO[ServiceIssue, Option[OriginalNormalized]]
  def originalMiniatures(originalId: OriginalId): IO[ServiceIssue, Option[OriginalMiniatures]]

  def originalFacesUpdate(originalId: OriginalId, facesIds: List[FaceId]): IO[ServiceIssue, Unit]

  // -------------------------------------------------------------------------------------------------------------------
  def faceList(): Stream[ServiceStreamIssue, DetectedFace]
  def faceCount(): IO[ServiceIssue, Long]
  def faceGet(faceId: FaceId): IO[ServiceIssue, Option[DetectedFace]]
  def faceExists(faceId: FaceId): IO[ServiceIssue, Boolean]
  def faceDelete(faceId: FaceId): IO[ServiceIssue, Unit]
  def faceUpdate(
    faceId: FaceId, // current face id
    face: DetectedFace // may contain and updated id
  ): IO[ServiceIssue, DetectedFace]
  def faceRead(faceId: FaceId): Stream[ServiceStreamIssue, Byte]

  // -------------------------------------------------------------------------------------------------------------------
  def faceFeaturesList(): Stream[ServiceStreamIssue, FaceFeatures]
  def faceFeaturesGet(faceId: FaceId): IO[ServiceIssue, Option[FaceFeatures]]

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
    birthDate: Option[BirthDate],
    description: Option[PersonDescription]
  ): IO[ServiceIssue, Person]
  def personUpdate(
    personId: PersonId,
    firstName: FirstName,
    lastName: LastName,
    birthDate: Option[BirthDate],
    description: Option[PersonDescription],
    chosenFaceId: Option[FaceId]
  ): IO[ServiceIssue, Option[Person]]
  def personFaceList(personId: PersonId): Stream[ServiceStreamIssue, DetectedFace]

  // -------------------------------------------------------------------------------------------------------------------
  def originalList(): Stream[ServiceStreamIssue, Original]
  def originalCount(): IO[ServiceIssue, Long]
  def originalGet(originalId: OriginalId): IO[ServiceIssue, Option[Original]]
  def originalExists(originalId: OriginalId): IO[ServiceIssue, Boolean]
  def originalDelete(originalId: OriginalId): IO[ServiceIssue, Unit]
  def originalUpsert(providedOriginal: Original): IO[ServiceIssue, Original]

  // -------------------------------------------------------------------------------------------------------------------
  def eventList(): Stream[ServiceStreamIssue, Event]
  def eventGet(eventId: EventId): IO[ServiceIssue, Option[Event]]
  def eventDelete(eventId: EventId): IO[ServiceIssue, Unit]
  def eventCreate(
    attachment: Option[EventAttachment],
    name: EventName,
    description: Option[EventDescription],
    keywords: Set[Keyword],
    location: Option[Location],
    timestamp: Option[ShootDateTime],
    originalId: Option[OriginalId]
  ): IO[ServiceIssue, Event]
  def eventUpdate(
    eventId: EventId,
    name: EventName,
    description: Option[EventDescription],
    location: Option[Location],
    timestamp: Option[ShootDateTime],
    coverOriginalId: Option[OriginalId],
    publishedOn: Option[URL],
    keywords: Set[Keyword]
  ): IO[ServiceIssue, Option[Event]]

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

  def mediaFind(nearKey: MediaAccessKey): ZIO[MediaService, ServiceIssue, Option[Media]] = ZIO.serviceWithZIO(_.mediaFind(nearKey))

  def mediaSearch(keywordsFilter: Set[Keyword]): ZStream[MediaService, ServiceStreamIssue, Media] = ZStream.serviceWithStream(_.mediaSearch(keywordsFilter))

  def mediaList(): ZStream[MediaService, ServiceStreamIssue, Media] = ZStream.serviceWithStream(_.mediaList())

  def mediaFirst(): ZIO[MediaService, ServiceIssue, Option[Media]] = ZIO.serviceWithZIO(_.mediaFirst())

  def mediaPrevious(nearKey: MediaAccessKey): ZIO[MediaService, ServiceIssue, Option[Media]] = ZIO.serviceWithZIO(_.mediaPrevious(nearKey))

  def mediaNext(nearKey: MediaAccessKey): ZIO[MediaService, ServiceIssue, Option[Media]] = ZIO.serviceWithZIO(_.mediaNext(nearKey))

  def mediaLast(): ZIO[MediaService, ServiceIssue, Option[Media]] = ZIO.serviceWithZIO(_.mediaLast())

  def mediaGet(key: MediaAccessKey): ZIO[MediaService, ServiceIssue, Option[Media]] = ZIO.serviceWithZIO(_.mediaGet(key))

  def mediaGetAt(index: Long): ZIO[MediaService, ServiceIssue, Option[Media]] = ZIO.serviceWithZIO(_.mediaGetAt(index))

  def mediaUpdate(
    key: MediaAccessKey,
    updatedMedia: Media
  ): ZIO[MediaService, ServiceIssue, Option[Media]] =
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
  def originalObjects(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[OriginalDetectedObjects]]         = ZIO.serviceWithZIO(_.originalObjects(originalId))
  def originalNormalized(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[OriginalNormalized]]           = ZIO.serviceWithZIO(_.originalNormalized(originalId))
  def originalMiniatures(originalId: OriginalId): ZIO[MediaService, ServiceIssue, Option[OriginalMiniatures]]           = ZIO.serviceWithZIO(_.originalMiniatures(originalId))

  def originalFacesUpdate(originalId: OriginalId, facesIds: List[FaceId]): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.originalFacesUpdate(originalId, facesIds))

  // -------------------------------------------------------------------------------------------------------------------
  def faceList(): ZStream[MediaService, ServiceStreamIssue, DetectedFace]            = ZStream.serviceWithStream(_.faceList())
  def faceCount(): ZIO[MediaService, ServiceIssue, Long]                             = ZIO.serviceWithZIO(_.faceCount())
  def faceGet(faceId: FaceId): ZIO[MediaService, ServiceIssue, Option[DetectedFace]] = ZIO.serviceWithZIO(_.faceGet(faceId))
  def faceExists(faceId: FaceId): ZIO[MediaService, ServiceIssue, Boolean]           = ZIO.serviceWithZIO(_.faceExists(faceId))
  def faceDelete(faceId: FaceId): ZIO[MediaService, ServiceIssue, Unit]              = ZIO.serviceWithZIO(_.faceDelete(faceId))
  def faceUpdate(
    faceId: FaceId, // current face id
    face: DetectedFace // may contain and updated id
  ): ZIO[MediaService, ServiceIssue, DetectedFace] = ZIO.serviceWithZIO(_.faceUpdate(faceId, face))
  def faceRead(faceId: FaceId): ZStream[MediaService, ServiceStreamIssue, Byte]      = ZStream.serviceWithStream(_.faceRead(faceId))

  // -------------------------------------------------------------------------------------------------------------------
  def faceFeaturesList(): ZStream[MediaService, ServiceStreamIssue, FaceFeatures]            = ZStream.serviceWithStream(_.faceFeaturesList())
  def faceFeaturesGet(faceId: FaceId): ZIO[MediaService, ServiceIssue, Option[FaceFeatures]] = ZIO.serviceWithZIO(_.faceFeaturesGet(faceId))

  // -------------------------------------------------------------------------------------------------------------------
  def personList(): ZStream[MediaService, ServiceStreamIssue, Person]                             = ZStream.serviceWithStream(_.personList())
  def personCount(): ZIO[MediaService, ServiceIssue, Long]                                        = ZIO.serviceWithZIO(_.personCount())
  def personGet(personId: PersonId): ZIO[MediaService, ServiceIssue, Option[Person]]              = ZIO.serviceWithZIO(_.personGet(personId))
  def personExists(personId: PersonId): ZIO[MediaService, ServiceIssue, Boolean]                  = ZIO.serviceWithZIO(_.personExists(personId))
  def personDelete(personId: PersonId): ZIO[MediaService, ServiceIssue, Unit]                     = ZIO.serviceWithZIO(_.personDelete(personId))
  def personCreate(
    id: Option[PersonId],
    firstName: FirstName,
    lastName: LastName,
    birthDate: Option[BirthDate],
    description: Option[PersonDescription]
  ): ZIO[MediaService, ServiceIssue, Person] = ZIO.serviceWithZIO(_.personCreate(id, firstName, lastName, birthDate, description))
  def personUpdate(
    personId: PersonId,
    firstName: FirstName,
    lastName: LastName,
    birthDate: Option[BirthDate],
    description: Option[PersonDescription],
    chosenFaceId: Option[FaceId]
  ): ZIO[MediaService, ServiceIssue, Option[Person]] = ZIO.serviceWithZIO(_.personUpdate(personId, firstName, lastName, birthDate, description, chosenFaceId))
  def personFaceList(personId: PersonId): ZStream[MediaService, ServiceStreamIssue, DetectedFace] = ZStream.serviceWithStream(_.personFaceList(personId))

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

  def eventList(): ZStream[MediaService, ServiceStreamIssue, Event] = ZStream.serviceWithStream(_.eventList())

  def eventGet(eventId: EventId): ZIO[MediaService, ServiceIssue, Option[Event]] = ZIO.serviceWithZIO(_.eventGet(eventId))

  def eventDelete(eventId: EventId): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.eventDelete(eventId))

  def eventCreate(
    attachment: Option[EventAttachment],
    name: EventName,
    description: Option[EventDescription],
    keywords: Set[Keyword],
    location: Option[Location],
    timestamp: Option[ShootDateTime],
    originalId: Option[OriginalId]
  ): ZIO[MediaService, ServiceIssue, Event] =
    ZIO.serviceWithZIO(_.eventCreate(attachment, name, description, keywords, location, timestamp, originalId))

  def eventUpdate(
    eventId: EventId,
    name: EventName,
    description: Option[EventDescription],
    location: Option[Location],
    timestamp: Option[ShootDateTime],
    coverOriginalId: Option[OriginalId],
    publishedOn: Option[URL],
    keywords: Set[Keyword]
  ): ZIO[MediaService, ServiceIssue, Option[Event]] =
    ZIO.serviceWithZIO(_.eventUpdate(eventId, name, description, location, timestamp, coverOriginalId, publishedOn, keywords))

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

  // -------------------------------------------------------------------------------------------------------------------
  def keywordSentenceToKeywords(storeId: StoreId, sentence: String): ZIO[MediaService, ServiceIssue, Set[Keyword]] = ZIO.serviceWithZIO(_.keywordSentenceToKeywords(storeId, sentence))

  def keywordList(storeId: StoreId): ZIO[MediaService, ServiceIssue, Map[Keyword, Int]]        = ZIO.serviceWithZIO(_.keywordList(storeId))
  def keywordDelete(storeId: StoreId, keyword: Keyword): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.keywordDelete(storeId, keyword))

  def keywordRulesList(): ZIO[MediaService, ServiceIssue, Chunk[KeywordRules]]                         = ZIO.serviceWithZIO(_.keywordRulesList())
  def keywordRulesGet(storeId: StoreId): ZIO[MediaService, ServiceIssue, Option[KeywordRules]]         = ZIO.serviceWithZIO(_.keywordRulesGet(storeId))
  def keywordRulesUpsert(storeId: StoreId, rules: KeywordRules): ZIO[MediaService, ServiceIssue, Unit] = ZIO.serviceWithZIO(_.keywordRulesUpsert(storeId, rules))
  def keywordRulesDelete(storeId: StoreId): ZIO[MediaService, ServiceIssue, Unit]                      = ZIO.serviceWithZIO(_.keywordRulesDelete(storeId))

}
