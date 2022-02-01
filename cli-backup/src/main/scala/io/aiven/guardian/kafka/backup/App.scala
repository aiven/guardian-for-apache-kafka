package io.aiven.guardian.kafka.backup

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer
import io.aiven.guardian.kafka.backup.BackupClientInterface
import io.aiven.guardian.kafka.backup.KafkaPublisher
import io.aiven.guardian.kafka.backup.KafkaPublisherInterface

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait App[T <: KafkaClientInterface] {
  implicit val kafkaClient: T
  implicit val backupClient: BackupClientInterface[KafkaClient]
  implicit val actorSystem: ActorSystem
  implicit val executionContext: ExecutionContext

  def run(): Consumer.Control = backupClient.backup.run()
  def shutdown(control: Consumer.Control): Future[Done] =
    // Ideally we should be using drainAndShutdown however this isn't possible due to
    // https://github.com/aiven/guardian-for-apache-kafka/issues/80
    control.stop().flatMap(_ => control.shutdown())
}
