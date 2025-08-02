package fr.janalyse.sotohp.media.imaging

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.{IIOImage, ImageIO, ImageWriteParam}
import scala.jdk.CollectionConverters.*
import scala.math.*
import scala.util.Using

case object BasicImaging {

  def fileTypeFromName(filename: String): Option[String] =
    filename.lastIndexOf(".") match {
      case -1 => None
      case i  => Some(filename.substring(i + 1).toLowerCase).filterNot(_.isEmpty)
    }

  def fileTypeFromName(path: Path): Option[String] =
    fileTypeFromName(path.getFileName.toString)

  def resize(
    originalImage: BufferedImage,
    targetWidth: Int,
    targetHeight: Int
  ): BufferedImage = {
    val ratio     = min(1d * targetWidth / originalImage.getWidth, 1d * targetHeight / originalImage.getHeight)
    val newWidth  = floor(originalImage.getWidth * ratio).toInt
    val newHeight = floor(originalImage.getHeight * ratio).toInt
    val newImage  = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
    val graphics  = newImage.createGraphics
    try {
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      graphics.drawImage(originalImage, 0, 0, newWidth, newHeight, null)
      newImage
    } finally {
      graphics.dispose()
    }
  }

  def rotate(
    originalImage: BufferedImage,
    angleDegree: Double
  ): BufferedImage = {
    val angle     = toRadians(angleDegree)
    val sin       = abs(Math.sin(angle))
    val cos       = abs(Math.cos(angle))
    val width     = originalImage.getWidth.toDouble
    val height    = originalImage.getHeight.toDouble
    val newWidth  = floor(width * cos + height * sin)
    val newHeight = floor(height * cos + width * sin)
    val newImage  = BufferedImage(newWidth.toInt, newHeight.toInt, BufferedImage.TYPE_INT_RGB)
    val graphics  = newImage.createGraphics
    try {
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      graphics.translate((newWidth - width) / 2d, (newHeight - height) / 2d)
      graphics.rotate(angle, width / 2d, height / 2d)
      graphics.drawImage(originalImage, 0, 0, null)
      // graphics.setColor(Color.RED)
      // graphics.drawRect(0, 0, newWidth - 1, newHeight - 1)
      newImage
    } finally {
      graphics.dispose()
    }
  }

  def mirror(originalImage: BufferedImage, horizontally: Boolean = true, vertically: Boolean = false): BufferedImage = {
    val width    = originalImage.getWidth
    val height   = originalImage.getHeight
    val newImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = newImage.createGraphics
    try {
      graphics.drawImage(
        originalImage,
        if (horizontally) width else 0,
        if (vertically) height else 0,
        if (horizontally) -width else width,
        if (vertically) -height else height,
        null
      )
      newImage
    } finally {
      graphics.dispose()
    }
  }

  def display(image: BufferedImage, title: String = "Image display"): javax.swing.JFrame = {
    import java.awt.FlowLayout
    import javax.swing.{ImageIcon, JFrame, JLabel, WindowConstants}

    val icon  = ImageIcon(image)
    val frame = JFrame()
    frame.setLayout(FlowLayout())
    frame.setSize(image.getWidth + 50, image.getHeight + 50)
    val label = JLabel()
    label.setIcon(icon)
    frame.add(label)
    frame.setVisible(true)
    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)
    frame
  }

  def load(input: Path): BufferedImage = {
    val image = ImageIO.read(input.toFile)
    if (image == null) throw RuntimeException(s"Unsupported input image format : $input") // TODO enhance error support
    image
  }

  def save(output: Path, image: BufferedImage, compressionLevel: Option[Double] = None): Unit = {
    val foundImageType   = fileTypeFromName(output)
    val foundImageWriter =
      foundImageType
        .flatMap(imageType => ImageIO.getImageWritersByFormatName(imageType).asScala.toList.headOption)

    foundImageWriter match {
      case Some(writer) =>
        val params = writer.getDefaultWriteParam
        if (compressionLevel.isDefined) {
          params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
          params.setCompressionQuality(compressionLevel.get.toFloat)
        }
        Using(ImageIO.createImageOutputStream(output.toFile)) { outputStream =>
          writer.setOutput(outputStream)
          val outputImage = IIOImage(image, null, null)
          writer.write(null, outputImage, params)
          writer.dispose()
        }

      case None => throw RuntimeException(s"Unsupported output image format : $output")  // TODO enhance error support
    }
  }

  def reshapeImage(
    input: Path,
    output: Path,
    targetMaxSize: Int,
    rotateDegrees: Option[Double] = None,
    compressionLevel: Option[Double] = None
  ): (width:Int, height:Int) = {
    val originalImage = load(input)
    val ratio        = targetMaxSize.toDouble / math.max(originalImage.getWidth, originalImage.getHeight)
    val targetWidth  = (originalImage.getWidth() * ratio).toInt
    val targetHeight = (originalImage.getHeight() * ratio).toInt
    val resizedImage = resize(originalImage, targetWidth, targetHeight)
    val finalImage   =
      if (rotateDegrees.exists(_ != 0d))
        rotate(resizedImage, rotateDegrees.get)
      else resizedImage
    save(output, finalImage, compressionLevel)
    (width = finalImage.getWidth, height = finalImage.getHeight)
  }

}
