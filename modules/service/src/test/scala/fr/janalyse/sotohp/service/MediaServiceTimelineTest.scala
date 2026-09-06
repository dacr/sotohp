package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import wvlet.airframe.ulid.ULID
import zio.*
import zio.lmdb.LMDB
import zio.test.*

import java.nio.file.Path

// The mosaic addresses medias by absolute offset: it sizes its scrollbar from `total`, and turns
// any offset into one request by starting an inclusive backward stream at that offset's anchor.
// Both of those are only true if the anchors tile the collection exactly — no gap, no overlap, no
// off-by-one at a page boundary — so that is what is pinned down here.
object MediaServiceTimelineTest extends BaseSpecDefault {

  private val step = 5

  // Every media, newest first — the order the mosaic presents them in, walked one at a time so it
  // owes nothing to the paging being tested.
  private def walkNewestFirst: ZIO[MediaService, ServiceIssue, List[MediaAccessKey]] = {
    def loop(current: MediaTuple, acc: List[MediaAccessKey]): ZIO[MediaService, ServiceIssue, List[MediaAccessKey]] =
      MediaService.mediaPrevious(current.key).flatMap {
        case None       => ZIO.succeed((current.key :: acc).reverse)
        case Some(prev) => loop(prev, current.key :: acc)
      }
    MediaService.mediaLast().flatMap {
      case None       => ZIO.succeed(Nil)
      case Some(last) => loop(last, Nil)
    }
  }

  def suiteTimeline = suite("Timeline")(
    test("anchors and inclusive paging tile the whole collection exactly") {
      for {
        owner    <- MediaService.ownerCreate(None, FirstName("John"), LastName("Doe"), None)
        samples   = scala.util.Properties.envOrElse("PHOTOS_TEST_SAMPLES", "samples")
        _        <- MediaService.storeCreate(None, None, owner.id, BaseDirectoryPath(Path.of(samples, "dataset3")), None, None)
        _        <- MediaService.synchronizeStart(None)
        _        <- MediaService.synchronizeWait()
        expected <- walkNewestFirst
        timeline <- MediaService.mediaTimeline(step)
        // Reassemble the collection page by page, exactly as the mosaic does.
        pages    <- ZIO.foreach(timeline.anchors)(anchor => MediaService.mediaStream(anchor.accessKey, backward = true, limit = step, inclusive = true).runCollect.map(_.toList.map(_.key)))
        assembled = pages.flatten
        // Without `inclusive` the same call must still skip its start key, as every existing
        // caller of mediaStream relies on.
        exclusive <- MediaService.mediaStream(timeline.anchors.head.accessKey, backward = true, limit = step, inclusive = false).runCollect.map(_.toList.map(_.key))
      } yield assertTrue(
        expected.size == 13,
        // The count the scrollbar is sized from is the real one.
        timeline.total == expected.size,
        timeline.step == step,
        // One anchor per page, at exact multiples of the step.
        timeline.anchors.map(_.offset) == List(0L, 5L, 10L),
        // Each anchor really is the media at its offset.
        timeline.anchors.forall(anchor => expected(anchor.offset.toInt) == anchor.accessKey),
        // Anchors run newest-first, matching the order the mosaic renders.
        timeline.anchors.map(_.timestamp).sliding(2).forall { case Seq(a, b) => a.isAfter(b) || a == b; case _ => true },
        // Pages are full except the last, which holds the remainder.
        pages.map(_.size) == List(5, 5, 3),
        // The union of the pages is the collection, in order, with nothing dropped or repeated.
        assembled == expected,
        assembled.distinct.size == expected.size,
        // Exclusive streaming is unchanged: it starts *after* the key it is given.
        exclusive == expected.slice(1, 1 + step)
      )
    }
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suiteTimeline
      .provideShared(
        LMDB.liveWithDatabaseName(s"sotohp-db-for-unit-tests-${getClass.getCanonicalName}-${ULID.newULID}") >>> MediaService.live,
        configProvider >>> SearchService.live,
        Scope.default
      )
      @@ TestAspect.sequential

}
