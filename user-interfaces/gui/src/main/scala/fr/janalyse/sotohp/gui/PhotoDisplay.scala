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
  private var imageOption: Option[Image]   = None
  private var canvasOption: Option[Canvas] = None

  private var imageX          = 0d
  private var imageY          = 0d
  private var centerX         = 0d
  private var centerY         = 0d
  private var rotationDegrees = 0

  setBackground(Background.fill(Color.BLACK))

  def restore(): Unit = canvasOption.foreach { canvas =>
    val gc = canvas.getGraphicsContext2D
    gc.setFill(Color.WHITE)
    gc.clearRect(imageX, imageY, canvas.getWidth, canvas.getHeight)
    gc.restore()
  }

  def drawImage(image: Image, rotationDegrees: Int = 0): Unit = {
    restore()
    this.rotationDegrees = rotationDegrees
    this.imageOption = Some(image)
    val canvas = new Canvas(image.getWidth, image.getHeight)
    canvasOption.foreach(canvas => getChildren.remove(canvas))
    getChildren.add(canvas)
    this.canvasOption = Some(canvas)

    this.imageX = canvas.getWidth() / 2 - image.getWidth / 2
    this.imageY = canvas.getHeight() / 2 - image.getHeight / 2
    this.centerX = imageX + image.getWidth / 2
    this.centerY = imageY + image.getHeight / 2
    val gc = canvas.getGraphicsContext2D
    gc.translate(centerX, centerY)
    gc.rotate(rotationDegrees)
    gc.translate(-centerX, -centerY)
    gc.drawImage(image, imageX, imageY, image.getWidth, image.getHeight)
    gc.translate(centerX, centerY)
    gc.rotate(-rotationDegrees)
    gc.translate(-centerX, -centerY)
    layoutChildren()
  }

  def addRect(x: Double, y: Double, w: Double, h: Double): Unit = canvasOption.foreach { canvas =>
    imageOption.foreach { image =>
      val gc = canvas.getGraphicsContext2D
      gc.setLineWidth(2d)
      gc.setStroke(Color.BLUE)
      gc.strokeRect(imageX + x * image.getWidth, imageY + y * image.getHeight, w * image.getWidth, h * image.getHeight)
    }
  }

  def rotateLeft(): Unit = canvasOption.foreach { canvas =>
    imageOption.foreach { image =>
      restore()
      rotationDegrees = (rotationDegrees + 270) % 360
      val gc = canvas.getGraphicsContext2D
      gc.translate(centerX, centerY)
      gc.rotate(rotationDegrees)
      gc.translate(-centerX, -centerY)
      gc.drawImage(image, imageX, imageY, image.getWidth, image.getHeight)
      gc.translate(centerX, centerY)
      gc.rotate(-rotationDegrees)
      gc.translate(-centerX, -centerY)
      layoutChildren()
    }
  }

  def rotateRight(): Unit = canvasOption.foreach { canvas =>
    imageOption.foreach { image =>
      restore()
      rotationDegrees = (rotationDegrees + 90) % 360
      val gc = canvas.getGraphicsContext2D
      gc.translate(centerX, centerY)
      gc.rotate(rotationDegrees)
      gc.translate(-centerX, -centerY)
      gc.drawImage(image, imageX, imageY, image.getWidth, image.getHeight)
      gc.translate(centerX, centerY)
      gc.rotate(-rotationDegrees)
      gc.translate(-centerX, -centerY)
      layoutChildren()
    }
  }

  override def layoutChildren(): Unit = canvasOption.foreach { canvas =>
    imageOption.foreach { image =>
      val x     = getInsets.getLeft
      val y     = getInsets.getTop
      val w     = getWidth - getInsets.getRight - x
      val h     = getHeight - getInsets.getBottom - y
      val ratio =
        if (rotationDegrees == 90 || rotationDegrees == 270)
          min(w / image.getHeight, h / image.getWidth)
        else
          min(w / image.getWidth, h / image.getHeight)
      canvas.setScaleX(ratio)
      canvas.setScaleY(ratio)
      positionInArea(canvas, x, y, w, h, -1, HPos.CENTER, VPos.CENTER)
    }
  }

}
