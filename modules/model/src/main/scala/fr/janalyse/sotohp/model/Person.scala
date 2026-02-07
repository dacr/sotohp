package fr.janalyse.sotohp.model

import fr.janalyse.sotohp.model.{BirthDate, FaceId, FirstName, LastName, PersonDescription, PersonEmail, PersonId}

case class Person(
  id: PersonId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate],
  email: Option[PersonEmail],
  description: Option[PersonDescription],
  chosenFaceId: Option[FaceId]
)
