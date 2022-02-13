package io.aiven.guardian.kafka.backup

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.SubstreamCancelStrategy
import akka.stream.scaladsl._
import akka.util.ByteString
import com.typesafe.scalalogging.LazyLogging
import io.aiven.guardian.kafka.Errors
import io.aiven.guardian.kafka.backup.configs._
import io.aiven.guardian.kafka.codecs.Circe._
import io.aiven.guardian.kafka.models.ReducedConsumerRecord
import io.circe.syntax._

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.DurationConverters._
import scala.util._

import java.time._
import java.time.format.DateTimeFormatter
import java.time.temporal._

/** An interface for a template on how to backup a Kafka Stream into some data storage
  * @tparam T
  *   The underlying `kafkaClientInterface` type
  */
trait BackupClientInterface[T <: KafkaClientInterface] extends LazyLogging {
  implicit val kafkaClientInterface: T
  implicit val backupConfig: Backup
  implicit val system: ActorSystem

  /** An element from the original record
    */
  private[backup] sealed trait RecordElement
  private[backup] case class Element(reducedConsumerRecord: ReducedConsumerRecord,
                                     context: kafkaClientInterface.CursorContext
  ) extends RecordElement
  private[backup] case object End extends RecordElement

  private[backup] sealed trait ByteStringContext {
    val context: kafkaClientInterface.CursorContext
  }

  private[backup] case class Start(override val context: kafkaClientInterface.CursorContext, key: String)
      extends ByteStringContext

  private[backup] case class Tail(override val context: kafkaClientInterface.CursorContext) extends ByteStringContext

  case class PreviousState(state: State, previousKey: String)
  case class UploadStateResult(current: Option[State], previous: Option[PreviousState])
  object UploadStateResult {
    val empty: UploadStateResult = UploadStateResult(None, None)
  }

  /** Override this type to define the result of backing up data to a datasource
    */
  type BackupResult

  /** Override this type to define the result of calculating the previous state (if it exists)
    */
  type State

  import BackupClientInterface._

  /** Override this method to define how to retrieve the current state of any unfinished backups.
    * @param key
    *   The object key or filename for what is currently being backed up
    * @return
    *   A [[scala.concurrent.Future]] with a [[UploadStateResult]] data structure that optionally contains the state
    *   associated with `key` along with the previous latest state before `key` (if it exists)
    */
  def getCurrentUploadState(key: String): Future[UploadStateResult]

  /** A sink that is executed whenever a previously existing Backup needs to be terminated and closed. Generally
    * speaking this [[akka.stream.scaladsl.Sink]] is similar to the `backupToStorageSink` except that
    * `kafkaClientInterface.CursorContext` is not required since no Kafka messages are being written.
    *
    * Note that the terminate refers to the fact that this Sink is executed with a `null]`
    * [[akka.stream.scaladsl.Source]] which when written to an already existing unfinished backup terminates the
    * containing JSON array so that it becomes valid parsable JSON.
    * @param previousState
    *   A data structure containing both the [[State]] along with the associated key which you can refer to in order to
    *   define your [[akka.stream.scaladsl.Sink]]
    * @return
    *   A [[akka.stream.scaladsl.Sink]] that points to an existing key defined by `previousState.previousKey`
    */
  def backupToStorageTerminateSink(previousState: PreviousState): Sink[ByteString, Future[BackupResult]]

  /** Override this method to define how to backup a [[akka.util.ByteString]] combined with Kafka
    * `kafkaClientInterface.CursorContext` to a `DataSource`
    * @param key
    *   The object key or filename for what is being backed up
    * @param currentState
    *   The current state if it exists. If this is empty then a new backup is being created with the associated `key`
    *   otherwise if this contains a [[State]] then the defined [[akka.stream.scaladsl.Sink]] needs to handle resuming a
    *   previously unfinished backup with that `key` by directly appending the [[akka.util.ByteString]] data.
    * @return
    *   A [[akka.stream.scaladsl.Sink]] that given a [[akka.util.ByteString]] (containing a single Kafka
    *   [[io.aiven.guardian.kafka.models.ReducedConsumerRecord]]) along with its `kafkaClientInterface.CursorContext`
    *   backs up the data to your data storage. The [[akka.stream.scaladsl.Sink]] is also responsible for executing
    *   `kafkaClientInterface.commitCursor` when the data is successfully backed up
    */
  def backupToStorageSink(key: String,
                          currentState: Option[State]
  ): Sink[(ByteString, kafkaClientInterface.CursorContext), Future[BackupResult]]

