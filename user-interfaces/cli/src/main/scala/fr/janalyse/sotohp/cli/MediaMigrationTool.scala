package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.dao.*
import fr.janalyse.sotohp.service.json.{given, *}
import zio.*
import zio.lmdb.*
import zio.lmdb.json.LMDBCodecJson
import zio.stream.*
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import scala.util.Try
import wvlet.airframe.ulid.ULID
import zio.lmdb.keycodecs.KeyCodec
import zio.lmdb.keycodecs.timestamp.TimestampCodec.given
import java.time.Instant

/** Migration tool to move from 'sotohp' to 'sotohp-v2' database.
  */
object MediaMigrationTool extends CommonsCLI {

  // ===================================================================================================================
  // OLD SCHEMA & CODECS (Source - String Based)
  // ===================================================================================================================

  object OldSchema {
    private val charset = StandardCharsets.UTF_8

    def stringKeyCodec[A](keyToStr: A => String, strToKey: String => A): KeyCodec[A] = new KeyCodec[A] {
      override def encode(key: A): Array[Byte]                     = keyToStr(key).getBytes(charset)
      override def decode(keyBytes: ByteBuffer): Either[String, A] =
        Try(strToKey(charset.decode(keyBytes).toString)).toEither.left.map(_.getMessage)
    }

    // DaoMedia with accessKey
    case class MigrationOldDaoMedia(
      accessKey: MediaAccessKey,
      originalId: OriginalId,
      events: Set[EventId],
      description: Option[MediaDescription],
      starred: Starred,
      keywords: Set[Keyword],
      orientation: Option[Orientation],
      shootDateTime: Option[ShootDateTime],
      userDefinedLocation: Option[DaoLocation],
      deductedLocation: Option[DaoLocation]
    ) derives LMDBCodecJson

    // DaoState with mediaAccessKey
    case class MigrationOldDaoState(
      originalId: OriginalId,
      originalHash: Option[OriginalHash],
      originalAddedOn: AddedOn,
      originalLastChecked: LastChecked,
      mediaAccessKey: MediaAccessKey,
      mediaLastSynchronized: Option[LastSynchronized]
    ) derives LMDBCodecJson

    // Codec instances
    val mediaAccessKeyCodec: KeyCodec[MediaAccessKey] = stringKeyCodec(_.asString, MediaAccessKey.apply)
    val originalIdCodec: KeyCodec[OriginalId]         = stringKeyCodec(_.asString, s => OriginalId(UUID.fromString(s)))
    val eventIdCodec: KeyCodec[EventId]               = stringKeyCodec(_.asString, s => EventId(UUID.fromString(s)))
    val storeIdCodec: KeyCodec[StoreId]               = stringKeyCodec(_.asString, s => StoreId(UUID.fromString(s)))
    val ownerIdCodec: KeyCodec[OwnerId]               = stringKeyCodec(_.asString, s => OwnerId(ULID(s)))
    val faceIdCodec: KeyCodec[FaceId]                 = stringKeyCodec(_.asString, s => FaceId(ULID(s)))
    val personIdCodec: KeyCodec[PersonId]             = stringKeyCodec(_.asString, s => PersonId(ULID(s)))
  }

  // ===================================================================================================================
  // NEW SCHEMA (Target - Binary Based)
  // ===================================================================================================================

  object NewSchema {
    // Import libraries for codecs
    import zio.lmdb.keycodecs.ulid.ULIDCodec
    import zio.lmdb.keycodecs.uuidv7.UUIDv7

    // Explicitly import givens to satisfy 'summon'
    import UUIDv7.given
    import ULIDCodec.given_KeyCodec_ULID

    // Adapters for Binary Codecs
    private def mapUUID[A](wrap: UUID => A, unwrap: A => UUID): KeyCodec[A] = new KeyCodec[A] {
      val base                  = summon[KeyCodec[UUID]]
      def encode(k: A)          = base.encode(unwrap(k))
      def decode(b: ByteBuffer) = base.decode(b).map(wrap)
    }

