package fr.janalyse.sotohp.media.model

case class State(
  originalId: OriginalId,
  originalHash: Option[OriginalHash], // lazily computed as it is a costly operation
  originalAddedOn: AddedOn,
  originalLastChecked: LastChecked,
  mediaAccessKey: Option[MediaAccessKey],
  mediaLastSynchronized: Option[LastSynchronized]
)
