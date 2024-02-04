package fr.janalyse.sotohp.gui

import fr.janalyse.sotohp.store.{PhotoStoreService, PhotoStoreSystemIssue}
import javafx.scene.input.KeyCode
import zio.*
import zio.lmdb.LMDB
import zio.stream.ZStream
import zio.config.typesafe.TypesafeConfigProvider
import scalafx.application.*
import scalafx.scene.Scene
import scalafx.scene.control.Label
import scalafx.scene.control.Button
import scalafx.scene.layout.{HBox, Region, VBox}
import scalafx.application.Platform
import scalafx.scene.image.Image
import scalafx.Includes.jfxRegion2sfx

enum UserAction {
  case First
  case Previous
  case Next
  case Last
}

object PhotoViewerApp extends ZIOAppDefault {

  class FxApp extends JFXApp3 {
    lazy val hasGPS      = Label("⌖")
    lazy val info        = Label("no photo found")
    lazy val first       = Button("⇤") // LEFTWARDS ARROW TO BAR
    lazy val previous    = Button("⇠") // LEFTWARDS DASHED ARROW
    lazy val next        = Button("⇢") // RIGHTWARDS DASHED ARROW
    lazy val last        = Button("⇥") // RIGHTWARDS ARROW TO BAR
    lazy val zoom        = Button("⚲")
    lazy val zoomReset   = Button("▭")
    lazy val faces       = Button("☺") // WHITE SMILING FACE
    lazy val rotateLeft  = Button("↺") // ANTICLOCKWISE OPEN CIRCLE ARROW
    lazy val rotateRight = Button("↻") // CLOCKWISE OPEN CIRCLE ARROW
    lazy val display     = PhotoDisplay()
    lazy val displaySFX  = jfxRegion2sfx(display)
    lazy val buttons     = HBox(first, previous, next, last, zoom, faces, rotateLeft, rotateRight)
    lazy val infos       = HBox(hasGPS, info)
    lazy val controls    = VBox(buttons, infos)

    override def start(): Unit = {
      stage = new JFXApp3.PrimaryStage {
        title = "SOTOHP Viewer"
        scene = new Scene {
          content = VBox(
            displaySFX,
            controls
          )
          onKeyPressed = key =>
            key.getCode match {
              case KeyCode.PAGE_UP                => previous.fire()
              case KeyCode.PAGE_DOWN              => next.fire()
              case KeyCode.OPEN_BRACKET           => rotateLeft.fire()
              case KeyCode.CLOSE_BRACKET          => rotateRight.fire()
              case KeyCode.HOME                   => first.fire()
              case KeyCode.END                    => last.fire()
              case KeyCode.INSERT                 => faces.fire()
              case KeyCode.Z if key.isControlDown => display.zoomOut()
              case KeyCode.Z                      => display.zoomIn()
              case KeyCode.X                      => zoomReset.fire()
              case _                              =>
            }
          rotateLeft.onAction = event => display.rotateLeft()
          rotateRight.onAction = event => display.rotateRight()
          faces.onAction = event => display.toggleFaces()
          zoom.onAction = event => display.zoomIn()
          zoomReset.onAction = event => display.zoomReset()
        }
      }
      displaySFX.maxWidth <== stage.width
      displaySFX.maxHeight <== (stage.height - buttons.height * 2.5) // TODO
    }

    def show(photo: PhotoToShow): Unit = {
      info.text = photo.shootDateTime.map(_.toString).getOrElse("Unknown shooting date")
      display.drawImage(photo) // normalized photo are already rotated
    }
  }

  val fxBridge = (fxApp: FxApp) => {
    for {
      userActionHub   <- Hub.unbounded[UserAction]
      photoHub        <- Hub.unbounded[PhotoToShow]
      currentPhotoRef <- Ref.make(Option.empty[PhotoToShow])
      toFirstPhoto     = PhotoStoreService
                           .photoFirst()
                           .some
                           .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                           .flatMap(photo => photoHub.offer(photo))
      toLastPhoto      = PhotoStoreService
                           .photoLast()
                           .some
                           .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                           .flatMap(photo => photoHub.offer(photo))
      toPreviousPhoto  = currentPhotoRef.get.flatMap(current =>
                           PhotoStoreService
                             .photoPrevious(current.get.source.photoId)
                             .some
                             .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                             .flatMap(photo => photoHub.offer(photo))
                             .orElse(toFirstPhoto)
                         )
      toNextPhoto      = currentPhotoRef.get.flatMap(current =>
                           PhotoStoreService
                             .photoNext(current.get.source.photoId)
                             .some
                             .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                             .flatMap(photo => photoHub.offer(photo))
                             .orElse(toLastPhoto)
                         )
      _               <- ZStream
                           .fromHub(userActionHub)
                           .runForeach {
                             case UserAction.First    => toFirstPhoto
                             case UserAction.Previous => toPreviousPhoto
                             case UserAction.Next     => toNextPhoto
                             case UserAction.Last     => toLastPhoto
                           }
                           .fork
      _               <- ZStream
                           .fromHub(photoHub)
                           .runForeach(currentPhoto => currentPhotoRef.update(_ => Some(currentPhoto)))
                           .fork
      _               <- ZStream
                           .async(callback => fxApp.first.onAction = (event => callback(userActionHub.offer(UserAction.First).as(Chunk.unit))))
                           .runDrain
                           .fork
      _               <- ZStream
                           .async(callback => fxApp.previous.onAction = (event => callback(userActionHub.offer(UserAction.Previous).as(Chunk.unit))))
                           .runDrain
                           .fork
      _               <- ZStream
                           .async(callback => fxApp.next.onAction = (event => callback(userActionHub.offer(UserAction.Next).as(Chunk.unit))))
                           .runDrain
                           .fork
      _               <- ZStream
                           .async(callback => fxApp.last.onAction = (event => callback(userActionHub.offer(UserAction.Last).as(Chunk.unit))))
                           .runDrain
                           .fork
      ui              <- ZStream
                           .fromHub(photoHub)
                           .runForeach(photo => ZIO.attemptBlocking(Platform.runLater(fxApp.show(photo))))
                           .fork
      _               <- toFirstPhoto
      // _               <- toNextPhoto.repeat(Schedule.fixed(2.seconds)).fork // run a default slideshow
      _               <- ui.join
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