    private def mapULID[A](wrap: ULID => A, unwrap: A => ULID): KeyCodec[A] = new KeyCodec[A] {
      val base                  = summon[KeyCodec[ULID]]
      def encode(k: A)          = base.encode(unwrap(k))
      def decode(b: ByteBuffer) = base.decode(b).map(wrap)
    }

    given originalIdCodec: KeyCodec[OriginalId] = mapUUID(OriginalId.apply, _.asUUID)
    given eventIdCodec: KeyCodec[EventId]       = mapUUID(EventId.apply, id => UUID.fromString(id.asString))
    given storeIdCodec: KeyCodec[StoreId]       = mapUUID(StoreId.apply, id => UUID.fromString(id.asString))

    given ownerIdCodec: KeyCodec[OwnerId]   = mapULID(OwnerId.apply, _.asULID)
    given faceIdCodec: KeyCodec[FaceId]     = mapULID(FaceId.apply, _.asULID)
    given personIdCodec: KeyCodec[PersonId] = mapULID(PersonId.apply, _.asULID)

    // DaoMedia WITHOUT accessKey
    case class MigrationNewDaoMedia(
      originalId: OriginalId,
      events: Set[EventId],
      description: Option[MediaDescription],
      starred: Starred,
      keywords: Set[Keyword],
      orientation: Option[Orientation],
      shootDateTime: Option[ShootDateTime],
      userDefinedLocation: Option[DaoLocation],
      deductedLocation: Option[DaoLocation]
    ) derives LMDBCodecJson

    // DaoState WITHOUT mediaAccessKey
    case class MigrationNewDaoState(
      originalId: OriginalId,
      originalHash: Option[OriginalHash],
      originalAddedOn: AddedOn,
      originalLastChecked: LastChecked,
      // mediaAccessKey removed
      mediaLastSynchronized: Option[LastSynchronized]
    ) derives LMDBCodecJson
  }

  // ===================================================================================================================
  // INDEXES
  // ===================================================================================================================

  def fillOriginalIdByTimestamp(target: LMDB): ZIO[Any, Throwable, Unit] = {
    import NewSchema.given

    for {
      _   <- ZIO.logInfo("Building 'originalIdByTimestamp' index...")
      _   <- target.indexDrop("originalIdByTimestamp").ignore
      idx <- target
               .indexCreate[(Instant, OriginalId), OriginalId]("originalIdByTimestamp", false)
               .tap(_ => ZIO.logInfo("Index 'originalIdByTimestamp' opened/created"))
               .mapError(e => new Exception(s"Index creation error: $e"))

      medias    <- target
                     .collectionGet[OriginalId, NewSchema.MigrationNewDaoMedia]("medias")
                     .mapError(e => new Exception(s"Collection 'medias' open error: $e"))
      originals <- target
                     .collectionGet[OriginalId, DaoOriginal]("originals")
                     .mapError(e => new Exception(s"Collection 'originals' open error: $e"))
      events    <- target
                     .collectionGet[EventId, DaoEvent]("events")
                     .mapError(e => new Exception(s"Collection 'events' open error: $e"))

      _     <- ZIO.logInfo("Starting 'originalIdByTimestamp' fill stream...")
      count <- medias
                 .stream()
                 .grouped(100)
                 .mapZIO { chunk =>
                   idx
                     .readWrite { ops =>
                       ZIO.foreach(chunk) { media =>
                         for {
                           original <- originals.fetch(media.originalId).some
                           evs      <- ZIO.foreach(media.events)(id => events.fetch(id)).map(_.flatten)

                           timestamp = media.shootDateTime
                                         .orElse(original.cameraShootDateTime)
                                         .orElse(evs.find(_.attachment.isDefined).flatMap(_.timestamp))
                                         .map(_.offsetDateTime)
                                         .getOrElse(original.fileLastModified.offsetDateTime)
                                         .toInstant

                           _ <- ops.index(timestamp -> media.originalId, media.originalId)
                         } yield ()
                       }
                     }
                     .as(chunk.size)
                 }
                 .runSum
                 .mapError(e => new Exception(s"Stream error: $e"))
      _     <- ZIO.logInfo(s"Indexed $count entries in 'originalIdByTimestamp'")
    } yield ()
  }

