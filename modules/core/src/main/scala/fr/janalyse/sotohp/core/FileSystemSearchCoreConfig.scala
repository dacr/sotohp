package fr.janalyse.sotohp.core

import java.nio.file.Path

case class FileSystemSearchCoreConfig(
  maxDepth: Int = 10,
  lockDirectory: Option[Path] = None,
  followLinks: Boolean = false
)
