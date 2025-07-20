package fr.janalyse.sotohp.media.model


case class Owner(
  id: OwnerId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate]
)