  def fillFaceIdByPersonId(target: LMDB): ZIO[Any, Throwable, Unit] = {
    import NewSchema.given

    for {
      _   <- ZIO.logInfo("Building 'faceIdByPersonId' index...")
      _   <- target.indexDrop("faceIdByPersonId").ignore
      // Updated index definition: From PersonId (ULID) to (Instant, FaceId (ULID))
      idx <- target
               .indexCreate[PersonId, (Instant, FaceId)]("faceIdByPersonId", false)
               .tap(_ => ZIO.logInfo("Index 'faceIdByPersonId' opened/created"))
               .mapError(e => new Exception(s"Index creation error: $e"))

      faces <- target
                 .collectionGet[FaceId, DaoDetectedFace]("detectedFaces")
                 .mapError(e => new Exception(s"Collection 'detectedFaces' open error: $e"))

      _     <- ZIO.logInfo("Starting 'faceIdByPersonId' fill stream...")
      count <- faces
                 .stream()
                 .grouped(100)
                 .mapZIO { chunk =>
                   idx
                     .readWrite { ops =>
                       ZIO.foreach(chunk) { face =>
                         val timestamp = face.timestamp.toInstant
                         val p1        = face.identifiedPersonId.map(pid => ops.index(pid, (timestamp, face.faceId)))
                         val p2        = face.inferredIdentifiedPersonId.map(pid => ops.index(pid, (timestamp, face.faceId)))
                         ZIO.collectAll(p1.toList ++ p2.toList)
                       }
                     }
                     .as(chunk.size)
                 }
                 .runSum
                 .mapError(e => new Exception(s"Stream error: $e"))
      _     <- ZIO.logInfo(s"Indexed $count faces in 'faceIdByPersonId'")
    } yield ()
  }

  def fillOriginalIdByEventId(target: LMDB): ZIO[Any, Throwable, Unit] = {
    import NewSchema.given

    for {
      _   <- ZIO.logInfo("Building 'originalIdByEventId' index...")
      _   <- target.indexDrop("originalIdByEventId").ignore
      // Updated index definition: From EventId (UUID) to (Instant, OriginalId (UUID))
      idx <- target
               .indexCreate[EventId, (Instant, OriginalId)]("originalIdByEventId", false)
               .tap(_ => ZIO.logInfo("Index 'originalIdByEventId' opened/created"))
               .mapError(e => new Exception(s"Index creation error: $e"))

      medias    <- target
                     .collectionGet[OriginalId, NewSchema.MigrationNewDaoMedia]("medias")
                     .mapError(e => new Exception(s"Collection 'medias' open error: $e"))
      originals <- target
                     .collectionGet[OriginalId, DaoOriginal]("originals")
                     .mapError(e => new Exception(s"Collection 'originals' open error: $e"))
      events    <- target
                     .collectionGet[EventId, DaoEvent]("events")
                     .mapError(e => new Exception(s"Collection 'events' open error: $e"))

      _     <- ZIO.logInfo("Starting 'originalIdByEventId' fill stream...")
      count <- medias
                 .stream()
                 .grouped(100)
                 .mapZIO { chunk =>
                   idx
                     .readWrite { ops =>
                       ZIO.foreach(chunk) { media =>
                         for {
                           original <- originals.fetch(media.originalId).some
                           evs      <- ZIO.foreach(media.events)(id => events.fetch(id)).map(_.flatten)

                           timestamp = media.shootDateTime
                                         .orElse(original.cameraShootDateTime)
                                         .orElse(evs.find(_.attachment.isDefined).flatMap(_.timestamp))
                                         .map(_.offsetDateTime)
                                         .getOrElse(original.fileLastModified.offsetDateTime)
                                         .toInstant

                           _ <- ZIO.foreach(media.events) { eventId =>
                                  ops.index(eventId, (timestamp, media.originalId))
                                }
                         } yield ()
                       }
                     }
                     .as(chunk.size)
                 }
                 .runSum
                 .mapError(e => new Exception(s"Stream error: $e"))
      _     <- ZIO.logInfo(s"Processed $count medias for 'originalIdByEventId'")
    } yield ()
  }

