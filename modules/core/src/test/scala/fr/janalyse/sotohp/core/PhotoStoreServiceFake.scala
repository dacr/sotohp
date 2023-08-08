package fr.janalyse.sotohp.core

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.store.*
import zio.*
import zio.ZIO.*

class PhotoStoreServiceFake(
  statesCollectionRef: Ref[Map[PhotoId, PhotoState]],
  sourcesCollectionRef: Ref[Map[OriginalId, PhotoSource]],
  metaDataCollectionRef: Ref[Map[PhotoId, PhotoMetaData]],
  placesCollectionRef: Ref[Map[PhotoId, PhotoPlace]],
  miniaturesCollectionRef: Ref[Map[PhotoId, Miniatures]],
  normalizedCollectionRef: Ref[Map[PhotoId, NormalizedPhoto]]
) extends PhotoStoreService {

  // ===================================================================================================================

  override def photoStateGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoState]] = for {
    collection <- statesCollectionRef.get
  } yield collection.get(photoId)

  override def photoStateContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] = for {
    collection <- statesCollectionRef.get
  } yield collection.contains(photoId)

  override def photoStateUpsert(photoId: PhotoId, photoState: PhotoState): IO[PhotoStoreIssue, Unit] =
    statesCollectionRef.update(_.updated(photoId, photoState))

  override def photoStateDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    statesCollectionRef.update(collection => collection.removed(photoId))

  // ===================================================================================================================
  override def photoSourceGet(originalId: OriginalId): IO[PhotoStoreIssue, Option[PhotoSource]] = for {
    collection <- sourcesCollectionRef.get
  } yield collection.get(originalId)

  override def photoSourceContains(originalId: OriginalId): IO[PhotoStoreIssue, Boolean] = for {
    collection <- sourcesCollectionRef.get
  } yield collection.contains(originalId)

  override def photoSourceUpsert(originalId: OriginalId, photoSource: PhotoSource): IO[PhotoStoreIssue, Unit] =
    sourcesCollectionRef.update(_.updated(originalId, photoSource))

  override def photoSourceDelete(originalId: OriginalId): IO[PhotoStoreIssue, Unit] =
    sourcesCollectionRef.update(collection => collection.removed(originalId))

  // ===================================================================================================================
  override def photoMetaDataGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoMetaData]] = for {
    collection <- metaDataCollectionRef.get
  } yield collection.get(photoId)

  override def photoMetaDataContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] = for {
    collection <- metaDataCollectionRef.get
  } yield collection.contains(photoId)

  override def photoMetaDataUpsert(photoId: PhotoId, photoMetaData: PhotoMetaData): IO[PhotoStoreIssue, Unit] =
    metaDataCollectionRef.update(_.updated(photoId, photoMetaData))

  override def photoMetaDataDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    metaDataCollectionRef.update(collection => collection.removed(photoId))

  // ===================================================================================================================
  override def photoPlaceGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoPlace]] = for {
    collection <- placesCollectionRef.get
  } yield collection.get(photoId)

  override def photoPlaceContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] = for {
    collection <- placesCollectionRef.get
  } yield collection.contains(photoId)

  override def photoPlaceUpsert(photoId: PhotoId, place: PhotoPlace): IO[PhotoStoreIssue, Unit] =
    placesCollectionRef.update(_.updated(photoId, place))

  override def photoPlaceDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    placesCollectionRef.update(collection => collection.removed(photoId))

  // ===================================================================================================================
  override def photoMiniaturesGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[Miniatures]] = for {
    collection <- miniaturesCollectionRef.get
  } yield collection.get(photoId)

  override def photoMiniaturesContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] = for {
    collection <- miniaturesCollectionRef.get
  } yield collection.contains(photoId)

  override def photoMiniaturesUpsert(photoId: PhotoId, miniatures: Miniatures): IO[PhotoStoreIssue, Unit] =
    miniaturesCollectionRef.update(_.updated(photoId, miniatures))

  override def photoMiniaturesDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    miniaturesCollectionRef.update(collection => collection.removed(photoId))

  // ===================================================================================================================
  override def photoNormalizedGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[NormalizedPhoto]] = for {
    collection <- normalizedCollectionRef.get
  } yield collection.get(photoId)

  override def photoNormalizedContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] = for {
    collection <- normalizedCollectionRef.get
  } yield collection.contains(photoId)

  override def photoNormalizedUpsert(photoId: PhotoId, normalized: NormalizedPhoto): IO[PhotoStoreIssue, Unit] =
    normalizedCollectionRef.update(_.updated(photoId, normalized))

  override def photoNormalizedDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    normalizedCollectionRef.update(collection => collection.removed(photoId))

  // ===================================================================================================================
}

object PhotoStoreServiceFake extends PhotoStoreCollections {

  val default = ZLayer.fromZIO(
    for {
      statesCollection     <- Ref.make(Map.empty[PhotoId, PhotoState])
      sourcesCollection    <- Ref.make(Map.empty[OriginalId, PhotoSource])
      metaDataCollection   <- Ref.make(Map.empty[PhotoId, PhotoMetaData])
      placesCollection     <- Ref.make(Map.empty[PhotoId, PhotoPlace])
      miniaturesCollection <- Ref.make(Map.empty[PhotoId, Miniatures])
      normalizedCollection <- Ref.make(Map.empty[PhotoId, NormalizedPhoto])
    } yield PhotoStoreServiceFake(
      statesCollection,
      sourcesCollection,
      metaDataCollection,
      placesCollection,
      miniaturesCollection,
      normalizedCollection
    )
  )

}
