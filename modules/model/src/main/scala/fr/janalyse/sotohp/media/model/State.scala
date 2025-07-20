package fr.janalyse.sotohp.media.model

case class State(
  originalId: OriginalId,
  mediaAccessKey: MediaAccessKey,
  firstSeen: FirstSeen,
  lastChecked: LastChecked,
  lastSynchronized: Option[LastSynchronized]
)
