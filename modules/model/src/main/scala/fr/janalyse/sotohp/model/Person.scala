package fr.janalyse.sotohp.model

case class Person(
  id: PersonId,
  firstName: FirstName,
  lastName: LastName,
  birthName: Option[BirthName],
  birthDate: Option[BirthDate],
  email: Option[PersonEmail],
  description: Option[PersonDescription],
  chosenFaceId: Option[FaceId]
)
