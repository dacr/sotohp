package fr.janalyse.sotohp.gui

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import fr.janalyse.sotohp.service.model.KeywordRules
import javafx.scene.input.{KeyCode, TransferMode}
import zio.*
import zio.json.*
import zio.lmdb.LMDB
import zio.stream.ZStream
import zio.config.typesafe.TypesafeConfigProvider
import scalafx.application.*
import scalafx.scene.Scene
import scalafx.scene.control.Label
import scalafx.scene.control.Hyperlink
import scalafx.scene.control.Button
import scalafx.scene.layout.{HBox, Region, VBox}
import scalafx.application.Platform
import scalafx.scene.image.Image
import scalafx.Includes.jfxRegion2sfx
import scalafx.scene.input.{Clipboard, ClipboardContent, DataFormat}

import java.nio.file.Path

enum UserAction {
  case First
  case Previous
  case Next
  case Last
}

object PhotoViewerApp extends ZIOAppDefault {

  def buildGoogleMapsHyperLink(location: Location): String = {
    val lat = location.latitude
    val lon = location.longitude
    s"https://www.google.com/maps/search/?api=1&query=$lat,$lon"
  }

  class FxApp extends JFXApp3 {
    private val hasNoGPSText        = "⌘"
    private val hasGPSText          = "⌘"
    private val noEventText         = "No event specified"
    private val noShootDateTimeText = "No shoot timestamp"

    lazy val infoHasGPS   = Label(hasNoGPSText)
    lazy val infoDateTime = Label(noShootDateTimeText)
    lazy val infoEvent    = Label(noEventText)
    lazy val first        = Button("⇤") // LEFTWARDS ARROW TO BAR
    lazy val previous     = Button("⇠") // LEFTWARDS DASHED ARROW
    lazy val next         = Button("⇢") // RIGHTWARDS DASHED ARROW
    lazy val last         = Button("⇥") // RIGHTWARDS ARROW TO BAR
    lazy val zoom         = Button("⚲")
    lazy val zoomReset    = Button("▭")
    lazy val faces        = Button("☺") // WHITE SMILING FACE
    lazy val rotateLeft   = Button("↺") // ANTICLOCKWISE OPEN CIRCLE ARROW
    lazy val rotateRight  = Button("↻") // CLOCKWISE OPEN CIRCLE ARROW
    lazy val display      = PhotoDisplay()
    lazy val displaySFX   = jfxRegion2sfx(display)
    lazy val buttons      = HBox(first, previous, next, last, zoom, faces, rotateLeft, rotateRight)
    lazy val infos        = HBox(5d, infoHasGPS, infoDateTime, infoEvent)
    lazy val controls     = VBox(buttons, infos)

    override def start(): Unit         = {
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
              case KeyCode.LEFT                   => display.panLeft()
              case KeyCode.RIGHT                  => display.panRight()
              case KeyCode.UP                     => display.panUp()
              case KeyCode.DOWN                   => display.panDown()
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
      infoDateTime.text = photo.shootDateTime.map(_.toString).getOrElse(noShootDateTimeText)
      infoHasGPS.text = photo.place.map(_ => hasGPSText).getOrElse(hasNoGPSText)
      infoHasGPS.style = photo.place
        .map(_ => "-fx-text-fill: green")
        .getOrElse("-fx-text-fill: red")
      infoHasGPS.onMouseClicked = event => {
        photo.place.foreach(place => hostServices.showDocument(buildGoogleMapsHyperLink(place)))
      }
      infoEvent.text = photo.event
        .map(_.name.text)
        .getOrElse(noEventText)
        .appendedAll(s" (${photo.media.accessKey.asString})") // TODO temporary added for debug purposes
      infoEvent.onMouseClicked = event => {
        val clipboard = Clipboard.systemClipboard
        clipboard.content = ClipboardContent(DataFormat.PlainText -> infoEvent.text.get())
      }
      display.onDragDetected = event => {
        if (event.isPrimaryButtonDown) {
          val dragBoard = display.startDragAndDrop(TransferMode.COPY)
          val content   = ClipboardContent(DataFormat.Files -> java.util.List.of(photo.media.original.mediaPath.path.toFile))
          dragBoard.setContent(content)
        }
      }
      display.drawImage(photo)                                // normalized photo are already rotated
    }
  }

