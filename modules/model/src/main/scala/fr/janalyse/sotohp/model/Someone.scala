package fr.janalyse.sotohp.model

import java.time.OffsetDateTime
import java.util.UUID

case class SomeoneId(
  uuid: UUID
) extends AnyVal

type FirstName = String
type LastName  = String
type BirthDate = OffsetDateTime

case class Someone(
  id: SomeoneId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate]
)
