package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.OriginalId
import fr.janalyse.sotohp.processor.model.{DetectedFacePath, FaceId, PersonId}
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}

import java.time.OffsetDateTime

case class DaoDetectedFace(
  faceId: FaceId,
  originalId: OriginalId,
  box: DaoBoundingBox,
  identifiedPersonId: Option[PersonId],
  inferredIdentifiedPersonId: Option[PersonId],
  timestamp: OffsetDateTime,
  path: DetectedFacePath
) derives LMDBCodecJson
