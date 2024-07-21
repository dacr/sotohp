package fr.janalyse.sotohp.processor

import zio.*
import zio.test.*
import fr.janalyse.sotohp.processor.BasicImaging.{display, fileTypeFromName, mirror, resize, rotate}

import java.awt.Color
import java.awt.image.BufferedImage

object BasicImagingSpec extends ZIOSpecDefault {

  val imageSampleWidth  = 800
  val imageSampleHeight = 600

  def imageSample(): BufferedImage = {
    val image      = BufferedImage(imageSampleWidth, imageSampleHeight, BufferedImage.TYPE_INT_RGB)
    val graphics2D = image.createGraphics
    try {
      graphics2D.setColor(Color.LIGHT_GRAY)
      graphics2D.fillRect(0, 0, imageSampleWidth, imageSampleHeight)
      graphics2D.setColor(Color.GREEN)
      graphics2D.fillRect(0, 0, imageSampleWidth / 4, imageSampleHeight / 4)
      graphics2D.setColor(Color.BLACK)
      graphics2D.drawRect(0, 0, imageSampleWidth - 1, imageSampleHeight - 1)
      image
    } finally {
      graphics2D.dispose()
    }
  }

  override def spec =
    suite("BasicImaging Test Suite")(
      suite("Image types feature")(
        test("should return correct type for a given filename") {
          assertTrue(
            fileTypeFromName("test.scala").contains("scala"),
            fileTypeFromName("test.scala.backup").contains("backup"),
            fileTypeFromName("test.c").contains("c"),
            fileTypeFromName("test.JPG").contains("jpg"),
            fileTypeFromName(".truc").contains("truc")
          )
        },
        test("should not return any type when file has no extension") {
          assertTrue(
            fileTypeFromName("test").isEmpty,
            fileTypeFromName("test.").isEmpty,
            fileTypeFromName("a.").isEmpty
          )
        }
      ),
      suite("Image resizing feature")(
        test("should resize the image to a lower size") {
          for {
            original <- ZIO.attempt(imageSample())
            resized  <- ZIO.attempt(resize(original, imageSampleWidth / 2, imageSampleHeight / 2))
          } yield assertTrue(
            resized.getWidth == imageSampleWidth / 2,
            resized.getHeight == imageSampleHeight / 2,
            resized.getRGB(10, 10) == Color.GREEN.getRGB
          )
        },
        test("should resize the image to a greater size") {
          for {
            original <- ZIO.attempt(imageSample())
            resized  <- ZIO.attempt(resize(original, imageSampleWidth * 2, imageSampleHeight * 2))
          } yield assertTrue(
            resized.getWidth == imageSampleWidth * 2,
            resized.getHeight == imageSampleHeight * 2,
            resized.getRGB(10, 10) == Color.GREEN.getRGB
          )
        },
        test("should keep the image ratio when resized to a greater size") {
          for {
            original <- ZIO.attempt(imageSample())
            resized1 <- ZIO.attempt(resize(original, imageSampleWidth * 2, imageSampleHeight * 4))
            resized2 <- ZIO.attempt(resize(original, imageSampleWidth * 4, imageSampleHeight * 2))
          } yield assertTrue(
            resized1.getWidth == imageSampleWidth * 2,
            resized1.getHeight == imageSampleHeight * 2,
            resized1.getRGB(10, 10) == Color.GREEN.getRGB,
            resized2.getWidth == imageSampleWidth * 2,
            resized2.getHeight == imageSampleHeight * 2,
            resized2.getRGB(10, 10) == Color.GREEN.getRGB
          )
        },
        test("should keep the image ratio when resized to a lower size") {
          for {
            original <- ZIO.attempt(imageSample())
            resized1 <- ZIO.attempt(resize(original, imageSampleWidth / 2, imageSampleHeight / 4))
            resized2 <- ZIO.attempt(resize(original, imageSampleWidth / 4, imageSampleHeight / 2))
          } yield assertTrue(
            resized1.getWidth == imageSampleWidth / 4,
            resized1.getHeight == imageSampleHeight / 4,
            resized1.getRGB(10, 10) == Color.GREEN.getRGB,
            resized2.getWidth == imageSampleWidth / 4,
            resized2.getHeight == imageSampleHeight / 4,
            resized2.getRGB(10, 10) == Color.GREEN.getRGB
          )
        }
      ),
      suite("Image rotating feature")(
        test("should rotate the image to the right") {
          for {
            original <- ZIO.attempt(imageSample())
            rotated  <- ZIO.attempt(rotate(original, 90))
          } yield assertTrue(
            rotated.getWidth == imageSampleHeight,
            rotated.getHeight == imageSampleWidth,
            rotated.getRGB(imageSampleHeight - 10, 10) == Color.GREEN.getRGB,
            rotated.getRGB(0, 0) == Color.BLACK.getRGB,
            rotated.getRGB(10, 10) == Color.LIGHT_GRAY.getRGB
          )
        },
        test("should rotate the image to the left") {
          for {
            original <- ZIO.attempt(imageSample())
            rotated  <- ZIO.attempt(rotate(original, -90))
          } yield assertTrue(
            rotated.getWidth == imageSampleHeight,
            rotated.getHeight == imageSampleWidth,
            rotated.getRGB(10, imageSampleWidth - 10) == Color.GREEN.getRGB,
            rotated.getRGB(0, 0) == Color.BLACK.getRGB,
            rotated.getRGB(10, 10) == Color.LIGHT_GRAY.getRGB,
            rotated.getRGB(imageSampleHeight - 1, imageSampleWidth - 1) == Color.BLACK.getRGB
          )
        }
      ),
      suite("Image mirroring feature")(
        test("should mirror the image horizontally") {
          for {
            original <- ZIO.attempt(imageSample())
            mirrored <- ZIO.attempt(mirror(original))
          } yield assertTrue(
            mirrored.getRGB(imageSampleWidth - 10, 10) == Color.GREEN.getRGB,
            mirrored.getRGB(0, 0) == Color.BLACK.getRGB,
            mirrored.getRGB(10, 10) == Color.LIGHT_GRAY.getRGB,
            mirrored.getRGB(imageSampleWidth - 1, imageSampleHeight - 1) == Color.BLACK.getRGB
          )
        },
        test("should mirror the image vertically") {
          for {
            original <- ZIO.attempt(imageSample())
            mirrored <- ZIO.attempt(mirror(original, horizontally = false, vertically = true))
          } yield assertTrue(
            mirrored.getRGB(10, imageSampleHeight - 10) == Color.GREEN.getRGB,
            mirrored.getRGB(0, 0) == Color.BLACK.getRGB,
            mirrored.getRGB(10, 10) == Color.LIGHT_GRAY.getRGB,
            mirrored.getRGB(imageSampleWidth - 1, imageSampleHeight - 1) == Color.BLACK.getRGB
          )
        }
      )
    )
}
