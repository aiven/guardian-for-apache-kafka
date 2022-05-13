package io.aiven.guardian.kafka.backup.s3

import akka.actor.ActorSystem
import akka.kafka.scaladsl.Producer
import akka.stream.KillSwitches
import akka.stream.SharedKillSwitch
import akka.stream.alpakka.s3.S3Settings
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.Sink
import com.softwaremill.diffx.scalatest.DiffMustMatcher._
import io.aiven.guardian.akka.AnyPropTestKit
import io.aiven.guardian.kafka.Generators.KafkaDataInChunksWithTimePeriod
import io.aiven.guardian.kafka.Generators.kafkaDataWithMinSizeGen
import io.aiven.guardian.kafka.KafkaClusterTest
import io.aiven.guardian.kafka.TestUtils._
import io.aiven.guardian.kafka.backup.KafkaClient
import io.aiven.guardian.kafka.backup.MockedBackupClientInterface
import io.aiven.guardian.kafka.backup.configs.Backup
import io.aiven.guardian.kafka.backup.configs.ChronoUnitSlice
import io.aiven.guardian.kafka.backup.configs.PeriodFromFirst
import io.aiven.guardian.kafka.codecs.Circe._
import io.aiven.guardian.kafka.configs.KafkaCluster
import io.aiven.guardian.kafka.models.ReducedConsumerRecord
import io.aiven.guardian.kafka.s3.Generators.s3ConfigGen
import io.aiven.guardian.kafka.s3.configs.{S3 => S3Config}
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.mdedetrich.akka.stream.support.CirceStreamSupport

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._
import scala.language.postfixOps

import java.time.temporal.ChronoUnit

