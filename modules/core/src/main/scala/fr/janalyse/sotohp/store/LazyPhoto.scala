package fr.janalyse.sotohp.store

import fr.janalyse.sotohp.config.{SotohpConfig, SotohpConfigIssue}
import fr.janalyse.sotohp.core.PhotoOperations
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.store.{PhotoStoreIssue, PhotoStoreService}
import zio.*

import java.nio.file.Path
import java.time.OffsetDateTime

case class LazyPhoto(state: PhotoState) {

  def source: ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoSource]] =
    PhotoStoreService.photoSourceGet(state.originalId)

  def hasMetaData: ZIO[PhotoStoreService, PhotoStoreIssue, Boolean] =
    PhotoStoreService.photoMetaDataContains(state.photoId)

  def metaData: ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoMetaData]] =
    PhotoStoreService.photoMetaDataGet(state.photoId)

  def hasPlace: ZIO[PhotoStoreService, PhotoStoreIssue, Boolean] =
    PhotoStoreService.photoPlaceContains(state.photoId)

  def place: ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoPlace]] =
    PhotoStoreService.photoPlaceGet(state.photoId)

  def hasMiniatures: ZIO[PhotoStoreService, PhotoStoreIssue, Boolean] =
    PhotoStoreService.photoMiniaturesContains(state.photoId)

  def miniatures: ZIO[PhotoStoreService, PhotoStoreIssue, Option[Miniatures]] =
    PhotoStoreService.photoMiniaturesGet(state.photoId)

  def hasNormalized: ZIO[PhotoStoreService, PhotoStoreIssue, Boolean] =
    PhotoStoreService.photoNormalizedContains(state.photoId)

  def normalized: ZIO[PhotoStoreService, PhotoStoreIssue, Option[NormalizedPhoto]] =
    PhotoStoreService.photoNormalizedGet(state.photoId)

  def hasDescription: ZIO[PhotoStoreService, PhotoStoreIssue, Boolean] =
    PhotoStoreService.photoDescriptionContains(state.photoId)

  def description: ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoDescription]] =
    PhotoStoreService.photoDescriptionGet(state.photoId)

  def hasFoundClassifications: ZIO[PhotoStoreService, PhotoStoreIssue, Boolean] =
    PhotoStoreService.photoClassificationsContains(state.photoId)

  def foundClassifications: ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoClassifications]] =
    PhotoStoreService.photoClassificationsGet(state.photoId)

  def hasFoundObjects: ZIO[PhotoStoreService, PhotoStoreIssue, Boolean] =
    PhotoStoreService.photoObjectsContains(state.photoId)

  def foundObjects: ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoObjects]] =
    PhotoStoreService.photoObjectsGet(state.photoId)

  def hasFoundFaces: ZIO[PhotoStoreService, PhotoStoreIssue, Boolean] =
    PhotoStoreService.photoFacesContains(state.photoId)

  def foundFaces: ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoFaces]] =
    PhotoStoreService.photoFacesGet(state.photoId)

  // -------------------------------------------------------------------------------------

  def photoOriginalPath: ZIO[PhotoStoreService, PhotoStoreNotFoundIssue, PhotoPath] = for {
    photoSource <- source.some.mapError(err => PhotoStoreNotFoundIssue("source not found"))
    path         = photoSource.original.path
  } yield path

  def photoNormalizedPath: ZIO[PhotoStoreService, SotohpConfigIssue | PhotoStoreNotFoundIssue, Path] = for {
    photoSource <- source.some.mapError(err => PhotoStoreNotFoundIssue("source not found"))
    _           <- normalized.some.mapError(err => PhotoStoreNotFoundIssue("normalized photo not found"))
    path        <- PhotoOperations.getNormalizedPhotoFilePath(photoSource)
  } yield path

  def photoMiniaturePath(size: Int): ZIO[PhotoStoreService, SotohpConfigIssue | PhotoStoreNotFoundIssue, Path] = for {
    photoSource     <- source.some.mapError(err => PhotoStoreNotFoundIssue("source not found"))
    photoMiniatures <- miniatures.some.mapError(err => PhotoStoreNotFoundIssue("miniatures not found"))
    _               <- ZIO.cond(photoMiniatures.sources.exists(_.size == size), (), PhotoStoreNotFoundIssue(s"miniature $size not found"))
    path            <- PhotoOperations.getMiniaturePhotoFilePath(photoSource, size)
  } yield path
}
