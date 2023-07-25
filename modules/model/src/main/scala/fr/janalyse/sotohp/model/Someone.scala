package fr.janalyse.sotohp.model

import java.time.OffsetDateTime
import java.util.UUID

case class SomeoneId(
  uuid: UUID
) extends AnyVal

case class Someone(
  id: SomeoneId,
  firstName: String,
  lastName: String,
  birthDate: Option[OffsetDateTime]
)
