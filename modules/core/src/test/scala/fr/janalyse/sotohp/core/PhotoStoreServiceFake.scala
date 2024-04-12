package fr.janalyse.sotohp.core

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.store.*
import zio.*
import zio.ZIO.*
import zio.stream.*

class PhotoStoreServiceFake(
  statesCollectionRef: Ref[Map[PhotoId, PhotoState]],
  sourcesCollectionRef: Ref[Map[OriginalId, PhotoSource]],
  metaDataCollectionRef: Ref[Map[PhotoId, PhotoMetaData]],
  placesCollectionRef: Ref[Map[PhotoId, PhotoPlace]],
  miniaturesCollectionRef: Ref[Map[PhotoId, Miniatures]],
  normalizedCollectionRef: Ref[Map[PhotoId, NormalizedPhoto]],
  classificationsCollectionRef: Ref[Map[PhotoId, PhotoClassifications]],
  objectsCollectionRef: Ref[Map[PhotoId, PhotoObjects]],
  facesCollectionRef: Ref[Map[PhotoId, PhotoFaces]],
  descriptionsCollectionRef: Ref[Map[PhotoId, PhotoDescription]]
) extends PhotoStoreService {

  // ===================================================================================================================

  override def photoDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] = {
    for {
      state <- photoStateGet(photoId).some.orElseFail[PhotoStoreIssue](PhotoStoreNotFoundIssue(s"$photoId not found"))
      _ <- photoClassificationsDelete(photoId)
      _ <- photoDescriptionDelete(photoId)
      _ <- photoFacesDelete(photoId)
      _ <- photoMetaDataDelete(photoId)
      _ <- photoMiniaturesDelete(photoId)
      _ <- photoNormalizedDelete(photoId)
      _ <- photoObjectsDelete(photoId)
      _ <- photoPlaceDelete(photoId)
      _ <- photoSourceDelete(state.originalId)
      _ <- photoStateDelete(photoId)
    } yield ()
  }

  // ===================================================================================================================

  override def photoStateGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoState]] = for {
    collection <- statesCollectionRef.get
  } yield collection.get(photoId)

  override def photoStateStream(): ZStream[Any, PhotoStoreIssue, PhotoState] = {
    val wrappedStream = for {
      collection <- statesCollectionRef.get
    } yield ZStream.from(collection.values)
    ZStream.unwrap(wrappedStream)
  }

  override def photoStateContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] = for {
    collection <- statesCollectionRef.get
  } yield collection.contains(photoId)

  def photoStateUpdate(photoId: PhotoId, photoStateUpdater: PhotoState => PhotoState): IO[PhotoStoreIssue, Option[PhotoState]] = {
    statesCollectionRef
      .updateAndGet { states =>
        states.get(photoId) match {
          case None        => states
          case Some(state) => states.updated(photoId, photoStateUpdater(state))
        }
      }
      .map(_.get(photoId))
  }

  override def photoStateUpsert(photoId: PhotoId, photoState: PhotoState): IO[PhotoStoreIssue, Unit] =
    statesCollectionRef.update(_.updated(photoId, photoState))

  override def photoStateDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] = statesCollectionRef.update(collection => collection.removed(photoId))

  override def photoStateFirst(): IO[PhotoStoreIssue, Option[PhotoState]] = ???

  override def photoStateNext(after: PhotoId): IO[PhotoStoreIssue, Option[PhotoState]] = ???

  override def photoStatePrevious(before: PhotoId): IO[PhotoStoreIssue, Option[PhotoState]] = ???

  override def photoStateLast(): IO[PhotoStoreIssue, Option[PhotoState]] = ???

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
  override def photoClassificationsGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoClassifications]] = for {
    collection <- classificationsCollectionRef.get
  } yield collection.get(photoId)

  override def photoClassificationsContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] = for {
    collection <- classificationsCollectionRef.get
  } yield collection.contains(photoId)

  override def photoClassificationsUpsert(photoId: PhotoId, photoClassifications: PhotoClassifications): IO[PhotoStoreIssue, Unit] =
    classificationsCollectionRef.update(_.updated(photoId, photoClassifications))

  override def photoClassificationsDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    classificationsCollectionRef.update(collection => collection.removed(photoId))

  // ===================================================================================================================
  override def photoObjectsGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoObjects]] = for {
    collection <- objectsCollectionRef.get
  } yield collection.get(photoId)

  override def photoObjectsContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] = for {
    collection <- objectsCollectionRef.get
  } yield collection.contains(photoId)

  override def photoObjectsUpsert(photoId: PhotoId, photoObjects: PhotoObjects): IO[PhotoStoreIssue, Unit] =
    objectsCollectionRef.update(_.updated(photoId, photoObjects))

  override def photoObjectsDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    objectsCollectionRef.update(collection => collection.removed(photoId))

  // ===================================================================================================================
  override def photoFacesGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoFaces]] = for {
    collection <- facesCollectionRef.get
  } yield collection.get(photoId)

  override def photoFacesContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] = for {
    collection <- facesCollectionRef.get
  } yield collection.contains(photoId)

  override def photoFacesUpsert(photoId: PhotoId, photoFaces: PhotoFaces): IO[PhotoStoreIssue, Unit] =
    facesCollectionRef.update(_.updated(photoId, photoFaces))

  override def photoFacesDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    facesCollectionRef.update(collection => collection.removed(photoId))

  // ===================================================================================================================
  override def photoDescriptionGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoDescription]] = for {
    collection <- descriptionsCollectionRef.get
  } yield collection.get(photoId)

  override def photoDescriptionContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] = for {
    collection <- descriptionsCollectionRef.get
  } yield collection.contains(photoId)

  override def photoDescriptionUpsert(photoId: PhotoId, photoDescription: PhotoDescription): IO[PhotoStoreIssue, Unit] =
    descriptionsCollectionRef.update(_.updated(photoId, photoDescription))

  override def photoDescriptionDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    descriptionsCollectionRef.update(collection => collection.removed(photoId))

  // ===================================================================================================================

  override def photoFirst(): IO[PhotoStoreIssue, Option[LazyPhoto]] = ???

  override def photoNext(after: PhotoId): IO[PhotoStoreIssue, Option[LazyPhoto]] = ???

  override def photoPrevious(before: PhotoId): IO[PhotoStoreIssue, Option[LazyPhoto]] = ???

  override def photoLast(): IO[PhotoStoreIssue, Option[LazyPhoto]] = ???
}

