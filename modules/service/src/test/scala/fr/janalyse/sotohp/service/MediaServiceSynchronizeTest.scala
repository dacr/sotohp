package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.media.model.*
import wvlet.airframe.ulid.ULID
import zio.*
import zio.lmdb.LMDB
import zio.test.*

import java.nio.file.Path

object MediaServiceSynchronizeTest extends BaseSpecDefault {

  def suiteSynchronization = suite("Synchronize")(
    test("standard scenario") {
      for {
        owner          <- MediaService.ownerCreate(None, FirstName("John"), LastName("Doe"), None)
        store          <- MediaService.storeCreate(None, owner.id, BaseDirectoryPath(Path.of("samples/dataset3")), None, None)
        _              <- MediaService.synchronize()
        originals      <- MediaService.originalList().runCollect
        events         <- MediaService.eventList().runCollect
        states         <- MediaService.stateList().runCollect
        medias         <- MediaService.mediaList().runCollect
        _              <- TestClock.adjust(1.hour)
        _              <- MediaService.synchronize()
        originalsAgain <- MediaService.originalList().runCollect
        eventsAgain    <- MediaService.eventList().runCollect
        statesAgain    <- MediaService.stateList().runCollect
        mediasAgain    <- MediaService.mediaList().runCollect
      } yield assertTrue(
        originals.size == 13,
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
        medias.filter(_.event.nonEmpty).size == 12,
        originalsAgain == originals,
        eventsAgain == events,
        statesAgain != states,                    // originalLastChecked should differ
        mediasAgain == medias
      )
    }
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    (suiteSynchronization)
      .provideShared(
        LMDB.liveWithDatabaseName(s"sotohp-db-for-unit-tests-${getClass.getCanonicalName}-${java.util.UUID.randomUUID()}") >>> MediaService.live,
        Scope.default
      )
      @@ TestAspect.sequential

}
