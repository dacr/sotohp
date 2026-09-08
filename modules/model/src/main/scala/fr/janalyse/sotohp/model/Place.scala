package fr.janalyse.sotohp.model

/** Human-readable location of a photo, deduced from its GPS point (see `Media.deductedPlace`) or
  * entered/corrected by the user (`Media.userDefinedPlace`).
  *
  * The default deduction is fully offline (nearest populated place in a GeoNames dump), so it fills
  * `town` / `region` / `country` / `countryCode` but never `street` - that field is only ever set
  * on a user-defined place.
  *
  * Plain `String` fields on purpose: these are free-text geographic labels coming from a
  * third-party dataset, not identifiers or units, so an opaque newtype per field would only add
  * codec/schema boilerplate for no compiler benefit.
  */
case class Place(
  street: Option[String],
  town: Option[String],
  region: Option[String],      // GeoNames first-level administrative division (state / région / ...)
  country: Option[String],
  countryCode: Option[String]  // ISO 3166-1 alpha-2
)

object Place {
  val empty: Place = Place(None, None, None, None, None)

  extension (place: Place) {
    /** True when every field is empty - nothing worth storing. */
    def isEmpty: Boolean =
      place.street.isEmpty && place.town.isEmpty && place.region.isEmpty && place.country.isEmpty && place.countryCode.isEmpty
  }
}
