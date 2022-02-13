package io.aiven.guardian.kafka.backup.configs

import scala.concurrent.duration.FiniteDuration

/** @param kafkaGroupId
  *   The group-id that the Kafka consumer will use
  * @param timeConfiguration
  *   Determines how the backed up objects/files are segregated depending on a time configuration
  * @param commitTimeoutBufferWindow
  *   A buffer that is added ontop of the `timeConfiguration` when setting the Kafka Consumer commit timeout.
  * @param compression
  *   Which compression to use for the backed up data
  */
final case class Backup(kafkaGroupId: String,
                        timeConfiguration: TimeConfiguration,
                        commitTimeoutBufferWindow: FiniteDuration,
                        compression: Option[Compression]
)
