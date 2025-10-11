package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.model.KeywordRules
import fr.janalyse.sotohp.service.model.SynchronizeAction.{Start, WaitForCompletion}
import wvlet.airframe.ulid.ULID
import zio.*
import zio.lmdb.LMDB
import zio.test.*

import java.nio.file.Path
import java.time.OffsetDateTime

object MediaServiceSynchronizeTest extends BaseSpecDefault {

  def suiteSynchronization = suite("Synchronize")(
    test("standard scenario") {
      for {
        epoch          <- Clock.currentDateTime           // Virtual Clock so == epoch
        owner          <- MediaService.ownerCreate(None, FirstName("John"), LastName("Doe"), None)
        store          <- MediaService.storeCreate(None, None, owner.id, BaseDirectoryPath(Path.of("samples/dataset3")), None, None)
        _              <- MediaService.keywordRulesUpsert(
                            store.id,
                            KeywordRules(
                              ignoring = Set("la", "dans", "le", "et", "en"),
                              mappings = Nil,
                              rewritings = Nil
                            )
                          )
        _              <- MediaService.synchronizeStart(None) // ------ FIRST SYNC
        _              <- MediaService.synchronizeWait()
        originals      <- MediaService.originalList().runCollect
        count          <- MediaService.originalCount()
        events         <- MediaService.eventList().runCollect
        states         <- MediaService.stateList().runCollect
        medias         <- MediaService.mediaList().runCollect
        _              <- TestClock.adjust(1.hour)
        _              <- MediaService.synchronizeStart(None) // ------ SECOND SYNC
        _              <- MediaService.synchronizeWait()
        originalsAgain <- MediaService.originalList().runCollect
        eventsAgain    <- MediaService.eventList().runCollect
        statesAgain    <- MediaService.stateList().runCollect
        mediasAgain    <- MediaService.mediaList().runCollect
        keywords       <- MediaService.keywordList(store.id)
      } yield assertTrue(
        originals.size == 13,
        count == originals.size,
        originals.forall(_.cameraShootDateTime.isDefined),
        originals.forall(_.cameraName.isDefined),
        originals.exists(_.artistInfo.isDefined), // available only if it has been configured on the camera
        originals.forall(_.dimension.isDefined),
        originals.forall(_.orientation.isDefined),
        originals.exists(_.location.isDefined),   // available only if gps info has been configured or was available
        originals.forall(_.aperture.isDefined),
        originals.forall(_.exposureTime.isDefined),
        originals.forall(_.iso.isDefined),
        originals.forall(_.focalLength.isDefined),
        events.size == 6,
        states.size == 13,
        medias.size == 13,
        medias.filter(_.events.nonEmpty).size == 12,
        originals == originalsAgain,
        events == eventsAgain,
        states != statesAgain,                    // originalLastChecked should differ :
        states == statesAgain.map(_.copy(originalLastChecked = LastChecked(epoch))),
        mediasAgain == medias,
        keywords.size == 19
      )
    } @@ TestAspect.ignore
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    (suiteSynchronization)
      .provideShared(
        LMDB.liveWithDatabaseName(s"sotohp-db-for-unit-tests-${getClass.getCanonicalName}-${ULID.newULID}") >>> MediaService.live,
        configProvider >>> SearchService.live,
        Scope.default
      )
      @@ TestAspect.sequential

}