object PhotoStoreServiceFake extends PhotoStoreCollections {

  val default = ZLayer.fromZIO(
    for {
      statesCollection          <- Ref.make(Map.empty[PhotoId, PhotoState])
      sourcesCollection         <- Ref.make(Map.empty[OriginalId, PhotoSource])
      metaDataCollection        <- Ref.make(Map.empty[PhotoId, PhotoMetaData])
      placesCollection          <- Ref.make(Map.empty[PhotoId, PhotoPlace])
      miniaturesCollection      <- Ref.make(Map.empty[PhotoId, Miniatures])
      normalizedCollection      <- Ref.make(Map.empty[PhotoId, NormalizedPhoto])
      classificationsCollection <- Ref.make(Map.empty[PhotoId, PhotoClassifications])
      objectsCollection         <- Ref.make(Map.empty[PhotoId, PhotoObjects])
      facesCollection           <- Ref.make(Map.empty[PhotoId, PhotoFaces])
      descriptionsCollection    <- Ref.make(Map.empty[PhotoId, PhotoDescription])
    } yield PhotoStoreServiceFake(
      statesCollection,
      sourcesCollection,
      metaDataCollection,
      placesCollection,
      miniaturesCollection,
      normalizedCollection,
      classificationsCollection,
      objectsCollection,
      facesCollection,
      descriptionsCollection
    )
  )

}
