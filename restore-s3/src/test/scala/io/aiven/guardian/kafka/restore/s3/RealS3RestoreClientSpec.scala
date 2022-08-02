package io.aiven.guardian.kafka.restore.s3

import akka.actor.ActorSystem
import akka.kafka.ConsumerSettings
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.Producer
import akka.stream.alpakka.s3.S3Settings
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.Sink
import com.softwaremill.diffx.scalatest.DiffMustMatcher._
import io.aiven.guardian.akka.AnyPropTestKit
import io.aiven.guardian.kafka.Generators._
import io.aiven.guardian.kafka.KafkaClusterTest
import io.aiven.guardian.kafka.TestUtils._
import io.aiven.guardian.kafka.backup.KafkaClient
import io.aiven.guardian.kafka.backup.configs.Backup
import io.aiven.guardian.kafka.backup.configs.PeriodFromFirst
import io.aiven.guardian.kafka.backup.s3.BackupClient
import io.aiven.guardian.kafka.configs.{KafkaCluster => KafkaClusterConfig}
import io.aiven.guardian.kafka.restore.KafkaProducer
import io.aiven.guardian.kafka.restore.configs.{Restore => RestoreConfig}
import io.aiven.guardian.kafka.s3.Generators.s3ConfigGen
import io.aiven.guardian.kafka.s3.S3Spec
import io.aiven.guardian.kafka.s3.configs.{S3 => S3Config}
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._
import scala.language.postfixOps

class RealS3RestoreClientSpec
    extends AnyPropTestKit(ActorSystem("RealS3RestoreClientSpec"))
    with S3Spec
    with Matchers
    with KafkaClusterTest
    with ScalaCheckPropertyChecks {

  override lazy val s3Settings: S3Settings = S3Settings()

  def waitForSingleDownload(dataBucket: String): Future[Unit] = waitForS3Download(
    dataBucket,
    {
      case Seq(_) => ()
      case rest =>
        throw DownloadNotReady(rest)
    }
  )

  /** Virtual Dot Host in bucket names are disabled because you need an actual DNS certificate otherwise AWS will fail
    * on bucket creation
    */
  override lazy val useVirtualDotHost: Boolean            = false
  override lazy val bucketPrefix: Option[String]          = Some("guardian-")
  override lazy val enableCleanup: Option[FiniteDuration] = Some(5 seconds)

  property("Round-trip with backup and restore", RealS3Available) {
    forAll(
      kafkaDataWithMinSizeRenamedTopicsGen(S3.MinChunkSize, 2, reducedConsumerRecordsToJson),
      s3ConfigGen(useVirtualDotHost, bucketPrefix),
      kafkaConsumerGroupGen
    ) {
      (kafkaDataInChunksWithTimePeriodRenamedTopics: KafkaDataInChunksWithTimePeriodRenamedTopics,
       s3Config: S3Config,
       kafkaConsumerGroup: String
      ) =>
        implicit val restoreConfig: RestoreConfig =
          RestoreConfig(None, Some(kafkaDataInChunksWithTimePeriodRenamedTopics.renamedTopics))
        implicit val kafkaProducer: KafkaProducer = new KafkaProducer(configureProducer = baseProducerConfig)
        implicit val kafkaClusterConfig: KafkaClusterConfig =
          KafkaClusterConfig(kafkaDataInChunksWithTimePeriodRenamedTopics.topics)

        val data = kafkaDataInChunksWithTimePeriodRenamedTopics.data.flatten

        val asProducerRecords = toProducerRecords(data)
        val baseSource        = toSource(asProducerRecords, 30 seconds)

        implicit val config: S3Config = s3Config
        implicit val backupConfig: Backup =
          Backup(kafkaConsumerGroup, PeriodFromFirst(1 minute), 10 seconds, None)

        val backupClient =
          new BackupClient(Some(s3Settings))(new KafkaClient(configureConsumer = baseKafkaConfig),
                                             implicitly,
                                             implicitly,
                                             implicitly,
                                             implicitly
          )

        val restoreClient =
          new RestoreClient[KafkaProducer](Some(s3Settings), None)

        val adminClient = AdminClient.create(
          Map[String, AnyRef](
            CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG -> container.bootstrapServers
          ).asJava
        )

        val createTopics = adminClient.createTopics(kafkaDataInChunksWithTimePeriodRenamedTopics.topics.map { topic =>
          new NewTopic(topic, 1, 1.toShort)
        }.asJava)

        val producerSettings = createProducer()

        val calculatedFuture = for {
          _ <- createTopics.all().toCompletableFuture.asScala
          _ <- createBucket(s3Config.dataBucket)
          _ = backupClient.backup.run()
          _ <- akka.pattern.after(KafkaInitializationTimeoutConstant)(
                 baseSource
                   .runWith(Producer.plainSink(producerSettings))
               )
          _ <- sendTopicAfterTimePeriod(1 minute,
                                        producerSettings,
                                        kafkaDataInChunksWithTimePeriodRenamedTopics.topics.head
               )
          _ <- waitForSingleDownload(s3Config.dataBucket)
          restoreResultConsumerTopicSettings =
            ConsumerSettings(system, new ByteArrayDeserializer, new ByteArrayDeserializer)
              .withBootstrapServers(
                container.bootstrapServers
              )
              .withGroupId(kafkaConsumerGroup)
          restoreResultConsumerSource =
            Consumer
              .plainSource(restoreResultConsumerTopicSettings,
                           Subscriptions.topics(kafkaDataInChunksWithTimePeriodRenamedTopics.renamedTopics.values.toSet)
              )
          eventualRestoredTopics = restoreResultConsumerSource.toMat(Sink.collection)(DrainingControl.apply).run()
          _ <- adminClient
                 .createTopics(kafkaDataInChunksWithTimePeriodRenamedTopics.renamedTopics.values.toList.map { topic =>
                   new NewTopic(topic, 1, 1.toShort)
                 }.asJava)
                 .all()
                 .toCompletableFuture
                 .asScala
          _              <- akka.pattern.after(5 seconds)(restoreClient.restore)
          receivedTopics <- akka.pattern.after(1 minute)(eventualRestoredTopics.drainAndShutdown())
          asConsumerRecords = receivedTopics.map(KafkaClient.consumerRecordToReducedConsumerRecord)
        } yield asConsumerRecords.toList

        val restoredConsumerRecords = calculatedFuture.futureValue

        val restoredGroupedAsKey = restoredConsumerRecords
          .groupBy(_.key)
          .view
          .mapValues { reducedConsumerRecords =>
            reducedConsumerRecords.map(_.value)
          }
          .toMap

        val inputAsKey = data
          .groupBy(_.key)
          .view
          .mapValues { reducedConsumerRecords =>
            reducedConsumerRecords.map(_.value)
          }
          .toMap

        restoredGroupedAsKey mustMatchTo inputAsKey
    }

  }

}
