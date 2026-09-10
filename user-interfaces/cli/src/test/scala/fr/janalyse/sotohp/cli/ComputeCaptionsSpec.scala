package fr.janalyse.sotohp.cli

import zio.test.*

import java.time.ZoneOffset

object ComputeCaptionsSpec extends ZIOSpecDefault {

  override def spec = suite("ComputeCaptions")(
    test("parseSince accepts a bare year and a full date, rejects anything else") {
      val year = ComputeCaptions.parseSince("2020")
      val date = ComputeCaptions.parseSince(" 2020-06-15 ")
      assertTrue(
        year.exists(d => d.getYear == 2020 && d.getMonthValue == 1 && d.getDayOfMonth == 1 && d.getOffset == ZoneOffset.UTC),
        date.exists(d => d.getYear == 2020 && d.getMonthValue == 6 && d.getDayOfMonth == 15),
        ComputeCaptions.parseSince("notadate").isEmpty,
        ComputeCaptions.parseSince("").isEmpty,
        ComputeCaptions.parseSince("1500").isEmpty,      // before photography existed
        ComputeCaptions.parseSince("2020-13-01").isEmpty // invalid month
      )
    }
  )

}
