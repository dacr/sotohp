package fr.janalyse.sotohp.media.model

case class State(
  originalId: OriginalId,
  originalHash: OriginalHash,
  originalAddedOn: AddedOn,
  originalLastChecked: LastChecked,
  mediaAccessKey: Option[MediaAccessKey],
  mediaLastSynchronized: Option[LastSynchronized]
)
