package fr.janalyse.sotohp.processor.model

import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName}

case class Person(
  id: PersonId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate],
  email: Option[PersonEmail],
  description: Option[PersonDescription],
  chosenFaceId: Option[FaceId]
)
