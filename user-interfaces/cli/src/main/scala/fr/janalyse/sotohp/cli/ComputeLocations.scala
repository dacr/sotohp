package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.lmdb.LMDB

/** Refreshes the locations deduced from a GPS reading that was later corrected by a user-defined
  * location (typically a wrong fix, by hand or by `GpsLocationFix`). The synchronization only ever
  * fills a missing deduction, so the photos without GPS around such a reading kept the wrong position
  * - since then, the API and `GpsLocationFix` refresh them on each correction, this is for the older
  * ones.
  *
  *   - `--fix` (alias `--execute` / `-f`) stores the new deductions and the deducted places of the
  *     photos concerned (default: dry-run, report only)
  *
  * The search documents are re-published along with each fixed photo, no reindex needed.
  */
object ComputeLocations extends CommonsCLI {

  override def run =
    logic
      .provideSome[ZIOAppArgs](
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  private def fmtLoc(location: Option[Location]): String =
    location.fold("none")(l => f"(${l.latitude.doubleValue}%.5f, ${l.longitude.doubleValue}%.5f)")

  private def corrected(media: Media): Boolean =
    media.original.hasLocation && media.userDefinedLocation.exists(_.isValid) && media.userDefinedLocation != media.original.location

  /** Reports the dependents of a corrected reading whose deduction would change, and refreshes them in
    * fix mode - right away, so an interrupted run keeps what it did.
    */
  private def refreshDependents(source: Media, fixMode: Boolean) =
    for {
      dependents <- MediaService.locationInductionAround(source.original.id)
      stale       = dependents.filter(dependent => dependent.induced != dependent.media.media.deductedLocation)
      _          <- Console.printLine(
                      s"${source.timestamp} ${source.original.mediaPath.path} : GPS ${fmtLoc(source.original.location)} corrected to ${fmtLoc(source.userDefinedLocation)}, ${stale.size} stale deductions"
                    ).when(stale.nonEmpty)
      _          <- ZIO.foreachDiscard(stale)(dependent =>
                      Console.printLine(s"    ${dependent.media.media.timestamp} ${dependent.media.media.original.mediaPath.path} : ${fmtLoc(dependent.media.media.deductedLocation)} -> ${fmtLoc(dependent.induced)}")
                    )
      _          <- MediaService.locationInductionRefreshAround(source.original.id).when(fixMode)
    } yield stale.size

  val logic = ZIO.logSpan("Refresh the locations deduced from corrected GPS readings") {
    for {
      args    <- getArgs
      fixMode  = args.exists(a => a == "--fix" || a == "--execute" || a == "-f")
      sources <- MediaService.mediaList().map(_.media).filter(corrected).runCollect
      _       <- Console.printLine(s"${sources.size} corrected GPS readings" + (if (fixMode) " - FIX mode, changes will be written" else " - dry-run, no change"))
      stale   <- ZIO.foreach(sources)(source => refreshDependents(source, fixMode))
      _       <- Console.printLine(s"done - ${stale.sum} deduced locations " + (if (fixMode) "refreshed" else "to refresh (run with --fix to apply)"))
    } yield ()
  }

}