  val fxBridge = (fxApp: FxApp) => {
    for {
      userActionHub   <- Hub.unbounded[UserAction]
      photoHub        <- Hub.unbounded[PhotoToShow]
      currentPhotoRef <- Ref.make(Option.empty[PhotoToShow])
      toFirstPhoto     = MediaService
                           .mediaFirst()
                           .some
                           .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                           .flatMap(photo => photoHub.offer(photo))
      toLastPhoto      = MediaService
                           .mediaLast()
                           .some
                           .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                           .flatMap(photo => photoHub.offer(photo))
      toPreviousPhoto  = currentPhotoRef.get.flatMap(current =>
                           MediaService
                             .mediaPrevious(current.get.media.accessKey)
                             .some
                             .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                             .flatMap(photo => photoHub.offer(photo))
                             .orElse(toFirstPhoto)
                         )
      toNextPhoto      = currentPhotoRef.get.flatMap(current =>
                           MediaService
                             .mediaNext(current.get.media.accessKey)
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

  val bootstrapForTest = {
    import fr.janalyse.sotohp.model.*
    import java.nio.file.Path
    import wvlet.airframe.ulid.ULID
    import java.util.UUID
    val ownerId = OwnerId(ULID.fromString("01F3Z0GHD0P7S9T7RK7JDJGZ8H"))
    val storeId = StoreId(UUID.fromString("cafecafe-beef-beef-beef-cafecafecafe"))
    for {
      owner <- MediaService.ownerCreate(Some(ownerId), FirstName("John"), LastName("Doe"), None)
      _     <- MediaService.storeCreate(Some(storeId), owner.id, BaseDirectoryPath(Path.of("samples")), None, None)
      // _     <- MediaService.synchronize()
    } yield ()
  }

  val bootstrapForQuickUsage = {
    for {
      ownerId        <- System.env("PHOTOS_OWNER_ID")
      ownerFirstName <- System.env("PHOTOS_OWNER_FIRST_NAME")
      ownerLastName  <- System.env("PHOTOS_OWNER_LAST_NAME")
      storeId        <- System.env("PHOTOS_STORE_ID")
      searchRoot     <- System.env("PHOTOS_SEARCH_ROOT").some
      includeMask    <- System.env("PHOTOS_SEARCH_INCLUDE_MASK")
      ignoreMask     <- System.env("PHOTOS_SEARCH_IGNORE_MASK")
      owner          <- MediaService.ownerCreate(
                          providedOwnerId = ownerId.map(OwnerId.fromString),
                          firstName = FirstName(ownerFirstName.getOrElse("john")),
                          lastName = LastName(ownerLastName.getOrElse("Doe")),
                          birthDate = None
                        )
      store          <- MediaService.storeCreate(
                          providedStoreId = storeId.map(StoreId.fromString),
                          ownerId = owner.id,
                          baseDirectory = BaseDirectoryPath(Path.of(searchRoot)),
                          includeMask = includeMask.map(IncludeMask.fromString),
                          ignoreMask = ignoreMask.map(IgnoreMask.fromString)
                        )
      rulesJson      <- System.env("PHOTOS_SEARCH_KEYWORD_RULES")
      rulesEither    <- ZIO.from(rulesJson.map(_.fromJson[KeywordRules]))
      rules          <- ZIO
                          .fromEither(rulesEither)
                          .logError("Failed to parse keyword rules")
                          .option
      _              <- ZIO.foreachDiscard(rules)(MediaService.keywordRulesUpsert(store.id, _))
      // _              <- MediaService.synchronize()
    } yield ()
  }

  val photoViewApp = {
    for {
      isTestEnv    <- System.env("PHOTOS_TEST_ENV").map(_.filter(_.trim.toLowerCase == "true").isDefined)
      isFirstStart <- MediaService.storeList().runCount.map(_ == 0)
      _            <- bootstrapForTest.when(isFirstStart && isTestEnv)
      //      _            <- bootstrapForQuickUsage.when(isFirstStart && !isTestEnv)
      //      syncFiber    <- MediaService.synchronize()//.fork // temporary call
      fx           <- ZIO.succeed(FxApp())
      _            <- ZIO.attemptBlocking(fx.main(Array.empty)).fork
      _            <- fxBridge(fx)
      // _            <- syncFiber.join
    } yield ()
  }

  val configProvider      = TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load())
  val configProviderLayer = Runtime.setConfigProvider(configProvider)

  override def run = photoViewApp.provide(
    configProviderLayer >>> LMDB.live,
    configProviderLayer >>> SearchService.live,
    MediaService.live,
    Scope.default,
    configProviderLayer
  )
}
