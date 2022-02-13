package io.aiven.guardian.kafka.backup

import akka.actor.ActorSystem
import akka.testkit.TestKit
import io.aiven.guardian.kafka.backup.configs.ChronoUnitSlice
import io.aiven.guardian.kafka.backup.configs.Gzip
import io.aiven.guardian.kafka.backup.configs.{Backup => BackupConfig}
import io.aiven.guardian.kafka.configs.{KafkaCluster => KafkaClusterConfig}
import markatta.futiles.CancellableFuture
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.propspec.AnyPropSpecLike

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.language.postfixOps

import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@nowarn("msg=method main in class CommandApp is deprecated")
class CliSpec extends TestKit(ActorSystem("BackupCliSpec")) with AnyPropSpecLike with Matchers with ScalaFutures {
  implicit val ec: ExecutionContext                    = system.dispatcher
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(5 minutes, 100 millis)

  property("Command line args are properly passed into application") {
    val groupId         = "my-consumer-group"
    val topic           = "topic"
    val bootstrapServer = "localhost:9092"
    val dataBucket      = "backup-bucket"

    val args = List(
      "--storage",
      "s3",
      "--kafka-topics",
      topic,
      "--kafka-bootstrap-servers",
      bootstrapServer,
      "--s3-data-bucket",
      dataBucket,
      "--kafka-group-id",
      groupId,
      "--chrono-unit-slice",
      "hours",
      "--commit-timeout-buffer-window",
      "1 second",
      "gzip"
    )

    val cancellable = CancellableFuture {
      Main.main(args.toArray)
    }

    def checkUntilMainInitialized(main: io.aiven.guardian.kafka.backup.Entry): Future[(App[_], Promise[Unit])] =
      main.initializedApp.get() match {
        case Some((app, promise)) => Future.successful((app, promise))
        case None                 => akka.pattern.after(100 millis)(checkUntilMainInitialized(main))
      }

    val (app, promise) = checkUntilMainInitialized(Main).futureValue

    cancellable.cancel()
    promise.success(())

    app match {
      case s3App: S3App =>
        s3App.backupConfig mustEqual BackupConfig(groupId,
                                                  ChronoUnitSlice(ChronoUnit.HOURS),
                                                  FiniteDuration(1, TimeUnit.SECONDS),
                                                  Some(Gzip(None))
        )
        s3App.kafkaClusterConfig mustEqual KafkaClusterConfig(Set(topic))
        s3App.kafkaClient.consumerSettings.getProperty("bootstrap.servers") mustEqual bootstrapServer
        s3App.s3Config.dataBucket mustEqual dataBucket
      case _ =>
        fail("Expected an App to be initialized")
    }
  }
}
