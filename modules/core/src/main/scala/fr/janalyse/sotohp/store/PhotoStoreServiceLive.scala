package fr.janalyse.sotohp.store

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.model.DecimalDegrees.*
import fr.janalyse.sotohp.store.dao.*
import zio.*
import zio.ZIO.*
import zio.lmdb.*
import java.nio.file.Path

trait PhotoStoreCollections {
  val photoStatesCollectionName   = "photo-states"
  val photoSourcesCollectionName  = "photo-sources"
  val photoMetaDataCollectionName = "photo-metadata"
  val photoPlacesCollectionName   = "photo-places"

  val allCollections = List(
    photoStatesCollectionName,
    photoSourcesCollectionName,
    photoMetaDataCollectionName,
    photoPlacesCollectionName
  )
}

class PhotoStoreServiceLive private (
  lmdb: LMDB,
  statesCollection: LMDBCollection[DaoPhotoState],
  sourcesCollection: LMDBCollection[DaoPhotoSource],
  metaDataCollection: LMDBCollection[DaoPhotoMetaData],
  placesCollection: LMDBCollection[DaoPhotoPlace]
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
        lastSynchronized = daoState.lastSynchronized,
        lastUpdated = daoState.lastUpdated,
        firstSeen = daoState.firstSeen
      )
    )
  }

  def stateToDaoState(from: PhotoState): DaoPhotoState = {
    DaoPhotoState(
      photoId = from.photoId.uuid,
      photoHash = from.photoHash.code,
      lastSynchronized = from.lastSynchronized,
      lastUpdated = from.lastUpdated,
      firstSeen = from.firstSeen
    )
  }

  override def photoStateGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoState]] =
    statesCollection
      .fetch(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, daoStateToState)

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

  override def photoPlaceUpsert(photoId: PhotoId, place: PhotoPlace): IO[PhotoStoreIssue, Unit] =
    placesCollection
      .upsertOverwrite(photoIdToCollectionKey(photoId), placeToDaoPlace(place))
      .mapBoth(convertFailures, _ => ())

  override def photoPlaceDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    placesCollection
      .delete(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, _ => ())

  // ===================================================================================================================
}

object PhotoStoreServiceLive extends PhotoStoreCollections {

  def setup(lmdb: LMDB) = for {
    _                  <- foreach(allCollections)(col => lmdb.collectionAllocate(col).ignore)
    statesCollection   <- lmdb.collectionGet[DaoPhotoState](photoStatesCollectionName)
    sourcesCollection  <- lmdb.collectionGet[DaoPhotoSource](photoSourcesCollectionName)
    metaDataCollection <- lmdb.collectionGet[DaoPhotoMetaData](photoMetaDataCollectionName)
    placesCollection   <- lmdb.collectionGet[DaoPhotoPlace](photoPlacesCollectionName)
  } yield PhotoStoreServiceLive(lmdb, statesCollection, sourcesCollection, metaDataCollection, placesCollection)
}
