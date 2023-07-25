package fr.janalyse.sotohp.model

import zio.*
import zio.ZIO.*
import zio.test.*
import fr.janalyse.sotohp.model.DegreeMinuteSeconds.*
import fr.janalyse.sotohp.model.DecimalDegrees.*

import scala.util.{Success, Try}

object GeoPointSpec extends ZIOSpecDefault {

  case class TestDataSet(testName: String, givenDMSSpec: String, expectedDegrees: Double)

  val latitudeTestDataset: List[TestDataSet] = List(
    TestDataSet("from wikipedia decimal places 0 case", "1° 00′ 0″ N", 1.0d),
    TestDataSet("from wikipedia decimal places 1 case", "0° 06′ 0″ N", 0.1d),
    TestDataSet("from wikipedia decimal places 2 case", "0° 00′ 36″ N", 0.01d),
    TestDataSet("from wikipedia decimal places 5 case", "0° 00′ 0.036″ N", 9.999999999999999e-6),
    TestDataSet("alternative representation 1", "3°58'24\" S", -3.9733333333333336d),
    TestDataSet("alternative representation 2", "03°58'24\" S", -3.9733333333333336d),
    //TestDataSet("alternative representation 3", "-3°58'24\" S", -3.9733333333333336d), // TODO Check the meaning of negative values in DMS part
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
    suite("Degrees minutes seconds")(
      suite("for latitude")(
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
      suite("for longitude")(
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
    )
}
