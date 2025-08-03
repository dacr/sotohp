package fr.janalyse.sotohp.gui

import zio.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.model.*
import fr.janalyse.sotohp.service.MediaService

import java.nio.file.Path
import java.time.OffsetDateTime

case class PhotoToShow(
  shootDateTime: Option[OffsetDateTime],
  orientation: Option[Orientation],
  media: Media,
  place: Option[Location] = None,
  normalized: Option[Normalized] = None,
  normalizedPath: Option[Path] = None,
  description: Option[MediaDescription] = None,
  event: Option[Event] = None,
  foundClassifications: Option[List[DetectedClassification]] = None,
  foundObjects: Option[List[DetectedObject]] = None,
  foundFaces: Option[List[DetectedFace]] = None
)

object PhotoToShow {
  def fromLazyPhoto(media: Media): ZIO[MediaService, Any, PhotoToShow] = for {
    normalized      <- MediaService.normalized(media.original.id).map(_.normalized)
    classifications <- MediaService.classifications(media.original.id).map(_.classifications).option
    objects         <- MediaService.objects(media.original.id).map(_.objects).option
    faces           <- MediaService.faces(media.original.id).map(_.faces).option
    place            = media.location.orElse(media.original.location)
    shootDateTime    = media.shootDateTime.orElse(media.original.cameraShootDateTime)
    orientation      = media.orientation.orElse(media.original.orientation)
    description      = media.description
    event            = media.events.find(_.attachment.isDefined)
    normalizedPath   = normalized.map(_.path)
    photoToView      = PhotoToShow(
                         shootDateTime = shootDateTime.map(_.offsetDateTime),
                         orientation = orientation,
                         media = media,
                         place = place,
                         normalized = normalized,
                         normalizedPath = normalizedPath,
                         description = description,
                         event = event,
                         foundClassifications = classifications,
                         foundObjects = objects,
                         foundFaces = faces
                       )
  } yield photoToView

}
