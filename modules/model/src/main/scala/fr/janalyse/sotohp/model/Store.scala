package fr.janalyse.sotohp.model

case class Store(
  id: StoreId,
  ownerId: OwnerId,
  // TODO add language ?
  baseDirectory: BaseDirectoryPath,
  includeMask: Option[IncludeMask] = None,
  ignoreMask: Option[IgnoreMask] = None
)
