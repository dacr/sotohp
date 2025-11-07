package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.OriginalId
import fr.janalyse.sotohp.processor.model.{DetectedFacePath, FaceId, PersonId}
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}

case class DaoDetectedFace(
  faceId: FaceId,
  originalId: OriginalId,
  box: DaoBoundingBox,
  identifiedPersonId: Option[PersonId],
  path: DetectedFacePath
) derives LMDBCodecJson
