package fr.janalyse.sotohp.core

import zio.*
import zio.stream.*
import fr.janalyse.sotohp.store.{PhotoStoreIssue, PhotoStoreService, LazyPhoto}
import fr.janalyse.sotohp.model.{Photo, PhotoOwnerId}

object PhotoStream {
  type PhotoStreamIssues = StreamIOIssue | PhotoStoreIssue | NotFoundInStore
  type PhotoStream       = ZStream[PhotoStoreService, PhotoStreamIssues, Photo]
  type PhotoLazyStream   = ZStream[PhotoStoreService, PhotoStreamIssues, LazyPhoto]

  def photoStream(photoOwnerId: PhotoOwnerId): PhotoStream = {
    ???
  }

  def photoStream(): PhotoStream = {
    PhotoStoreService
      .photoStateStream()
      .mapZIO(state => PhotoOperations.makePhotoFromStoredState(state))
  }

  def photoLazyStream(photoOwnerId: PhotoOwnerId): PhotoLazyStream = {
    ???
  }

  def photoLazyStream(): PhotoLazyStream = {
    PhotoStoreService
      .photoLazyStream()
  }
}
