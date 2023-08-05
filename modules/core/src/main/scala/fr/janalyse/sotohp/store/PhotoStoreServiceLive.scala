package fr.janalyse.sotohp.store

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.model.DecimalDegrees.*
import fr.janalyse.sotohp.store.dao.*
import zio.*
import zio.ZIO.*
import zio.lmdb.*
import java.nio.file.Path

trait PhotoStoreCollections {
  val photoStatesCollectionName     = "photo-states"
  val photoSourcesCollectionName    = "photo-sources"
  val photoMetaDataCollectionName   = "photo-metadata"
  val photoPlacesCollectionName     = "photo-places"
  val photoMiniaturesCollectionName = "photo-miniatures"
  val photoNormalizedCollectionName = "photo-normalized"

  val allCollections = List(
    photoStatesCollectionName,
    photoSourcesCollectionName,
    photoMetaDataCollectionName,
    photoPlacesCollectionName,
    photoMiniaturesCollectionName,
    photoNormalizedCollectionName
  )
}

class PhotoStoreServiceLive private (
  lmdb: LMDB,
  statesCollection: LMDBCollection[DaoPhotoState],
  sourcesCollection: LMDBCollection[DaoPhotoSource],
  metaDataCollection: LMDBCollection[DaoPhotoMetaData],
  placesCollection: LMDBCollection[DaoPhotoPlace],
  miniaturesCollection: LMDBCollection[DaoMiniatures],
  normalizedCollection: LMDBCollection[DaoNormalizedPhoto]
) extends PhotoStoreService {

  private def convertFailures: PartialFunction[StorageSystemError | StorageUserError, PhotoStoreIssue] = {
    case e: StorageSystemError => PhotoStoreSystemIssue(e.toString)
    case e: StorageUserError   => PhotoStoreUserIssue(e.toString)
  }

  private def photoIdToCollectionKey(photoId: PhotoId): String = photoId.uuid.toString

  // ===================================================================================================================
  def daoStateToState(from: Option[DaoPhotoState]): Option[PhotoState] = {
    from.map(daoState =>
      PhotoState(
        photoId = PhotoId(daoState.photoId),
        photoHash = PhotoHash(daoState.photoHash),
        lastSeen = daoState.lastSynchronized,
        lastUpdated = daoState.lastUpdated,
        firstSeen = daoState.firstSeen
      )
    )
  }

  def stateToDaoState(from: PhotoState): DaoPhotoState = {
    DaoPhotoState(
      photoId = from.photoId.uuid,
      photoHash = from.photoHash.code,
      lastSynchronized = from.lastSeen,
      lastUpdated = from.lastUpdated,
      firstSeen = from.firstSeen
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
        ownerId = PhotoOwnerId(daoSource.ownerId),
        baseDirectory = Path.of(daoSource.baseDirectory),
        photoPath = Path.of(daoSource.photoPath),
        fileSize = daoSource.fileSize,
        fileHash = PhotoHash(daoSource.fileHash),
        fileLastModified = daoSource.fileLastModified
      )
    )
  }

  def sourceToDaoSource(from: PhotoSource): DaoPhotoSource = {
    DaoPhotoSource(
      ownerId = from.ownerId.uuid,
      baseDirectory = from.baseDirectory.toString,
      photoPath = from.photoPath.toString,
      fileSize = from.fileSize,
      fileHash = from.fileHash.code,
      fileLastModified = from.fileLastModified
    )
  }

  override def photoSourceGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoSource]] =
    sourcesCollection
      .fetch(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, daoSourceToSource)

  override def photoSourceContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] =
    sourcesCollection
      .contains(photoIdToCollectionKey(photoId))
      .mapError(convertFailures)

  override def photoSourceUpsert(photoId: PhotoId, photoSource: PhotoSource): IO[PhotoStoreIssue, Unit] =
    sourcesCollection
      .upsertOverwrite(photoIdToCollectionKey(photoId), sourceToDaoSource(photoSource))
      .mapBoth(convertFailures, _ => ())

  override def photoSourceDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    sourcesCollection
      .delete(photoIdToCollectionKey(photoId))
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
        altitude = daoPlace.altitude
      )
    )
  }

  def placeToDaoPlace(from: PhotoPlace): DaoPhotoPlace =
    DaoPhotoPlace(
      latitude = from.latitude.doubleValue,
      longitude = from.longitude.doubleValue,
      altitude = from.altitude.doubleValue
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
        sources = daoMiniatures.sources.map(s => MiniatureSource(path = Path.of(s.path), referenceSize = s.referenceSize)),
        lastUpdated = daoMiniatures.lastUpdated
      )
    )
  }

  def miniaturesToDaoMiniatures(from: Miniatures): DaoMiniatures =
    DaoMiniatures(
      sources = from.sources.map(s => DaoMiniatureSource(path = s.path.toString, referenceSize = s.referenceSize)),
      lastUpdated = from.lastUpdated
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
        path = Path.of(daoNormalized.path),
        dimension = Dimension2D(
          width = daoNormalized.dimension.width,
          height = daoNormalized.dimension.height
        )
      )
    )
  }

  def normalizedToDaoNormalized(from: NormalizedPhoto): DaoNormalizedPhoto =
    DaoNormalizedPhoto(
      path = from.path.toString,
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
}

object PhotoStoreServiceLive extends PhotoStoreCollections {

  def setup(lmdb: LMDB) = for {
    _                    <- foreach(allCollections)(col => lmdb.collectionAllocate(col).ignore)
    statesCollection     <- lmdb.collectionGet[DaoPhotoState](photoStatesCollectionName)
    sourcesCollection    <- lmdb.collectionGet[DaoPhotoSource](photoSourcesCollectionName)
    metaDataCollection   <- lmdb.collectionGet[DaoPhotoMetaData](photoMetaDataCollectionName)
    placesCollection     <- lmdb.collectionGet[DaoPhotoPlace](photoPlacesCollectionName)
    miniaturesCollection <- lmdb.collectionGet[DaoMiniatures](photoMiniaturesCollectionName)
    normalizedCollection <- lmdb.collectionGet[DaoNormalizedPhoto](photoNormalizedCollectionName)
  } yield PhotoStoreServiceLive(
    lmdb,
    statesCollection,
    sourcesCollection,
    metaDataCollection,
    placesCollection,
    miniaturesCollection,
    normalizedCollection
  )
}