  // ===================================================================================================================
  // MIGRATION LOGIC
  // ===================================================================================================================

  private def allocateIgnoreExists(lmdb: LMDB, name: String): ZIO[Any, Throwable, Unit] = {
    lmdb.collectionAllocate(name).catchAll {
      case e if e.toString.contains("CollectionAlreadExists")  => ZIO.unit
      case e if e.toString.contains("CollectionAlreadyExists") => ZIO.unit
      case e                                                   => ZIO.fail(new Exception(s"Target allocation error for '$name': $e"))
    }
  }

  def migrateMedias(source: LMDB, target: LMDB): ZIO[Any, Throwable, Unit] = {
    // Explicitly use OldSchema codec for reading
    implicit val oldKey: KeyCodec[MediaAccessKey] = OldSchema.mediaAccessKeyCodec

    for {
      _       <- ZIO.logInfo("Migrating 'medias' collection...")
      srcColl <- source
                   .collectionGet[MediaAccessKey, OldSchema.MigrationOldDaoMedia]("medias")
                   .tap(_ => ZIO.logInfo("Source 'medias' collection opened"))
                   .mapError(e => new Exception(s"Source collection error: $e"))
      _       <- allocateIgnoreExists(target, "medias")
                   .tap(_ => ZIO.logInfo("Target 'medias' allocated"))
      // Explicitly use NewSchema codec for writing
      tgtColl <- target
                   .collectionGet[OriginalId, NewSchema.MigrationNewDaoMedia]("medias")(using NewSchema.originalIdCodec, implicitly)
                   .tap(_ => ZIO.logInfo("Target 'medias' collection opened"))
                   .mapError(e => new Exception(s"Target collection error: $e"))

      _     <- ZIO.logInfo("Starting 'medias' migration stream...")
      count <- srcColl
                 .stream()
                 .grouped(100) // Batch processing
                 .mapZIO { chunk =>
                   tgtColl
                     .readWrite { ops =>
                       ZIO.foreach(chunk) { oldMedia =>
                         val newMedia = NewSchema.MigrationNewDaoMedia(
                           originalId = oldMedia.originalId,
                           events = oldMedia.events,
                           description = oldMedia.description,
                           starred = oldMedia.starred,
                           keywords = oldMedia.keywords,
                           orientation = oldMedia.orientation,
                           shootDateTime = oldMedia.shootDateTime,
                           userDefinedLocation = oldMedia.userDefinedLocation,
                           deductedLocation = oldMedia.deductedLocation
                         )
                         ops.upsert(newMedia.originalId, _ => newMedia).as(1L)
                       }
                     }
                     .map(_.sum)
                     .tap(c => ZIO.logInfo(s"Processed batch of $c medias"))
                 }
                 .runSum
                 .mapError(e => new Exception(s"Stream error: $e"))
      _     <- ZIO.logInfo(s"Migrated $count records in 'medias'")
    } yield ()
  }

