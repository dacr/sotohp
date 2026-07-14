package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.{MediaService, MediaTuple, ServiceInternalIssue}
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

import java.nio.file.{Files, Path, Paths}
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import scala.io.AnsiColor.*

/** Find (and optionally fix) obviously invalid GPS locations.
  *
  * Heuristic — for a *single camera* (grouped by `cameraName`), all photos taken close together in time are
  * physically close together in space. So the tool builds, for every photo, a robust *consensus* location:
  * the median latitude/longitude of all same-camera photos taken within a time window around it (default
  * ±4 hours). A photo whose own GPS location is further than a threshold (default 500 km) from that consensus
  * is flagged as wrong.
  *
  * Using the median (rather than comparing to immediate neighbours) is what makes it robust to *bursts* of
  * consecutive wrong readings: as long as correct photos are the majority inside the window, a run of dozens
  * of spurious fixes is still caught — each of them is far from the median that the surrounding correct
  * photos define. A single genuine photo can never be flagged as long as it sits among matching neighbours.
  *
  * When run with `--fix` (alias `--execute` / `-f`) the bad location is overridden by setting the media's
  * `userDefinedLocation` to the location of the closest-in-time in-window neighbour that agrees with the
  * consensus (the best available estimate of where the photo was really taken). Without that flag the tool
  * only reports, changing nothing.
  *
  * Only the ORIGINAL's own GPS reading is analysed, and only when it is actually present — deducted and bag
  * fallback locations are ignored so they cannot pollute the consensus. Any media that already carries a
  * `userDefinedLocation` is left untouched (a human/earlier correction is trusted).
  *
  * Limitations:
  *   - a window in which wrong readings are the *majority* (very sparse shooting) cannot be resolved reliably;
  *   - a whole camera whose GPS is systematically wrong for a long stretch cannot be told apart from truth.
  *
  * Options (all optional):
  *   - `--fix` / `--execute` / `-f`   apply the corrections (default: dry-run, report only)
  *   - `--camera=<substring>`         only scan cameras whose name contains this (case-insensitive) substring
  *   - `--distance-km=<n>`            outlier distance threshold in km (default 500)
  *   - `--window-hours=<n>`           consensus time window, each side, in hours (default 4)
  *   - `--min-neighbours=<n>`         minimum in-window neighbours required to judge a photo (default 4)
  *   - `--min-support=<r>`            minimum fraction (0..1) of neighbours that must agree with the consensus
  *                                    for the window to be trusted (default 0.6) — guards travel boundaries
  *
  * Examples:
  *   mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.GpsLocationFix
  *   mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.GpsLocationFix --camera="R5m2" --fix
  *   mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.GpsLocationFix --distance-km=300 --window-hours=6 --fix
  */
object GpsLocationFix extends CommonsCLI {

