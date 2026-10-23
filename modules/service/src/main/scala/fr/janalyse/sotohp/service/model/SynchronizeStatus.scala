package fr.janalyse.sotohp.service.model

import fr.janalyse.sotohp.model.MediaAccessKey

import java.time.OffsetDateTime

/** What a synchronization run is busy with. A run walks every store first (`Scanning` : new files
  * get their original, state and media records, nothing heavy), then runs the processors on the
  * medias found never synchronized (`Processing` : normalization, miniatures, faces, AI steps,
  * location/place deduction, search engine publication). Splitting it that way gives the
  * processing phase - the long one - a known total, hence a progress bar and a remaining time.
  */
enum SynchronizePhase {
  case Idle, Scanning, Processing, Stopping
}

enum SynchronizeOutcome {
  case Completed, Stopped, Failed
}

/** Something that went wrong without stopping the run : an unreadable file, a failed processor... */
case class SynchronizeProblem(
  at: OffsetDateTime,
  message: String,
  path: Option[String]
)

case class SynchronizeCurrentMedia(
  accessKey: MediaAccessKey,
  path: String
)

/** Summary of the last finished run, kept until the next one finishes. */
case class SynchronizeRun(
  addedThoseLastDays: Option[Int],
  startedAt: OffsetDateTime,
  finishedAt: OffsetDateTime,
  outcome: SynchronizeOutcome,
  checkedCount: Long,
  toProcessCount: Long,
  processedCount: Long,
  problemCount: Long,
  failure: Option[String]
)

case class SynchronizeStatus(
  running: Boolean,
  phase: SynchronizePhase,
  addedThoseLastDays: Option[Int],
  startedAt: Option[OffsetDateTime],
  lastUpdated: Option[OffsetDateTime],
  storeCount: Int,
  storeIndex: Int,             // 1-based index of the store being scanned, 0 before the first one
  currentStore: Option[String],
  checkedCount: Long,          // files looked at
  unreadableCount: Long,       // files found but impossible to read as a media
  toProcessCount: Long,        // never synchronized medias found so far, the processing phase total
  processedCount: Long,        // medias that went through the processors
  indexedCount: Long,          // medias published to the search engine and marked synchronized
  processingStartedAt: Option[OffsetDateTime],
  remainingSeconds: Option[Long], // estimation, only while processing
  currentMedia: Option[SynchronizeCurrentMedia],
  unavailableSteps: List[String], // remote steps skipped for the rest of the run, their server looks down
  problemCount: Long,
  problems: List[SynchronizeProblem], // the most recent ones, newest first
  lastRun: Option[SynchronizeRun]
)

object SynchronizeStatus {
  val maxProblems = 20

  val empty: SynchronizeStatus = SynchronizeStatus(
    running = false,
    phase = SynchronizePhase.Idle,
    addedThoseLastDays = None,
    startedAt = None,
    lastUpdated = None,
    storeCount = 0,
    storeIndex = 0,
    currentStore = None,
    checkedCount = 0,
    unreadableCount = 0,
    toProcessCount = 0,
    processedCount = 0,
    indexedCount = 0,
    processingStartedAt = None,
    remainingSeconds = None,
    currentMedia = None,
    unavailableSteps = Nil,
    problemCount = 0,
    problems = Nil,
    lastRun = None
  )

  /** Remaining time of the processing phase, from the pace of the last completed medias (a sliding
    * window, so it follows a slowing down or speeding up AI server). The media in progress counts as
    * a lower bound of that pace : a stalled run sees its estimation grow instead of freezing.
    *
    * @param completions
    *   epoch millis of the processing phase start then of each completed media, oldest first
    */
  def estimateRemainingSeconds(status: SynchronizeStatus, completions: Seq[Long], nowMillis: Long): Option[Long] = {
    val remaining = status.toProcessCount - status.processedCount
    if (status.phase != SynchronizePhase.Processing || remaining <= 0 || completions.size < 2) None
    else {
      val sinceLast  = math.max(0L, nowMillis - completions.last).toDouble
      val windowPace = (completions.last - completions.head).toDouble / (completions.size - 1)
      val pace       = math.max(windowPace, (nowMillis - completions.head).toDouble / completions.size)
      Some(math.max(0L, math.round((remaining * pace - math.min(sinceLast, pace)) / 1000d)))
    }
  }
}
