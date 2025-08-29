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
import scalafx.scene.layout.{HBox, Region, VBox, Priority, StackPane}
import scalafx.application.Platform
import scalafx.scene.image.Image
import scalafx.Includes.jfxRegion2sfx
import scalafx.scene.input.{Clipboard, ClipboardContent, DataFormat}

import java.nio.file.Path
import java.io.ByteArrayInputStream

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
    lazy val mosaicToggle = Button("▦") // SQUARE FOUR CORNERS (mosaic toggle)
    lazy val display      = PhotoDisplay()
    lazy val mosaic       = MosaicDisplay()
    lazy val displaySFX   = jfxRegion2sfx(display)
    lazy val mosaicSFX    = jfxRegion2sfx(mosaic)
    lazy val stack        = new StackPane { children = Seq(displaySFX, mosaicSFX) }
    lazy val buttons      = HBox(first, previous, next, last, zoom, faces, rotateLeft, rotateRight, mosaicToggle)
    lazy val infos        = HBox(5d, infoHasGPS, infoDateTime, infoEvent)
    lazy val controls     = VBox(buttons, infos)

    override def start(): Unit         = {
      stage = new JFXApp3.PrimaryStage {
        title = "SOTOHP Viewer"
        scene = new Scene {
          content = VBox(
            stack,
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
      VBox.setVgrow(stack, Priority.Always)
      stack.prefWidth <== stage.width
      stack.prefHeight <== (stage.height - controls.height)
      mosaicSFX.visible = false
      mosaicSFX.managed = false
      displaySFX.visible = true
      displaySFX.managed = true
    }
    def switchToMosaic(): Unit = {
      mosaicSFX.visible = true
      mosaicSFX.managed = true
      displaySFX.visible = false
      displaySFX.managed = false
    }
    def switchToSingle(): Unit = {
      mosaicSFX.visible = false
      mosaicSFX.managed = false
      displaySFX.visible = true
      displaySFX.managed = true
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
        .appendedAll(s" (${photo.media.original.dimension.map(d => d.width.toString+"x"+d.height.toString)})")
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
      userActionHub     <- Hub.unbounded[UserAction]
      photoHub          <- Hub.unbounded[PhotoToShow]
      currentPhotoRef   <- Ref.make(Option.empty[PhotoToShow])
      mosaicLoadedRef   <- Ref.make(false)
      mosaicVisibleRef  <- Ref.make(false)
      toFirstPhoto       = MediaService
                             .mediaFirst()
                             .some
                             .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                             .flatMap(photo => photoHub.offer(photo))
      toLastPhoto        = MediaService
                             .mediaLast()
                             .some
                             .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                             .flatMap(photo => photoHub.offer(photo))
      toPreviousPhoto    = currentPhotoRef.get.flatMap(current =>
                             MediaService
                               .mediaPrevious(current.get.media.accessKey)
                               .some
                               .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                               .flatMap(photo => photoHub.offer(photo))
                               .orElse(toFirstPhoto)
                           )
      toNextPhoto        = currentPhotoRef.get.flatMap(current =>
                             MediaService
                               .mediaNext(current.get.media.accessKey)
                               .some
                               .flatMap(photo => PhotoToShow.fromLazyPhoto(photo))
                               .flatMap(photo => photoHub.offer(photo))
                               .orElse(toLastPhoto)
                           )
      loadedCountRef    <- Ref.make(0)
      lastLoadedKeyRef  <- Ref.make(Option.empty[MediaAccessKey])
      loadSem           <- Semaphore.make(1)
      ensureLoaded      <- ZIO.succeed { (desired: Int) =>
                             loadSem.withPermit {
                               def loop(maybeKey: Option[MediaAccessKey]): ZIO[MediaService, Any, Unit] =
                                 for {
                                   count <- loadedCountRef.get
                                   _     <- if (count >= desired) ZIO.unit
                                            else {
                                              for {
                                                maybeMedia <- maybeKey match
                                                               case None    => MediaService.mediaFirst()
                                                               case Some(k) => MediaService.mediaNext(k)
                                                _ <- ZIO.foreachDiscard(maybeMedia) { media =>
                                                       val key = media.accessKey
                                                       for {
                                                         res   <- MediaService.mediaMiniatureRead(key).runCollect.either
                                                         added <- res.fold(
                                                                    _ => ZIO.succeed(false),
                                                                    bytes => {
                                                                      val arr = bytes.toArray
                                                                      ZIO
                                                                        .attempt {
                                                                          if (arr.nonEmpty) {
                                                                            val image = new javafx.scene.image.Image(new ByteArrayInputStream(arr), 180, 180, true, true)
                                                                            val ok    = image != null && image.getWidth > 0 && !image.isError
                                                                            if (ok) Platform.runLater(fxApp.mosaic.addTile(key, image))
                                                                            ok
                                                                          } else false
                                                                        }
                                                                        .catchAll(_ => ZIO.succeed(false))
                                                                    }
                                                                  )
                                                         _     <- lastLoadedKeyRef.set(Some(key))
                                                         _     <- ZIO.when(added)(loadedCountRef.update(_ + 1))
                                                         _     <- loop(Some(key))
                                                       } yield ()
                                                     }
                                              } yield ()
                                            }
                                 } yield ()
                               for {
                                 start <- lastLoadedKeyRef.get
                                 _     <- loop(start)
                               } yield ()
                             }
                           }
      setupMosaicLazy   <- ZIO.succeed {
                             for {
                               already <- mosaicLoadedRef.get
                               _       <- (for {
                                             _ <- ZIO.attempt(Platform.runLater(fxApp.mosaic.clearTiles())).logError("Can't clear the mosaic").ignore
                                             _ <- loadedCountRef.set(0)
                                             _ <- lastLoadedKeyRef.set(None)
                                             _ <- ZStream
                                                    .async[MediaService, Throwable, Unit] { callback =>
                                                      fxApp.mosaic.onNeedMore = (desired: Int) => callback(ensureLoaded(desired).logError("not loaded").ignore.as(Chunk.unit))
                                                    }
                                                    .runDrain
                                                    .forkDaemon
                                             _ <- ZIO.attempt(Platform.runLater(fxApp.mosaic.triggerNeedMore())).logError("Can't not load more").ignore
                                             _ <- mosaicLoadedRef.set(true)
                                           } yield ()).when(!already)
                             } yield ()
                           }
      _                 <- ZStream
                             .fromHub(userActionHub)
                             .runForeach {
                               case UserAction.First    => toFirstPhoto
                               case UserAction.Previous => toPreviousPhoto
                               case UserAction.Next     => toNextPhoto
                               case UserAction.Last     => toLastPhoto
                             }
                             .fork
      _                 <- ZStream
                             .fromHub(photoHub)
                             .runForeach(currentPhoto => currentPhotoRef.update(_ => Some(currentPhoto)))
                             .fork
      _                 <- ZStream
                             .async(callback => fxApp.first.onAction = (_ => callback(userActionHub.offer(UserAction.First).as(Chunk.unit))))
                             .runDrain
                             .fork
      _                 <- ZStream
                             .async(callback => fxApp.previous.onAction = (_ => callback(userActionHub.offer(UserAction.Previous).as(Chunk.unit))))
                             .runDrain
                             .fork
      _                 <- ZStream
                             .async(callback => fxApp.next.onAction = (_ => callback(userActionHub.offer(UserAction.Next).as(Chunk.unit))))
                             .runDrain
                             .fork
      _                 <- ZStream
                             .async(callback => fxApp.last.onAction = (_ => callback(userActionHub.offer(UserAction.Last).as(Chunk.unit))))
                             .runDrain
                             .fork
      // Mosaic toggle handler
      _                 <- ZStream
                             .async[MediaService, Throwable, Unit] { callback =>
                               fxApp.mosaicToggle.onAction = (_ => callback({
                                 val eff = for {
                                   visible <- mosaicVisibleRef.get
                                   _       <- if (visible) {
                                                 ZIO.attempt(Platform.runLater(fxApp.switchToSingle())).ignore *>
                                                 mosaicVisibleRef.set(false)
                                               } else {
                                                 ZIO.attempt(Platform.runLater(fxApp.switchToMosaic())).ignore *>
                                                 mosaicVisibleRef.set(true) *>
                                                 setupMosaicLazy.forkDaemon.unit *>
                                                 ZIO.attempt(Platform.runLater(fxApp.mosaic.triggerNeedMore())).logError("Couldn't trigger need more").ignore *>
                                                 // Fallback: ensure at least one tile loads immediately
                                                 ensureLoaded(1).forkDaemon.unit
                                               }
                                 } yield ()
                                 eff.ignore.as(Chunk.unit)
                               }))
                             }
                             .runDrain
                             .fork
      // Mosaic selection handler: open clicked photo and switch back to single view
      _                 <- ZStream
                             .async[MediaService, Throwable, Unit] { callback =>
                               fxApp.mosaic.onSelect = (key => callback({
                                 val eff = for {
                                   maybe  <- MediaService.mediaGet(key)
                                   _      <- ZIO.foreachDiscard(maybe)(m => PhotoToShow.fromLazyPhoto(m).flatMap(photoHub.offer))
                                   _      <- ZIO.attemptBlocking(Platform.runLater(fxApp.switchToSingle()))
                                   _      <- mosaicVisibleRef.set(false)
                                 } yield ()
                                 eff.logError("mosaic selection handler").ignore.as(Chunk.unit)
                               }))
                             }
                             .runDrain
                             .fork
      ui                <- ZStream
                             .fromHub(photoHub)
                             .runForeach(photo => ZIO.attemptBlocking(Platform.runLater(fxApp.show(photo))))
                             .fork
      _                 <- toFirstPhoto
      // _               <- toNextPhoto.repeat(Schedule.fixed(2.seconds)).fork // run a default slideshow
      _                 <- ui.join
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
