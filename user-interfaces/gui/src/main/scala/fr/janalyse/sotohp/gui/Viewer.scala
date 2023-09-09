package fr.janalyse.sotohp.gui

import javafx.application.*
import javafx.scene.*
import javafx.scene.image.*
import javafx.scene.layout.*
import javafx.scene.control.*
import javafx.scene.input.*
import javafx.event.*
import javafx.stage.*

import java.nio.file.Path

class Viewer extends Application {

  override def init(): Unit = super.init()

  def loadImage(filepath: Path): Image = {
    Image(java.io.FileInputStream(filepath.toFile))
  }

  lazy val imageFilenames = {
    import zio.*
    import zio.config.typesafe.*
    import zio.lmdb.LMDB
    import fr.janalyse.sotohp.store.PhotoStoreService
    import fr.janalyse.sotohp.core.PhotoStream

    val logic =
      PhotoStream
        .photoLazyStream()
        .filterZIO(_.foundFaces.map(faces => faces.exists(_.count == 0))) // Only photo with people faces :)
        .mapZIO(_.photoNormalizedPath.either)
        .runCollect
        .map(_.map(_.toOption).flatten)                                   // TODO BAD VERY BAD OF COURSE - for temporary quick & dirty implementation
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

  private var position: Int = 0 // TODO BAD VERY BAD OF COURSE - for temporary quick & dirty implementation

  def firstImage(): Image = {
    position = 0
    loadImage(imageFilenames(position))
  }
  def prevImage(): Image  = {
    position = position - 1
    if (position < 0) position = imageFilenames.size - 1
    loadImage(imageFilenames(position))
  }
  def nextImage(): Image  = {
    position = (position + 1) % imageFilenames.size
    loadImage(imageFilenames(position))
  }
  def lastImage(): Image  = {
    position = imageFilenames.size - 1
    loadImage(imageFilenames(position))
  }

  override def start(stage: Stage): Unit = {
    val imageView = ImageView()
    imageView.setImage(loadImage(imageFilenames.head))
    imageView.setPreserveRatio(true)

    val actionFirst = () => imageView.setImage(firstImage())
    val actionNext  = () => imageView.setImage(nextImage())
    val actionPrev  = () => imageView.setImage(prevImage())
    val actionLast  = () => imageView.setImage(lastImage())

    val buttonNext = Button("next")
    buttonNext.setOnAction(event => actionNext())

    val buttonPrev = Button("previous")
    buttonPrev.setOnAction(event => actionPrev())

    val hbox = HBox(buttonPrev, buttonNext)

    val vbox  = VBox(imageView, hbox)
    val scene = Scene(vbox, 900, 600)

    // imageView.fitWidthProperty().bind(stage.widthProperty())
    // imageView.fitHeightProperty().bind(stage.heightProperty())
    imageView.fitHeightProperty().bind(stage.heightProperty())

    val keyHandler: EventHandler[KeyEvent] = keyEvent => {
      keyEvent.getCode match {
        case KeyCode.PAGE_UP   => actionPrev()
        case KeyCode.PAGE_DOWN => actionNext()
        case KeyCode.HOME      => actionFirst()
        case KeyCode.END       => actionLast()
        case _                 =>
      }
    }
    // scene.setOnKeyReleased(keyHandler)
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
