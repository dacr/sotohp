package fr.janalyse.sotohp.processor

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.FileOutputStream
import java.nio.file.Path
import javax.imageio.stream.{FileCacheImageOutputStream, ImageOutputStream}
import javax.imageio.{IIOImage, ImageIO, ImageWriteParam}
import scala.jdk.CollectionConverters.*
import scala.util.Using

case object BasicImaging {

  def resizeBufferedImage(
    originalImage: BufferedImage,
    targetWidth: Int,
    targetHeight: Int
  ): BufferedImage = {
    val resizedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
    val graphics2D   = resizedImage.createGraphics
    try {
      graphics2D.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      graphics2D.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null)
      resizedImage
    } finally {
      graphics2D.dispose()
    }
  }

  def rotateBufferedImage(
    originalImage: BufferedImage,
    angleDegree: Double
  ): BufferedImage = {
    import scala.math.*
    val angle     = toRadians(angleDegree)
    val sin       = abs(Math.sin(angle))
    val cos       = abs(Math.cos(angle))
    val width     = originalImage.getWidth.toDouble
    val height    = originalImage.getHeight.toDouble
    val newWidth  = floor(width * cos + height * sin)
    val newHeight = floor(height * cos + width * sin)

    val rotatedImage = new BufferedImage(newWidth.toInt, newHeight.toInt, BufferedImage.TYPE_INT_RGB)
    val graphics2D   = rotatedImage.createGraphics

    try {
      graphics2D.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
      graphics2D.translate((newWidth - width) / 2d, (newHeight - height) / 2d)
      graphics2D.rotate(angle, width / 2d, height / 2d)
      graphics2D.drawImage(originalImage, 0, 0, null)
      // graphics2D.setColor(Color.RED)
      // graphics2D.drawRect(0, 0, newWidth - 1, newHeight - 1)
      rotatedImage
    } finally {
      graphics2D.dispose()
    }
  }

  def imageTypeFromPath(path: Path): Option[String] = {
    path.getFileName.toString.split("[.]").lastOption.map(_.toLowerCase)
  }

  def resizeImage(
    input: Path,
    output: Path,
    targetMaxSize: Int,
    rotateDegrees: Option[Double] = None,
    compressionLevel: Option[Double] = None
  ): Unit = {
    val originalImage = ImageIO.read(input.toFile)
    if (originalImage == null) throw new Exception(s"Unsupported image format : $input") // TODO enhance error support
    val ratio         = targetMaxSize.toDouble / math.max(originalImage.getWidth, originalImage.getHeight)
    val targetWidth   = (originalImage.getWidth() * ratio).toInt
    val targetHeight  = (originalImage.getHeight() * ratio).toInt
    val resizedImage  = resizeBufferedImage(originalImage, targetWidth, targetHeight)
    val finalImage    =
      if (rotateDegrees.filter(_ != 0d).isDefined)
        rotateBufferedImage(resizedImage, rotateDegrees.get)
      else resizedImage

    val foundImageType   = imageTypeFromPath(output)
    val foundImageWriter =
      foundImageType
        .flatMap(imageType => ImageIO.getImageWritersByFormatName(imageType).asScala.toList.headOption)

    foundImageWriter match {
      case Some(writer) =>
        val params = writer.getDefaultWriteParam()
        if (compressionLevel.isDefined) {
          params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
          params.setCompressionQuality(compressionLevel.get.toFloat)
        }
        Using(ImageIO.createImageOutputStream(output.toFile)) { outputStream =>
          writer.setOutput(outputStream)
          val outputImage = new IIOImage(finalImage, null, null)
          writer.write(null, outputImage, params)
          writer.dispose()
        }
      case None         =>
    }
  }

}
