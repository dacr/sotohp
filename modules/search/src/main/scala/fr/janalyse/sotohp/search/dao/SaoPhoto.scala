package fr.janalyse.sotohp.search.dao

import zio.json.JsonCodec
import fr.janalyse.sotohp.model.Photo

import java.time.OffsetDateTime
import scala.util.matching.Regex

// SearchAccessObject GeoPoint
case class SaoGeoPoint(
  lat: Double,
  lon: Double
) derives JsonCodec

// SearchAccessObject Photo
case class SaoPhoto(
  id: String,
  timestamp: OffsetDateTime,
  filePath: String,
  fileSize: Long,
  fileHash: String,
  fileLastUpdated: OffsetDateTime,
  category: Option[String],
  shootDateTime: Option[OffsetDateTime],
  camera: Option[String],
  // tags: Map[String, String],
  keywords: List[String],
  classifications: List[String],
  detectedObjects: List[String],
  detectedObjectsCount: Int,
  detectedFacesCount: Int,
  place: Option[SaoGeoPoint]
) derives JsonCodec

object SaoPhoto {
  // ------------------------------------------
  // TODO temporary keyword extraction from category
  def camelTokenize(that: String): Array[String] = that.split("(?=[A-Z][^A-Z])|(?:(?<=[^A-Z])(?=[A-Z]+))")

  def camelToKebabCase(that: String): String = camelTokenize(that).map(_.toLowerCase).mkString("-")

  val excludes = Set(
    "100nikon",
    "a",
    "all",
    "and",
    "apple",
    "archive",
    "au",
    "aux",
    "avec",
    "backup",
    "chez",
    "co",
    "d",
    "dans",
    "de",
    "des",
    "du",
    "en",
    "end",
    "et",
    "fin",
    "htc",
    "iphone",
    "la",
    "le",
    "les",
    "nexus",
    "notre",
    "of",
    "ou",
    "par",
    "photo",
    "photos",
    "photos_",
    "pour",
    "puis",
    "semaine1",
    "semaine2",
    "shootings",
    "sur",
    "week",
    "à"
  )

  val fixes = List(
    "(?i)(\\d+) ans"                  -> "$1ans",
    "(?i)Tourisme Le Mans"            -> "tourisme lemans",
    "(?i)weenprovence"                -> "weekend en provence",
    "(?i)montstmichelparbrieuc"       -> "mont saintmichel par brieuc",
    "(?i)westmaloetperenoelplouasne"  -> "weekend saintmalo et père noël plouasne",
    "(?i)stmalo"                      -> "saintmalo",
    "(?i)saint malo"                  -> "saintmalo",
    "(?i)montstmichel"                -> "mont saintmichel",
    "(?i)weenprovence"                -> "weekend en provence",
    "(?i)DiversEtWEMarne"             -> "divers et weekend marne",
    "(?i)wemarne"                     -> "weekend marne",
    "(?i)f[eè]reChampenois$"          -> "fèrechampenoise",
    "(?i)week-end"                    -> "weekend",
    "(?i)gr34"                        -> "gr34",
    "(?i)le mans musée 24 heures"     -> "lemans musée 24heures",
    "(?i)NOELs"                       -> "noël",
    "(?i)WE-Fere$"                    -> "weekend fèrechampenoise",
    "(?i)WEFere$"                     -> "weekend fèrechampenoise",
    "(?i)WE-FereChampenoise$"         -> "weekend fèrechampenoise",
    "(?i)VacancesLauLauManuPlouasne$" -> "Vacances laurence manu plouasne",
    "(?i)saint Jean de Belleville"    -> "saintjeandebelleville",
    "(?i)Saint Martin de Belleville"  -> "saintmartindebelleville",
    "(?i)Saint Cloud"                 -> "saintcloud",
    "(?i)Saint Brieuc"                -> "saintbrieuc",
    "(?i)Saint Jean"                  -> "saintjean",
    "(?i)saint-cast-le-guildo"        -> "saintcastleguildo",
    "(?i)saint briac sur mer"         -> "saintbriacsurmer",
    "(?i)Saint-Michel"                -> "saintmichel",
    "(?i)saint Mathieu"               -> "saintmathieu",
    "(?i)saint germain"               -> "saintgermain",
    "(?i)saint lunaire"               -> "saintlunaire",
    "(?i)Saint Pern"                  -> "saintpern",
    "(?i)sainte suzanne"              -> "saintesuzanne",
    "(?i)WeekEndPlageSaintMalo"       -> "weekend plage saintmalo",
    "(?i)Noel2006$"                   -> "noël",
    "(?i)Sezanne ParChrist"           -> "sézanne par christiane",
    "(?i)HTC DESIRE C"                -> "",
    "(?i)Anniversaire 50 balais$"     -> "anniversaire 50ans david",
    "(?i)WE-EloiseJeJe *"             -> "weekend éloise",
    "(?i)intranode-birthdayII"        -> "intranode birthday",
    "(?i)experiences 5d m4"           -> "experiences 5dmark4",
    "(?i)WE10ansCooperants"           -> "weekend 10ans coopérants",
    "(?i)Jerem30ans-et-Paques"        -> "jérémy 30ans pâques",
    "(?i)DiverEtWEParents"            -> "divers weekend parents",
    "(?i)VIDEO-DIVERS1"               -> "vidéo divers",
    "(?i)WE AIN Cooperant 17ans"      -> "weekend ain coopérants 17ans",
    "(?i)AnniversaireBrieuc1an"       -> "anniversaire brieuc 1an",
    "(?i)2023-08-11harry"             -> "2023-08-11 harry"
  ).map { case (pattern, replacement) => pattern.r -> replacement }