  /** Override this method to define a zero vale that covers the case that occurs immediately when `SubFlow` has been
    * split at `BackupStreamPosition.Start`. If you have difficulties defining an empty value for `BackupResult` then
    * you can wrap it in an `Option` and just use `None` for the empty case
    * @return
    *   An "empty" value that is used when a split `SubFlow` has just started
    */
  def empty: () => Future[BackupResult]

  private[backup] def calculateBackupStreamPositions(
      sourceWithPeriods: SourceWithContext[(ReducedConsumerRecord, Long),
                                           kafkaClientInterface.CursorContext,
                                           kafkaClientInterface.Control
      ]
  ): Source[RecordElement, kafkaClientInterface.Control] =
    sourceWithPeriods.asSource
      .prefixAndTail(2)
      // This algorithm only works with Source's that have 2 or more elements
      .flatMapConcat {
        case (Seq(
                ((firstReducedConsumerRecord, firstDivision), firstContext),
                ((secondReducedConsumerRecord, secondDivision), secondContext)
              ),
              rest
            ) =>
          val all = Source
            .combine(
              Source(
                List(
                  ((firstReducedConsumerRecord, firstDivision), firstContext),
                  ((secondReducedConsumerRecord, secondDivision), secondContext)
                )
              ),
              rest
            )(Concat(_))

          val withDivisions =
            all
              .sliding(2)
              .map {
                case Seq(((_, beforeDivisions), _), ((afterReducedConsumerRecord, afterDivisions), afterContext)) =>
                  if (isAtBoundary(beforeDivisions, afterDivisions))
                    List(
                      End,
                      Element(afterReducedConsumerRecord, afterContext)
                    )
                  else
                    List(Element(afterReducedConsumerRecord, afterContext))
                case rest =>
                  throw Errors.UnhandledStreamCase(rest)
              }
              .mapConcat(identity)

          Source.combine(
            Source.single(Element(firstReducedConsumerRecord, firstContext)),
            withDivisions
          )(Concat(_))
        // This case only occurs if a Source has a single element so we just directly return it
        case (Seq(((singleReducedConsumerRecord, _), singleContext)), _) =>
          Source.single(Element(singleReducedConsumerRecord, singleContext))
        case (rest, _) =>
          throw Errors.UnhandledStreamCase(rest)
      }

  private[backup] def sourceWithPeriods(
      source: Source[(OffsetDateTime, (ReducedConsumerRecord, kafkaClientInterface.CursorContext)),
                     kafkaClientInterface.Control
      ]
  ): SourceWithContext[(ReducedConsumerRecord, Long),
                       kafkaClientInterface.CursorContext,
                       kafkaClientInterface.Control
  ] = SourceWithContext.fromTuples(source.map { case (firstTimestamp, (record, context)) =>
    val period = calculateNumberOfPeriodsFromTimestamp(firstTimestamp, backupConfig.timeConfiguration, record)
    ((record, period), context)
  })

  private[backup] def sourceWithFirstRecord
      : Source[(OffsetDateTime, (ReducedConsumerRecord, kafkaClientInterface.CursorContext)),
               kafkaClientInterface.Control
      ] =
    kafkaClientInterface.getSource.asSource.prefixAndTail(1).flatMapConcat { case (head, rest) =>
      head.headOption match {
        case Some((firstReducedConsumerRecord, firstCursorContext)) =>
          val firstTimestamp = firstReducedConsumerRecord.toOffsetDateTime

          Source.combine(
            Source.single((firstTimestamp, (firstReducedConsumerRecord, firstCursorContext))),
            rest.map { case (reducedConsumerRecord, context) =>
              (firstTimestamp, (reducedConsumerRecord, context))
            }
          )(Concat(_))
        case None => throw Errors.ExpectedStartOfSource
      }
    }