  override def run =
    logic
      .provideSome[ZIOAppArgs](
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  // -------------------------------------------------------------------------------------------------------------------
  private val defaultMaxDistanceMeters = 500_000d  // 500 km
  private val defaultWindowSeconds     = 4L * 3600L // ±4 hours
  private val defaultMinNeighbours     = 4
  private val defaultMinSupportRatio   = 0.6d

  /** A media that carries an effective location and a camera name, pre-projected for the analysis. */
  private case class LocatedMedia(
    key: MediaAccessKey,
    media: Media,
    camera: CameraName,
    epoch: Long, // shooting time as epoch seconds, for fast time-distance computation
    location: Location
  ) {
    def timestamp: OffsetDateTime = media.timestamp
    def fileName: String          = media.original.mediaPath.fileName
  }

  /** An outlier: the wrong media, the consensus it disagrees with, its distance to that consensus, and the
    * neighbour whose real location will be used as replacement.
    */
  private case class Outlier(bad: LocatedMedia, consensus: Location, distanceMeters: Double, replacement: LocatedMedia)

  // -------------------------------------------------------------------------------------------------------------------
  private def fmtLoc(location: Location): String =
    f"(${location.latitude.doubleValue}%.5f, ${location.longitude.doubleValue}%.5f)"

  private def parseStringArg(args: Chunk[String], name: String): Option[String] =
    args.collectFirst { case a if a.startsWith(s"$name=") => a.stripPrefix(s"$name=") }

  private def parseDoubleArg(args: Chunk[String], name: String): Option[Double] =
    parseStringArg(args, name).flatMap(raw => scala.util.Try(raw.toDouble).toOption)

  private def median(values: Array[Double]): Double = {
    val sorted = values.sorted
    val n      = sorted.length
    if (n == 0) 0d
    else if (n % 2 == 1) sorted(n / 2)
    else (sorted(n / 2 - 1) + sorted(n / 2)) / 2d
  }

  // -------------------------------------------------------------------------------------------------------------------
  /** For a single camera's medias sorted by shooting time, return the outliers.
    *
    * For each photo, the consensus location is the component-wise median of all same-camera photos within
    * `windowSeconds` (a sliding time window). A photo further than `maxDistanceMeters` from that consensus is
    * an outlier; its replacement is the closest-in-time in-window neighbour that itself agrees with the
    * consensus. Photos with fewer than `minNeighbours` in-window neighbours are left undecided, and windows
    * where fewer than `minSupportRatio` of neighbours agree with the consensus are not trusted (this keeps
    * legitimate travel boundaries — two dense clusters straddling the window — from being mislabelled).
    */
  private def findOutliers(
    sorted: Vector[LocatedMedia],
    maxDistanceMeters: Double,
    windowSeconds: Long,
    minNeighbours: Int,
    minSupportRatio: Double
  ): Vector[Outlier] = {
    val n       = sorted.length
    val builder = Vector.newBuilder[Outlier]
    var lo      = 0 // window lower bound (inclusive), monotonically increasing with i
    var hi      = 0 // window upper bound (exclusive), monotonically increasing with i
    var i       = 0
    while (i < n) {
      val current = sorted(i)
      // Slide [lo, hi) to cover every photo within ±windowSeconds of the current one (sorted by epoch asc).
      while (lo < n && current.epoch - sorted(lo).epoch > windowSeconds) lo += 1
      if (hi < lo) hi = lo
      while (hi < n && sorted(hi).epoch - current.epoch <= windowSeconds) hi += 1

      val neighbourCount = hi - lo - 1 // window size excluding the current photo itself
      if (neighbourCount >= minNeighbours) {
        val lats = new Array[Double](neighbourCount)
        val lons = new Array[Double](neighbourCount)
        var idx  = 0
        var k    = lo
        while (k < hi) {
          if (k != i) {
            lats(idx) = sorted(k).location.latitude.doubleValue
            lons(idx) = sorted(k).location.longitude.doubleValue
            idx += 1
          }
          k += 1
        }
        val consensus = Location.fromDecimalDegrees(LatitudeDecimalDegrees(median(lats)), LongitudeDecimalDegrees(median(lons)))
        val distance  = current.location.distanceTo(consensus)
        if (distance > maxDistanceMeters) {
          // Count how many neighbours back the consensus, and pick the closest-in-time one as replacement.
          var support: Int       = 0
          var best: LocatedMedia = null
          var bestDelta          = Long.MaxValue
          var k2                 = lo
          while (k2 < hi) {
            if (k2 != i) {
              val nb = sorted(k2)
              if (nb.location.distanceTo(consensus) <= maxDistanceMeters) {
                support += 1
                val delta = math.abs(nb.epoch - current.epoch)
                if (delta < bestDelta) { bestDelta = delta; best = nb }
              }
            }
            k2 += 1
          }
          // Trust the verdict only when a clear majority of the window agrees with the consensus.
          if (best != null && support >= minSupportRatio * neighbourCount) builder += Outlier(current, consensus, distance, best)
        }
      }
      i += 1
    }
    builder.result()
  }

  // -------------------------------------------------------------------------------------------------------------------
  private def reportOutlier(outlier: Outlier) = {
    val bad         = outlier.bad
    val replacement = outlier.replacement
    val km          = outlier.distanceMeters / 1000d
    val minutesGap  = math.abs(replacement.epoch - bad.epoch) / 60d
    Console.printLine(
      f"$RED- [${bad.camera.text}] ${bad.fileName} @ ${bad.timestamp}$RESET\n" +
        f"    ${YELLOW}bad location ${fmtLoc(bad.location)} is $km%.0f km away from consensus ${fmtLoc(outlier.consensus)}$RESET\n" +
        f"    ${GREEN}replacement ${fmtLoc(replacement.location)} from ${replacement.fileName} ($minutesGap%.1f min apart)$RESET"
    )
  }

  private def applyFix(outlier: Outlier) = {
    val bad     = outlier.bad
    val updated = bad.media.copy(userDefinedLocation = Some(outlier.replacement.location))
    MediaService
      .mediaUpdate(bad.key, updated)
      .uninterruptible
      .as(())
  }

  // -------------------------------------------------------------------------------------------------------------------
  /** Build a self-contained markdown summary report of the run (parameters, per-camera counts, full details). */
  private def buildReport(
    generatedAt: OffsetDateTime,
    fixMode: Boolean,
    cameraFilter: Option[String],
    maxDistanceMeters: Double,
    windowSeconds: Long,
    minNeighbours: Int,
    minSupportRatio: Double,
    totalMedias: Int,
    consideredCount: Int,
    cameraCount: Int,
    outliers: Vector[Outlier]
  ): String = {
    val sb = new StringBuilder
    sb.append("# GPS location fix — summary report\n\n")
    sb.append(s"- Generated: $generatedAt\n")
    sb.append(s"- Mode: ${if (fixMode) "FIX (corrections written to userDefinedLocation)" else "dry-run (no change)"}\n")
    cameraFilter.foreach(f => sb.append(s"""- Camera filter: "$f"\n"""))
    sb.append(f"- Distance threshold: ${maxDistanceMeters / 1000d}%.0f km\n")
    sb.append(f"- Consensus window: ±${windowSeconds / 3600d}%.1f h (per camera, median of original GPS)\n")
    sb.append(s"- Min neighbours: $minNeighbours — Min consensus support: $minSupportRatio\n")
    sb.append("\n## Overview\n\n")
    sb.append(s"- Medias scanned: $totalMedias\n")
    sb.append(s"- Medias with a defined original GPS considered: $consideredCount across $cameraCount cameras\n")
    sb.append(s"- Invalid GPS locations found: ${outliers.size}\n")

    sb.append("\n## Invalid GPS locations by camera\n\n")
    val perCamera = outliers.groupBy(_.bad.camera.text).view.mapValues(_.size).toList.sortBy { case (cam, count) => (-count, cam) }
    if (perCamera.isEmpty) sb.append("_none_\n")
    else {
      sb.append("| Camera | Invalid GPS count |\n|---|---:|\n")
      perCamera.foreach { case (cam, count) => sb.append(s"| $cam | $count |\n") }
    }

    sb.append("\n## Details\n\n")
    if (outliers.isEmpty) sb.append("_none_\n")
    else
      outliers.sortBy(o => (o.bad.camera.text, o.bad.epoch)).foreach { o =>
        val km  = o.distanceMeters / 1000d
        val gap = math.abs(o.replacement.epoch - o.bad.epoch) / 60d
        sb.append(
          f"- **[${o.bad.camera.text}] ${o.bad.fileName}** @ ${o.bad.timestamp} — bad ${fmtLoc(o.bad.location)} is $km%.0f km from consensus ${fmtLoc(o.consensus)}; replaced with ${fmtLoc(o.replacement.location)} (${o.replacement.fileName}, $gap%.1f min apart)\n"
        )
      }
    sb.toString
  }

  private def writeReport(content: String, generatedAt: OffsetDateTime): ZIO[Any, Throwable, Path] = ZIO.attemptBlocking {
    val stamp     = generatedAt.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
    val outputDir = Paths.get("out")
    Files.createDirectories(outputDir)
    val target    = outputDir.resolve(s"gps-location-fix-report-$stamp.md")
    Files.writeString(target, content)
    target
  }

  // -------------------------------------------------------------------------------------------------------------------
  private def project(mediaTuple: MediaTuple): Option[LocatedMedia] = {
    val media = mediaTuple.media
    // Skip medias already carrying a user correction — those are trusted and must not be overwritten.
    if (media.userDefinedLocation.isDefined) None
    else
      // Only consider the ORIGINAL's GPS (the real camera reading), and only when it is actually defined —
      // never the deducted/bag fallback, which would otherwise pollute the consensus with derived positions.
      for {
        camera   <- media.original.cameraName
        location <- media.original.location
        if media.original.hasLocation // excludes the (0,0) placeholder
      } yield LocatedMedia(
        key = mediaTuple.key,
        media = media,
        camera = camera,
        epoch = media.timestamp.toInstant.getEpochSecond,
        location = location
      )
  }

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Find and optionally fix invalid GPS locations") {
    for {
      args             <- getArgs
      fixMode           = args.exists(a => a == "--fix" || a == "--execute" || a == "-f")
      cameraFilter      = parseStringArg(args, "--camera").map(_.toLowerCase)
      maxDistanceMeters = parseDoubleArg(args, "--distance-km").map(_ * 1000d).getOrElse(defaultMaxDistanceMeters)
      windowSeconds     = parseDoubleArg(args, "--window-hours").map(h => (h * 3600d).toLong).getOrElse(defaultWindowSeconds)
      minNeighbours     = parseDoubleArg(args, "--min-neighbours").map(_.toInt).getOrElse(defaultMinNeighbours)
      minSupportRatio   = parseDoubleArg(args, "--min-support").getOrElse(defaultMinSupportRatio)
      _                <- ZIO.logInfo(
                            s"Scanning for invalid GPS locations (threshold ${maxDistanceMeters / 1000d} km vs median over ±${windowSeconds / 3600d}h per camera)" +
                              cameraFilter.fold("")(f => s""", camera filter "$f"""") +
                              (if (fixMode) " — FIX mode, corrections will be written" else " — dry-run, no change")
                          )
      allMedia         <- MediaService.mediaList().runCollect
      located           = allMedia.toVector.flatMap(project)
      byCamera          = located.groupBy(_.camera).filter { case (camera, _) => cameraFilter.forall(f => camera.text.toLowerCase.contains(f)) }
      consideredCount   = byCamera.valuesIterator.map(_.size).sum
      outliers          = byCamera.toVector.flatMap { case (_, group) => findOutliers(group.sortBy(_.epoch), maxDistanceMeters, windowSeconds, minNeighbours, minSupportRatio) }
      _                <- ZIO.logInfo(s"Scanned ${allMedia.size} medias, $consideredCount with a defined original GPS across ${byCamera.size} cameras")
      _                <- Console.printLine("-----------------------------------------------------------------------------------------")
      _                <- ZIO.foreachDiscard(outliers.sortBy(o => (o.bad.camera.text, o.bad.epoch)))(reportOutlier)
      _                <- Console.printLine("-----------------------------------------------------------------------------------------")
      byCameraCount     = outliers.groupBy(_.bad.camera.text).view.mapValues(_.size).toList.sortBy { case (cam, count) => (-count, cam) }
      _                <- ZIO.foreachDiscard(byCameraCount) { case (cam, count) => Console.printLine(f"$YELLOW$count%5d invalid GPS locations for camera $cam$RESET") }
      _                <- if (fixMode) ZIO.foreachDiscard(outliers)(applyFix) *> ZIO.logInfo(s"Fixed ${outliers.size} invalid GPS locations (set userDefinedLocation)")
                          else ZIO.logInfo(s"Found ${outliers.size} invalid GPS locations (run with --fix to correct them)")
      generatedAt      <- Clock.currentDateTime
      report            = buildReport(generatedAt, fixMode, cameraFilter, maxDistanceMeters, windowSeconds, minNeighbours, minSupportRatio, allMedia.size, consideredCount, byCamera.size, outliers)
      reportPath       <- writeReport(report, generatedAt).mapError(err => ServiceInternalIssue(s"Couldn't write report : $err"))
      _                <- ZIO.logInfo(s"Summary report written to ${reportPath.toAbsolutePath}")
    } yield ()
  }

}
