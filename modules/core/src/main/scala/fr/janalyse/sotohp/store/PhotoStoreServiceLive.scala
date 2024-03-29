package fr.janalyse.sotohp.store

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.model.DecimalDegrees.*
import fr.janalyse.sotohp.store.dao.*
import wvlet.airframe.ulid.ULID
import zio.*
import zio.stream.*
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
  val photoObjectsCollectionName         = "photo-objects"
  val photoFacesCollectionName           = "photo-faces"
  val photoDescriptionsCollectionName    = "photo-descriptions"

  val allCollections = List(
    photoStatesCollectionName,
    photoSourcesCollectionName,
    photoMetaDataCollectionName,
    photoPlacesCollectionName,
    photoMiniaturesCollectionName,
    photoNormalizedCollectionName,
    photoClassificationsCollectionName,
    photoObjectsCollectionName,
    photoFacesCollectionName,
    photoDescriptionsCollectionName
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
  classificationsCollection: LMDBCollection[DaoPhotoClassifications],
  objectsCollection: LMDBCollection[DaoPhotoObjects],
  facesCollection: LMDBCollection[DaoPhotoFaces],
  descriptionsCollection: LMDBCollection[DaoPhotoDescription]
) extends PhotoStoreService {

  private def convertFailures: PartialFunction[StorageSystemError | StorageUserError, PhotoStoreIssue] = {
    case e: StorageSystemError => PhotoStoreSystemIssue(e.toString)
    case e: StorageUserError   => PhotoStoreUserIssue(e.toString)
  }

  private def photoIdToCollectionKey(photoId: PhotoId): String          = photoId.id.toString
  private def originalIdToCollectionKey(originalId: OriginalId): String = originalId.id.toString

  // ===================================================================================================================
  override def photoDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] = {
    // TODO to redesign & refactor / requires an overall transaction / to move elsewhere
    for {
      state <- photoStateGet(photoId).some.orElseFail[PhotoStoreIssue](PhotoStoreNotFoundIssue(s"$photoId not found"))
      _     <- photoClassificationsDelete(photoId)
      _     <- photoDescriptionDelete(photoId)
      _     <- photoFacesDelete(photoId)
      _     <- photoMetaDataDelete(photoId)
      _     <- photoMiniaturesDelete(photoId)
      _     <- photoNormalizedDelete(photoId)
      _     <- photoObjectsDelete(photoId)
      _     <- photoPlaceDelete(photoId)
      _     <- photoSourceDelete(state.originalId)
      _     <- photoStateDelete(photoId)
    } yield ()
  }

  def photoFirst(): IO[PhotoStoreIssue, Option[LazyPhoto]]                   = photoStateFirst().map(foundThatState => foundThatState.map(LazyPhoto.apply))
  def photoNext(after: PhotoId): IO[PhotoStoreIssue, Option[LazyPhoto]]      = photoStateNext(after).map(foundThatState => foundThatState.map(LazyPhoto.apply))
  def photoPrevious(before: PhotoId): IO[PhotoStoreIssue, Option[LazyPhoto]] = photoStatePrevious(before).map(foundThatState => foundThatState.map(LazyPhoto.apply))
  def photoLast(): IO[PhotoStoreIssue, Option[LazyPhoto]]                    = photoStateLast().map(foundThatState => foundThatState.map(LazyPhoto.apply))

  // ===================================================================================================================
  def daoStateToState(from: DaoPhotoState): PhotoState = {
    PhotoState(
      photoId = PhotoId(ULID(from.photoId)),
      originalId = OriginalId(UUID.fromString(from.originalId)),
      photoHash = PhotoHash(from.photoHash),
      photoOwnerId = PhotoOwnerId(ULID.fromString(from.photoOwnerId)),
      photoTimestamp = from.photoTimestamp,
      lastSeen = from.lastSeen,
      firstSeen = from.firstSeen,
      lastSynchronized = from.lastSynchronized
    )
  }

  def stateToDaoState(from: PhotoState): DaoPhotoState = {
    DaoPhotoState(
      photoId = from.photoId.id.toString,
      originalId = from.originalId.id.toString,
      photoHash = from.photoHash.code,
      photoOwnerId = from.photoOwnerId.toString,
      photoTimestamp = from.photoTimestamp,
      lastSeen = from.lastSeen,
      firstSeen = from.firstSeen,
      lastSynchronized = from.lastSynchronized
    )
  }

  override def photoStateGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoState]] =
    statesCollection
      .fetch(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, found => found.map(daoStateToState))

  override def photoStateStream(): ZStream[Any, PhotoStoreIssue, PhotoState] =
    statesCollection
      .stream()
      .mapBoth(convertFailures, daoStateToState)

  override def photoStateContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] =
    statesCollection
      .contains(photoIdToCollectionKey(photoId))
      .mapError(convertFailures)

  override def photoStateUpsert(photoId: PhotoId, photoState: PhotoState): IO[PhotoStoreIssue, Unit] =
    statesCollection
      .upsertOverwrite(photoIdToCollectionKey(photoId), stateToDaoState(photoState))
      .mapBoth(convertFailures, _ => ())

  override def photoStateUpdate(photoId: PhotoId, photoStateUpdater: PhotoState => PhotoState): IO[PhotoStoreIssue, Option[PhotoState]] = {
    statesCollection
      .update(photoIdToCollectionKey(photoId), daoState => stateToDaoState(photoStateUpdater(daoStateToState(daoState))))
      .mapBoth(convertFailures, _.map(daoStateToState))
  }

  override def photoStateDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    statesCollection
      .delete(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, _ => ())

  def photoStateFirst(): IO[PhotoStoreIssue, Option[PhotoState]] =
    statesCollection
      .head()
      .mapBoth(convertFailures, result => result.map((key, daoState) => daoStateToState(daoState)))

  def photoStateNext(after: PhotoId): IO[PhotoStoreIssue, Option[PhotoState]] =
    statesCollection
      .next(photoIdToCollectionKey(after))
      .mapBoth(convertFailures, result => result.map((key, daoState) => daoStateToState(daoState)))

  def photoStatePrevious(before: PhotoId): IO[PhotoStoreIssue, Option[PhotoState]] =
    statesCollection
      .previous(photoIdToCollectionKey(before))
      .mapBoth(convertFailures, result => result.map((key, daoState) => daoStateToState(daoState)))

  def photoStateLast(): IO[PhotoStoreIssue, Option[PhotoState]] =
    statesCollection
      .last()
      .mapBoth(convertFailures, result => result.map((key, daoState) => daoStateToState(daoState)))

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
  def daoObjectsToObjects(from: Option[DaoPhotoObjects]): Option[PhotoObjects] = {
    from.map(daoObjects =>
      PhotoObjects(
        objects = daoObjects.objects.map(that =>
          DetectedObject(
            name = that.name,
            box = BoundingBox(
              x = that.box.x,
              y = that.box.y,
              width = that.box.width,
              height = that.box.height
            )
          )
        )
      )
    )
  }

  def objectsToDaoObjects(from: PhotoObjects): DaoPhotoObjects = {
    DaoPhotoObjects(
      objects = from.objects.map(that =>
        DaoDetectedObject(
          name = that.name,
          box = DaoBoundingBox(
            x = that.box.x,
            y = that.box.y,
            width = that.box.width,
            height = that.box.height
          )
        )
      )
    )
  }

  override def photoObjectsGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoObjects]] =
    objectsCollection
      .fetch(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, daoObjectsToObjects)

  override def photoObjectsContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] =
    objectsCollection
      .contains(photoIdToCollectionKey(photoId))
      .mapError(convertFailures)

  override def photoObjectsUpsert(photoId: PhotoId, photoObjects: PhotoObjects): IO[PhotoStoreIssue, Unit] =
    objectsCollection
      .upsertOverwrite(photoIdToCollectionKey(photoId), objectsToDaoObjects(photoObjects))
      .mapBoth(convertFailures, _ => ())

  override def photoObjectsDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    objectsCollection
      .delete(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, _ => ())

  // ===================================================================================================================
  def daoFacesToFaces(from: Option[DaoPhotoFaces]): Option[PhotoFaces] = {
    from.map(daoFaces =>
      PhotoFaces(
        count = daoFaces.count,
        faces = daoFaces.faces.map(that =>
          DetectedFace(
            someoneId = that.someoneId.map(id => SomeoneId(ULID.fromString(id))),
            box = BoundingBox(
              x = that.box.x,
              y = that.box.y,
              width = that.box.width,
              height = that.box.height
            ),
            faceId = ULID.fromString(that.faceId)
          )
        )
      )
    )
  }

  def facesToDaoFaces(from: PhotoFaces): DaoPhotoFaces = {
    DaoPhotoFaces(
      count = from.count,
      faces = from.faces.map(that =>
        DaoDetectedFace(
          someoneId = that.someoneId.map(_.toString),
          box = DaoBoundingBox(
            x = that.box.x,
            y = that.box.y,
            width = that.box.width,
            height = that.box.height
          ),
          faceId = that.faceId.toString
        )
      )
    )
  }

  override def photoFacesGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoFaces]] =
    facesCollection
      .fetch(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, daoFacesToFaces)

  override def photoFacesContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] =
    facesCollection
      .contains(photoIdToCollectionKey(photoId))
      .mapError(convertFailures)

  override def photoFacesUpsert(photoId: PhotoId, photoFaces: PhotoFaces): IO[PhotoStoreIssue, Unit] =
    facesCollection
      .upsertOverwrite(photoIdToCollectionKey(photoId), facesToDaoFaces(photoFaces))
      .mapBoth(convertFailures, _ => ())

  override def photoFacesDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    facesCollection
      .delete(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, _ => ())

  // ===================================================================================================================
  def daoDescriptionToDescription(from: DaoPhotoDescription): PhotoDescription = {
    PhotoDescription(
      text = from.text,
      category = from.category.map(PhotoCategory.apply),
      keywords = from.keywords.map(keywords => keywords.map(PhotoKeyword.apply))
    )
  }

  def descriptionsToDaoDescription(from: PhotoDescription): DaoPhotoDescription = {
    DaoPhotoDescription(
      text = from.text,
      category = from.category.map(_.text),
      keywords = from.keywords.map(keywords => keywords.map(_.text))
    )
  }

  override def photoDescriptionGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoDescription]] =
    descriptionsCollection
      .fetch(photoIdToCollectionKey(photoId))
      .mapBoth(convertFailures, _.map(daoDescriptionToDescription))

  override def photoDescriptionContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean] =
    descriptionsCollection
      .contains(photoIdToCollectionKey(photoId))
      .mapError(convertFailures)

  override def photoDescriptionUpsert(photoId: PhotoId, photoDescription: PhotoDescription): IO[PhotoStoreIssue, Unit] =
    descriptionsCollection
      .upsertOverwrite(photoIdToCollectionKey(photoId), descriptionsToDaoDescription(photoDescription))
      .mapBoth(convertFailures, _ => ())

  override def photoDescriptionDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    descriptionsCollection
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
    objectsCollection         <- lmdb.collectionGet[DaoPhotoObjects](photoObjectsCollectionName)
    facesCollection           <- lmdb.collectionGet[DaoPhotoFaces](photoFacesCollectionName)
    descriptionsCollection    <- lmdb.collectionGet[DaoPhotoDescription](photoDescriptionsCollectionName)
  } yield PhotoStoreServiceLive(
    lmdb,
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
}
