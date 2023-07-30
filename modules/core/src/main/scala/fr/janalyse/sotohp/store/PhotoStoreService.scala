package fr.janalyse.sotohp.store

import fr.janalyse.sotohp.model.*
import zio.*
import zio.ZIO.*

trait PhotoStoreService {
  // photo states collection
  def photoStateGet(photoId: PhotoId): Task[Option[PhotoState]]
  def photoStateUpsert(photoState: PhotoState): Task[PhotoState]
  def photoStateDelete(photoId: PhotoId): Task[Unit]

  // photos collection
  def photoGet(photoId: PhotoId): Task[Photo]
  def photoUpsert(photo: Photo): Task[Photo]
  def photoDelete(photoId: PhotoId): Task[Unit]

  // Miniatures collection
  def photoMiniaturesGet(photoId: PhotoId): Task[Miniatures]
  def photoMiniaturesUpsert(photoId: PhotoId, miniatures: Miniatures): Task[Miniatures]
  def photoMiniaturesDelete(photoId: PhotoId): Task[Unit]
}
