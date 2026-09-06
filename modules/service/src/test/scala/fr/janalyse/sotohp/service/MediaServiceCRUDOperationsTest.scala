package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.model.{KeywordRules, Mapping, Rewriting}
import wvlet.airframe.ulid.ULID
import zio.*
import zio.lmdb.LMDB
import zio.test.*

import java.nio.file.Path

object MediaServiceCRUDOperationsTest extends BaseSpecDefault {

  def suiteOwners = suite("Owners")(
    test("owner create read update delete")(
      for {
        ownerCreated <- MediaService.ownerCreate(None, FirstName("tested-first-name"), LastName("tested-last-name"), None)
        ownerFetched <- MediaService.ownerGet(ownerCreated.id)
        ownerUpdated <- MediaService.ownerUpdate(ownerId = ownerCreated.id, firstName = FirstName("updated-first-name"), lastName = LastName("updated-last-name"), birthDate = None, coverOriginalId = None).some
        _            <- MediaService.ownerDelete(ownerCreated.id)
        afterDelete  <- MediaService.ownerGet(ownerCreated.id)
      } yield assertTrue(
        ownerCreated.firstName == FirstName("tested-first-name"),
        ownerFetched.contains(ownerCreated),
        ownerUpdated.lastName == LastName("updated-last-name"),
        afterDelete.isEmpty
      )
    ),
    test("list owners") {
      val lastNames = List("doe1", "doe2", "doe3")
      for {
        createdOwners <- ZIO.foreach(lastNames)(name => MediaService.ownerCreate(None, FirstName("joe"), LastName(name), None))
        ownersFetched <- MediaService.ownerList().runCollect
        _             <- ZIO.foreachDiscard(ownersFetched)(owner => MediaService.ownerDelete(owner.id))
      } yield assertTrue(
        ownersFetched.size == 3
      )
    }
  )

  def suiteStores = suite("Stores")(
    test("store create read update delete")(
      for {
        fakeOwnerId  <- ZIO.attempt(OwnerId(ULID.newULID))
        testSamples     = scala.util.Properties.envOrElse("PHOTOS_TEST_SAMPLES", "samples")
        storeCreated <- MediaService.storeCreate(None, None, fakeOwnerId, BaseDirectoryPath(Path.of(testSamples, "dataset3")), None, None)
        storeFetched <- MediaService.storeGet(storeCreated.id)
        storeUpdated <- MediaService
                          .storeUpdate(
                            storeId = storeCreated.id,
                            name = None,
                            baseDirectory = storeCreated.baseDirectory,
                            includeMask = Some(IncludeMask(".*".r)),
                            ignoreMask = storeCreated.ignoreMask
                          )
                          .some
        _            <- MediaService.storeDelete(storeCreated.id)
        afterDelete  <- MediaService.storeGet(storeCreated.id)
      } yield assertTrue(
        storeCreated.ownerId == fakeOwnerId,
        storeFetched.contains(storeCreated),
        storeUpdated.includeMask.isDefined,
        afterDelete.isEmpty
      )
    ),
    test("list stores")(
      for {
        fakeOwnerId   <- ZIO.attempt(OwnerId(ULID.newULID))
        testSamples     = scala.util.Properties.envOrElse("PHOTOS_TEST_SAMPLES", "samples")
        paths          = List("dataset1", "dataset2", "dataset3").map(dir => BaseDirectoryPath(Path.of(testSamples, dir)))
        createdStores <- ZIO.foreach(paths)(path => MediaService.storeCreate(None, None, fakeOwnerId, path, None, None))
        storesFetched <- MediaService.storeList().runCollect
        _             <- ZIO.foreachDiscard(storesFetched)(store => MediaService.storeDelete(store.id))
      } yield assertTrue(
        storesFetched.size == 3
      )
    )
  )

