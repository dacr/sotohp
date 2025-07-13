package fr.janalyse.sotohp.media.model

import wvlet.airframe.ulid.ULID

import java.time.OffsetDateTime

opaque type OwnerId   = ULID
opaque type FirstName = String
opaque type LastName  = String
opaque type BirthDate = OffsetDateTime

object OwnerId {
  def apply(id: ULID): OwnerId = id
}
object FirstName {
  def apply(name: String): FirstName = name
}
object LastName {
  def apply(name: String): LastName = name
}
object BirthDate {
  def apply(date: OffsetDateTime): BirthDate = date
}

case class Owner(
  id: OwnerId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate],
  originalsBaseDirectory: List[BaseDirectoryPath]
)
