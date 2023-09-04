package fr.janalyse.sotohp.store

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.store.{PhotoStoreIssue, PhotoStoreService}
import zio.*

import java.time.OffsetDateTime

trait ZPhoto {
  val state: PhotoState

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
}
