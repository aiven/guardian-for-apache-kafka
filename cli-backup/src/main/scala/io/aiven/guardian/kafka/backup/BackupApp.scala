package io.aiven.guardian.kafka.backup

import io.aiven.guardian.cli.AkkaSettings
import io.aiven.guardian.kafka.backup.KafkaPublisher
import io.aiven.guardian.kafka.backup.{Config => BackupConfig}
import io.aiven.guardian.kafka.{Config => KafkaConfig}

trait BackupApp extends BackupConfig with KafkaConfig with AkkaSettings {
  implicit lazy val kafkaClient: KafkaClient = new KafkaClient()
}