  /** Transforms a sequence of [[RecordElement]]'s into a ByteString so that it can be persisted into the data storage
    *
    * @param sourceElements
    *   A sequence of [[RecordElement]]'s as a result of `sliding(2)`
    * @return
    *   a [[ByteString]] ready to be persisted along with the original context form the [[RecordElement]]
    */
  private[backup] def transformReducedConsumerRecords(sourceElements: Seq[RecordElement]) = {
    val stringWithContext = sourceElements match {
      case Seq(Element(reducedConsumerRecord, context)) =>
        // Happens in Sentinel case that is explicitly called at start of stream OR when a stream is interrupted by the user
        // in which case stream needs to be terminated with `null]` in order to be valid
        List((s"${reducedConsumerRecordAsString(reducedConsumerRecord)},", Some(context)))
      case Seq(Element(firstReducedConsumerRecord, firstContext),
               Element(secondReducedConsumerRecord, secondContext)
          ) =>
        List(
          (s"${reducedConsumerRecordAsString(firstReducedConsumerRecord)},", Some(firstContext)),
          (s"${reducedConsumerRecordAsString(secondReducedConsumerRecord)},", Some(secondContext))
        )
      case Seq(Element(reducedConsumerRecord, context), End) =>
        List((s"${reducedConsumerRecordAsString(reducedConsumerRecord)}]", Some(context)))
      case Seq(End) =>
        List(("]", None))
      case rest => throw Errors.UnhandledStreamCase(rest)
    }
    stringWithContext.map { case (string, context) => (ByteString(string), context) }
  }

  private[backup] def dropCommaFromEndOfJsonArray(byteString: ByteString) =
    byteString.dropRight(1)

  /** Applies the transformation to the first element of a Stream so that it starts of as a JSON array.
    *
    * @param element
    *   Starting [[Element]]
    * @param key
    *   The current key being processed
    * @param terminate
    *   Whether to immediately terminate the JSON array for single element in Stream case
    * @return
    *   A [[List]] containing a single [[Start]] ready to be processed in the [[Sink]]
    */
  private[backup] def transformFirstElement(element: Element, key: String, terminate: Boolean) =
    SourceWithContext.fromTuples(Source(transformReducedConsumerRecords(List(element)).map {
      case (byteString, Some(context)) =>
        val bs =
          if (terminate)
            ByteString("[") ++ dropCommaFromEndOfJsonArray(byteString) ++ ByteString("]")
          else
            ByteString("[") ++ byteString

        (bs, Start(context, key))
      case _ =>
        throw Errors.UnhandledStreamCase(List(element))
    }))

  /** Fixes the case where is an odd amount of elements in the stream
    * @param head
    *   of stream as a result of `prefixAndTail`
    * @param restSource
    *   of the stream as a result of `prefixAndTail`
    * @return
    *   A [[List]] of ([[ByteString]], [[kafkaClientInterface.CursorContext]]) with the tail elements fixed up.
    */
  private[backup] def transformTailingElement(
      head: Seq[(ByteString, Option[kafkaClientInterface.CursorContext])],
      restSource: Source[(ByteString, Option[kafkaClientInterface.CursorContext]), NotUsed]
  ) = {
    val restTransformed = restSource
      .sliding(2, step = 2)
      .map {
        case Seq((before, Some(context)), (after, None)) =>
          List((dropCommaFromEndOfJsonArray(before) ++ after, context))
        case Seq((before, Some(beforeContext)), (after, Some(afterContext))) =>
          List((before, beforeContext), (after, afterContext))
        case Seq((single, Some(context))) =>
          List((single, context))
        case rest =>
          throw Errors.UnhandledStreamCase(rest)
      }

    head match {
      case Seq((byteString, Some(cursorContext))) =>
        Source.combine(
          Source.single(List((byteString, cursorContext))),
          restTransformed
        )(Concat(_))
      case rest =>
        throw Errors.UnhandledStreamCase(rest)
    }
  }

  private[backup] val terminateSource: Source[ByteString, NotUsed] =
    Source.single(ByteString("null]"))

  private[backup] def compressContextFlow[CtxIn, CtxOut, Mat](
      flowWithContext: FlowWithContext[ByteString, CtxIn, ByteString, CtxOut, Mat]
  ) =
    backupConfig.compression match {
      case Some(compression) =>
        flowWithContext.unsafeDataVia(compressionLevel(compression))
      case None => flowWithContext
    }

  private[backup] def compressContextSink[Ctx, Mat](sink: Sink[(ByteString, Ctx), Mat]) =
    backupConfig.compression match {
      case Some(compression) =>
        FlowWithContext[ByteString, Ctx]
          .unsafeDataVia(compressionLevel(compression))
          .asFlow
          .toMat(
            sink
          )(Keep.right)
      case None => sink
    }

  private[backup] def compressSource[Mat](source: Source[ByteString, Mat]) =
    backupConfig.compression match {
      case Some(compression) =>
        source.via(compressionLevel(compression))
      case None =>
        source
    }

  private[backup] def compressionLevel(compression: Compression) = compression match {
    case Gzip(Some(level)) => Compression.gzip(level)
    case Gzip(None)        => Compression.gzip
  }

