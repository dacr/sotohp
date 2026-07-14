package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.media.imaging.BasicImaging
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
import zio.*
import zio.lmdb.LMDB

import java.awt.image.BufferedImage
import java.awt.{Color, Font, RenderingHints}
import java.nio.file.{Files, Path, Paths}
import java.util.Comparator
import scala.io.AnsiColor.*

/** Generate a video (slideshow) from each portfolio, one MP4 per portfolio.
  *
  * The video opens with a title card : the portfolio name in a big font with the portfolio description
  * below it (wrapped over several lines when long), over a blurry mosaic built from the portfolio assets,
  * animated with the same zoom-in effect as the photos.
  *
  * Each photo of the portfolio then becomes a video segment with a slow zoom-in ("Ken Burns") effect,
  * segments are chained with a crossfade transition, and the asset description (when defined) is displayed
  * as a static caption at the bottom of the frame during the whole segment. Photos appear in chronological
  * order, from the oldest to the newest (shoot date).
  *
  * Asset croppings are taken into account : when an asset has a crop box (`selectedBox`), the segment is
  * rendered from the cropped area of the original, after the same orientation correction the normalized
  * rendition gets (the box is defined against the orientation-corrected image). Photos that don't fill the
  * video aspect ratio are letterboxed. Video assets are skipped (with a warning).
  *
  * Rendering is delegated to the `ffmpeg` command line tool, which must be available in the PATH. The
  * produced MP4 is fragmented (streamable) : it can be played while it is still being downloaded or
  * generated.
  *
  * Options (all optional):
  *   - `--portfolio=<substring>`   only portfolios whose name contains this (case-insensitive) substring
  *   - `--output=<directory>`      where the videos are written (default: current directory)
  *   - `--duration=<seconds>`      time each photo stays on screen, transitions included (default: 10)
  *   - `--transition=<seconds>`    crossfade duration between two photos (default: 1, 0 disables)
  *   - `--effect=<name>`           transition effect, any ffmpeg xfade transition name : fade, wipeleft,
  *                                 circleopen, slideleft, dissolve, pixelize, ... (default: fade)
  *   - `--zoom=<factor>`           zoom level reached at the end of each segment (default: 1.15)
  *   - `--size=<WxH>`              video frame size (default: 1920x1080)
  *   - `--fps=<n>`                 video frame rate (default: 25)
  *   - `--music=<file>`            sound track (mp3, ...), repeatable : the tracks are played in the given
  *                                 order and the playlist loops back to the first track until the end of the
  *                                 video ; fades in over the first 5s, fades out to silence at the end
  *   - `--optimize`                reduce the file size (~2x smaller) : H.265/HEVC encoding at a slightly
  *                                 stronger compression level, 128k audio ; encoding is slower and very old
  *                                 players may not support HEVC
  *
  * Examples:
  *   mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.PortfolioVideoGenerator --portfolio=vacations
  *   mill --no-server user-interfaces.cli.runMain fr.janalyse.sotohp.cli.PortfolioVideoGenerator --duration=6 --transition=1.5 --effect=circleopen --output=/tmp/videos
  */
object PortfolioVideoGenerator extends CommonsCLI {

