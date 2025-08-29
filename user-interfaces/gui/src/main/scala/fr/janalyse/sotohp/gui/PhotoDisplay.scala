package fr.janalyse.sotohp.gui

import scala.math.min
import javafx.scene.canvas.Canvas
import javafx.scene.layout.Background
import javafx.scene.image.Image
import javafx.scene.paint.Color
import javafx.geometry
import javafx.geometry.{HPos, VPos}
import javafx.scene.layout.Region
import javafx.scene.input.{MouseButton, MouseEvent, ScrollEvent}

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
  private val zoomStep         = 0.25d
  // Tiny extra zoom to avoid rounding/clipping artifacts at first zoom
  private val zoomSafetyMargin = 0.002d

  // Panning & zoom helpers
  private var lastRatio: Double           = 1d
  private var draggingMiddle: Boolean     = false
  private var lastDragViewX: Double       = 0d
  private var lastDragViewY: Double       = 0d
  private val arrowStepPixels: Double     = 40d

  private var currentCanvas: Option[Canvas] = {
    val canvas = new Canvas(3 * 1920, 3 * 1080)
    getChildren.add(canvas)
    setBackground(Background.fill(Color.BLACK))
    // Register mouse handlers for middle-button panning
    setOnMousePressed((e: MouseEvent) => {
      if (e.getButton == MouseButton.MIDDLE && isZoomed()) {
        draggingMiddle = true
        lastDragViewX = e.getX
        lastDragViewY = e.getY
      }
    })
    setOnMouseReleased((e: MouseEvent) => {
      if (e.getButton == MouseButton.MIDDLE) draggingMiddle = false
    })
    setOnMouseDragged((e: MouseEvent) => {
      if (draggingMiddle && isZoomed()) {
        val dxView = e.getX - lastDragViewX
        val dyView = e.getY - lastDragViewY
        lastDragViewX = e.getX
        lastDragViewY = e.getY
        panByScreen(dxView, dyView)
      }
    })
    setOnScroll((e: ScrollEvent) => {
      val dy = e.getDeltaY
      if (dy > 0) zoomIn() else if (dy < 0) zoomOut()
      e.consume()
    })
    Some(canvas)
  }

  def isZoomed() = zoomLevel > zoomLevelDefault

  def clear(): Unit = currentCanvas.foreach { canvas =>
    val gc = canvas.getGraphicsContext2D
    // Clear the full canvas using identity transform to avoid residual artifacts
    gc.clearRect(0, 0, canvas.getWidth, canvas.getHeight)
  }

  private def setup(): Unit = {
    currentPhoto.foreach { photo =>
      clear()
      val (filepath, rotationDegrees) = {
        if (isZoomed()) photo.media.original.mediaPath.path -> photo.orientation.map(_.rotationDegrees).getOrElse(0)
        else photo.normalizedPath.map(_ -> 0).getOrElse(
          photo.media.original.mediaPath.path -> photo.orientation.map(_.rotationDegrees).getOrElse(0)
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

  private def computeRatio(image: Image): Double = {
    val x = getInsets.getLeft
    val y = getInsets.getTop
    val w = getWidth - getInsets.getRight - x
    val h = getHeight - getInsets.getBottom - y
    val base =
      if (rotationDegrees == 90 || rotationDegrees == 270)
        min(w / image.getHeight, h / image.getWidth)
      else
        min(w / image.getWidth, h / image.getHeight)
    val r = zoomLevel * base
    if (zoomLevel > zoomLevelDefault) r * (1.0 + zoomSafetyMargin) else r
  }

  private def clampToBounds(): Unit = {
    (for {
      img    <- currentImage
      canvas <- currentCanvas
    } yield (img, canvas)).foreach { case (img, canvas) =>
      val ratio      = computeRatio(img)
      lastRatio = ratio
      val viewHalfW  = (getWidth / ratio) / 2.0
      val viewHalfH  = (getHeight / ratio) / 2.0
      val ccx        = canvas.getWidth / 2.0
      val ccy        = canvas.getHeight / 2.0
      val theta      = Math.toRadians(rotationDegrees.toDouble)
      val w          = img.getWidth
      val h          = img.getHeight
      val halfWb     = 0.5 * (Math.abs(w * Math.cos(theta)) + Math.abs(h * Math.sin(theta)))
      val halfHb     = 0.5 * (Math.abs(w * Math.sin(theta)) + Math.abs(h * Math.cos(theta)))
      val allowHalfX = Math.max(0.0, halfWb - viewHalfW)
      val allowHalfY = Math.max(0.0, halfHb - viewHalfH)
      // Clamp center to keep viewport inside rotated image bounding box
      if (allowHalfX == 0.0) centerX = ccx
      else centerX = Math.max(ccx - allowHalfX, Math.min(ccx + allowHalfX, centerX))
      if (allowHalfY == 0.0) centerY = ccy
      else centerY = Math.max(ccy - allowHalfY, Math.min(ccy + allowHalfY, centerY))
      // Update imageX/Y from center
      imageX = centerX - w / 2.0
      imageY = centerY - h / 2.0
    }
  }

  private def panByCanvas(dx: Double, dy: Double): Unit = {
    if (!isZoomed()) return
    currentImage.foreach { img =>
      centerX += dx
      centerY += dy
      clampToBounds()
      clear()
      displayPhoto()
    }
  }

  private def currentRatio(): Double = currentImage.map(computeRatio).getOrElse(1.0)

  private def panByScreen(dxView: Double, dyView: Double): Unit = {
    val r = currentRatio()
    // Move image following mouse direction
    panByCanvas(dxView / r, dyView / r)
  }

  def panLeft(): Unit  = panByScreen(-arrowStepPixels, 0)
  def panRight(): Unit = panByScreen(arrowStepPixels, 0)
  def panUp(): Unit    = panByScreen(0, arrowStepPixels)
  def panDown(): Unit  = panByScreen(0, -arrowStepPixels)

  def displayPhoto(): Unit = {
    // Ensure canvas transform matches current zoom/rotation before drawing
    layoutChildren()
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
              photoFaces.foreach { face =>
                import face.box.x, face.box.y, face.box.{width => w}, face.box.{height => h}
                gc.setLineWidth(2d)
                gc.setStroke(Color.BLUE)
                gc.strokeRect(
                  imageX + x.value * image.getWidth,
                  imageY + y.value * image.getHeight,
                  w.value * image.getWidth,
                  h.value * image.getHeight
                )
              }
            }
          }
          gc.restore()
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
      val ratio = computeRatio(image)
      lastRatio = ratio
      canvas.setScaleX(ratio)
      canvas.setScaleY(ratio)
      positionInArea(canvas, x, y, w, h, -1, HPos.CENTER, VPos.CENTER)
      // ensure pan is clamped when viewport size changes
      clampToBounds()
    }
  }

}
