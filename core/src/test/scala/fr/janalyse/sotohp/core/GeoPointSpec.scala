package fr.janalyse.sotohp.core

import org.junit.runner.RunWith
import zio.*
import zio.test.*

import scala.util.{Success, Try}
import fr.janalyse.sotohp.core.GeoPoint.{degreesMinuteSecondsToDecimalDegrees => dms}

@RunWith(classOf[zio.test.junit.ZTestJUnitRunner])
class GeoPointSpec extends ZIOSpecDefault {
  override def spec = suite("degrees minutes seconds")(
    test("be decoded to decimal degrees case 1")(assertTrue(dms("1° 00′ 0″", "N") == Success(1.0d))),
    test("be decoded to decimal degrees case 2")(assertTrue(dms("0° 06′ 0″", "N") == Success(0.1d))),
    test("be decoded to decimal degrees case 3")(assertTrue(dms("0° 00′ 36″", "N") == Success(0.01d))),
    test("be decoded to decimal degrees case 4")(assertTrue(dms("0° 00′ 0.036″", "N") == Success(9.999999999999999E-6))),
    test("be decoded to decimal degrees case 5")(assertTrue(dms("30° 15' 50\"", "E") == Success(30.26388888888889))),
    test("be decoded to decimal degrees case 6")(assertTrue(dms("3°58'24\"", "S") == Success(-3.9733333333333336d))),
    test("be decoded to decimal degrees case 7")(assertTrue(dms("-3°58'24\"", "S") == Success(-3.9733333333333336d))),
    test("be decoded to decimal degrees case 8")(assertTrue(dms("48° 18' 57,67\"", "N") == Success(48.31601944444444d)))
  )
}
