package fr.janalyse.sotohp.service.model

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.dao.*
import zio.lmdb.LMDBCollection

case class MediaServiceCollections(
  originals: LMDBCollection[OriginalId, DaoOriginal],
  states: LMDBCollection[OriginalId, DaoState],
  events: LMDBCollection[EventId, DaoEvent],
  medias: LMDBCollection[MediaAccessKey, DaoMedia],
  owners: LMDBCollection[OwnerId, DaoOwner],
  stores: LMDBCollection[StoreId, DaoStore],
  keywordRules: LMDBCollection[StoreId, DaoKeywordRules],
  classifications: LMDBCollection[OriginalId, DaoOriginalClassifications],
  detectedFaces: LMDBCollection[FaceId, DaoDetectedFace],
  originalFaces: LMDBCollection[OriginalId, DaoOriginalFaces],
  faceFeatures: LMDBCollection[FaceId, DaoFaceFeatures],
  originalFaceFeatures: LMDBCollection[OriginalId, DaoOriginalFaceFeatures],
  objects: LMDBCollection[OriginalId, DaoOriginalDetectedObjects],
  miniatures: LMDBCollection[OriginalId, DaoOriginalMiniatures],
  normalized: LMDBCollection[OriginalId, DaoOriginalNormalized],
  persons: LMDBCollection[PersonId, DaoPerson],
)