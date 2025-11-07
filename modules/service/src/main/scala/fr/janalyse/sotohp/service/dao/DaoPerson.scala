package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName}
import fr.janalyse.sotohp.processor.model.{FaceId, PersonDescription, PersonId}
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}

case class DaoPerson(
  id: PersonId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate],
  description: Option[PersonDescription],
  chosenFaceId: Option[FaceId]
) derives LMDBCodecJson