  override def run =
    logic
      .provideSome[ZIOAppArgs](
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  // -------------------------------------------------------------------------------------------------------------------

  private case class VideoConfig(
    outputDirectory: Path,
    photoDurationSeconds: Double,
    transitionSeconds: Double,
    transitionEffect: String,
    endZoom: Double,
    width: Int,
    height: Int,
    fps: Int,
    musicPaths: List[Path],
    optimize: Boolean
  ) {
    def frameCountPerPhoto: Int = (photoDurationSeconds * fps).round.toInt

    def totalSeconds(segmentCount: Int): Double = segmentCount * photoDurationSeconds - (segmentCount - 1).max(0) * transitionSeconds
  }

  private def parseConfig(args: Chunk[String]): Task[VideoConfig] = ZIO.attempt {
    def stringOption(name: String): Option[String] = args.collectFirst { case a if a.startsWith(s"--$name=") => a.stripPrefix(s"--$name=") }
    val (width, height) = stringOption("size")
      .map { size =>
        size.split("[xX]", 2) match {
          case Array(w, h) => (w.toInt, h.toInt)
          case _           => throw IllegalArgumentException(s"Invalid --size '$size', expected <width>x<height> such as 1920x1080")
        }
      }
      .getOrElse((1920, 1080))
    val config          = VideoConfig(
      outputDirectory = stringOption("output").map(Paths.get(_)).getOrElse(Paths.get(".")),
      photoDurationSeconds = stringOption("duration").map(_.toDouble).getOrElse(10d),
      transitionSeconds = stringOption("transition").map(_.toDouble).getOrElse(1d),
      transitionEffect = stringOption("effect").getOrElse("fade"),
      endZoom = stringOption("zoom").map(_.toDouble).getOrElse(1.15d),
      width = width,
      height = height,
      fps = stringOption("fps").map(_.toInt).getOrElse(25),
      musicPaths = args.collect { case a if a.startsWith("--music=") => Paths.get(a.stripPrefix("--music=")) }.toList,
      optimize = args.contains("--optimize")
    )
    require(config.photoDurationSeconds > 0d, "--duration must be positive")
    require(config.transitionSeconds >= 0d, "--transition can't be negative")
    require(config.transitionSeconds < config.photoDurationSeconds, "--transition must be shorter than --duration")
    require(config.endZoom >= 1d, "--zoom must be >= 1")
    require(config.width > 0 && config.height > 0 && config.fps > 0, "--size and --fps must be positive")
    config.musicPaths.foreach(path => require(Files.isReadable(path), s"--music file not found or not readable : $path"))
    config
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Per-photo frame preparation : orientation correction + crop box + caption overlay
  // -------------------------------------------------------------------------------------------------------------------

  /** Load the original, apply the same orientation correction as the normalized rendition, then the crop
    * box when the asset has one (the box is relative, 0..1, against the orientation-corrected image).
    */
  private def renderAssetImage(original: Original, selectedBox: Option[BoundingBox]): Task[BufferedImage] =
    ZIO.attemptBlocking {
      val loaded  = BasicImaging.load(original.absoluteMediaPath)
      val degrees = original.orientation.map(_.rotationDegrees.toDouble).getOrElse(0d)
      val rotated = BasicImaging.rotate(loaded, degrees)
      selectedBox.filter(box => box.width.value > 0d && box.height.value > 0d) match {
        case None      => rotated
        case Some(box) =>
          val x      = (box.x.value * rotated.getWidth).round.toInt.max(0).min(rotated.getWidth - 1)
          val y      = (box.y.value * rotated.getHeight).round.toInt.max(0).min(rotated.getHeight - 1)
          val width  = (box.width.value * rotated.getWidth).round.toInt.max(1).min(rotated.getWidth - x)
          val height = (box.height.value * rotated.getHeight).round.toInt.max(1).min(rotated.getHeight - y)
          rotated.getSubimage(x, y, width, height)
      }
    }

  /** Fit the image inside a frame of exactly (width, height) with black letterbox bars. The frame is
    * rendered at twice the video size : zoompan then works on an oversampled picture, which avoids the
    * shakiness the filter exhibits when panning/zooming at output resolution.
    */
  private def letterbox(image: BufferedImage, width: Int, height: Int): BufferedImage = {
    val scaled   = if (image.getWidth <= width && image.getHeight <= height) image else BasicImaging.resize(image, width, height)
    val frame    = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = frame.createGraphics
    try {
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      graphics.setColor(Color.BLACK)
      graphics.fillRect(0, 0, width, height)
      val ratio       = (1d * width / scaled.getWidth).min(1d * height / scaled.getHeight)
      val drawnWidth  = (scaled.getWidth * ratio).round.toInt
      val drawnHeight = (scaled.getHeight * ratio).round.toInt
      graphics.drawImage(scaled, (width - drawnWidth) / 2, (height - drawnHeight) / 2, drawnWidth, drawnHeight, null)
      frame
    } finally graphics.dispose()
  }

  private def wrapText(text: String, metrics: java.awt.FontMetrics, maxWidth: Int, maxLines: Int): List[String] = {
    val lines = text.linesIterator.flatMap { paragraph =>
      paragraph.split(" ").foldLeft(List.empty[String]) { (lines, word) =>
        lines match {
          case current :: previous if metrics.stringWidth(s"$current $word") <= maxWidth => s"$current $word" :: previous
          case _                                                                         => word :: lines
        }
      }.reverse
    }.toList
    if (lines.size <= maxLines) lines else lines.take(maxLines - 1) :+ (lines(maxLines - 1) + " …")
  }

  /** Transparent PNG of the video frame size holding the caption : white text over a translucent dark
    * band at the bottom. Rendered once per asset and kept static while the photo zooms behind it.
    */
  private def renderCaptionOverlay(description: String, width: Int, height: Int): BufferedImage = {
    val overlay  = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = overlay.createGraphics
    try {
      graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      val fontSize   = (height / 28).max(14)
      graphics.setFont(Font(Font.SANS_SERIF, Font.PLAIN, fontSize))
      val metrics    = graphics.getFontMetrics
      val lines      = wrapText(description.trim, metrics, (width * 0.85).toInt, maxLines = 4)
      val lineHeight = metrics.getHeight
      val padding    = fontSize / 2
      val bandHeight = lines.size * lineHeight + 2 * padding
      val bandTop    = height - bandHeight - height / 20
      graphics.setColor(Color(0, 0, 0, 150))
      graphics.fillRoundRect(width / 40, bandTop, width - 2 * (width / 40), bandHeight, fontSize, fontSize)
      graphics.setColor(Color.WHITE)
      lines.zipWithIndex.foreach { case (line, index) =>
        val lineWidth = metrics.stringWidth(line)
        graphics.drawString(line, (width - lineWidth) / 2, bandTop + padding + index * lineHeight + metrics.getAscent)
      }
      overlay
    } finally graphics.dispose()
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Title card : portfolio name + description over a blurry mosaic of the portfolio assets
  // -------------------------------------------------------------------------------------------------------------------

  /** Tile the asset thumbnails on a grid covering the whole frame (each cell filled center-cropped,
    * thumbnails repeated when there are fewer assets than cells), then blur by strong down/up scaling
    * and darken so the title text stays readable.
    */
  private def renderMosaicBackground(thumbnails: List[BufferedImage], width: Int, height: Int): BufferedImage = {
    val aspect     = 1d * width / height
    val columns    = math.ceil(math.sqrt(thumbnails.size * aspect)).toInt.max(2)
    val rows       = math.ceil(1d * height / (width / columns)).toInt.max(2)
    val mosaic     = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics   = mosaic.createGraphics
    try {
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      for (row <- 0 until rows; column <- 0 until columns) {
        val thumbnail  = thumbnails((row * columns + column) % thumbnails.size)
        val cellX      = column * width / columns
        val cellY      = row * height / rows
        val cellWidth  = (column + 1) * width / columns - cellX
        val cellHeight = (row + 1) * height / rows - cellY
        val scale      = (1d * cellWidth / thumbnail.getWidth).max(1d * cellHeight / thumbnail.getHeight)
        val drawnW     = (thumbnail.getWidth * scale).ceil.toInt
        val drawnH     = (thumbnail.getHeight * scale).ceil.toInt
        graphics.setClip(cellX, cellY, cellWidth, cellHeight)
        graphics.drawImage(thumbnail, cellX + (cellWidth - drawnW) / 2, cellY + (cellHeight - drawnH) / 2, drawnW, drawnH, null)
      }
      graphics.setClip(null)
    } finally graphics.dispose()
    // cheap strong gaussian-like blur : iterative downscale then bicubic upscale
    val small    = (1 to 4).foldLeft(mosaic)((image, _) => BasicImaging.resize(image, image.getWidth / 2, image.getHeight / 2))
    val blurred  = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val blurring = blurred.createGraphics
    try {
      blurring.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      blurring.drawImage(small, 0, 0, width, height, null)
      blurring.setColor(Color(0, 0, 0, 110)) // darken for text legibility
      blurring.fillRect(0, 0, width, height)
      blurred
    } finally blurring.dispose()
  }

  /** Transparent PNG with the portfolio name in a big font and the (wrapped) description below it in a
    * smaller one, both centered, with a soft shadow. Kept static while the mosaic zooms behind it.
    */
  private def renderTitleOverlay(name: String, description: Option[String], width: Int, height: Int): BufferedImage = {
    val overlay  = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = overlay.createGraphics
    try {
      graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      val titleFont       = Font(Font.SANS_SERIF, Font.BOLD, (height / 11).max(24))
      val descriptionFont = Font(Font.SANS_SERIF, Font.PLAIN, (height / 26).max(16))
      graphics.setFont(titleFont)
      val titleLines      = wrapText(name.trim, graphics.getFontMetrics, (width * 0.9).toInt, maxLines = 2)
      val titleMetrics    = graphics.getFontMetrics
      graphics.setFont(descriptionFont)
      val descLines       = description.map(_.trim).filter(_.nonEmpty).fold(List.empty[String])(text => wrapText(text, graphics.getFontMetrics, (width * 0.8).toInt, maxLines = 6))
      val descMetrics     = graphics.getFontMetrics
      val spacing         = if (descLines.isEmpty) 0 else height / 24
      val totalHeight     = titleLines.size * titleMetrics.getHeight + spacing + descLines.size * descMetrics.getHeight
      var y               = (height - totalHeight) / 2

      def drawCentered(line: String, metrics: java.awt.FontMetrics, baseline: Int): Unit = {
        val x = (width - metrics.stringWidth(line)) / 2
        graphics.setColor(Color(0, 0, 0, 180))
        graphics.drawString(line, x + metrics.getHeight / 12, baseline + metrics.getHeight / 12) // soft shadow
        graphics.setColor(Color.WHITE)
        graphics.drawString(line, x, baseline)
      }

      graphics.setFont(titleFont)
      titleLines.foreach { line =>
        drawCentered(line, titleMetrics, y + titleMetrics.getAscent)
        y += titleMetrics.getHeight
      }
      y += spacing
      graphics.setFont(descriptionFont)
      descLines.foreach { line =>
        drawCentered(line, descMetrics, y + descMetrics.getAscent)
        y += descMetrics.getHeight
      }
      overlay
    } finally graphics.dispose()
  }

  /** The title card is a regular segment (it gets the same zoom-in and transition as the photos) : the
    * blurry mosaic is the zooming frame, the title text is a static overlay.
    */
  private def prepareTitleCard(portfolio: Portfolio, photos: List[PreparedPhoto], config: VideoConfig, workDirectory: Path): Task[PreparedPhoto] =
    ZIO.attemptBlocking {
      val thumbnails  = photos.map(photo => BasicImaging.load(photo.thumbnailPath))
      val framePath   = workDirectory.resolve("title_frame.jpg")
      BasicImaging.save(framePath, renderMosaicBackground(thumbnails, config.width * 2, config.height * 2), Some(0.95d))
      val overlayPath = workDirectory.resolve("title_overlay.png")
      BasicImaging.save(overlayPath, renderTitleOverlay(portfolio.name.text, portfolio.description.map(_.text), config.width, config.height))
      PreparedPhoto(framePath, Some(overlayPath), framePath)
    }

  // -------------------------------------------------------------------------------------------------------------------
  // ffmpeg orchestration : one zoompan segment per photo, segments chained with xfade transitions
  // -------------------------------------------------------------------------------------------------------------------

  /** A photo ready to be turned into a video segment : the pre-rendered frame, its optional caption, and a
    * small thumbnail reused by the title card mosaic.
    */
  private case class PreparedPhoto(framePath: Path, overlayPath: Option[Path], thumbnailPath: Path)

  /** Locale-independent decimal formatting : ffmpeg expressions require a dot separator. */
  private def decimal(value: Double): String = String.format(java.util.Locale.ROOT, "%.6f", value)

  /** zoompan rounds its crop window to whole input pixels on every frame, which shows as shakiness when
    * the input is close to the output size. So the picture is first upscaled to 4x the video size, zoompan
    * renders at 2x (rounding errors become sub-pixel at the final size), and the result is scaled down.
    */
  private def zoompanFilter(config: VideoConfig): String = {
    val frames = config.frameCountPerPhoto
    val zoomIn = s"1+${decimal(config.endZoom - 1d)}*on/${(frames - 1).max(1)}"
    s"scale=${config.width * 4}x${config.height * 4}:flags=lanczos" +
      s",zoompan=z='$zoomIn':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=$frames:s=${config.width * 2}x${config.height * 2}:fps=${config.fps}" +
      s",scale=${config.width}x${config.height}:flags=bicubic"
  }

  /** The complete -filter_complex graph : inputs 0..n-1 are the photo frames, then one input per caption
    * overlay, then the audio inputs. Each photo goes through zoompan (+ caption overlay), then segments are
    * folded left to right with xfade ; with a single photo or a zero transition the segments are simply
    * concatenated.
    */
  private def buildFilterGraph(photos: List[PreparedPhoto], config: VideoConfig, audioInputCount: Int): String = {
    val overlayInputIndexes = photos.indices
      .filter(index => photos(index).overlayPath.isDefined)
      .zipWithIndex
      .map { case (photoIndex, position) => photoIndex -> (photos.size + position) }
      .toMap

    val segments = photos.indices.map { index =>
      val zoomed = s"[$index:v]${zoompanFilter(config)},format=yuv420p,settb=AVTB[z$index]"
      overlayInputIndexes.get(index) match {
        case Some(overlayInput) => s"$zoomed;\n[z$index][$overlayInput:v]overlay=0:0:format=auto,format=yuv420p,settb=AVTB[v$index]"
        case None               => zoomed.replace(s"[z$index]", s"[v$index]")
      }
    }

    val chain =
      if (photos.size == 1) List(s"[v0]null[vout]")
      else if (config.transitionSeconds == 0d) List(photos.indices.map(i => s"[v$i]").mkString("") + s"concat=n=${photos.size}:v=1:a=0[vout]")
      else
        photos.indices.tail.map { index =>
          val previous = if (index == 1) "[v0]" else s"[x${index - 1}]"
          val target   = if (index == photos.size - 1) "[vout]" else s"[x$index]"
          val offset   = index * (config.photoDurationSeconds - config.transitionSeconds)
          s"$previous[v$index]xfade=transition=${config.transitionEffect}:duration=${decimal(config.transitionSeconds)}:offset=${decimal(offset)}$target"
        }.toList

    // sound track playlist : the audio inputs (the tracks, in playlist order, already repeated enough times
    // to cover the whole video) are normalized, concatenated, cut at the video duration, then fade in over
    // the first 5s and fade out over the last 5s
    val audio =
      if (audioInputCount == 0) Nil
      else {
        val firstAudioInput = photos.size + overlayInputIndexes.size
        val total           = config.totalSeconds(photos.size)
        val fadeSeconds     = 5d.min(total / 2)
        val normalized      = (0 until audioInputCount).map(i => s"[${firstAudioInput + i}:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[snd$i]").toList
        val playlist        = (0 until audioInputCount).map(i => s"[snd$i]").mkString("") + (if (audioInputCount > 1) s"concat=n=$audioInputCount:v=0:a=1," else "anull,")
        normalized :+ s"$playlist" +
          s"atrim=0:${decimal(total)},afade=t=in:st=0:d=${decimal(fadeSeconds)},afade=t=out:st=${decimal(total - fadeSeconds)}:d=${decimal(fadeSeconds)}[aout]"
      }

    (segments ++ chain ++ audio).mkString(";\n")
  }

  private def probeAudioDuration(path: Path): Task[Double] =
    ZIO.attemptBlocking {
      val outputBuffer = collection.mutable.ListBuffer.empty[String]
      val command      = List("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path.toString)
      val exitCode     = scala.sys.process.Process(command).!(scala.sys.process.ProcessLogger(outputBuffer.append, outputBuffer.append))
      val duration     = outputBuffer.headOption.flatMap(_.trim.toDoubleOption).filter(_ > 0d)
      if (exitCode != 0 || duration.isEmpty) throw Exception(s"Couldn't read the duration of the sound track $path : ${outputBuffer.mkString(" ")}")
      duration.get
    }

  /** The sound tracks in play order, the whole playlist repeated (looping back to the first track) as many
    * times as needed to cover the video ; each occurrence is passed to ffmpeg as a separate input, so
    * nothing gets buffered ahead. The extra repetition absorbs probed-duration rounding, the audio filter
    * chain cuts at the exact video duration anyway.
    */
  private def audioPlaylist(config: VideoConfig, videoSeconds: Double): Task[List[Path]] =
    if (config.musicPaths.isEmpty) ZIO.succeed(Nil)
    else
      for {
        durations       <- ZIO.foreach(config.musicPaths)(probeAudioDuration)
        playlistSeconds  = durations.sum
        repetitions      = math.ceil(videoSeconds / playlistSeconds).toInt.max(1) + 1
      } yield List.fill(repetitions)(config.musicPaths).flatten

  /** Encoder settings : H.264 CRF 20 by default ; with `--optimize`, H.265/HEVC CRF 26 which halves the
    * file size at comparable visual quality (`hvc1` tag so Apple players recognize the codec), falling
    * back to a stronger H.264 compression when the local ffmpeg build has no libx265.
    */
  private def videoCodecArguments(optimize: Boolean): Task[List[String]] =
    if (!optimize) ZIO.succeed(List("-c:v", "libx264", "-preset", "medium", "-crf", "20"))
    else
      ZIO
        .attemptBlocking {
          val encoders = new StringBuilder
          scala.sys.process.Process(List("ffmpeg", "-hide_banner", "-encoders")).!(scala.sys.process.ProcessLogger(line => encoders.append(line).append('\n'), _ => ()))
          encoders.toString.contains("libx265")
        }
        .flatMap {
          case true  => ZIO.succeed(List("-c:v", "libx265", "-preset", "medium", "-crf", "26", "-tag:v", "hvc1", "-x265-params", "log-level=error"))
          case false => ZIO.logWarning("libx265 not available in this ffmpeg build, using stronger H.264 compression instead").as(List("-c:v", "libx264", "-preset", "slow", "-crf", "26"))
        }

  private def runFfmpeg(photos: List[PreparedPhoto], config: VideoConfig, workDirectory: Path, outputFile: Path): Task[Unit] =
    for {
      playlist <- audioPlaylist(config, config.totalSeconds(photos.size))
      codec    <- videoCodecArguments(config.optimize)
      _        <- ZIO.attemptBlocking {
                    val filterGraphFile = workDirectory.resolve("filtergraph.txt")
                    Files.writeString(filterGraphFile, buildFilterGraph(photos, config, playlist.size))
                    val inputs          = photos.flatMap(photo => List("-i", photo.framePath.toString)) ++
                      photos.flatMap(_.overlayPath).flatMap(path => List("-i", path.toString)) ++
                      playlist.flatMap(path => List("-i", path.toString))
                    val command         = List("ffmpeg", "-y", "-nostdin", "-hide_banner", "-loglevel", "warning") ++
                      inputs ++
                      List("-filter_complex_script", filterGraphFile.toString, "-map", "[vout]") ++
                      codec ++
                      List("-pix_fmt", "yuv420p") ++
                      (if (playlist.isEmpty) Nil else List("-map", "[aout]", "-c:a", "aac", "-b:a", if (config.optimize) "128k" else "192k")) ++
                      List(
                        // fragmented MP4 : the metadata comes first and the stream is cut in ~2s independent fragments,
                        // so playback can start while the file is still being downloaded (or even still being encoded)
                        "-g", (config.fps * 2).toString,
                        "-movflags", "+frag_keyframe+empty_moov+default_base_moof",
                        outputFile.toString
                      )
                    val outputBuffer    = collection.mutable.ListBuffer.empty[String]
                    val exitCode        = scala.sys.process.Process(command).!(scala.sys.process.ProcessLogger(outputBuffer.append, outputBuffer.append))
                    if (exitCode != 0) throw Exception(s"ffmpeg failed (exit code $exitCode) :\n${outputBuffer.takeRight(30).mkString("\n")}")
                  }
    } yield ()

  private def checkFfmpegAvailable: Task[Unit] =
    ZIO
      .attemptBlocking(scala.sys.process.Process(List("ffmpeg", "-version")).!(scala.sys.process.ProcessLogger(_ => ())))
      .orElseFail(Exception("ffmpeg not found, install it and make sure it is available in the PATH"))
      .filterOrFail(_ == 0)(Exception("ffmpeg is present but 'ffmpeg -version' failed"))
      .unit

  // -------------------------------------------------------------------------------------------------------------------
  // Portfolio processing
  // -------------------------------------------------------------------------------------------------------------------

  /** Prepare one asset, tagged with its shoot timestamp (used to order the video chronologically) :
    * returns None (with a warning) for videos, unknown originals or unreadable files.
    */
  private def prepareAsset(asset: Asset, index: Int, config: VideoConfig, workDirectory: Path) = {
    val step = for {
      mediaTuple <- MediaService.mediaGet(asset.originalId).some.mapError(_ => Exception(s"No media found for original ${asset.originalId.asString}"))
      original    = mediaTuple.media.original
      _          <- ZIO.when(original.kind != MediaKind.Photo)(ZIO.fail(Exception("not a photo, videos can't be included in the slideshow")))
      image      <- renderAssetImage(original, asset.selectedBox)
      photo      <- ZIO.attemptBlocking {
                      val framePath     = workDirectory.resolve(f"frame_$index%05d.jpg")
                      BasicImaging.save(framePath, letterbox(image, config.width * 2, config.height * 2), Some(0.95d))
                      val overlayPath   = asset.description.map(_.text.trim).filter(_.nonEmpty).map { text =>
                        val path = workDirectory.resolve(f"caption_$index%05d.png")
                        BasicImaging.save(path, renderCaptionOverlay(text, config.width, config.height))
                        path
                      }
                      val thumbnailPath = workDirectory.resolve(f"thumb_$index%05d.jpg")
                      val thumbnail     = if (image.getWidth <= 1600 && image.getHeight <= 1600) image else BasicImaging.resize(image, 1600, 1600)
                      BasicImaging.save(thumbnailPath, thumbnail, Some(0.9d))
                      PreparedPhoto(framePath, overlayPath, thumbnailPath)
                    }
    } yield (mediaTuple.media.timestamp, photo)
    step
      .map(Some(_))
      .catchAll(err => ZIO.logWarning(s"Skipping asset ${asset.originalId.asString} : ${err.getMessage}").as(None))
  }

  private def fileNameFor(portfolio: Portfolio): String =
    portfolio.name.text.trim.replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("(^-)|(-$)", "").toLowerCase + ".mp4"

  private def generatePortfolioVideo(portfolio: Portfolio, config: VideoConfig): RIO[MediaService, Unit] =
    ZIO.scoped {
      for {
        _             <- Console.printLine(s"$CYAN- portfolio '${portfolio.name.text}' : ${portfolio.assets.size} assets$RESET")
        workDirectory <- ZIO.acquireRelease(
                           ZIO.attemptBlocking(Files.createTempDirectory("sotohp-portfolio-video-"))
                         )(directory =>
                           ZIO
                             .attemptBlocking(Files.walk(directory).sorted(Comparator.reverseOrder()).forEach(path => Files.delete(path)))
                             .ignoreLogged
                         )
        photos        <- ZIO
                           .foreach(portfolio.assets.zipWithIndex) { case (asset, index) => prepareAsset(asset, index, config, workDirectory) }
                           .map(_.flatten.sortBy { case (timestamp, _) => timestamp.toInstant.toEpochMilli }.map { case (_, photo) => photo })
        _             <- ZIO.when(photos.isEmpty)(ZIO.fail(Exception(s"No usable photo in portfolio '${portfolio.name.text}', no video generated")))
        titleCard     <- prepareTitleCard(portfolio, photos, config, workDirectory)
        segments       = titleCard :: photos
        _             <- ZIO.attemptBlocking(Files.createDirectories(config.outputDirectory))
        outputFile     = config.outputDirectory.resolve(fileNameFor(portfolio))
        totalSeconds   = config.totalSeconds(segments.size)
        _             <- ZIO.logInfo(f"'${portfolio.name.text}' : encoding ${photos.size} photos, ~$totalSeconds%.0fs of video, this can take a while...")
        _             <- runFfmpeg(segments, config, workDirectory, outputFile)
        _             <- ZIO.logInfo(s"'${portfolio.name.text}' : video written to $outputFile")
      } yield ()
    }.catchAll(err => ZIO.logWarning(err.getMessage))

  // -------------------------------------------------------------------------------------------------------------------
  val logic = ZIO.logSpan("Generate portfolio videos") {
    for {
      args           <- getArgs
      config         <- parseConfig(args)
      portfolioFilter = args.collectFirst { case a if a.startsWith("--portfolio=") => a.stripPrefix("--portfolio=").toLowerCase }
      _              <- checkFfmpegAvailable
      portfolios     <- MediaService
                          .portfolioList()
                          .runCollect
                          .map(_.toList.filter(p => portfolioFilter.forall(f => p.name.text.toLowerCase.contains(f))).sortBy(_.name.text))
      _              <- ZIO.logInfo(
                          s"${portfolios.size} portfolios to render" +
                            portfolioFilter.fold("")(f => s""" (portfolio filter "$f")""") +
                            s" — ${config.photoDurationSeconds}s per photo, ${config.transitionSeconds}s '${config.transitionEffect}' transitions, zoom x${config.endZoom}, ${config.width}x${config.height}@${config.fps}fps"
                        )
      _              <- ZIO.foreachDiscard(portfolios)(portfolio => generatePortfolioVideo(portfolio, config))
    } yield ()
  }
}