class RealS3BackupClientSpec
    extends AnyPropTestKit(ActorSystem("RealS3BackupClientSpec"))
    with KafkaClusterTest
    with BackupClientSpec {
  override lazy val s3Settings: S3Settings = S3Settings()

  /** Virtual Dot Host in bucket names are disabled because you need an actual DNS certificate otherwise AWS will fail
    * on bucket creation
    */
  override lazy val useVirtualDotHost: Boolean            = false
  override lazy val bucketPrefix: Option[String]          = Some("guardian-")
  override lazy val enableCleanup: Option[FiniteDuration] = Some(5 seconds)

  def createKafkaClient(
      killSwitch: SharedKillSwitch
  )(implicit kafkaClusterConfig: KafkaCluster, backupConfig: Backup): KafkaClientWithKillSwitch =
    new KafkaClientWithKillSwitch(
      configureConsumer = baseKafkaConfig,
      killSwitch = killSwitch
    )

  def getKeyFromSingleDownload(dataBucket: String): Future[String] = waitForS3Download(
    dataBucket,
    {
      case Seq(single) => single.key
      case rest =>
        throw DownloadNotReady(rest)
    }
  )

  def getKeysFromTwoDownloads(dataBucket: String): Future[(String, String)] = waitForS3Download(
    dataBucket,
    {
      case Seq(first, second) => (first.key, second.key)
      case rest =>
        throw DownloadNotReady(rest)
    }
  )

  def waitUntilBackupClientHasCommitted(backupClient: BackupClientChunkState[_],
                                        step: FiniteDuration = 100 millis,
                                        delay: FiniteDuration = 5 seconds
  ): Future[Unit] =
    if (backupClient.processedChunks.size() > 0)
      akka.pattern.after(delay)(Future.successful(()))
    else
      akka.pattern.after(step)(waitUntilBackupClientHasCommitted(backupClient, step, delay))

  property("basic flow without interruptions using PeriodFromFirst works correctly", RealS3Available) {
    forAll(kafkaDataWithMinSizeGen(S3.MinChunkSize, 2, reducedConsumerRecordsToJson),
           s3ConfigGen(useVirtualDotHost, bucketPrefix)
    ) { (kafkaDataInChunksWithTimePeriod: KafkaDataInChunksWithTimePeriod, s3Config: S3Config) =>
      logger.info(s"Data bucket is ${s3Config.dataBucket}")

      val data = kafkaDataInChunksWithTimePeriod.data.flatten

      val topics = data.map(_.topic).toSet

      val asProducerRecords = toProducerRecords(data)
      val baseSource        = toSource(asProducerRecords, 30 seconds)

      implicit val kafkaClusterConfig: KafkaCluster = KafkaCluster(topics)

      implicit val config: S3Config = s3Config
      implicit val backupConfig: Backup =
        Backup(MockedBackupClientInterface.KafkaGroupId, PeriodFromFirst(1 minute), 10 seconds)

      val producerSettings = createProducer()

      val backupClient =
        new BackupClient(Some(s3Settings))(new KafkaClient(configureConsumer = baseKafkaConfig),
                                           implicitly,
                                           implicitly,
                                           implicitly,
                                           implicitly
        )

      val adminClient = AdminClient.create(
        Map[String, AnyRef](
          CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG -> container.bootstrapServers
        ).asJava
      )

      val createTopics = adminClient.createTopics(topics.map { topic =>
        new NewTopic(topic, 1, 1.toShort)
      }.asJava)

      val calculatedFuture = for {
        _ <- createTopics.all().toCompletableFuture.asScala
        _ <- createBucket(s3Config.dataBucket)
        _ = backupClient.backup.run()
        _ <- akka.pattern.after(KafkaInitializationTimeoutConstant)(
               baseSource
                 .runWith(Producer.plainSink(producerSettings))
             )
        _   <- sendTopicAfterTimePeriod(1 minute, producerSettings, topics.head)
        key <- getKeyFromSingleDownload(s3Config.dataBucket)
        downloaded <-
          S3.download(s3Config.dataBucket, key)
            .withAttributes(s3Attrs)
            .runWith(Sink.head)
            .flatMap {
              case Some(downloadSource, _) =>
                downloadSource.via(CirceStreamSupport.decode[List[Option[ReducedConsumerRecord]]]).runWith(Sink.seq)
              case None => throw new Exception(s"Expected object in bucket ${s3Config.dataBucket} with key $key")
            }

      } yield downloaded.toList.flatten.collect { case Some(reducedConsumerRecord) =>
        reducedConsumerRecord
      }

      val downloaded = calculatedFuture.futureValue

      val downloadedGroupedAsKey = downloaded
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

      downloadedGroupedAsKey mustMatchTo inputAsKey
    }
  }

  property("suspend/resume using PeriodFromFirst creates separate object after resume point", RealS3Available) {
    forAll(kafkaDataWithMinSizeGen(S3.MinChunkSize, 2, reducedConsumerRecordsToJson),
           s3ConfigGen(useVirtualDotHost, bucketPrefix)
    ) { (kafkaDataInChunksWithTimePeriod: KafkaDataInChunksWithTimePeriod, s3Config: S3Config) =>
      logger.info(s"Data bucket is ${s3Config.dataBucket}")

      val data = kafkaDataInChunksWithTimePeriod.data.flatten

      val topics = data.map(_.topic).toSet

      implicit val kafkaClusterConfig: KafkaCluster = KafkaCluster(topics)

      implicit val config: S3Config = s3Config
      implicit val backupConfig: Backup =
        Backup(MockedBackupClientInterface.KafkaGroupId, PeriodFromFirst(1 minute), 10 seconds)

      val producerSettings = createProducer()

      val killSwitch = KillSwitches.shared("kill-switch")

      val backupClient =
        new BackupClientChunkState(Some(s3Settings))(createKafkaClient(killSwitch),
                                                     implicitly,
                                                     implicitly,
                                                     implicitly,
                                                     implicitly
        )

      val asProducerRecords = toProducerRecords(data)
      val baseSource        = toSource(asProducerRecords, 30 seconds)

      val adminClient = AdminClient.create(
        Map[String, AnyRef](
          CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG -> container.bootstrapServers
        ).asJava
      )

      val createTopics = adminClient.createTopics(topics.map { topic =>
        new NewTopic(topic, 1, 1.toShort)
      }.asJava)

      val calculatedFuture = for {
        _ <- createTopics.all().toCompletableFuture.asScala
        _ <- createBucket(s3Config.dataBucket)
        _ = backupClient.backup.run()
        _ = baseSource.runWith(Producer.plainSink(producerSettings))
        _ <- waitUntilBackupClientHasCommitted(backupClient)
        _ = killSwitch.abort(TerminationException)
        secondBackupClient <- akka.pattern.after(2 seconds) {
                                Future {
                                  new BackupClient(Some(s3Settings))(
                                    new KafkaClient(configureConsumer = baseKafkaConfig),
                                    implicitly,
                                    implicitly,
                                    implicitly,
                                    implicitly
                                  )
                                }
                              }
        _ = secondBackupClient.backup.run()
        _                     <- sendTopicAfterTimePeriod(1 minute, producerSettings, topics.head)
        (firstKey, secondKey) <- getKeysFromTwoDownloads(s3Config.dataBucket)
        firstDownloaded <- S3.download(s3Config.dataBucket, firstKey)
                             .withAttributes(s3Attrs)
                             .runWith(Sink.head)
                             .flatMap {
                               case Some(downloadSource, _) =>
                                 downloadSource
                                   .via(CirceStreamSupport.decode[List[Option[ReducedConsumerRecord]]])
                                   .runWith(Sink.seq)
                               case None =>
                                 throw new Exception(s"Expected object in bucket ${s3Config.dataBucket} with key $key")
                             }
        secondDownloaded <- S3.download(s3Config.dataBucket, secondKey)
                              .withAttributes(s3Attrs)
                              .runWith(Sink.head)
                              .flatMap {
                                case Some(downloadSource, _) =>
                                  downloadSource
                                    .via(CirceStreamSupport.decode[List[Option[ReducedConsumerRecord]]])
                                    .runWith(Sink.seq)
                                case None =>
                                  throw new Exception(s"Expected object in bucket ${s3Config.dataBucket} with key $key")
                              }

      } yield {
        val first = firstDownloaded.toList.flatten.collect { case Some(reducedConsumerRecord) =>
          reducedConsumerRecord
        }

        val second = secondDownloaded.toList.flatten.collect { case Some(reducedConsumerRecord) =>
          reducedConsumerRecord
        }
        (first, second)
      }

      val (firstDownloaded, secondDownloaded) = calculatedFuture.futureValue

      // Only care about ordering when it comes to key
      val firstDownloadedGroupedAsKey = firstDownloaded
        .groupBy(_.key)
        .view
        .mapValues { reducedConsumerRecords =>
          reducedConsumerRecords.map(_.value)
        }
        .toMap

      val secondDownloadedGroupedAsKey = secondDownloaded
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

      val downloaded = (firstDownloadedGroupedAsKey.keySet ++ secondDownloadedGroupedAsKey.keySet).map { key =>
        (key,
         firstDownloadedGroupedAsKey.getOrElse(key, List.empty) ++ secondDownloadedGroupedAsKey.getOrElse(key,
                                                                                                          List.empty
         )
        )
      }.toMap

      downloaded mustMatchTo inputAsKey

    }
  }

  property("suspend/resume for same object using ChronoUnitSlice works correctly", RealS3Available) {
    forAll(kafkaDataWithMinSizeGen(S3.MinChunkSize, 2, reducedConsumerRecordsToJson),
           s3ConfigGen(useVirtualDotHost, bucketPrefix)
    ) { (kafkaDataInChunksWithTimePeriod: KafkaDataInChunksWithTimePeriod, s3Config: S3Config) =>
      logger.info(s"Data bucket is ${s3Config.dataBucket}")

      val data = kafkaDataInChunksWithTimePeriod.data.flatten

      val topics = data.map(_.topic).toSet

      implicit val kafkaClusterConfig: KafkaCluster = KafkaCluster(topics)

      implicit val config: S3Config = s3Config
      implicit val backupConfig: Backup =
        Backup(MockedBackupClientInterface.KafkaGroupId, ChronoUnitSlice(ChronoUnit.MINUTES), 10 seconds)

      val producerSettings = createProducer()

      val killSwitch = KillSwitches.shared("kill-switch")

      val backupClient =
        new BackupClientChunkState(Some(s3Settings))(createKafkaClient(killSwitch),
                                                     implicitly,
                                                     implicitly,
                                                     implicitly,
                                                     implicitly
        )

      val asProducerRecords = toProducerRecords(data)
      val baseSource        = toSource(asProducerRecords, 30 seconds)

      val adminClient = AdminClient.create(
        Map[String, AnyRef](
          CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG -> container.bootstrapServers
        ).asJava
      )

      val createTopics = adminClient.createTopics(topics.map { topic =>
        new NewTopic(topic, 1, 1.toShort)
      }.asJava)

      val calculatedFuture = for {
        _ <- createTopics.all().toCompletableFuture.asScala
        _ <- createBucket(s3Config.dataBucket)
        _ = backupClient.backup.run()
        _ <- waitForStartOfTimeUnit(ChronoUnit.MINUTES)
        _ = baseSource.runWith(Producer.plainSink(producerSettings))
        _ <- waitUntilBackupClientHasCommitted(backupClient)
        _ = killSwitch.abort(TerminationException)
        secondBackupClient <- akka.pattern.after(2 seconds) {
                                Future {
                                  new BackupClient(Some(s3Settings))(
                                    new KafkaClient(configureConsumer = baseKafkaConfig),
                                    implicitly,
                                    implicitly,
                                    implicitly,
                                    implicitly
                                  )
                                }
                              }
        _ = secondBackupClient.backup.run()
        _   <- sendTopicAfterTimePeriod(1 minute, producerSettings, topics.head)
        key <- getKeyFromSingleDownload(s3Config.dataBucket)
        downloaded <- S3.download(s3Config.dataBucket, key)
                        .withAttributes(s3Attrs)
                        .runWith(Sink.head)
                        .flatMap {
                          case Some(downloadSource, _) =>
                            downloadSource
                              .via(CirceStreamSupport.decode[List[Option[ReducedConsumerRecord]]])
                              .runWith(Sink.seq)
                          case None =>
                            throw new Exception(s"Expected object in bucket ${s3Config.dataBucket} with key $key")
                        }

      } yield downloaded.toList.flatten.collect { case Some(reducedConsumerRecord) =>
        reducedConsumerRecord
      }

      val downloaded = calculatedFuture.futureValue

      // Only care about ordering when it comes to key
      val downloadedGroupedAsKey = downloaded
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

      downloadedGroupedAsKey mustMatchTo inputAsKey
    }
  }
}
