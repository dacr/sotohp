package fr.janalyse.sotohp.media.core

import fr.janalyse.sotohp.media.model.{BaseDirectoryPath, Original, OriginalPath, Owner}

/*
Introduce original's cache as some operations can be very costly, such as file hash computation
 */

trait MediaCache {
  def originalGet(baseDirectory: BaseDirectoryPath, mediaPath: OriginalPath, owner: Owner): Either[MediaIssue, Option[Original]]
  def originalUpdate(original: Original): Either[MediaIssue, Unit]
}

object MediaNoCache extends MediaCache {
  def originalGet(baseDirectory: BaseDirectoryPath, mediaPath: OriginalPath, owner: Owner): Either[MediaIssue, Option[Original]] = {
    Right(None)
  }

  def originalUpdate(original: Original): Either[MediaIssue, Unit] = {
    Right(())
  }
}
