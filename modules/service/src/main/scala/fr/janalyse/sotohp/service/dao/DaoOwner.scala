package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.media.model.*
import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

case class DaoOwner(
  id: OwnerId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate]
) derives LMDBCodecJson
