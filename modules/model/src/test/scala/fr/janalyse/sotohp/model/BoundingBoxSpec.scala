package fr.janalyse.sotohp.model

import zio.test.*
import zio.test.Assertion.*

object BoundingBoxSpec extends ZIOSpecDefault {

  // A box covering the left third of the image, full height : x=[0, 1/3], y=[0,1]
  val leftThird: BoundingBox = BoundingBox(x = XAxis(0d), y = YAxis(0d), width = BoxWidth(1d / 3d), height = BoxHeight(1d))

  private def near(a: Double, b: Double): Boolean = math.abs(a - b) < 1e-9

  def approxBox(actual: BoundingBox, expected: BoundingBox): TestResult =
    assertTrue(
      near(actual.x.value, expected.x.value),
      near(actual.y.value, expected.y.value),
      near(actual.width.value, expected.width.value),
      near(actual.height.value, expected.height.value)
    )

  override def spec = suite("BoundingBoxSpec")(
    test("0 turns is the identity") {
      approxBox(leftThird.rotatedClockwise90(0), leftThird)
    },
    test("90° clockwise moves the left edge to the top edge") {
      // left-third-of-a-landscape becomes top-third-of-the-now-portrait image
      val expected = BoundingBox(x = XAxis(0d), y = YAxis(0d), width = BoxWidth(1d), height = BoxHeight(1d / 3d))
      approxBox(leftThird.rotatedClockwise90(1), expected)
    },
    test("180° flips both axes") {
      val expected = BoundingBox(x = XAxis(2d / 3d), y = YAxis(0d), width = BoxWidth(1d / 3d), height = BoxHeight(1d))
      approxBox(leftThird.rotatedClockwise90(2), expected)
    },
    test("270° clockwise moves the left edge to the bottom edge") {
      val expected = BoundingBox(x = XAxis(0d), y = YAxis(2d / 3d), width = BoxWidth(1d), height = BoxHeight(1d / 3d))
      approxBox(leftThird.rotatedClockwise90(3), expected)
    },
    test("4 turns round-trips to the identity") {
      approxBox(leftThird.rotatedClockwise90(4), leftThird)
    },
    test("negative turns normalize (mod 4), -1 == 3") {
      approxBox(leftThird.rotatedClockwise90(-1), leftThird.rotatedClockwise90(3))
    },
    test("a turn then its inverse round-trips") {
      approxBox(leftThird.rotatedClockwise90(1).rotatedClockwise90(3), leftThird)
    }
  )
}
