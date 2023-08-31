package fr.janalyse.sotohp.core

import zio.*
import zio.stream.*
import fr.janalyse.sotohp.store.{PhotoStoreIssue, PhotoStoreService}
import fr.janalyse.sotohp.model.{Photo, PhotoOwnerId}

object PhotoStream {
  type PhotoStreamIssues = StreamIOIssue | PhotoStoreIssue | NotFoundInStore
  type PhotoStream       = ZStream[PhotoStoreService, PhotoStreamIssues, Photo]

  def photoStream(photoOwnerId: PhotoOwnerId): PhotoStream = {
    ???
  }
  
  def photoStream(): PhotoStream = {
    PhotoStoreService
      .photoStateStream()
      .mapZIO(state => PhotoOperations.makePhotoFromStoredState(state))
  }
}
