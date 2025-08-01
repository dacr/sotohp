package fr.janalyse.sotohp.model

case class Owner(
  id: OwnerId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate]
)
