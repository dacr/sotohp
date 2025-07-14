package fr.janalyse.sotohp.media.model

import wvlet.airframe.ulid.ULID
import java.time.OffsetDateTime

opaque type OwnerId   = ULID
object OwnerId {
  def apply(id: ULID): OwnerId = id
}

opaque type FirstName = String
object FirstName {
  def apply(name: String): FirstName = name
}

opaque type LastName  = String
object LastName {
  def apply(name: String): LastName = name
}

opaque type BirthDate = OffsetDateTime
object BirthDate {
  def apply(date: OffsetDateTime): BirthDate = date
}

case class Owner(
  id: OwnerId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate]
)
