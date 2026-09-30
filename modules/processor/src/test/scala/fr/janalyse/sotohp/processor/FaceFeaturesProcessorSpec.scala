package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.model.*
import zio.*
import zio.test.*

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

object FaceFeaturesProcessorSpec extends BaseSpecDefault {

  private def box(x: Double, y: Double, width: Double, height: Double) =
    BoundingBox(XAxis(x), YAxis(y), BoxWidth(width), BoxHeight(height))

  def suiteLocalAI = suite("LocalAI face features processor")(
    test("the margin rectangle grows the box on each side") {
      // a 100x200 px box at (400, 300) in a 1000x1000 image, grown by 30% of its size on each side
      val (x, y, width, height) = LocalAIFaceFeaturesProcessor.marginRectangle(box(0.4, 0.3, 0.1, 0.2), 1000, 1000, 0.3)
      assertTrue(x == 370, y == 240, width == 160, height == 320)
    },
    test("the margin rectangle is clamped to the image") {
      val (x, y, width, height) = LocalAIFaceFeaturesProcessor.marginRectangle(box(0.0, 0.9, 0.2, 0.1), 1000, 500, 0.5)
      assertTrue(x == 0, y >= 0, x + width <= 1000, y + height <= 500, width == 300, y == 425)
    },
    test("a zero margin is the detector box") {
      val (x, y, width, height) = LocalAIFaceFeaturesProcessor.marginRectangle(box(0.25, 0.5, 0.5, 0.25), 800, 400, 0d)
      assertTrue(x == 200, y == 200, width == 400, height == 100)
    },
    test("LocalAI's no-face answers are told apart from real failures") {
      assertTrue(
        // what LocalAI 4.10 actually answers for a crop without a face, and for an unknown model
        LocalAIFaceFeaturesProcessor.isNoFaceAnswer(404, """{"error":{"code":404,"message":"Resource not found","type":""}}"""),
        !LocalAIFaceFeaturesProcessor.isNoFaceAnswer(404, """{"error":{"code":404,"message":"model \"no-such-model\" not found. To see available models, call GET /v1/models","type":"invalid_request_error"}}"""),
        LocalAIFaceFeaturesProcessor.isNoFaceAnswer(500, """{"error":{"code":500,"message":"rpc error: code = NotFound desc = no face detected","type":""}}"""),
        !LocalAIFaceFeaturesProcessor.isNoFaceAnswer(400, """{"error":{"code":400,"message":"invalid image input","type":""}}"""),
        !LocalAIFaceFeaturesProcessor.isNoFaceAnswer(500, "connection refused")
      )
    },
    test("an image with an alpha channel is encoded as JPEG") {
      val image = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB)
      val bytes = LocalAIFaceFeaturesProcessor.jpegBytes(image)
      val back  = ImageIO.read(new ByteArrayInputStream(bytes))
      assertTrue(back != null, back.getWidth == 32, back.getHeight == 16)
    }
  )

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suiteLocalAI

}
