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
        owner     <- MediaService.ownerCreate(None, FirstName("John"), LastName("Doe"), None)
        store     <- MediaService.storeCreate(None, owner.id, BaseDirectoryPath(Path.of("samples/dataset3")), None, None)
        _         <- MediaService.synchronize()
        originals <- MediaService.originalList().runCollect
      } yield assertTrue(
        originals.size == 12
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
