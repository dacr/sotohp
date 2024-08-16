package fr.janalyse.sotohp.gui

import fr.janalyse.sotohp.core.PhotoOperations
import zio.*
import fr.janalyse.sotohp.model.{Miniatures, NormalizedPhoto, PhotoClassifications, PhotoDescription, PhotoFaces, PhotoObjects, PhotoOrientation, PhotoPlace, PhotoSource}
import fr.janalyse.sotohp.store.{PhotoStoreService, LazyPhoto}

import java.nio.file.Path
import java.time.OffsetDateTime

case class PhotoToShow(
  shootDateTime: Option[OffsetDateTime],
  orientation: Option[PhotoOrientation],
  source: PhotoSource,
  place: Option[PhotoPlace] = None,
  miniatures: Option[Miniatures] = None,
  normalized: Option[NormalizedPhoto] = None,
  normalizedPath: Option[Path] = None,
  description: Option[PhotoDescription] = None,
  foundClassifications: Option[PhotoClassifications] = None,
  foundObjects: Option[PhotoObjects] = None,
  foundFaces: Option[PhotoFaces] = None
)

object PhotoToShow {
  def fromLazyPhoto(zphoto: LazyPhoto): ZIO[PhotoStoreService, Any, PhotoToShow] = for {
    source          <- zphoto.source.some
    place           <- zphoto.place
    shootDateTime   <- zphoto.metaData.map(_.flatMap(_.shootDateTime))
    orientation     <- zphoto.metaData.map(_.flatMap(_.orientation))
    miniatures      <- zphoto.miniatures
    normalized      <- zphoto.normalized
    normalizedPath  <- PhotoOperations.getNormalizedPhotoFilePath(source).when(normalized.isDefined)
    description     <- zphoto.description
    classifications <- zphoto.foundClassifications
    objects         <- zphoto.foundObjects
    faces           <- zphoto.foundFaces
    photoToView      = PhotoToShow(
                         shootDateTime = shootDateTime,
                         orientation = orientation,
                         source = source,
                         place = place,
                         miniatures = miniatures,
                         normalized = normalized,
                         normalizedPath = normalizedPath,
                         description = description,
                         foundClassifications = classifications,
                         foundObjects = objects,
                         foundFaces = faces
                       )
  } yield photoToView

}
