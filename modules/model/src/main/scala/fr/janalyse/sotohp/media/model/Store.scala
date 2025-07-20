package fr.janalyse.sotohp.media.model

case class Store(
  id: StoreId,
  ownerId: OwnerId,
  baseDirectory: BaseDirectoryPath,
  includeMask: Option[IncludeMask],
  ignoreMask: Option[IgnoreMask]
)
