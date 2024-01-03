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
  private var image: Option[Image] = None
  private var imageWidth           = width
  private var imageHeight          = height
  private var imageX               = 0d
  private var imageY               = 0d
  private var centerX              = 0d
  private var centerY              = 0d
  private var rotationDegrees      = 0

  private val canvas = Canvas(width, height)
  getChildren.add(canvas)

  def restore() = {
    val gc = canvas.getGraphicsContext2D
    gc.setFill(Color.WHITE)
    gc.clearRect(imageX, imageY, imageWidth, imageHeight)
    gc.clearRect(imageX, imageY, imageHeight, imageWidth)
    gc.restore()
  }

  def drawImage(image: Image, rotationDegrees: Int): Unit = {
    restore()
    this.rotationDegrees = rotationDegrees
    this.image = Some(image)
    this.imageWidth = this.image.map(_.getWidth).getOrElse(width)
    this.imageHeight = this.image.map(_.getHeight).getOrElse(height)
    this.imageX = canvas.getWidth() / 2 - imageWidth / 2
    this.imageY = canvas.getHeight() / 2 - imageHeight / 2
    this.centerX = imageX + imageWidth / 2
    this.centerY = imageY + imageHeight / 2
    val gc = canvas.getGraphicsContext2D
    gc.translate(centerX, centerY)
    gc.rotate(rotationDegrees)
    gc.translate(-centerX, -centerY)
    gc.drawImage(image, imageX, imageY, imageWidth, imageHeight)
    gc.translate(centerX, centerY)
    gc.rotate(-rotationDegrees)
    gc.translate(-centerX, -centerY)
    layoutChildren()
  }

  def addRect(x: Double, y: Double, w: Double, h: Double): Unit = {
    val gc = canvas.getGraphicsContext2D
    gc.setLineWidth(2d)
    gc.setStroke(Color.BLUE)
    gc.strokeRect(imageX + x * imageWidth, imageY + y * imageHeight, w * imageWidth, h * imageHeight)
  }

  def rotateLeft(): Unit = {
    if (image.isDefined) {
      restore()
      rotationDegrees = (rotationDegrees + 270) % 360
      val gc = canvas.getGraphicsContext2D
      gc.translate(centerX, centerY)
      gc.rotate(rotationDegrees)
      gc.translate(-centerX, -centerY)
      gc.drawImage(image.get, imageX, imageY, imageWidth, imageHeight)
      gc.translate(centerX, centerY)
      gc.rotate(-rotationDegrees)
      gc.translate(-centerX, -centerY)
      layoutChildren()
    }
  }

  def rotateRight(): Unit = {
    if (image.isDefined) {
      restore()
      rotationDegrees = (rotationDegrees + 90) % 360
      val gc = canvas.getGraphicsContext2D
      gc.translate(centerX, centerY)
      gc.rotate(rotationDegrees)
      gc.translate(-centerX, -centerY)
      gc.drawImage(image.get, imageX, imageY, imageWidth, imageHeight)
      gc.translate(centerX, centerY)
      gc.rotate(-rotationDegrees)
      gc.translate(-centerX, -centerY)
      layoutChildren()
    }
  }

  override def layoutChildren(): Unit = {
    val x     = getInsets.getLeft
    val y     = getInsets.getTop
    val w     = getWidth - getInsets.getRight - x
    val h     = getHeight - getInsets.getBottom - y
    val ratio =
      if (rotationDegrees == 90 || rotationDegrees == 270)
        Math.min(w / imageHeight, h / imageWidth)
      else
        Math.min(w / imageWidth, h / imageHeight)
    canvas.setScaleX(ratio)
    canvas.setScaleY(ratio)
    positionInArea(canvas, x, y, w, h, -1, HPos.CENTER, VPos.CENTER)
  }
}

