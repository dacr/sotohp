package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.media.model.*
import wvlet.airframe.ulid.ULID
import zio.*
import zio.lmdb.LMDB
import zio.test.*

import java.nio.file.Path

object MediaServiceTest extends BaseSpecDefault {

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
        storesFetched <- MediaService.storeList()
        _             <- ZIO.foreach(storesFetched)(store => MediaService.storeDelete(store.id))
      } yield assertTrue(
        storesFetched.size == 3
      )
    )
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suiteStores.provideShared(LMDB.liveWithDatabaseName("sotohp-unit-test-db") >>> MediaService.live, Scope.default)

}
