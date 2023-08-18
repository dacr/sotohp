package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.model.{PhotoOwnerId, PhotoSearchRoot}
import fr.janalyse.sotohp.core.OriginalsStream
import fr.janalyse.sotohp.store.PhotoStoreService
import wvlet.airframe.ulid.ULID
import zio.*

trait CommonsCLI {
  val getSearchRoots = for {
    config                <- ZIO.config(SearchConfig.config)
    ownerId               <- ZIO.attempt(ULID.fromString(config.ownerId)).map(PhotoOwnerId.apply)
    baseDirectories        = config.roots.split("[,;]").toList.map(_.trim)
    searchRootsFromConfig <- ZIO.foreach(baseDirectories)(baseDirectory => ZIO.from(PhotoSearchRoot.build(ownerId, baseDirectory, config.includeMask, config.ignoreMask)))
  } yield searchRootsFromConfig

}
