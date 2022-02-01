package io.aiven.guardian.kafka.backup.s3

import akka.Done
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.alpakka.s3.FailedUploadPart
import akka.stream.alpakka.s3.ListMultipartUploadResultUploads
import akka.stream.alpakka.s3.MultipartUploadResult
import akka.stream.alpakka.s3.Part
import akka.stream.alpakka.s3.S3Attributes
import akka.stream.alpakka.s3.S3Headers
import akka.stream.alpakka.s3.S3Settings
import akka.stream.alpakka.s3.SuccessfulUploadPart
import akka.stream.alpakka.s3.UploadPartResponse
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl._
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import io.aiven.guardian.kafka.backup.BackupClientInterface
import io.aiven.guardian.kafka.backup.KafkaPublisherInterface
import io.aiven.guardian.kafka.backup.configs.Backup
import io.aiven.guardian.kafka.s3.configs.{S3 => S3Config}

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import java.time.Instant

class BackupClient[T <: KafkaClientInterface](maybeS3Settings: Option[S3Settings])(implicit
    override val kafkaClientInterface: T,
    override val backupConfig: Backup,
    override val system: ActorSystem,
    s3Config: S3Config,
    s3Headers: S3Headers
) extends BackupClientInterface[T]
    with StrictLogging {
  import BackupClient._

  override def empty: () => Future[Option[MultipartUploadResult]] = () => Future.successful(None)

  override type BackupResult = Option[MultipartUploadResult]

  override type State = CurrentS3State

  private def extractStateFromUpload(keys: Seq[ListMultipartUploadResultUploads], current: Boolean)(implicit
      executionContext: ExecutionContext
  ): Future[Some[(CurrentS3State, String)]] = {
    val listMultipartUploads = keys match {
      case Seq(single) =>
        if (current)
          logger.info(
            s"Found previous uploadId: ${single.uploadId} and bucket: ${s3Config.dataBucket} with key: ${single.key}"
          )
        else
          logger.info(
            s"Found current uploadId: ${single.uploadId} and bucket: ${s3Config.dataBucket} with key: ${single.key}"
          )
        single
      case rest =>
        val last = rest.maxBy(_.initiated)(Ordering[Instant])
        if (current)
          logger.warn(
            s"Found multiple currently cancelled uploads for key: ${last.key} and bucket: ${s3Config.dataBucket}, picking uploadId: ${last.uploadId}"
          )
        else
          logger.warn(
            s"Found multiple previously cancelled uploads for key: ${last.key} and bucket: ${s3Config.dataBucket}, picking uploadId: ${last.uploadId}"
          )
        last
    }
    val uploadId = listMultipartUploads.uploadId
    val key      = listMultipartUploads.key
    val baseList = S3.listParts(s3Config.dataBucket, key, listMultipartUploads.uploadId)

    for {
      parts <- maybeS3Settings
                 .fold(baseList)(s3Settings => baseList.withAttributes(S3Attributes.settings(s3Settings)))
                 .runWith(Sink.seq)

      finalParts = parts.lastOption match {
                     case Some(part) if part.size >= akka.stream.alpakka.s3.scaladsl.S3.MinChunkSize =>
                       parts
                     case _ =>
                       // We drop the last part here since its broken
                       parts.dropRight(1)
                   }
    } yield Some((CurrentS3State(uploadId, finalParts.map(_.toPart)), key))
  }

  def getCurrentUploadState(key: String): Future[UploadStateResult] = {
    implicit val ec: ExecutionContext = system.dispatcher

    val baseListMultipart = S3.listMultipartUpload(s3Config.dataBucket, None)

    for {
      incompleteUploads <-
        maybeS3Settings
          .fold(baseListMultipart)(s3Settings => baseListMultipart.withAttributes(S3Attributes.settings(s3Settings)))
          .runWith(Sink.seq)
      (currentKeys, previousKeys) = incompleteUploads.partition(_.key == key)
      current <- if (currentKeys.nonEmpty)
                   extractStateFromUpload(currentKeys, current = true)
                 else
                   Future.successful(None)
      previous <- if (previousKeys.nonEmpty)
                    extractStateFromUpload(previousKeys, current = false)
                  else
                    Future.successful(None)

    } yield UploadStateResult(current.map(_._1),
                              previous.map { case (state, previousKey) => PreviousState(state, previousKey) }
    )

  }

  private[s3] def failureSink
      : Sink[(FailedUploadPart, immutable.Iterable[kafkaClientInterface.CursorContext]), Future[Done]] = Sink
    .foreach[(FailedUploadPart, immutable.Iterable[kafkaClientInterface.CursorContext])] { case (failedPart, _) =>
      logger.warn(
        s"Failed to upload a chunk into S3 with bucket: ${failedPart.multipartUpload.bucket}, key: ${failedPart.multipartUpload.key}, uploadId: ${failedPart.multipartUpload.uploadId} and partNumber: ${failedPart.partNumber}",
        failedPart.exception
      )
    }

  private[s3] def successSink
      : Sink[(SuccessfulUploadPart, immutable.Iterable[kafkaClientInterface.CursorContext]), Future[Done]] =
    kafkaClientInterface.commitCursor
      .contramap[(SuccessfulUploadPart, immutable.Iterable[kafkaClientInterface.CursorContext])] { case (_, cursors) =>
        kafkaClientInterface.batchCursorContext(cursors)
      }

  private[s3] def kafkaBatchSink
      : Sink[(UploadPartResponse, immutable.Iterable[kafkaClientInterface.CursorContext]), NotUsed] = {

    val success = Flow[(UploadPartResponse, immutable.Iterable[kafkaClientInterface.CursorContext])]
      .collectType[(SuccessfulUploadPart, immutable.Iterable[kafkaClientInterface.CursorContext])]
      .wireTap { data =>
        val (part, _) = data
        logger.info(
          s"Committing kafka cursor for uploadId:${part.multipartUpload.uploadId} key: ${part.multipartUpload.key} and S3 part: ${part.partNumber}"
        )
      }
      .toMat(successSink)(Keep.none)

    val failure = Flow[(UploadPartResponse, immutable.Iterable[kafkaClientInterface.CursorContext])]
      .collectType[(FailedUploadPart, immutable.Iterable[kafkaClientInterface.CursorContext])]
      .toMat(failureSink)(Keep.none)

    Sink.combine(success, failure)(Broadcast(_))
  }

  override def backupToStorageTerminateSink(
      previousState: PreviousState
  ): Sink[ByteString, Future[BackupResult]] = {
    logger.info(
      s"Terminating and completing previous backup with key: ${previousState.previousKey} and uploadId:${previousState.state.uploadId}"
    )
    val sink = S3
      .resumeMultipartUploadWithHeaders(
        s3Config.dataBucket,
        previousState.previousKey,
        previousState.state.uploadId,
        previousState.state.parts,
        s3Headers = s3Headers,
        chunkingParallelism = 1
      )

    val base = sink.mapMaterializedValue(future => future.map(result => Some(result))(ExecutionContext.parasitic))

    maybeS3Settings
      .fold(base)(s3Settings => base.withAttributes(S3Attributes.settings(s3Settings)))
  }

  override def backupToStorageSink(key: String,
                                   currentState: Option[CurrentS3State]
  ): Sink[(ByteString, kafkaClientInterface.CursorContext), Future[BackupResult]] = {

    // Note that chunkingParallelism is pointless for this usecase since we are directly streaming from Kafka.
    // Furthermore the real `KafkaClient` implementation uses `CommittableOffsetBatch` which is a global singleton so
    // we can't have concurrent updates to this data structure.

    val sink = currentState match {
      case Some(state) =>
        logger.info(
          s"Resuming previous upload with uploadId: ${state.uploadId} and bucket: ${s3Config.dataBucket} with key: $key"
        )
        S3.resumeMultipartUploadWithHeadersAndContext[kafkaClientInterface.CursorContext](
          s3Config.dataBucket,
          key,
          state.uploadId,
          state.parts,
          kafkaBatchSink,
          s3Headers = s3Headers,
          chunkingParallelism = 1
        )
      case None =>
        logger.info(
          s"Creating new upload with bucket: ${s3Config.dataBucket} and key: $key"
        )
        S3.multipartUploadWithHeadersAndContext[kafkaClientInterface.CursorContext](
          s3Config.dataBucket,
          key,
          kafkaBatchSink,
          s3Headers = s3Headers,
          chunkingParallelism = 1
        )
    }

    val base = sink.mapMaterializedValue(future => future.map(result => Some(result))(ExecutionContext.parasitic))
    maybeS3Settings.fold(base)(s3Settings => base.withAttributes(S3Attributes.settings(s3Settings)))
  }
}

object BackupClient {
  final case class CurrentS3State(uploadId: String, parts: Seq[Part])
}
