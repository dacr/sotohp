package fr.janalyse.sotohp.model

case class Owner(
  id: OwnerId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate],
  originalId: Option[OriginalId],         // reference/chosen original, which will be shown as the owner cover
)
