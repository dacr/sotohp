package fr.janalyse.sotohp.gui

import fr.janalyse.sotohp.store.{PhotoStoreService, PhotoStoreSystemIssue}
import zio.*
import zio.lmdb.LMDB
import zio.stream.ZStream
import zio.config.typesafe.TypesafeConfigProvider
import scalafx.application.*
import scalafx.scene.Scene
import scalafx.scene.control.Label
import scalafx.scene.control.Button
import scalafx.scene.layout.VBox
import scalafx.scene.layout.HBox
import scalafx.application.Platform

object PhotoView extends ZIOAppDefault {

  class FxApp extends JFXApp3 {
    lazy val info              = Label("")
    lazy val first             = Button("first")
    lazy val previous          = Button("previous")
    lazy val next              = Button("next")
    lazy val last              = Button("last")
    lazy val buttonFaces       = Button("faces")
    override def start(): Unit =
      stage = new JFXApp3.PrimaryStage {
        title = "SOTOHP Viewer"
        scene = new Scene {
          content = HBox(first, previous, next, last, buttonFaces, info)
        }
      }
  }

  val fxBridge = (fxApp: FxApp) => {
    for {
      actionHub       <- Hub.unbounded[String]
      hub             <- Hub.unbounded[PhotoToShow]
      currentPhotoRef <- Ref.make(Option.empty[PhotoToShow])
      toFirstPhoto     = PhotoStoreService
                           .photoFirst()
                           .some
                           .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                           .flatMap(photo => hub.offer(photo))
      toLastPhoto      = PhotoStoreService
                           .photoLast()
                           .some
                           .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                           .flatMap(photo => hub.offer(photo))
      toPreviousPhoto  = currentPhotoRef.get.flatMap(current =>
                           PhotoStoreService
                             .photoPrevious(current.get.source.photoId)
                             .some
                             .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                             .flatMap(photo => hub.offer(photo))
                             .orElse(toFirstPhoto)
                         )
      toNextPhoto      = currentPhotoRef.get.flatMap(current =>
                           PhotoStoreService
                             .photoNext(current.get.source.photoId)
                             .some
                             .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                             .flatMap(photo => hub.offer(photo))
                             .orElse(toLastPhoto)
                         )
      _               <- ZStream
                           .fromHub(actionHub)
                           .runForeach(action =>
                             action match {
                               case "first"    => toFirstPhoto
                               case "previous" => toPreviousPhoto
                               case "next"     => toNextPhoto
                               case "last"     => toLastPhoto
                               case _          => ZIO.unit
                             }
                           )
                           .fork
      _               <- ZStream
                           .fromHub(hub)
                           .runForeach(currentPhoto => currentPhotoRef.update(_ => Some(currentPhoto)))
                           .fork
      _               <- toFirstPhoto
      _               <- ZStream
                           .async(callback => fxApp.first.onAction = (event => callback(actionHub.offer("first").as(Chunk.unit))))
                           .runDrain
                           .fork
      _               <- ZStream
                           .async(callback => fxApp.previous.onAction = (event => callback(actionHub.offer("previous").as(Chunk.unit))))
                           .runDrain
                           .fork
      _               <- ZStream
                           .async(callback => fxApp.next.onAction = (event => callback(actionHub.offer("next").as(Chunk.unit))))
                           .runDrain
                           .fork
      _               <- ZStream
                           .async(callback => fxApp.last.onAction = (event => callback(actionHub.offer("last").as(Chunk.unit))))
                           .runDrain
                           .fork
      _               <- ZStream
                           .fromHub(hub)
                           .runForeach(offered => ZIO.attemptBlocking(Platform.runLater(fxApp.info.text = offered.source.original.path.toString)))
    } yield ()
  }

  val photoViewApp = for {
    fx <- ZIO.succeed(FxApp())
    _  <- ZIO.attemptBlocking(fx.main(Array.empty)).fork
    _  <- fxBridge(fx)
  } yield ()

  override def run = photoViewApp.provide(
    LMDB.liveWithDatabaseName("photos"),
    PhotoStoreService.live,
    Scope.default,
    Runtime.setConfigProvider(TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load()))
  )
}
