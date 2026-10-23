package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.model.{KeywordRules, SynchronizeOutcome, SynchronizePhase, SynchronizeStatus}
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
        epoch          <- Clock.currentDateTime               // Virtual Clock so == epoch
        owner          <- MediaService.ownerCreate(None, FirstName("John"), LastName("Doe"), None)
        testSamples     = scala.util.Properties.envOrElse("PHOTOS_TEST_SAMPLES", "samples")
        store          <- MediaService.storeCreate(None, None, owner.id, BaseDirectoryPath(Path.of(testSamples, "dataset3")), None, None)
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
        firstStatus    <- MediaService.synchronizeStatus()
        originals      <- MediaService.originalList().runCollect
        count          <- MediaService.originalCount()
        bags           <- MediaService.bagList().runCollect
        states         <- MediaService.stateList().runCollect
        medias         <- MediaService.mediaList().runCollect
        _              <- TestClock.adjust(1.hour)
        _              <- MediaService.synchronizeStart(None) // ------ SECOND SYNC
        _              <- MediaService.synchronizeWait()
        secondStatus   <- MediaService.synchronizeStatus()
        originalsAgain <- MediaService.originalList().runCollect
        bagsAgain      <- MediaService.bagList().runCollect
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
        bags.size == 6,
        states.size == 13,
        medias.size == 13,
        medias.filter(_.media.bag.isDefined).size == 12,
        originals == originalsAgain,
        bags == bagsAgain,
        states != statesAgain,                    // originalLastChecked should differ :
        states == statesAgain.map(_.copy(originalLastChecked = LastChecked(epoch))),
        mediasAgain == medias,
        keywords.size == 19,
        !firstStatus.running,
        firstStatus.phase == SynchronizePhase.Idle,
        firstStatus.lastRun.map(_.outcome).contains(SynchronizeOutcome.Completed),
        firstStatus.checkedCount == 13,
        firstStatus.toProcessCount == 13,
        firstStatus.processedCount == 13,
        firstStatus.indexedCount == 13,
        firstStatus.unreadableCount == 0,
        secondStatus.lastRun.map(_.outcome).contains(SynchronizeOutcome.Completed),
        secondStatus.checkedCount == 13,
        secondStatus.toProcessCount == 0, // everything already synchronized
        secondStatus.processedCount == 0
      )
    },
    test("remaining time estimation") {
      val processing = SynchronizeStatus.empty.copy(running = true, phase = SynchronizePhase.Processing, toProcessCount = 10, processedCount = 2)
      assertTrue(
        SynchronizeStatus.estimateRemainingSeconds(processing, Seq(0L), 1_000L).isEmpty,                                     // nothing completed yet
        SynchronizeStatus.estimateRemainingSeconds(processing, Seq(0L, 2_000L, 4_000L), 4_000L).contains(16L),             // 8 left at 2s each
        SynchronizeStatus.estimateRemainingSeconds(processing, Seq(0L, 2_000L, 4_000L), 5_000L).contains(15L),             // the next one is half done
        SynchronizeStatus.estimateRemainingSeconds(processing, Seq(0L, 2_000L, 4_000L), 30_000L).exists(_ > 16L),          // stalled : grows
        SynchronizeStatus.estimateRemainingSeconds(processing.copy(phase = SynchronizePhase.Scanning), Seq(0L, 2_000L), 2_000L).isEmpty
      )
    }
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
