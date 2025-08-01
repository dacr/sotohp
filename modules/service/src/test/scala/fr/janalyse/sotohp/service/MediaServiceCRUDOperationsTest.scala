package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.model.{KeywordRules, Rewriting}
import wvlet.airframe.ulid.ULID
import zio.*
import zio.lmdb.LMDB
import zio.test.*

import java.nio.file.Path

object MediaServiceCRUDOperationsTest extends BaseSpecDefault {

  def suiteEvents = suite("Events")(
    test("event create read update delete")(
      for {
        eventCreated <- MediaService.eventCreate(None, EventName("test-event"), None, Set.empty)
        eventFetched <- MediaService.eventGet(eventCreated.id)
        eventUpdated <- MediaService
                          .eventUpdate(
                            eventId = eventCreated.id,
                            attachment = None,
                            name = EventName("updated-event"),
                            description = Some(EventDescription("hello")),
                            keywords = Set.empty
                          )
                          .some
        _            <- MediaService.eventDelete(eventCreated.id)
        afterDelete  <- MediaService.eventGet(eventCreated.id)
      } yield assertTrue(
        eventCreated.name == EventName("test-event"),
        eventFetched.contains(eventCreated),
        eventUpdated.name == EventName("updated-event"),
        eventUpdated.description.contains("hello"),
        afterDelete.isEmpty
      )
    ),
    test("list events") {
      val eventNames = List("event1", "event2", "event3")
      for {
        createdEvents <- ZIO.foreach(eventNames)(name => MediaService.eventCreate(None, EventName(name), None, Set.empty))
        eventsFetched <- MediaService.eventList().runCollect
        _             <- ZIO.foreachDiscard(eventsFetched)(event => MediaService.eventDelete(event.id))
      } yield assertTrue(
        createdEvents.size == 3,
        eventsFetched.size == 3
      )
    }
  )

  def suiteOwners = suite("Owners")(
    test("owner create read update delete")(
      for {
        ownerCreated <- MediaService.ownerCreate(None, FirstName("tested-first-name"), LastName("tested-last-name"), None)
        ownerFetched <- MediaService.ownerGet(ownerCreated.id)
        ownerUpdated <- MediaService.ownerUpdate(ownerId = ownerCreated.id, firstName = FirstName("updated-first-name"), lastName = LastName("updated-last-name"), birthDate = None).some
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
        storeCreated <- MediaService.storeCreate(None, fakeOwnerId, BaseDirectoryPath(Path.of("samples/dataset3")), None, None)
        storeFetched <- MediaService.storeGet(storeCreated.id)
        storeUpdated <- MediaService.storeUpdate(storeId = storeCreated.id, includeMask = Some(IncludeMask(".*".r)), ignoreMask = storeCreated.ignoreMask).some
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
        paths          = List("samples/dataset1", "samples/dataset2", "samples/dataset3").map(dir => BaseDirectoryPath(Path.of(dir)))
        createdStores <- ZIO.foreach(paths)(path => MediaService.storeCreate(None, fakeOwnerId, path, None, None))
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
        store        <- MediaService.storeCreate(None, owner.id, BaseDirectoryPath(Path.of("samples/dataset1")), None, None)
        rules         = KeywordRules(ignoring = Set.empty, mappings = Map.empty, rewritings = Nil)
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
        owner       <- MediaService.ownerCreate(None, FirstName("John"), LastName("Doe"), None)
        store       <- MediaService.storeCreate(None, owner.id, BaseDirectoryPath(Path.of("samples/dataset1")), None, None)
        _           <- MediaService.keywordRulesUpsert(
                         store.id,
                         KeywordRules(ignoring = Set("with", "i", "am"), mappings = Map("nigght" -> "night"), rewritings = Rewriting("(42)(thing)".r, "$2$1") :: Nil)
                       )
        result1 <- MediaService.keywordSentenceToKeywords(store.id, "I am with nigght 42thing")
      } yield assertTrue(
        result1 == Set("night", "thing42").map(Keyword.apply)
      )
    )
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    (suiteStores + suiteOwners + suiteEvents + suiteKeywords)
      .provideShared(LMDB.liveWithDatabaseName(s"sotohp-db-for-unit-tests-${getClass.getCanonicalName}-${ULID.newULID}") >>> MediaService.live, Scope.default)
      @@ TestAspect.sequential

}
