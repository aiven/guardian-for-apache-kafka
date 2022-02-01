package io.aiven.guardian.kafka.backup.gcs

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ContentTypes
import akka.stream.alpakka.google.GoogleAttributes
import akka.stream.alpakka.google.GoogleSettings
import akka.stream.alpakka.googlecloud.storage.StorageObject
import akka.stream.alpakka.googlecloud.storage.scaladsl.GCStorage
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import io.aiven.guardian.kafka.backup.BackupClientInterface
import io.aiven.guardian.kafka.backup.KafkaPublisherInterface
import io.aiven.guardian.kafka.backup.configs.Backup
import io.aiven.guardian.kafka.gcs.configs.{GCS => GCSConfig}

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// TODO: GCS implementation currently does not work correctly because of inability of current GCS implementation in
// Alpakka to allow us to commit Kafka cursor whenever chunks are uploaded
class BackupClient[T <: KafkaClientInterface](maybeGoogleSettings: Option[GoogleSettings])(implicit
    override val kafkaClientInterface: T,
    override val backupConfig: Backup,
    override val system: ActorSystem,
    gcsConfig: GCSConfig
) extends BackupClientInterface[T] {

  override def empty: () => Future[Option[StorageObject]] = () => Future.successful(None)

  override type BackupResult = Option[StorageObject]

  override type State = Nothing

  override def getCurrentUploadState(key: String): Future[UploadStateResult] =
    Future.successful(UploadStateResult.empty)

  override def backupToStorageTerminateSink(
      previousState: PreviousState
  ): Sink[ByteString, Future[Option[StorageObject]]] = {
    val base = GCStorage
      .resumableUpload(gcsConfig.dataBucket, previousState.previousKey, ContentTypes.`application/json`)
      .mapMaterializedValue(future => future.map(result => Some(result))(ExecutionContext.parasitic))

    maybeGoogleSettings
      .fold(base)(googleSettings => base.withAttributes(GoogleAttributes.settings(googleSettings)))
  }

  override def backupToStorageSink(key: String,
                                   currentState: Option[Nothing]
  ): Sink[(ByteString, kafkaClientInterface.CursorContext), Future[BackupResult]] = {
    val base = GCStorage
      .resumableUpload(gcsConfig.dataBucket, key, ContentTypes.`application/json`)
      .mapMaterializedValue(future => future.map(result => Some(result))(ExecutionContext.parasitic))

    maybeGoogleSettings
      .fold(base)(googleSettings => base.withAttributes(GoogleAttributes.settings(googleSettings)))
      .contramap[(ByteString, kafkaClientInterface.CursorContext)] { case (byteString, _) =>
        byteString
      }
  }
}