case class PhotoToView(
  shootDateTime: Option[OffsetDateTime],
  orientation: Option[PhotoOrientation],
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

  def showImage(canvas: AutoScalingImageCanvas, label: Label, photo: PhotoToView): Unit = {
    label.setText(photo.description.flatMap(_.category.map(_.text)).getOrElse("no category"))
    val filepath = photo.normalizedPath.getOrElse(photo.source.original.path)
    val image    = Image(java.io.FileInputStream(filepath.toFile))
    //canvas.drawImage(image, photo.orientation.map(_.rotationDegrees).getOrElse(0))
    canvas.drawImage(image, 0) // normalized photo are already rotated
    if (showFaces) drawFaces(canvas, photo)
    println(photo.orientation)
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
      orientation     <- zphoto.metaData.map(_.flatMap(_.orientation))
      miniatures      <- zphoto.miniatures
      normalized      <- zphoto.normalized
      normalizedPath  <- PhotoOperations.makeNormalizedFilePath(source).when(normalized.isDefined)
      description     <- zphoto.description
      classifications <- zphoto.foundClassifications
      objects         <- zphoto.foundObjects
      faces           <- zphoto.foundFaces
      photoToView      = PhotoToView(
                           shootDateTime = shootDateTime,
                           orientation = orientation,
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
//        .filter(_.normalizedPath.exists(p => p.toFile.exists())) // TODO temporary hack
//         .filter(_.foundFaces.exists(faces => faces.count > 0)) // Only photo with people faces :)
//        .take(100)
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

  def firstImage(canvas: AutoScalingImageCanvas, label: Label): Unit = {
    position = 0
    showImage(canvas, label, photos(position))
  }

  def prevImage(canvas: AutoScalingImageCanvas, label: Label): Unit = {
    position = position - 1
    if (position < 0) position = photos.size - 1
    showImage(canvas, label, photos(position))
  }

  def nextImage(canvas: AutoScalingImageCanvas, label: Label): Unit = {
    position = (position + 1) % photos.size
    showImage(canvas, label, photos(position))
  }

  def lastImage(canvas: AutoScalingImageCanvas, label: Label): Unit = {
    position = photos.size - 1
    showImage(canvas, label, photos(position))
  }

  def reloadImage(canvas: AutoScalingImageCanvas, label: Label): Unit = {
    showImage(canvas, label, photos(position))
  }

  override def start(stage: Stage): Unit = {
    val imageView = AutoScalingImageCanvas(1920 * 2, 1080 * 2)

    val photoLabel = Label("something")
    firstImage(imageView, photoLabel)

    val actionFirst       = () => firstImage(imageView, photoLabel)
    val actionNext        = () => nextImage(imageView, photoLabel)
    val actionPrev        = () => prevImage(imageView, photoLabel)
    val actionLast        = () => lastImage(imageView, photoLabel)
    val actionFaces       = () => { showFaces = !showFaces; reloadImage(imageView, photoLabel) }
    val actionRotateLeft  = () => { imageView.rotateLeft() }
    val actionRotateRight = () => { imageView.rotateRight() }

    val buttonFirst = Button("first")
    buttonFirst.setOnAction(event => actionFirst())

    val buttonPrev = Button("previous")
    buttonPrev.setOnAction(event => actionPrev())

    val buttonNext = Button("next")
    buttonNext.setOnAction(event => actionNext())

    val buttonLast = Button("last")
    buttonLast.setOnAction(event => actionLast())

    val buttonFaces = Button("faces")
    buttonFaces.setOnAction(event => actionFaces())

    val hbox = HBox(buttonFirst, buttonPrev, buttonNext, buttonLast, buttonFaces, photoLabel)

    val vbox  = VBox(imageView, hbox)
    val scene = Scene(vbox, 900, 600)

    val keyHandler: EventHandler[KeyEvent] = keyEvent => {
      keyEvent.getCode match {
        case KeyCode.PAGE_UP       => actionPrev()
        case KeyCode.PAGE_DOWN     => actionNext()
        case KeyCode.OPEN_BRACKET  => actionRotateLeft()
        case KeyCode.CLOSE_BRACKET => actionRotateRight()
        case KeyCode.HOME          => actionFirst()
        case KeyCode.END           => actionLast()
        case KeyCode.INSERT        => actionFaces()
        case _                     =>
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