  /** Prepares the sink before it gets handed to `backupToStorageSink`
    */
  private[backup] def prepareStartOfStream(uploadStateResult: UploadStateResult,
                                           start: Start
  ): Sink[(ByteString, ByteStringContext), Future[BackupResult]] =
    (uploadStateResult.previous, uploadStateResult.current) match {
      case (Some(previous), None) =>
        backupConfig.timeConfiguration match {
          case _: PeriodFromFirst =>
            compressContextSink(
              backupToStorageSink(start.key, None)
                .contramap[(ByteString, ByteStringContext)] { case (byteString, byteStringContext) =>
                  (byteString, byteStringContext.context)
                }
            )
          case _: ChronoUnitSlice =>
            logger.warn(
              s"Detected previous backup using PeriodFromFirst however current configuration is now changed to ChronoUnitSlice. Object/file with an older key: ${start.key} may contain newer events than object/file with newer key: ${previous.previousKey}"
            )
            compressContextSink(
              backupToStorageSink(start.key, None)
                .contramap[(ByteString, ByteStringContext)] { case (byteString, byteStringContext) =>
                  (byteString, byteStringContext.context)
                }
            )
        }
      case (None, Some(current)) =>
        backupConfig.timeConfiguration match {
          case _: PeriodFromFirst =>
            throw Errors.UnhandledStreamCase(List(current))
          case _: ChronoUnitSlice =>
            compressContextFlow(
              FlowWithContext
                .fromTuples(
                  Flow[(ByteString, ByteStringContext)]
                    .flatMapPrefix(1) {
                      case Seq((byteString, start: Start)) =>
                        val withoutStartOfJsonArray = byteString.drop(1)
                        Flow[(ByteString, ByteStringContext)].prepend(
                          Source.single((withoutStartOfJsonArray, start))
                        )
                      case _ => throw Errors.ExpectedStartOfSource
                    }
                )
            ).asFlow
              .toMat(backupToStorageSink(start.key, Some(current)).contramap[(ByteString, ByteStringContext)] {
                case (byteString, byteStringContext) =>
                  (byteString, byteStringContext.context)
              })(Keep.right)
        }
      case (None, None) =>
        compressContextSink(
          backupToStorageSink(start.key, None)
            .contramap[(ByteString, ByteStringContext)] { case (byteString, byteStringContext) =>
              (byteString, byteStringContext.context)
            }
        )
      case (Some(previous), Some(current)) =>
        throw Errors.UnhandledStreamCase(List(previous.state, current))
    }

  /** The entire flow that involves reading from Kafka, transforming the data into JSON and then backing it up into a
    * data source.
    * @return
    *   The `KafkaClientInterface.Control` which depends on the implementation of `T` (typically this is used to control
    *   the underlying stream).
    */
  def backup: RunnableGraph[kafkaClientInterface.MatCombineResult] = {
    val withBackupStreamPositions = calculateBackupStreamPositions(sourceWithPeriods(sourceWithFirstRecord))

    val split = withBackupStreamPositions
      .splitAfter(SubstreamCancelStrategy.propagate) {
        case End => true
        case _   => false
      }

    val substreams = split
      .prefixAndTail(2)
      .flatMapConcat {
        case (Seq(only: Element, End), _) =>
          // This case only occurs when you have a single element in a timeslice.
          // We have to terminate immediately to create a JSON array with a single element
          val key = calculateKey(only.reducedConsumerRecord.toOffsetDateTime,
                                 backupConfig.timeConfiguration,
                                 backupConfig.compression
          )
          transformFirstElement(only, key, terminate = true)
        case (Seq(first: Element, second: Element), restOfReducedConsumerRecords) =>
          val key = calculateKey(first.reducedConsumerRecord.toOffsetDateTime,
                                 backupConfig.timeConfiguration,
                                 backupConfig.compression
          )
          val firstSource = transformFirstElement(first, key, terminate = false)

          val rest = Source.combine(
            Source.single(second),
            restOfReducedConsumerRecords
          )(Concat(_))

          val restTransformed = rest
            .sliding(2, step = 2)
            .map(transformReducedConsumerRecords)
            .mapConcat(identity)
            .prefixAndTail(1)
            .flatMapConcat((transformTailingElement _).tupled)
            .mapConcat(identity)
            .map { case (byteString, context) => (byteString, Tail(context)) }

          SourceWithContext.fromTuples(
            Source.combine(
              firstSource.asSource,
              restTransformed
            )(Concat(_))
          )
        case (Seq(only: Element), _) =>
          // This case can also occur when user terminates the stream
          val key = calculateKey(only.reducedConsumerRecord.toOffsetDateTime,
                                 backupConfig.timeConfiguration,
                                 backupConfig.compression
          )
          transformFirstElement(only, key, terminate = false)
        case (rest, _) =>
          throw Errors.UnhandledStreamCase(rest)
      }

    @nowarn("msg=method lazyInit in object Sink is deprecated")
    val subFlowSink = substreams
      .alsoTo(
        // See https://stackoverflow.com/questions/68774425/combine-prefixandtail1-with-sink-lazysink-for-subflow-created-by-splitafter/68776660?noredirect=1#comment121558518_68776660
        Sink.lazyInit(
          {
            case (_, start: Start) =>
              implicit val ec: ExecutionContext = system.dispatcher
              logger.debug(s"Calling getCurrentUploadState with key:${start.key}")
              val f = for {
                uploadStateResult <- getCurrentUploadState(start.key)
                _ = logger.debug(s"Received $uploadStateResult from getCurrentUploadState with key:${start.key}")
                _ <- (uploadStateResult.previous, uploadStateResult.current) match {
                       case (Some(previous), None) =>
                         compressSource(terminateSource)
                           .runWith(backupToStorageTerminateSink(previous))
                           .map(Some.apply)(ExecutionContext.parasitic)
                       case _ => Future.successful(None)
                     }
              } yield prepareStartOfStream(uploadStateResult, start)

              // TODO This is temporary until https://github.com/aiven/guardian-for-apache-kafka/issues/221 is resolved.
              // Since SubFlow currently ignores any given supervision strategy we have to use a
              // SubstreamCancelStrategy.propagate however the exception message is still hence why we need to manually
              // log it.
              f.onComplete {
                case Failure(e) =>
                  logger.error("Unhandled exception in stream", e)
                case Success(_) =>
              }

              f
            case _ => throw Errors.ExpectedStartOfSource
          },
          empty
        )
      )
      .mergeSubstreamsWithParallelism(1)

    subFlowSink.toMat(Sink.ignore)(kafkaClientInterface.matCombine)
  }
}