  def migrateStates(source: LMDB, target: LMDB): ZIO[Any, Throwable, Unit] = {
    implicit val oldKey: KeyCodec[OriginalId] = OldSchema.originalIdCodec

    for {
      _       <- ZIO.logInfo("Migrating 'states' collection...")
      srcColl <- source
                   .collectionGet[OriginalId, OldSchema.MigrationOldDaoState]("states")
                   .tap(_ => ZIO.logInfo("Source 'states' collection opened"))
                   .mapError(e => new Exception(s"Source collection error: $e"))
      _       <- allocateIgnoreExists(target, "states")
      tgtColl <- target
                   .collectionGet[OriginalId, NewSchema.MigrationNewDaoState]("states")(using NewSchema.originalIdCodec, implicitly)
                   .mapError(e => new Exception(s"Target collection error: $e"))

      _     <- ZIO.logInfo("Starting 'states' migration stream...")
      count <- srcColl
                 .stream()
                 .grouped(100)
                 .mapZIO { chunk =>
                   tgtColl
                     .readWrite { ops =>
                       ZIO.foreach(chunk) { oldState =>
                         val newState = NewSchema.MigrationNewDaoState(
                           originalId = oldState.originalId,
                           originalHash = oldState.originalHash,
                           originalAddedOn = oldState.originalAddedOn,
                           originalLastChecked = oldState.originalLastChecked,
                           mediaLastSynchronized = oldState.mediaLastSynchronized
                         )
                         ops.upsert(newState.originalId, _ => newState).as(1L)
                       }
                     }
                     .map(_.sum)
                 }
                 .runSum
                 .mapError(e => new Exception(s"Stream error: $e"))
      _     <- ZIO.logInfo(s"Migrated $count records in 'states'")
    } yield ()
  }

  def migrateIdentity[K, V](
    source: LMDB,
    target: LMDB,
    collectionName: String,
    oldKeyCodec: KeyCodec[K],
    extractKey: V => K
  )(using
    newKeyCodec: KeyCodec[K],
    valCodec: LMDBCodec[V],
    tagK: Tag[K],
    tagV: Tag[V]
  ): ZIO[Any, Throwable, Unit] = {
    // We need to explicitly use the old codec for reading
    val srcCollZIO = source.collectionGet(collectionName)(using oldKeyCodec, valCodec)

    for {
      _       <- ZIO.logInfo(s"Migrating '$collectionName' collection...")
      srcColl <- srcCollZIO.mapError(e => new Exception(s"Source collection error: $e"))
      _       <- allocateIgnoreExists(target, collectionName)
      tgtColl <- target
                   .collectionGet[K, V](collectionName)(using newKeyCodec, valCodec)
                   .mapError(e => new Exception(s"Target collection error: $e"))

      count <- srcColl
                 .stream()
                 .grouped(100)
                 .mapZIO { chunk =>
                   tgtColl
                     .readWrite { ops =>
                       ZIO.foreach(chunk) { value =>
                         val key = extractKey(value)
                         ops.upsert(key, _ => value).as(1L)
                       }
                     }
                     .map(_.sum)
                 }
                 .runSum
                 .mapError(e => new Exception(s"Stream error: $e"))
      _     <- ZIO.logInfo(s"Migrated $count records in '$collectionName'")
    } yield ()
  }

  def migrateKeywordRules(source: LMDB, target: LMDB): ZIO[Any, Throwable, Unit] = {
    // keywordRules key is StoreId, but value (DaoKeywordRules) does NOT contain StoreId.
    // Strategy: Iterate 'stores' collection to get StoreIds, then fetch corresponding rules.
    implicit val oldStoreKey: KeyCodec[StoreId] = OldSchema.storeIdCodec

    for {
      _         <- ZIO.logInfo("Migrating 'keywordRules' collection...")
      srcStores <- source
                     .collectionGet[StoreId, DaoStore]("stores")
                     .mapError(e => new Exception(s"Source stores error: $e"))
      srcRules  <- source
                     .collectionGet[StoreId, DaoKeywordRules]("keywordRules")
                     .mapError(e => new Exception(s"Source rules error: $e"))

      _        <- allocateIgnoreExists(target, "keywordRules")
      tgtRules <- target
                    .collectionGet[StoreId, DaoKeywordRules]("keywordRules")(using NewSchema.storeIdCodec, implicitly)
                    .mapError(e => new Exception(s"Target rules error: $e"))

      count <- srcStores
                 .stream()
                 .mapZIO { store =>
                   val storeId = store.id
                   srcRules.fetch(storeId).flatMap {
                     case Some(rules) => tgtRules.upsert(storeId, _ => rules).as(1L)
                     case None        => ZIO.succeed(0L)
                   }
                 }
                 .runSum
                 .mapError(e => new Exception(s"Stream error: $e"))
      _     <- ZIO.logInfo(s"Migrated $count records in 'keywordRules'")
    } yield ()
  }

