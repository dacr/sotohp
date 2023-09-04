package fr.janalyse.sotohp.store

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.store.{PhotoStoreIssue, PhotoStoreService}
import zio.*

import java.time.OffsetDateTime

trait ZPhoto {
  val state: PhotoState

  def source: ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoSource]] =
    PhotoStoreService.photoSourceGet(state.originalId)

  def metaData: ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoMetaData]] =
    PhotoStoreService.photoMetaDataGet(state.photoId)

  def place: ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoPlace]] =
    PhotoStoreService.photoPlaceGet(state.photoId)

  def miniatures: ZIO[PhotoStoreService, PhotoStoreIssue, Option[Miniatures]] =
    PhotoStoreService.photoMiniaturesGet(state.photoId)

  def normalized: ZIO[PhotoStoreService, PhotoStoreIssue, Option[NormalizedPhoto]] =
    PhotoStoreService.photoNormalizedGet(state.photoId)

  def description: ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoDescription]] =
    PhotoStoreService.photoDescriptionGet(state.photoId)

  def foundClassifications: ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoClassifications]] =
    PhotoStoreService.photoClassificationsGet(state.photoId)

  def foundObjects: ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoObjects]] =
    PhotoStoreService.photoObjectsGet(state.photoId)

  def foundFaces: ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoFaces]] =
    PhotoStoreService.photoFacesGet(state.photoId)
}
