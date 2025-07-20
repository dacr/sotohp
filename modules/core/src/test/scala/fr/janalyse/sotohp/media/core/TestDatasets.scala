package fr.janalyse.sotohp.media.core

import fr.janalyse.sotohp.media.model.*
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

  val dataset1: BaseDirectoryPath    = BaseDirectoryPath(Path.of("samples/dataset1"))
  val dataset1Example1: OriginalPath = OriginalPath(Path.of("samples/dataset1/example1.jpg"))
  val dataset1Example2: OriginalPath = OriginalPath(Path.of("samples/dataset1/example2.jpg"))
  val dataset1Example3: OriginalPath = OriginalPath(Path.of("samples/dataset1/example3.gif"))
  val dataset1Example4: OriginalPath = OriginalPath(Path.of("samples/dataset1/example4.tif"))
  val dataset1Example5: OriginalPath = OriginalPath(Path.of("samples/dataset1/example5.png"))
  val fakeStoreId1: StoreId = StoreId(UUID.fromString("cfc0f571-48d7-4c43-9ee3-1c2cd923386d"))
  val fakeStore1 : Store = Store(fakeStoreId1, fakeOwner.id, dataset1)

  val dataset2: BaseDirectoryPath      = BaseDirectoryPath(Path.of("samples/dataset2"))
  val dataset2tag1: OriginalPath       = OriginalPath(Path.of("samples/dataset2/tags/tag1.jpg"))
  val dataset2landscape1: OriginalPath = OriginalPath(Path.of("samples/dataset2/landscapes/landscape1.jpg"))
  val fakeStoreId2: StoreId = StoreId(UUID.fromString("514f2381-92a8-40d2-add1-1c22769001a2"))
  val fakeStore2 : Store = Store(fakeStoreId2, fakeOwner.id, dataset2)
}
