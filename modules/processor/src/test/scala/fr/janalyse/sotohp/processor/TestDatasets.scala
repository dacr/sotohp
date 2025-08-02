package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.model.*
import wvlet.airframe.ulid.ULID

import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.UUID

trait TestDatasets {
  val fakeOwner: Owner = Owner(
    id = OwnerId(ULID("01H84VVZRXZDZ5184V44KVWS3J")),
    firstName = FirstName("John"),
    lastName = LastName("Doe"),
    birthDate = Some(BirthDate(OffsetDateTime.parse("1970-01-01T00:00:00Z")))
  )

  // -------------------------------------------------------------------------------------------------------------------
  val datasetObjectsPath: BaseDirectoryPath = BaseDirectoryPath(Path.of("samples/dataset-processors-object"))
  val datasetObjectsFakeStoreId: StoreId    = StoreId(UUID.fromString("cfc0f571-48d7-4c43-9ee3-1c2cd923386d"))
  val datasetObjectsFakeStore: Store        = Store(datasetObjectsFakeStoreId, fakeOwner.id, datasetObjectsPath)

  val datasetObjectsFileMixtureOfObjects: OriginalPath = OriginalPath(Path.of("samples/dataset-processors-objects/mixture-of-objects.jpg"))
  val datasetObjectsFilePersonBicycle: OriginalPath    = OriginalPath(Path.of("samples/dataset-processors-objects/person-bicycle.jpg"))

  // -------------------------------------------------------------------------------------------------------------------
  val datasetClassesPath: BaseDirectoryPath = BaseDirectoryPath(Path.of("samples/dataset-processors-object"))
  val datasetClassesFakeStoreId: StoreId    = StoreId(UUID.fromString("cfc0f571-48d7-4c43-9ee3-1c2cd923386d"))
  val datasetClassesFakeStore: Store        = Store(datasetClassesFakeStoreId, fakeOwner.id, datasetClassesPath)

  val datasetClassesFileLakeForest: OriginalPath = OriginalPath(Path.of("samples/dataset-processors-classifications/lake-forest.jpg"))
  val datasetClassesFileMountain: OriginalPath   = OriginalPath(Path.of("samples/dataset-processors-classifications/mountain.jpg"))
  val datasetClassesFileSeacoast: OriginalPath   = OriginalPath(Path.of("samples/dataset-processors-classifications/seacoast.jpg"))
  val datasetClassesFileSkiWinter: OriginalPath  = OriginalPath(Path.of("samples/dataset-processors-classifications/ski-winter.jpg"))
  val datasetClassesFileCarRace: OriginalPath    = OriginalPath(Path.of("samples/dataset-processors-classifications/car-race.jpg"))

  // -------------------------------------------------------------------------------------------------------------------
}