object BackupClientInterface {
  def reducedConsumerRecordAsString(reducedConsumerRecord: ReducedConsumerRecord): String =
    io.circe.Printer.noSpaces.print(reducedConsumerRecord.asJson)

  def formatOffsetDateTime(offsetDateTime: OffsetDateTime): String =
    offsetDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

  /** Calculate an object storage key or filename for a ReducedConsumerRecord
    * @param offsetDateTime
    *   A given time
    * @return
    *   A `String` that can be used either as some object key or a filename
    */
  def calculateKey(offsetDateTime: OffsetDateTime,
                   timeConfiguration: TimeConfiguration,
                   maybeCompression: Option[Compression]
  ): String = {
    val finalTime = timeConfiguration match {
      case ChronoUnitSlice(chronoUnit) => offsetDateTime.truncatedTo(chronoUnit)
      case _                           => offsetDateTime
    }

    val extension = maybeCompression match {
      case Some(_: Gzip) => "json.gz"
      case None          => "json"
    }

    s"${BackupClientInterface.formatOffsetDateTime(finalTime)}.$extension"
  }

  /** Calculates whether we have rolled over a time period given number of divided periods.
    * @param dividedPeriodsBefore
    *   The number of divided periods in the first element of the slide. -1 is used as a sentinel value to indicate the
    *   start of the stream
    * @param dividedPeriodsAfter
    *   The number of divided periods in the second element of the slide
    * @return
    *   `true` if we have hit a time boundary otherwise `false`
    */
  def isAtBoundary(dividedPeriodsBefore: Long, dividedPeriodsAfter: Long): Boolean =
    (dividedPeriodsBefore, dividedPeriodsAfter) match {
      case (before, after) if after > before =>
        true
      case _ =>
        false
    }

  protected def calculateNumberOfPeriodsFromTimestamp(initialTime: OffsetDateTime,
                                                      timeConfiguration: TimeConfiguration,
                                                      reducedConsumerRecord: ReducedConsumerRecord
  ): Long = {
    val (period, finalInitialTime) = timeConfiguration match {
      case PeriodFromFirst(duration) => (duration, initialTime)
      case ChronoUnitSlice(chronoUnit) =>
        (chronoUnit.getDuration.toScala, initialTime.truncatedTo(chronoUnit))
    }

    // TODO handle overflow?
    ChronoUnit.MICROS.between(finalInitialTime, reducedConsumerRecord.toOffsetDateTime) / period.toMicros
  }
}