  val remap = Map(
    "adelaide"      -> "adélaïde",
    "adelaïde"      -> "adélaïde",
    "agnes"         -> "agnès",
    "annee"         -> "année",
    "annees"        -> "année",
    "anniv"         -> "anniversaire",
    "anniversaires" -> "anniversaire",
    "annivs"        -> "anniversaire",
    "balades"       -> "balade",
    "bapteme"       -> "baptême",
    "baskets"       -> "basket",
    "bebe"          -> "bébé",
    "betineuc"      -> "bétineuc",
    "broceliande"   -> "brocéliande",
    "chats"         -> "chat",
    "chiens"        -> "chien",
    "christianne"   -> "christiane",
    "eloise"        -> "éloise",
    "ete"           -> "été",
    "fete"          -> "fête",
    "fete"          -> "fête",
    "fetes"         -> "fête",
    "fred"          -> "frédéric",
    "ile"           -> "île",
    "jerem"         -> "jérémy",
    "juju"          -> "julien",
    "kermess"       -> "kermesse",
    "lolo"          -> "éloise",
    "maelys"        -> "maëlys",
    "maude"         -> "maud",
    "menuires"      -> "ménuires",
    "musee"         -> "musée",
    "naissance1"    -> "naissance",
    "naissance2"    -> "naissance",
    "neal"          -> "néal",
    "newscraft"     -> "newcrafts",
    "noel"          -> "noël",
    "noels"         -> "noël",
    "operation"     -> "opération",
    "paques"        -> "pâques",
    "pere"          -> "père",
    "rando"         -> "randonnée",
    "randonnées"    -> "randonnée",
    "reveillon"     -> "réveillon",
    "seb"           -> "sébastien",
    "sebast"        -> "sébastien",
    "sebastien"     -> "sébastien",
    "sezanne"       -> "sézanne",
    "skis"          -> "ski",
    "soiree"        -> "soirée",
    "steph"         -> "stéphanie",
    "vaches"        -> "vache",
    "velo"          -> "vélo",
    "we"            -> "weekend"
  )

  def applyFixes(fixes: List[(Regex, String)], input: String): String = {
    fixes match {
      case Nil                            => input
      case (regex, replacement) :: remain => applyFixes(remain, regex.replaceAllIn(input, replacement))
    }
  }

  def extractKeywords(input: Option[String]): List[String] =
    input match {
      case None           => Nil
      case Some(category) =>
        applyFixes(fixes, category)
          .split("[- /,]+")
          .toList
          .filter(_.size > 0)
          .filterNot(_.contains("'"))
          .flatMap(key => camelToKebabCase(key).split("-"))
          .map(token => remap.get(token.toLowerCase).getOrElse(token))
          .flatMap(_.split("[- ]+"))
          .filter(_.trim.size > 0)
          .filterNot(_.matches("^[0-9]+$"))
          .map(_.toLowerCase)
          .filter(_.size > 1)
          .filterNot(key => excludes.contains(key))
    }
  // ------------------------------------------

  def fromPhoto(photo: Photo): SaoPhoto = {
    val category = photo.description.flatMap(_.category).map(_.text)
    SaoPhoto(
      id = photo.source.photoId.id.toString,
      timestamp = photo.timestamp,
      filePath = photo.source.original.path.toString,
      fileSize = photo.source.fileSize,
      fileHash = photo.source.fileHash.code,
      fileLastUpdated = photo.source.fileLastModified,
      category = category,
      shootDateTime = photo.metaData.flatMap(_.shootDateTime),
      camera = photo.metaData.flatMap(_.cameraName),
      // tags = photo.metaData.map(_.tags).getOrElse(Map.empty),
      // keywords = photo.description.map(_.keywords.toList).getOrElse(Nil),
      keywords = extractKeywords(category), // TODO temporary keyword extraction from category
      classifications = photo.foundClassifications.map(_.classifications.map(_.name).distinct).getOrElse(Nil),
      detectedObjects = photo.foundObjects.map(_.objects.map(_.name).distinct).getOrElse(Nil),
      detectedObjectsCount = photo.foundObjects.map(_.objects.size).getOrElse(0),
      detectedFacesCount = photo.foundFaces.map(_.faces.size).getOrElse(0),
      place = photo.place.map(place => SaoGeoPoint(lat = place.latitude.doubleValue, lon = place.longitude.doubleValue))
    )
  }
}
