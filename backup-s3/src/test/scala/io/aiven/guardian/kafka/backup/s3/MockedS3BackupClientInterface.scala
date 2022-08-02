package io.aiven.guardian.kafka.backup.s3

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.alpakka.s3.S3Headers
import akka.stream.alpakka.s3.S3Settings
import akka.stream.scaladsl.Source
import io.aiven.guardian.kafka.backup.MockedBackupClientInterface
import io.aiven.guardian.kafka.backup.MockedKafkaClientInterface
import io.aiven.guardian.kafka.backup.configs.Backup
import io.aiven.guardian.kafka.backup.configs.TimeConfiguration
import io.aiven.guardian.kafka.models.ReducedConsumerRecord
import io.aiven.guardian.kafka.s3.configs.{S3 => S3Config}

import scala.concurrent.duration._
import scala.language.postfixOps

class MockedS3BackupClientInterface(
    kafkaData: Source[ReducedConsumerRecord, NotUsed],
    timeConfiguration: TimeConfiguration,
    s3Config: S3Config,
    maybeS3Settings: Option[S3Settings]
)(implicit val s3Headers: S3Headers, system: ActorSystem)
    extends BackupClient(maybeS3Settings)(
      new MockedKafkaClientInterface(kafkaData),
      Backup(MockedBackupClientInterface.KafkaGroupId, timeConfiguration, 10 seconds, None),
      implicitly,
      s3Config,
      implicitly
    )
