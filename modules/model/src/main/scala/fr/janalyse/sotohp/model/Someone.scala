package fr.janalyse.sotohp.model

import wvlet.airframe.ulid.ULID
import java.time.OffsetDateTime
import java.util.UUID

case class SomeoneId(
  ulid: ULID
) extends AnyVal {
  override def toString: String = ulid.toString
}

type FirstName = String
type LastName  = String
type BirthDate = OffsetDateTime

case class Someone(
  id: SomeoneId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate]
)
