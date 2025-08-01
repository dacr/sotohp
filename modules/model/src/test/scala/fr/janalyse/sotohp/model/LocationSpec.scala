package fr.janalyse.sotohp.model

import fr.janalyse.sotohp
import fr.janalyse.sotohp.model
import fr.janalyse.sotohp.model.*
import zio.*
import zio.ZIO.*
import zio.test.*

import scala.util.{Success, Try}

object LocationSpec extends ZIOSpecDefault {

  case class TestDataSet(testName: String, givenDMSSpec: String, expectedDegrees: Double)

  val latitudeTestDataset: List[TestDataSet] = List(
    TestDataSet("from wikipedia decimal places 0 case", "1° 00′ 0″ N", 1.0d),
    TestDataSet("from wikipedia decimal places 1 case", "0° 06′ 0″ N", 0.1d),
    TestDataSet("from wikipedia decimal places 2 case", "0° 00′ 36″ N", 0.01d),
    TestDataSet("from wikipedia decimal places 5 case", "0° 00′ 0.036″ N", 9.999999999999999e-6),
    TestDataSet("alternative representation 1", "3°58'24\" S", -3.9733333333333336d),
    TestDataSet("alternative representation 2", "03°58'24\" S", -3.9733333333333336d),
    // TestDataSet("alternative representation 3", "-3°58'24\" S", -3.9733333333333336d), // TODO Check the meaning of negative values in DMS part
    TestDataSet("alternative representation 4", "3° 58'  24\"  S", -3.9733333333333336d),
    TestDataSet("alternative representation 5", "3° 58'  24''  S", -3.9733333333333336d),
    TestDataSet("alternative representation 6", "3° 58'  24″  S", -3.9733333333333336d),
    TestDataSet("alternative representation 7", "3° 58′  24′′  S", -3.9733333333333336d)
  )

  val longitudeTestDataSet: List[TestDataSet] = List(
    TestDataSet("sample 1", "30° 15' 50\" E", 30.26388888888889d),
    TestDataSet("sample 2", "30° 15' 50″ E", 30.26388888888889d),
    TestDataSet("sample 3", "30° 15' 50'' E", 30.26388888888889d),
    TestDataSet("sample 4", "77° 00′ 32″ W", -77.00888888888889)
  )

  override def spec =
    suite("PhotoPlace Test Suite")(
      suite("DegreesMinutesSeconds features")(
        suite("should support various encoding for latitude")(
          for {
            TestDataSet(testName, givenDMSSpec, expectedDegrees) <- latitudeTestDataset
          } yield test(testName)(
            for {
              dms <- from(LatitudeDegreeMinuteSeconds.fromSpec(givenDMSSpec))
            } yield assertTrue(
              dms.toDecimalDegrees == LatitudeDecimalDegrees(expectedDegrees)
            )
          )
        ),
        suite("should support various encoding for latitude")(
          for {
            TestDataSet(testName, givenDMSSpec, expectedDegrees) <- longitudeTestDataSet
          } yield test(testName)(
            for {
              dms <- from(LongitudeDegreeMinuteSeconds.fromSpec(givenDMSSpec))
            } yield assertTrue(
              dms.toDecimalDegrees == LongitudeDecimalDegrees(expectedDegrees)
            )
          )
        )
      ),
      suite("Distance features")(
        test("should return zero when the same place is given") {
          val from = Location.fromDecimalDegrees(LatitudeDecimalDegrees(0d), LongitudeDecimalDegrees(0d))
          val to   = from
          assertTrue(
            from.distanceTo(to) == 0
          )
        },
        test("should return the right distance between two places") {
          ZIO.fromTry(
            for {
              paris <- Location.fromLocationSpecs("48° 51' 52.9776'' N", "2° 20' 56.4504'' E")
              brest <- Location.fromLocationSpecs("48° 23' 23.9964'' N", "4° 29' 24.0000'' W")
              dist1  = paris.distanceTo(brest)
              dist2  = brest.distanceTo(paris)
            } yield {
              assertTrue(
                dist1 == dist2,
                dist1 < 506_000,
                dist1 > 504_000
              )
            }
          )
        }
      )
    )
}
