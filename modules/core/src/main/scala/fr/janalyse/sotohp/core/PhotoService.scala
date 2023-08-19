package fr.janalyse.sotohp.core

import zio.*
import zio.stream.*
import fr.janalyse.sotohp.model.*

case class PhotoServiceUserIssue(message: String)
case class PhotoServiceSystemIssue(message: String)
type PhotoServiceIssue = PhotoServiceUserIssue | PhotoServiceSystemIssue

case class PhotoSimpleQuery(
  keywords: Option[List[String]]
)

case class CategorySimpleQuery(
  keywords: Option[List[String]]
)

trait PhotoService {
  def photoGet(id: PhotoId): IO[PhotoServiceIssue, Photo]
  def photoFind(query: PhotoSimpleQuery): Stream[PhotoServiceIssue, Photo]
  def categoryFind(query: CategorySimpleQuery): Stream[PhotoServiceIssue, PhotoCategory]
}
