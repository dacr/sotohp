package fr.janalyse.sotohp.gui

import fr.janalyse.sotohp.config.SotohpConfig
import fr.janalyse.sotohp.core.PhotoOperations
import javafx.application.*
import javafx.scene.*
import javafx.scene.image.*
import javafx.scene.layout.*
import javafx.scene.control.*
import javafx.scene.input.*
import javafx.scene.paint.*
import javafx.scene.canvas.*
import javafx.event.*
import javafx.stage.*
import javafx.geometry.*

import java.nio.file.Path
import java.time.OffsetDateTime

import fr.janalyse.sotohp.model.*

class AutoScalingImageCanvas(width: Double, height: Double) extends Region {
  var imageWidth  = width
  var imageHeight = height
  var imageX      = 0d
  var imageY      = 0d
  val canvas      = Canvas(width, height)
  getChildren.add(canvas)

  def getGraphicsContext2D() = canvas.getGraphicsContext2D

  def clear() = {
    val gc = getGraphicsContext2D()
    gc.setFill(Color.BLACK)
    gc.clearRect(0, 0, width, height)
  }

  def drawImage(image: Image): Unit = {
    clear()
    val gc = getGraphicsContext2D()
    imageWidth = image.getWidth
    imageHeight = image.getHeight
    imageX = canvas.getWidth() / 2 - imageWidth / 2
    imageY = canvas.getHeight() / 2 - imageHeight / 2
    gc.drawImage(image, imageX, imageY, imageWidth, imageHeight)
    layoutChildren()
  }

  def addRect(x: Double, y: Double, w: Double, h: Double): Unit = {
    val gc = getGraphicsContext2D()
    gc.setLineWidth(2d)
    gc.setStroke(Color.BLUE)
    gc.strokeRect(imageX + x * imageWidth, imageY + y * imageHeight, w * imageWidth, h * imageHeight)
  }

  override def layoutChildren(): Unit = {
    val x     = getInsets.getLeft
    val y     = getInsets.getTop
    val w     = getWidth - getInsets.getRight - x
    val h     = getHeight - getInsets.getBottom - y
    val ratio = Math.min(w / imageWidth, h / imageHeight)
    canvas.setScaleX(ratio)
    canvas.setScaleY(ratio)
    positionInArea(canvas, x, y, w, h, -1, HPos.CENTER, VPos.CENTER)
  }
}

case class PhotoToView(
  shootDateTime: Option[OffsetDateTime],
  source: PhotoSource,
  place: Option[PhotoPlace] = None,
  miniatures: Option[Miniatures] = None,
  normalized: Option[NormalizedPhoto] = None,
  normalizedPath: Option[Path] = None,
  description: Option[PhotoDescription] = None,
  foundClassifications: Option[PhotoClassifications] = None,
  foundObjects: Option[PhotoObjects] = None,
  foundFaces: Option[PhotoFaces] = None
)

class Viewer extends Application {

  override def init(): Unit = super.init()

  def drawFaces(canvas: AutoScalingImageCanvas, photo: PhotoToView): Unit = {
    photo.foundFaces.foreach(foundFaces => foundFaces.faces.foreach(face => canvas.addRect(face.box.x, face.box.y, face.box.width, face.box.height)))
  }

  def showImage(canvas: AutoScalingImageCanvas, photo: PhotoToView): Unit = {
    val filepath = photo.normalizedPath.getOrElse(photo.source.original.path)
    val image    = Image(java.io.FileInputStream(filepath.toFile))
    canvas.drawImage(image)
    if (showFaces) drawFaces(canvas, photo)
  }

