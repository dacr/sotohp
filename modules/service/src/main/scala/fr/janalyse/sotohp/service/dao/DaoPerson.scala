package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName}
import fr.janalyse.sotohp.model.{FaceId, PersonDescription, PersonEmail, PersonId}
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}

case class DaoPerson(
  id: PersonId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate],
  email: Option[PersonEmail],
  description: Option[PersonDescription],
  chosenFaceId: Option[FaceId]
) derives LMDBCodecJson
