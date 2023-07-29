package fr.janalyse.sotohp.store

import fr.janalyse.sotohp.model.*
import zio.*
import zio.ZIO.*

trait PhotoStoreService {
  // photo states collection
  def photoStateGet(photoId: PhotoId): Task[Option[PhotoState]]
  def photoStateUpsert(photoState: PhotoState): Task[PhotoState]
  def photoStateDelete(photoId: PhotoId): Task[Unit]

  // photo hashes collection
  def photoHashGet(photoId: PhotoId): Task[Option[PhotoHash]]
  def photoHashAdd(photoId: PhotoId, hash: PhotoHash): Task[PhotoHash]

  // photos collection (with subqueries to complete data structure)
  def photoGet(photoId: PhotoId): Task[Photo]
  def photoUpsert(photo: Photo): Task[Photo]
  def photoDelete(photoId: PhotoId): Task[Unit]

  // Miniatures collection
  def photoMiniaturesGet(photoId: PhotoId): Task[Miniatures]
  def photoMiniaturesUpsert(photoId: PhotoId, miniatures: Miniatures): Task[Miniatures]
  def photoMiniaturesDelete(photoId: PhotoId): Task[Unit]
}
