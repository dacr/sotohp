package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.OriginalsStream
import fr.janalyse.sotohp.store.PhotoStoreService
import zio.*
import zio.lmdb.LMDB
import zio.config.typesafe.*

object Synchronize extends ZIOAppDefault with CommonsCLI {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load()))

  override def run =
    logic
      .provide(
        LMDB.liveWithDatabaseName("photos"),
        PhotoStoreService.live,
        Scope.default
      )

  val logic = ZIO.logSpan("synchronize") {
    for {
      _           <- ZIO.logInfo("photos synchronization")
      searchRoots <- getSearchRoots
      originals    = OriginalsStream.photoStream(searchRoots)
      count       <- originals.runCount
      _           <- ZIO.logInfo(s"Synchronization done - found $count photos")
      // photoFiles         <- originals.map(_.source.photoPath.toString).runCollect
      // _                  <- ZIO.foreach(photoFiles.sorted)(f => Console.printLine(f))
    } yield ()
  }

}
