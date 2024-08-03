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
    PhotoStoreService
      .photoStateStream()
      .filter(_.photoOwnerId == photoOwnerId) // TODO refactor required - performance issue
      .mapZIO(state => PhotoOperations.makePhotoFromStoredState(state))
  }

  def photoStream(): PhotoStream = {
    PhotoStoreService
      .photoStateStream()
      .mapZIO(state => PhotoOperations.makePhotoFromStoredState(state))
  }

  def photoLazyStream(photoOwnerId: PhotoOwnerId): PhotoLazyStream = {
    PhotoStoreService
      .photoLazyStream()
      .filter(_.state.photoOwnerId == photoOwnerId) // TODO refactor required - performance issue
  }

  def photoLazyStream(): PhotoLazyStream = {
    PhotoStoreService
      .photoLazyStream()
  }
}