  lazy val photos = {
    import zio.*
    import zio.config.typesafe.*
    import zio.lmdb.LMDB
    import fr.janalyse.sotohp.store.PhotoStoreService
    import fr.janalyse.sotohp.store.ZPhoto
    import fr.janalyse.sotohp.core.PhotoStream

    def toPhotoToView(zphoto: ZPhoto): ZIO[PhotoStoreService, Any, PhotoToView] = for {
      source          <- zphoto.source.some
      place           <- zphoto.place
      shootDateTime   <- zphoto.metaData.map(_.flatMap(_.shootDateTime))
      miniatures      <- zphoto.miniatures
      normalized      <- zphoto.normalized
      normalizedPath  <- PhotoOperations.makeNormalizedFilePath(source).when(normalized.isDefined)
      description     <- zphoto.description
      classifications <- zphoto.foundClassifications
      objects         <- zphoto.foundObjects
      faces           <- zphoto.foundFaces
      photoToView      = PhotoToView(
                           shootDateTime = shootDateTime,
                           source = source,
                           place = place,
                           miniatures = miniatures,
                           normalized = normalized,
                           normalizedPath = normalizedPath,
                           description = description,
                           foundClassifications = classifications,
                           foundObjects = objects,
                           foundFaces = faces
                         )
    } yield photoToView

    val logic =
      PhotoStream
        .photoLazyStream()
        .mapZIO(toPhotoToView)
        // .filterZIO(_.foundFaces.map(faces => faces.exists(_.count == 0))) // Only photo with people faces :)
        .runCollect
        .provide(
          LMDB.liveWithDatabaseName("photos"),
          PhotoStoreService.live,
          Scope.default,
          Runtime.setConfigProvider(TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load()))
        )

    val runtime = Runtime.default
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(logic).getOrThrowFiberFailure()
    }
  }

  private var position: Int      = 0     // TODO BAD VERY BAD OF COURSE - for temporary quick & dirty implementation
  private var showFaces: Boolean = false // TODO BAD VERY BAD OF COURSE - for temporary quick & dirty implementation

  def firstImage(canvas: AutoScalingImageCanvas): Unit = {
    position = 0
    showImage(canvas, photos(position))
  }

  def prevImage(canvas: AutoScalingImageCanvas): Unit = {
    position = position - 1
    if (position < 0) position = photos.size - 1
    showImage(canvas, photos(position))
  }

  def nextImage(canvas: AutoScalingImageCanvas): Unit = {
    position = (position + 1) % photos.size
    showImage(canvas, photos(position))
  }

  def lastImage(canvas: AutoScalingImageCanvas): Unit = {
    position = photos.size - 1
    showImage(canvas, photos(position))
  }

  def reloadImage(canvas: AutoScalingImageCanvas): Unit = {
    showImage(canvas, photos(position))
  }

  override def start(stage: Stage): Unit = {
    val imageView = AutoScalingImageCanvas(1920 * 2, 1080 * 2)
    firstImage(imageView)

    val actionFirst = () => firstImage(imageView)
    val actionNext  = () => nextImage(imageView)
    val actionPrev  = () => prevImage(imageView)
    val actionLast  = () => lastImage(imageView)
    val actionFaces = () => { showFaces = !showFaces; reloadImage(imageView) }

    val buttonNext = Button("next")
    buttonNext.setOnAction(event => actionNext())

    val buttonPrev = Button("previous")
    buttonPrev.setOnAction(event => actionPrev())

    val hbox = HBox(buttonPrev, buttonNext)

    val vbox  = VBox(imageView, hbox)
    val scene = Scene(vbox, 900, 600)

    val keyHandler: EventHandler[KeyEvent] = keyEvent => {
      keyEvent.getCode match {
        case KeyCode.PAGE_UP   => actionPrev()
        case KeyCode.PAGE_DOWN => actionNext()
        case KeyCode.HOME      => actionFirst()
        case KeyCode.END       => actionLast()
        case KeyCode.INSERT    => actionFaces()
        case _                 =>
      }
    }
    scene.setOnKeyPressed(keyHandler)

    stage.setTitle("Photo viewer")
    stage.setScene(scene)
    stage.show()
  }

  override def stop(): Unit = {}

  def go(): Unit = {
    Application.launch()
  }
}

object Viewer {
  def main(args: Array[String]): Unit = {
    new Viewer().go()
  }
}