  def makeLMDBLayer(name: String, pathStr: String): ZLayer[Scope, Throwable, LMDB] = {
    val path   = Paths.get(pathStr)
    val dbHome = path.getParent.toString

    val props    = Map(
      "lmdb.name"     -> name,
      "lmdb.home"     -> dbHome,
      "lmdb.sync"     -> "false",
      // Set larger map size to avoid "MapFull" errors during migration (10GB)
      "lmdb.map-size" -> (10L * 1024 * 1024 * 1024).toString
    )
    val provider = ConfigProvider.fromMap(props)

    // Provide the config provider to the runtime, then initialize LMDB.live
    // We map errors to Throwable to satisfy strict typing of this method
    ((Runtime.setConfigProvider(provider) ++ ZLayer.service[Scope]) >>> LMDB.live).mapError {
      case t: Throwable => t
      case other        => new Exception(s"LMDB initialization failed: $other")
    }
  }

  def verifyMigration(source: LMDB, target: LMDB): ZIO[Any, Throwable, Unit] = {
    // Explicitly import specific codecs to avoid ambiguity, or pass them explicitly

    val oldMediaKey: KeyCodec[MediaAccessKey] = OldSchema.mediaAccessKeyCodec
    val oldOriginalKey: KeyCodec[OriginalId]  = OldSchema.originalIdCodec

    val newOriginalKey: KeyCodec[OriginalId] = NewSchema.originalIdCodec

    for {
      _ <- ZIO.logInfo("Verifying migration...")

      // Verify Medias
      srcMediasCount <- source
                          .collectionGet[MediaAccessKey, OldSchema.MigrationOldDaoMedia]("medias")(using oldMediaKey, implicitly)
                          .mapError(e => new Exception(s"Source medias error: $e"))
                          .flatMap(_.size().mapError(e => new Exception(s"Source medias size error: $e")))
      tgtMediasCount <- target
                          .collectionGet[OriginalId, NewSchema.MigrationNewDaoMedia]("medias")(using newOriginalKey, implicitly)
                          .mapError(e => new Exception(s"Target medias error: $e"))
                          .flatMap(_.size().mapError(e => new Exception(s"Target medias size error: $e")))
      _              <- if (srcMediasCount == tgtMediasCount) ZIO.logInfo(s"Medias verification SUCCESS: $srcMediasCount records.")
                        else ZIO.fail(new Exception(s"Medias verification FAILED: Source=$srcMediasCount, Target=$tgtMediasCount"))

      // Verify States
      srcStatesCount <- source
                          .collectionGet[OriginalId, OldSchema.MigrationOldDaoState]("states")(using oldOriginalKey, implicitly)
                          .mapError(e => new Exception(s"Source states error: $e"))
                          .flatMap(_.size().mapError(e => new Exception(s"Source states size error: $e")))
      tgtStatesCount <- target
                          .collectionGet[OriginalId, NewSchema.MigrationNewDaoState]("states")(using newOriginalKey, implicitly)
                          .mapError(e => new Exception(s"Target states error: $e"))
                          .flatMap(_.size().mapError(e => new Exception(s"Target states size error: $e")))
      _              <- if (srcStatesCount == tgtStatesCount) ZIO.logInfo(s"States verification SUCCESS: $srcStatesCount records.")
                        else ZIO.fail(new Exception(s"States verification FAILED: Source=$srcStatesCount, Target=$tgtStatesCount"))

      // Verify Originals
      srcOriginalsCount <- source
                             .collectionGet[OriginalId, DaoOriginal]("originals")(using oldOriginalKey, implicitly)
                             .mapError(e => new Exception(s"Source originals error: $e"))
                             .flatMap(_.size().mapError(e => new Exception(s"Source originals size error: $e")))
      tgtOriginalsCount <- target
                             .collectionGet[OriginalId, DaoOriginal]("originals")(using newOriginalKey, implicitly)
                             .mapError(e => new Exception(s"Target originals error: $e"))
                             .flatMap(_.size().mapError(e => new Exception(s"Target originals size error: $e")))
      _                 <- if (srcOriginalsCount == tgtOriginalsCount) ZIO.logInfo(s"Originals verification SUCCESS: $srcOriginalsCount records.")
                           else ZIO.fail(new Exception(s"Originals verification FAILED: Source=$srcOriginalsCount, Target=$tgtOriginalsCount"))

    } yield ()
  }

