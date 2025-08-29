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
  // Callback when the control recycles (removes) tiles; n = number of tiles removed from the top
  var onTilesRemoved: Int => Unit      = _ => ()

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

  private def disposeNode(node: javafx.scene.Node): Unit = node match {
    case iv: ImageView =>
      try {
        iv.setOnMouseClicked(null)
        iv.setClip(null)
        val img = iv.getImage
        iv.setImage(null)
        // img resources will be GC'ed; no explicit dispose in JavaFX Image
      } catch {
        case _: Throwable => ()
      }
    case _ => ()
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

  private def nearBottom(): Boolean = {
    val vpH         = scroll.getViewportBounds.getHeight
    val contentH    = Math.max(flow.getLayoutBounds.getHeight, 0.0)
    val scrollableH = Math.max(0.0, contentH - vpH)
    if (scrollableH <= 0.0) false
    else {
      val scrollTop  = scroll.getVvalue * scrollableH
      val remaining  = scrollableH - scrollTop
      remaining <= (tileSize + tilePadding) // within one row from bottom
    }
  }

  private def maybeRecycleAtBottom(): Unit = {
    if (!nearBottom()) return
    val cols = columnsForViewport()
    if (cols <= 0) return
    val current = tilesCount()
    if (current <= cols) return
    // Remove exactly one row from the top with explicit disposal
    try {
      val toRemove = Math.min(cols, current)
      var i        = 0
      while (i < toRemove) {
        val node = flow.getChildren.get(0)
        disposeNode(node)
        flow.getChildren.remove(0)
        i += 1
      }
      lastAsked = Math.max(0, lastAsked - toRemove)
      onTilesRemoved(toRemove)
      // Keep the viewport at bottom and request enough to build a new line
      scroll.setVvalue(1.0)
      onNeedMore(tilesCount() + cols)
    } catch {
      case _: Throwable => ()
    }
  }

  // React to viewport resizes and scrolling
  scroll.viewportBoundsProperty().addListener((_, _, _) => { requestIfNeeded(); maybeRecycleAtBottom() })
  scroll.vvalueProperty().addListener((_, _, _) => { requestIfNeeded(); maybeRecycleAtBottom() })
  // Also react when this control gets resized or becomes visible
  this.widthProperty().addListener((_, _, _) => { requestIfNeeded(); maybeRecycleAtBottom() })
  this.heightProperty().addListener((_, _, _) => { requestIfNeeded(); maybeRecycleAtBottom() })
  this.visibleProperty().addListener((_, _, _) => { requestIfNeeded(); maybeRecycleAtBottom() })

  def triggerNeedMore(): Unit = { requestIfNeeded(); maybeRecycleAtBottom() }

  def clearTiles(): Unit = {
    try {
      val it = flow.getChildren.iterator()
      while (it.hasNext) disposeNode(it.next())
      flow.getChildren.clear()
    } catch {
      case _: Throwable => flow.getChildren.clear()
    }
    lastAsked = 0
  }

  def addTile(key: MediaAccessKey, image: Image): Unit = {
    val iv = buildImageView(Tile(key, image))
    flow.getChildren.add(iv)
  }

  def addTileFirst(key: MediaAccessKey, image: Image): Unit = {
    val iv = buildImageView(Tile(key, image))
    flow.getChildren.add(0, iv)
  }
}
