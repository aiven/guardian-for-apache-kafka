package io.aiven.guardian.kafka.backup.s3

import akka.actor.ActorSystem
import akka.kafka.CommitterSettings
import akka.kafka.ConsumerMessage
import akka.kafka.ConsumerSettings
import akka.kafka.scaladsl.Consumer
import akka.stream.SharedKillSwitch
import akka.stream.scaladsl.SourceWithContext
import io.aiven.guardian.kafka.backup.KafkaPublisher
import io.aiven.guardian.kafka.backup.configs.Backup
import io.aiven.guardian.kafka.configs.KafkaCluster
import io.aiven.guardian.kafka.models.ReducedConsumerRecord

class KafkaClientWithKillSwitch(
    configureConsumer: Option[
      ConsumerSettings[Array[Byte], Array[Byte]] => ConsumerSettings[Array[Byte], Array[Byte]]
    ] = None,
    configureCommitter: Option[
      CommitterSettings => CommitterSettings
    ] = None,
    killSwitch: SharedKillSwitch
)(implicit system: ActorSystem, kafkaClusterConfig: KafkaCluster, backupConfig: Backup)
    extends KafkaClient(configureConsumer, configureCommitter) {
  override def getSource
      : SourceWithContext[ReducedConsumerRecord, ConsumerMessage.CommittableOffset, Consumer.Control] =
    super.getSource.via(killSwitch.flow)
}
