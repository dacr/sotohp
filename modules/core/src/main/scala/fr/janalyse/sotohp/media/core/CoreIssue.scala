package fr.janalyse.sotohp.media.core

import java.nio.file.Path

sealed trait CoreIssue extends Exception {
  val message: String
}

sealed trait OriginalIssue extends CoreIssue {
  val path: Path
}

case class OriginalFileIssue(message: String, path: Path, throwable: Throwable)     extends Exception(message, throwable) with OriginalIssue
case class OriginalInternalIssue(message: String, path: Path, throwable: Throwable) extends Exception(message, throwable) with OriginalIssue

sealed trait FileSystemSearchIssue extends CoreIssue

case class FileSystemSearchInvalidBaseDirectory(message: String, path: String, throwable: Throwable) extends Exception(message, throwable) with FileSystemSearchIssue
case class FileSystemSearchInvalidPatternFilter(message: String, throwable: Throwable)               extends Exception(message, throwable) with FileSystemSearchIssue
case class FileSystemSearchFindIssue(message: String, throwable: Throwable)                          extends Exception(message, throwable) with FileSystemSearchIssue