  override def run = {
    val home       = java.lang.System.getProperty("user.home")
    val sourcePath = Paths.get(home, ".lmdb", "sotohp").toString
    val targetPath = Paths.get(home, ".lmdb", "sotohp-v2").toString

    // Import NewSchema codecs for implicit resolution in the main scope
    import NewSchema.given

    ZIO.logInfo(s"Starting migration from $sourcePath to $targetPath") *>
      ZIO.scoped {
        for {
          source <- makeLMDBLayer("sotohp", sourcePath).build.map(_.get[LMDB])
          target <- makeLMDBLayer("sotohp-v2", targetPath).build.map(_.get[LMDB])

          _ <- migrateMedias(source, target)
          _ <- migrateStates(source, target)

          _ <- migrateIdentity[OriginalId, DaoOriginal](source, target, "originals", OldSchema.originalIdCodec, _.id)
          _ <- migrateIdentity[EventId, DaoEvent](source, target, "events", OldSchema.eventIdCodec, _.id)
          _ <- migrateIdentity[OwnerId, DaoOwner](source, target, "owners", OldSchema.ownerIdCodec, _.id)
          _ <- migrateIdentity[StoreId, DaoStore](source, target, "stores", OldSchema.storeIdCodec, _.id)

          _ <- migrateKeywordRules(source, target)

          _ <- migrateIdentity[OriginalId, DaoOriginalClassifications](source, target, "classifications", OldSchema.originalIdCodec, _.originalId)
          _ <- migrateIdentity[FaceId, DaoDetectedFace](source, target, "detectedFaces", OldSchema.faceIdCodec, _.faceId)
          _ <- migrateIdentity[OriginalId, DaoOriginalFaces](source, target, "faces", OldSchema.originalIdCodec, _.originalId)
          _ <- migrateIdentity[FaceId, DaoFaceFeatures](source, target, "detectedFaceFeatures", OldSchema.faceIdCodec, _.faceId)
          _ <- migrateIdentity[OriginalId, DaoOriginalFaceFeatures](source, target, "faceFeatures", OldSchema.originalIdCodec, _.originalId)
          _ <- migrateIdentity[OriginalId, DaoOriginalDetectedObjects](source, target, "objects", OldSchema.originalIdCodec, _.originalId)
          _ <- migrateIdentity[OriginalId, DaoOriginalMiniatures](source, target, "miniatures", OldSchema.originalIdCodec, _.originalId)
          _ <- migrateIdentity[OriginalId, DaoOriginalNormalized](source, target, "normalized", OldSchema.originalIdCodec, _.originalId)
          _ <- migrateIdentity[PersonId, DaoPerson](source, target, "persons", OldSchema.personIdCodec, _.id)

          // Index filling (Post-migration)
          _ <- fillOriginalIdByTimestamp(target)
          _ <- fillFaceIdByPersonId(target)
          _ <- fillOriginalIdByEventId(target)

          // Verification
          _ <- verifyMigration(source, target)

          _ <- ZIO.logInfo("Migration completed successfully!")
          _ <- ZIO.logWarning(s"IMPORTANT: New database created at $targetPath.")
          _ <- ZIO.logWarning("Next steps:")
          _ <- ZIO.logWarning("1. Update 'DaoMedia' and 'DaoState' in application code to match the new schema.")
          _ <- ZIO.logWarning("2. Update application config to point to 'sotohp-v2' (or rename the directory).")
          _ <- ZIO.logWarning("3. Ensure binary key codecs are used in the application.")
        } yield ()
      }
  }
}
