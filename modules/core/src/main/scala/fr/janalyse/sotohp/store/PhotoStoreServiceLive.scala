package fr.janalyse.sotohp.store

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.model.DecimalDegrees.*
import fr.janalyse.sotohp.store.dao.*
import wvlet.airframe.ulid.ULID
import zio.*
import zio.ZIO.*
import zio.lmdb.*

import java.nio.file.Path
import java.util.UUID

trait PhotoStoreCollections {
  val photoStatesCollectionName          = "photo-states"
  val photoSourcesCollectionName         = "photo-sources"
  val photoMetaDataCollectionName        = "photo-metadata"
  val photoPlacesCollectionName          = "photo-places"
  val photoMiniaturesCollectionName      = "photo-miniatures"
  val photoNormalizedCollectionName      = "photo-normalized"
  val photoClassificationsCollectionName = "photo-classifications"

  val allCollections = List(
    photoStatesCollectionName,
    photoSourcesCollectionName,
    photoMetaDataCollectionName,
    photoPlacesCollectionName,
    photoMiniaturesCollectionName,
    photoNormalizedCollectionName,
    photoClassificationsCollectionName
  )
}

class PhotoStoreServiceLive private (
  lmdb: LMDB,
  statesCollection: LMDBCollection[DaoPhotoState],
  sourcesCollection: LMDBCollection[DaoPhotoSource],
  metaDataCollection: LMDBCollection[DaoPhotoMetaData],
  placesCollection: LMDBCollection[DaoPhotoPlace],
  miniaturesCollection: LMDBCollection[DaoMiniatures],
  normalizedCollection: LMDBCollection[DaoNormalizedPhoto],
  classificationsCollection: LMDBCollection[DaoPhotoClassifications]
) extends PhotoStoreService {

  private def convertFailures: PartialFunction[StorageSystemError | StorageUserError, PhotoStoreIssue] = {
    case e: StorageSystemError => PhotoStoreSystemIssue(e.toString)
    case e: StorageUserError   => PhotoStoreUserIssue(e.toString)
  }

  private def photoIdToCollectionKey(photoId: PhotoId): String          = photoId.id.toString
  private def originalIdToCollectionKey(originalId: OriginalId): String = originalId.id.toString

  // ===================================================================================================================
  def daoStateToState(from: Option[DaoPhotoState]): Option[PhotoState] = {
    from.map(daoState =>
      PhotoState(
        photoId = PhotoId(ULID(daoState.photoId)),
        photoHash = PhotoHash(daoState.photoHash),
        lastSeen = daoState.lastSynchronized,
        lastUpdated = daoState.lastUpdated,
        firstSeen = daoState.firstSeen,
        originalAddedOn = daoState.originalAddedOn
      )
    )
  }

  def stateToDaoState(from: PhotoState): DaoPhotoState = {
    DaoPhotoState(
      photoId = from.photoId.id.toString,
      photoHash = from.photoHash.code,
      lastSynchronized = from.lastSeen,
      lastUpdated = from.lastUpdated,
      firstSeen = from.firstSeen,
      originalAddedOn = from.originalAddedOn
    )
  }

  override def photoStateGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoState]] =
    statesCollection
      .fetch(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, daoStateToState)

  override def photoStateContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] =
    statesCollection
      .contains(photoIdToCollectionKey(photoId))
      .mapError(convertFailures)

  override def photoStateUpsert(photoId: PhotoId, photoState: PhotoState): IO[PhotoStoreIssue, Unit] =
    statesCollection
      .upsertOverwrite(photoIdToCollectionKey(photoId), stateToDaoState(photoState))
      .mapBoth(convertFailures, _ => ())

  override def photoStateDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    statesCollection
      .delete(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, _ => ())

  // ===================================================================================================================
  def daoSourceToSource(from: Option[DaoPhotoSource]): Option[PhotoSource] = {
    from.map(daoSource =>
      PhotoSource(
        photoId = PhotoId(ULID(daoSource.photoId)),
        original = Original(
          ownerId = PhotoOwnerId(ULID(daoSource.originalOwnerId)),
          baseDirectory = Path.of(daoSource.originalBaseDirectory),
          path = Path.of(daoSource.originalPath)
        ),
        fileSize = daoSource.fileSize,
        fileHash = PhotoHash(daoSource.fileHash),
        fileLastModified = daoSource.fileLastModified
      )
    )
  }

  def sourceToDaoSource(from: PhotoSource): DaoPhotoSource = {
    DaoPhotoSource(
      photoId = from.photoId.id.toString,
      originalOwnerId = from.original.ownerId.id.toString,
      originalBaseDirectory = from.original.baseDirectory.toString,
      originalPath = from.original.path.toString,
      fileSize = from.fileSize,
      fileHash = from.fileHash.code,
      fileLastModified = from.fileLastModified
    )
  }

  override def photoSourceGet(originalId: OriginalId): IO[PhotoStoreIssue, Option[PhotoSource]] =
    sourcesCollection
      .fetch(originalIdToCollectionKey(originalId))
      .mapBoth(convertFailures, daoSourceToSource)

  override def photoSourceContains(originalId: OriginalId): IO[PhotoStoreIssue, Boolean] =
    sourcesCollection
      .contains(originalIdToCollectionKey(originalId))
      .mapError(convertFailures)

  override def photoSourceUpsert(originalId: OriginalId, photoSource: PhotoSource): IO[PhotoStoreIssue, Unit] =
    sourcesCollection
      .upsertOverwrite(originalIdToCollectionKey(originalId), sourceToDaoSource(photoSource))
      .mapBoth(convertFailures, _ => ())

  override def photoSourceDelete(originalId: OriginalId): IO[PhotoStoreIssue, Unit] =
    sourcesCollection
      .delete(originalIdToCollectionKey(originalId))
      .mapBoth(convertFailures, _ => ())

  // ===================================================================================================================
  def daoMetaDataToMetaData(from: Option[DaoPhotoMetaData]): Option[PhotoMetaData] = {
    from.map(daoMetaData =>
      PhotoMetaData(
        dimension = daoMetaData.dimension.map(daoDim => Dimension2D(width = daoDim.width, height = daoDim.height)),
        shootDateTime = daoMetaData.shootDateTime,
        orientation = PhotoOrientation.values.find(orientation => daoMetaData.orientation.isDefined && orientation.code == daoMetaData.orientation.get), // TODO can be enhanced
        cameraName = daoMetaData.cameraName,
        tags = daoMetaData.tags
      )
    )
  }

  def metaDataToDaoMetaData(from: PhotoMetaData): DaoPhotoMetaData = {
    DaoPhotoMetaData(
      dimension = from.dimension.map(dim => DaoDimension2D(width = dim.width, height = dim.height)),
      shootDateTime = from.shootDateTime,
      orientation = from.orientation.map(_.code),
      cameraName = from.cameraName,
      tags = from.tags
    )
  }

  override def photoMetaDataGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoMetaData]] =
    metaDataCollection
      .fetch(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, daoMetaDataToMetaData)

  override def photoMetaDataContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] =
    metaDataCollection
      .contains(photoIdToCollectionKey(photoId))
      .mapError(convertFailures)

  override def photoMetaDataUpsert(photoId: PhotoId, photoMetaData: PhotoMetaData): IO[PhotoStoreIssue, Unit] =
    metaDataCollection
      .upsertOverwrite(photoIdToCollectionKey(photoId), metaDataToDaoMetaData(photoMetaData))
      .mapBoth(convertFailures, _ => ())

  override def photoMetaDataDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    metaDataCollection
      .delete(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, _ => ())

  // ===================================================================================================================
  def daoPlaceToPlace(from: Option[DaoPhotoPlace]): Option[PhotoPlace] = {
    from.map(daoPlace =>
      PhotoPlace(
        latitude = LatitudeDecimalDegrees(daoPlace.latitude),
        longitude = LongitudeDecimalDegrees(daoPlace.longitude),
        altitude = daoPlace.altitude,
        deducted = daoPlace.deducted
      )
    )
  }

  def placeToDaoPlace(from: PhotoPlace): DaoPhotoPlace =
    DaoPhotoPlace(
      latitude = from.latitude.doubleValue,
      longitude = from.longitude.doubleValue,
      altitude = from.altitude.map(_.doubleValue),
      deducted = from.deducted
    )

  override def photoPlaceGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoPlace]] =
    placesCollection
      .fetch(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, daoPlaceToPlace)

  override def photoPlaceContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] =
    placesCollection
      .contains(photoIdToCollectionKey(photoId))
      .mapError(convertFailures)

  override def photoPlaceUpsert(photoId: PhotoId, place: PhotoPlace): IO[PhotoStoreIssue, Unit] =
    placesCollection
      .upsertOverwrite(photoIdToCollectionKey(photoId), placeToDaoPlace(place))
      .mapBoth(convertFailures, _ => ())

  override def photoPlaceDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    placesCollection
      .delete(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, _ => ())

  // ===================================================================================================================
  def daoMiniaturesToMiniatures(from: Option[DaoMiniatures]): Option[Miniatures] = {
    from.map(daoMiniatures =>
      Miniatures(
        sources = daoMiniatures.sources.map(s => MiniatureSource(size = s.size, dimension = Dimension2D(width = s.dimension.width, height = s.dimension.height)))
      )
    )
  }

  def miniaturesToDaoMiniatures(from: Miniatures): DaoMiniatures =
    DaoMiniatures(
      sources = from.sources.map(s => DaoMiniatureSource(size = s.size, dimension = DaoDimension2D(width = s.dimension.width, height = s.dimension.height)))
    )

  override def photoMiniaturesGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[Miniatures]] =
    miniaturesCollection
      .fetch(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, daoMiniaturesToMiniatures)

  override def photoMiniaturesContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] =
    miniaturesCollection
      .contains(photoIdToCollectionKey(photoId))
      .mapError(convertFailures)

  override def photoMiniaturesUpsert(photoId: PhotoId, miniatures: Miniatures): IO[PhotoStoreIssue, Unit] =
    miniaturesCollection
      .upsertOverwrite(photoIdToCollectionKey(photoId), miniaturesToDaoMiniatures(miniatures))
      .mapBoth(convertFailures, _ => ())

  override def photoMiniaturesDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    miniaturesCollection
      .delete(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, _ => ())

  // ===================================================================================================================  // ===================================================================================================================
  def daoNormalizedToNormalized(from: Option[DaoNormalizedPhoto]): Option[NormalizedPhoto] = {
    from.map(daoNormalized =>
      NormalizedPhoto(
        size = daoNormalized.size,
        dimension = Dimension2D(
          width = daoNormalized.dimension.width,
          height = daoNormalized.dimension.height
        )
      )
    )
  }

  def normalizedToDaoNormalized(from: NormalizedPhoto): DaoNormalizedPhoto =
    DaoNormalizedPhoto(
      size = from.size,
      dimension = DaoDimension2D(
        width = from.dimension.width,
        height = from.dimension.height
      )
    )

  override def photoNormalizedGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[NormalizedPhoto]] =
    normalizedCollection
      .fetch(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, daoNormalizedToNormalized)

  override def photoNormalizedContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] =
    normalizedCollection
      .contains(photoIdToCollectionKey(photoId))
      .mapError(convertFailures)

  override def photoNormalizedUpsert(photoId: PhotoId, normalized: NormalizedPhoto): IO[PhotoStoreIssue, Unit] =
    normalizedCollection
      .upsertOverwrite(photoIdToCollectionKey(photoId), normalizedToDaoNormalized(normalized))
      .mapBoth(convertFailures, _ => ())

  override def photoNormalizedDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    normalizedCollection
      .delete(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, _ => ())

  // ===================================================================================================================
  def daoClassificationsToClassifications(from: Option[DaoPhotoClassifications]): Option[PhotoClassifications] = {
    from.map(daoClassifications =>
      PhotoClassifications(
        classifications = daoClassifications.classifications.map(that => DetectedClassification(that.name))
      )
    )
  }

  def classificationsToDaoClassifications(from: PhotoClassifications): DaoPhotoClassifications = {
    DaoPhotoClassifications(
      classifications = from.classifications.map(that => DaoDetectedClassification(name = that.name))
    )
  }

  override def photoClassificationsGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoClassifications]] =
    classificationsCollection
      .fetch(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, daoClassificationsToClassifications)

  override def photoClassificationsContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] =
    classificationsCollection
      .contains(photoIdToCollectionKey(photoId))
      .mapError(convertFailures)

  override def photoClassificationsUpsert(photoId: PhotoId, photoClassifications: PhotoClassifications): IO[PhotoStoreIssue, Unit] =
    classificationsCollection
      .upsertOverwrite(photoIdToCollectionKey(photoId), classificationsToDaoClassifications(photoClassifications))
      .mapBoth(convertFailures, _ => ())

  override def photoClassificationsDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    classificationsCollection
      .delete(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, _ => ())

  // ===================================================================================================================
}

object PhotoStoreServiceLive extends PhotoStoreCollections {

  def setup(lmdb: LMDB) = for {
    _                         <- foreach(allCollections)(col => lmdb.collectionAllocate(col).ignore)
    statesCollection          <- lmdb.collectionGet[DaoPhotoState](photoStatesCollectionName)
    sourcesCollection         <- lmdb.collectionGet[DaoPhotoSource](photoSourcesCollectionName)
    metaDataCollection        <- lmdb.collectionGet[DaoPhotoMetaData](photoMetaDataCollectionName)
    placesCollection          <- lmdb.collectionGet[DaoPhotoPlace](photoPlacesCollectionName)
    miniaturesCollection      <- lmdb.collectionGet[DaoMiniatures](photoMiniaturesCollectionName)
    normalizedCollection      <- lmdb.collectionGet[DaoNormalizedPhoto](photoNormalizedCollectionName)
    classificationsCollection <- lmdb.collectionGet[DaoPhotoClassifications](photoClassificationsCollectionName)
  } yield PhotoStoreServiceLive(
    lmdb,
    statesCollection,
    sourcesCollection,
    metaDataCollection,
    placesCollection,
    miniaturesCollection,
    normalizedCollection,
    classificationsCollection
  )
}