  def suiteKeywords = suite("keywords")(
    test("keyword rules create read update delete")(
      for {
        owner        <- MediaService.ownerCreate(None, FirstName("John"), LastName("Doe"), None)
        testSamples     = scala.util.Properties.envOrElse("PHOTOS_TEST_SAMPLES", "samples")
        store        <- MediaService.storeCreate(None, None, owner.id, BaseDirectoryPath(Path.of(testSamples, "dataset1")), None, None)
        rules         = KeywordRules(ignoring = Set.empty, mappings = Nil, rewritings = Nil)
        _            <- MediaService.keywordRulesUpsert(store.id, rules)
        rulesFetched <- MediaService.keywordRulesGet(store.id).some
        _            <- MediaService.keywordRulesUpsert(store.id, rules.copy(ignoring = Set("with")))
        rulesUpdated <- MediaService.keywordRulesGet(store.id).some
        _            <- MediaService.keywordRulesDelete(store.id)
      } yield assertTrue(
        rulesFetched.ignoring.isEmpty,
        rulesUpdated.ignoring.size == 1
      )
    ),
    test("keyword rules usage")(
      for {
        owner   <- MediaService.ownerCreate(None, FirstName("John"), LastName("Doe"), None)
        testSamples     = scala.util.Properties.envOrElse("PHOTOS_TEST_SAMPLES", "samples")
        store   <- MediaService.storeCreate(None, None, owner.id, BaseDirectoryPath(Path.of(testSamples, "dataset1")), None, None)
        _       <- MediaService.keywordRulesUpsert(
                     store.id,
                     KeywordRules(ignoring = Set("with", "i", "am"), mappings = Mapping("nigght", "night") :: Nil, rewritings = Rewriting("(42)(thing)", "$2$1") :: Nil)
                   )
        result1 <- MediaService.keywordSentenceToKeywords(store.id, "I am with nigght 42thing")
      } yield assertTrue(
        result1 == Set("night", "thing42").map(Keyword.apply)
      )
    )
  )

  def suiteFaces = {
    val referenceDateTime = java.time.OffsetDateTime.parse("2024-01-01T10:00:00Z")

    def fakeFace(identifiedPersonId: Option[PersonId], inferredIdentifiedPersonId: Option[PersonId]) = Face(
      faceId = FaceId(ULID.newULID),
      originalId = OriginalId(java.util.UUID.randomUUID()),
      box = BoundingBox(XAxis(0.1d), YAxis(0.1d), BoxWidth(0.2d), BoxHeight(0.2d)),
      identifiedPersonId = identifiedPersonId,
      inferredIdentifiedPersonId = inferredIdentifiedPersonId,
      inferredIdentifiedPersonConfidence = Some(0.9d),
      inferredTimestamp = Some(referenceDateTime),
      inferredIgnore = Some(true),
      timestamp = referenceDateTime,
      path = FacePath(Path.of("faces", s"${ULID.newULID}.jpg"))
    )

    suite("Faces")(
      test("identifying a face drops all its inferred fields")(
        for {
          personId  <- ZIO.attempt(PersonId(ULID.newULID))
          inferred   = fakeFace(identifiedPersonId = None, inferredIdentifiedPersonId = Some(personId))
          _         <- MediaService.faceUpdate(inferred.faceId, inferred)
          stored    <- MediaService.faceGet(inferred.faceId).some
          _         <- MediaService.faceUpdate(inferred.faceId, inferred.copy(identifiedPersonId = Some(personId)))
          confirmed <- MediaService.faceGet(inferred.faceId).some
        } yield assertTrue(
          stored.inferredIdentifiedPersonId.contains(personId),
          stored.inferredIgnore.contains(true),
          confirmed.identifiedPersonId.contains(personId),
          !confirmed.hasInferredIdentification
        )
      ),
      test("an unidentified face keeps its inferred fields")(
        for {
          personId <- ZIO.attempt(PersonId(ULID.newULID))
          inferred  = fakeFace(identifiedPersonId = None, inferredIdentifiedPersonId = Some(personId))
          _        <- MediaService.faceUpdate(inferred.faceId, inferred)
          stored   <- MediaService.faceGet(inferred.faceId).some
        } yield assertTrue(
          stored.inferredIdentifiedPersonId.contains(personId),
          stored.inferredIdentifiedPersonConfidence.contains(0.9d),
          stored.inferredTimestamp.contains(referenceDateTime)
        )
      )
    )
  }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    (suiteStores + suiteOwners + suiteKeywords + suiteFaces)
      .provideShared(
        LMDB.liveWithDatabaseName(s"sotohp-db-for-unit-tests-${getClass.getCanonicalName}-${ULID.newULID}") >>> MediaService.live,
        configProvider >>> SearchService.live,
        Scope.default
      )
      @@ TestAspect.sequential

}
