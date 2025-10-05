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

object MediaServiceNavigationTest extends BaseSpecDefault {

  def suiteNavigation = suite("Navigation")(
    test("standard scenario") {
      for {
        owner             <- MediaService.ownerCreate(None, FirstName("John"), LastName("Doe"), None)
        store             <- MediaService.storeCreate(None, None, owner.id, BaseDirectoryPath(Path.of("samples/dataset3")), None, None)
        _                 <- MediaService.synchronize(Start)
        _                 <- MediaService.synchronize(WaitForCompletion)
        medias            <- MediaService.mediaList().runCollect
        last              <- MediaService.mediaLast().some
        previousLast      <- MediaService.mediaPrevious(last.accessKey).some
        previousNextLast  <- MediaService.mediaNext(previousLast.accessKey).some
        first             <- MediaService.mediaFirst().some
        nextFirst         <- MediaService.mediaNext(first.accessKey).some
        nextPreviousFirst <- MediaService.mediaPrevious(nextFirst.accessKey).some
      } yield assertTrue(
        medias.size == 13,
        first == nextPreviousFirst,
        last == previousNextLast
      )
    }
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    (suiteNavigation)
      .provideShared(
        LMDB.liveWithDatabaseName(s"sotohp-db-for-unit-tests-${getClass.getCanonicalName}-${ULID.newULID}") >>> MediaService.live,
        configProvider >>> SearchService.live,
        Scope.default
      )
      @@ TestAspect.sequential

}
