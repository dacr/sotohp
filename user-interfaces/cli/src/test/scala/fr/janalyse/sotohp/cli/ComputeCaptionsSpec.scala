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
    },
    test("parseInstant accepts a full offset date-time, incl. a two-digit offset with no minutes") {
      val withMinutes = ComputeCaptions.parseInstant("2026-09-12T19:04:24.825477913+02:00")
      val noMinutes    = ComputeCaptions.parseInstant("2026-09-12T19:04:24.825477913+02") // as our own logs render it
      assertTrue(
        withMinutes.exists(d => d.getYear == 2026 && d.getMonthValue == 9 && d.getDayOfMonth == 12 && d.getHour == 19),
        noMinutes.exists(d => d.getOffset == ZoneOffset.ofHours(2)),
        withMinutes == noMinutes,
        ComputeCaptions.parseInstant("2026-09-12").isEmpty,   // no time component
        ComputeCaptions.parseInstant("notadate").isEmpty,
        ComputeCaptions.parseInstant("").isEmpty
      )
    }
  )

}
