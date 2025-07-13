package fr.janalyse.sotohp.media.core

import java.nio.file.Path

sealed trait CoreIssue extends Exception

sealed trait OriginalIssue extends CoreIssue {
  val message: String
  val filePath: Path
}

case class OriginalFileIssue(message: String, filePath: Path, throwable: Throwable)     extends Exception(message, throwable) with OriginalIssue
case class OriginalInternalIssue(message: String, filePath: Path, throwable: Throwable) extends Exception(message, throwable) with OriginalIssue
