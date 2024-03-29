package fr.janalyse.sotohp.gui

import scala.math.min
import javafx.scene.canvas.Canvas
import javafx.scene.layout.Background
import javafx.scene.image.Image
import javafx.scene.paint.Color
import javafx.geometry
import javafx.geometry.{HPos, VPos}
import javafx.scene.layout.Region

class PhotoDisplay extends Region {
  private var currentPhoto: Option[PhotoToShow] = None
  private var currentImage: Option[Image]       = None

  private var imageX  = 0d
  private var imageY  = 0d
  private var centerX = 0d
  private var centerY = 0d

  private var rotationDegrees  = 0
  private var showFaces        = false
  private val zoomLevelDefault = 1d
  private val zoomLevelMax     = 10d
  private var zoomLevel        = zoomLevelDefault
  private val zoomStep         = 0.5d

  private var currentCanvas: Option[Canvas] = {
    val canvas = new Canvas(3 * 1920, 3 * 1080)
    getChildren.add(canvas)
    setBackground(Background.fill(Color.BLACK))
    Some(canvas)
  }

  def isZoomed() = zoomLevel > zoomLevelDefault

  def clear(): Unit = currentCanvas.foreach { canvas =>
    val gc = canvas.getGraphicsContext2D
    gc.save()
    gc.translate(centerX, centerY)
    gc.rotate(rotationDegrees)
    gc.translate(-centerX, -centerY)
    gc.setFill(Color.BLACK)
    gc.clearRect(imageX, imageY, canvas.getWidth, canvas.getHeight)
    gc.restore()
  }

  private def setup(): Unit = {
    currentPhoto.foreach { photo =>
      clear()
      val (filepath, rotationDegrees) = {
        if (isZoomed()) photo.source.original.path -> photo.orientation.map(_.rotationDegrees).getOrElse(0)
        else photo.normalizedPath.map(_ -> 0).getOrElse(
          photo.source.original.path -> photo.orientation.map(_.rotationDegrees).getOrElse(0)
        )
      }
      val image    = Image(java.io.FileInputStream(filepath.toFile))
      this.rotationDegrees = rotationDegrees
      this.currentImage = Some(image)
      currentCanvas.foreach { canvas =>
        this.imageX = canvas.getWidth() / 2 - image.getWidth / 2
        this.imageY = canvas.getHeight() / 2 - image.getHeight / 2
        this.centerX = imageX + image.getWidth / 2
        this.centerY = imageY + image.getHeight / 2
        displayPhoto()
      }
    }
  }

  def drawImage(photo: PhotoToShow): Unit = {
    this.currentPhoto = Some(photo)
    setup()
  }

  def displayPhoto(): Unit = {
    currentPhoto.foreach { photo =>
      currentImage.foreach { image =>
        currentCanvas.foreach { canvas =>
          val gc = canvas.getGraphicsContext2D
          gc.save()
          gc.translate(centerX, centerY)
          gc.rotate(rotationDegrees)
          gc.translate(-centerX, -centerY)
          gc.drawImage(image, imageX, imageY, image.getWidth, image.getHeight)
          if (showFaces) {
            photo.foundFaces.foreach { photoFaces =>
              photoFaces.faces.foreach { face =>
                import face.box.x, face.box.y, face.box.{width => w}, face.box.{height => h}
                gc.setLineWidth(2d)
                gc.setStroke(Color.BLUE)
                gc.strokeRect(
                  imageX + x * image.getWidth,
                  imageY + y * image.getHeight,
                  w * image.getWidth,
                  h * image.getHeight
                )
              }
            }
          }
          gc.restore()
          layoutChildren()
        }
      }
    }
  }

  def toggleFaces(): Unit = {
    showFaces = !showFaces
    clear()
    displayPhoto()
  }

  def zoomIn(): Unit = {
    if (zoomLevel < zoomLevelMax) {
      clear()
      val wasZoomed = isZoomed()
      zoomLevel = zoomLevel + zoomStep
      if (!wasZoomed) setup()
      else displayPhoto()
    }
  }

  def zoomOut(): Unit = {
    if (isZoomed()) {
      clear()
      zoomLevel = zoomLevel - zoomStep
      if (!isZoomed()) setup()
      displayPhoto()
    }
  }

  def zoomReset(): Unit = {
    if (isZoomed()) {
      zoomLevel = zoomLevelDefault
      setup()
    }
  }

  def rotateLeft(): Unit = currentCanvas.foreach { canvas =>
    clear()
    rotationDegrees = (rotationDegrees + 270) % 360
    displayPhoto()
  }

  def rotateRight(): Unit = currentCanvas.foreach { canvas =>
    clear()
    rotationDegrees = (rotationDegrees + 90) % 360
    displayPhoto()
  }

  override def layoutChildren(): Unit = currentCanvas.foreach { canvas =>
    currentImage.foreach { image =>
      val x     = getInsets.getLeft
      val y     = getInsets.getTop
      val w     = getWidth - getInsets.getRight - x
      val h     = getHeight - getInsets.getBottom - y
      val ratio = zoomLevel * (
        if (rotationDegrees == 90 || rotationDegrees == 270)
          min(w / image.getHeight, h / image.getWidth)
        else
          min(w / image.getWidth, h / image.getHeight)
      )
      canvas.setScaleX(ratio)
      canvas.setScaleY(ratio)
      positionInArea(canvas, x, y, w, h, -1, HPos.CENTER, VPos.CENTER)
    }
  }

}
