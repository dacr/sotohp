package fr.janalyse.sotohp.gui

import javafx.scene.layout.{Pane, FlowPane}
import javafx.scene.control.ScrollPane
import javafx.scene.image.{Image, ImageView}
import javafx.scene.shape.Rectangle
import javafx.scene.input.MouseEvent

import fr.janalyse.sotohp.model.MediaAccessKey

/**
 * Simple mosaic view optimized to display many thumbnails quickly.
 * Thumbnails are provided as JavaFX Images by the caller (service layer produces bytes -> images).
 */
class MosaicDisplay extends Pane {
  case class Tile(key: MediaAccessKey, image: Image)

  private val tileSize    = 180.0
  private val tilePadding = 6.0
  private val bufferRows  = 2
  private val flow        = new FlowPane
  flow.setHgap(tilePadding)
  flow.setVgap(tilePadding)
  private val scroll      = new ScrollPane
  scroll.setContent(flow)
  scroll.setFitToWidth(true)

  // Selection callback (tile click)
  var onSelect: MediaAccessKey => Unit = _ => ()
  // Lazy loading callback (request more tiles up to desired total)
  var onNeedMore: Int => Unit          = _ => ()

  // Track last requested count to avoid spamming onNeedMore
  private var lastAsked: Int = 0

  getChildren.add(scroll)

  override def layoutChildren(): Unit = {
    val x = getInsets.getLeft
    val y = getInsets.getTop
    val w = getWidth - getInsets.getRight - x
    val h = getHeight - getInsets.getBottom - y
    // Ensure the ScrollPane actually gets resized (Pane does not resize children automatically)
    scroll.resizeRelocate(x, y, Math.max(0.0, w), Math.max(0.0, h))
    // Help FlowPane wrap thumbnails according to available width
    flow.setPrefWrapLength(Math.max(1.0, w))
  }

  private def buildImageView(tile: Tile): ImageView = {
    val iv = new ImageView(tile.image)
    iv.setFitWidth(tileSize)
    iv.setFitHeight(tileSize)
    iv.setPreserveRatio(true)
    val clip = new Rectangle
    clip.setWidth(tileSize)
    clip.setHeight(tileSize)
    clip.setArcWidth(10)
    clip.setArcHeight(10)
    iv.setClip(clip)
    iv.setOnMouseClicked((_: MouseEvent) => onSelect(tile.key))
    iv
  }

  def tilesCount(): Int = flow.getChildren.size

  private def columnsForViewport(): Int = {
    val vpW   = scroll.getViewportBounds.getWidth
    val cols  = Math.max(1, Math.floor((vpW + tilePadding) / (tileSize + tilePadding)).toInt)
    cols
  }

  private def visibleRowsForViewport(): Int = {
    val vpH  = scroll.getViewportBounds.getHeight
    val rows = Math.max(1, Math.ceil((vpH + tilePadding) / (tileSize + tilePadding)).toInt)
    rows
  }

  private def desiredTotalForCurrentView(): Int = {
    val cols            = columnsForViewport()
    val vpH             = scroll.getViewportBounds.getHeight
    val contentH        = Math.max(flow.getLayoutBounds.getHeight, 0.0)
    val scrollableH     = Math.max(0.0, contentH - vpH)
    val scrollTop       = scroll.getVvalue * scrollableH
    val topRow          = Math.max(0, Math.floor(scrollTop / (tileSize + tilePadding)).toInt)
    val visibleRows     = visibleRowsForViewport()
    val desiredRows     = topRow + visibleRows + bufferRows
    val desiredTotal    = Math.max(cols * desiredRows, cols * visibleRows)
    desiredTotal
  }

  private def requestIfNeeded(): Unit = {
    val desired = desiredTotalForCurrentView()
    if (desired > lastAsked) {
      lastAsked = desired
      onNeedMore(desired)
    }
  }

  // React to viewport resizes and scrolling
  scroll.viewportBoundsProperty().addListener((_, _, _) => requestIfNeeded())
  scroll.vvalueProperty().addListener((_, _, _) => requestIfNeeded())
  // Also react when this control gets resized or becomes visible
  this.widthProperty().addListener((_, _, _) => requestIfNeeded())
  this.heightProperty().addListener((_, _, _) => requestIfNeeded())
  this.visibleProperty().addListener((_, _, _) => requestIfNeeded())

  def triggerNeedMore(): Unit = requestIfNeeded()

  def clearTiles(): Unit = {
    flow.getChildren.clear()
    lastAsked = 0
  }

  def addTile(key: MediaAccessKey, image: Image): Unit = {
    val iv = buildImageView(Tile(key, image))
    flow.getChildren.add(iv)
  }
}
