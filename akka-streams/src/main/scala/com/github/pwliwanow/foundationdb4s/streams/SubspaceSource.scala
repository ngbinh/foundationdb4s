package com.github.pwliwanow.foundationdb4s.streams

import akka.NotUsed
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream.Supervision.Decider
import akka.stream.scaladsl.Source
import akka.stream.{Attributes, Outlet, SourceShape, Supervision}
import akka.stream.stage.{AsyncCallback, GraphStage, GraphStageLogic, OutHandler}
import com.apple.foundationdb._
import com.github.pwliwanow.foundationdb4s.core.{
  RefreshingSubspaceStream,
  Transactor,
  TypedSubspace
}

import scala.util.Try

/** Factories to create sources from provided subspace.
  *
  * SubspaceSource does not guarantee to stream all data within a single transaction.
  * It can be useful when data within a subspace is immutable and append-only,
  * or if approximation is good enough.
  *
  * SubspaceSource will:
  * - complete when all elements were produced
  * - emit failure when [[KeyValue]] cannot be converted into entity
  * - fail stage if it cannot connect to the database
  */
object SubspaceSource {
  private val MaxAllowedNumberOfRestartsWithoutProgress = 3

  def from[Entity, KeyRepr](
      subspace: TypedSubspace[Entity, KeyRepr],
      transactor: Transactor): Source[Entity, NotUsed] = {
    val createStream = () => RefreshingSubspaceStream.fromTypedSubspace(subspace, transactor)
    Source.fromGraph(new SubspaceSource[Entity](createStream))
  }

  def from[Entity, KeyRepr](
      subspace: TypedSubspace[Entity, KeyRepr],
      transactor: Transactor,
      begin: KeySelector): Source[Entity, NotUsed] = {
    val createStream = () => RefreshingSubspaceStream.fromTypedSubspace(subspace, transactor, begin)
    Source.fromGraph(new SubspaceSource[Entity](createStream))
  }

  def from[Entity, KeyRepr](
      subspace: TypedSubspace[Entity, KeyRepr],
      transactor: Transactor,
      begin: KeySelector,
      end: KeySelector,
      reverse: Boolean = false,
      streamingMode: StreamingMode = StreamingMode.MEDIUM): Source[Entity, NotUsed] = {
    val createStream = () =>
      RefreshingSubspaceStream.fromTypedSubspace(
        subspace,
        transactor,
        begin,
        end,
        reverse,
        streamingMode,
        MaxAllowedNumberOfRestartsWithoutProgress)
    Source.fromGraph(new SubspaceSource[Entity](createStream))
  }
}

private final class SubspaceSource[Entity](createStream: () => RefreshingSubspaceStream[Entity])
    extends GraphStage[SourceShape[Entity]] {

  private val out: Outlet[Entity] = Outlet("SubspaceSource.out")

  override def shape: SourceShape[Entity] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new GraphStageLogic(shape) { stage =>
      def decider: Decider =
        inheritedAttributes
          .get[SupervisionStrategy]
          .map(_.decider)
          .getOrElse(Supervision.stoppingDecider)

      private var underlyingStream: RefreshingSubspaceStream[Entity] = _

      override def preStart(): Unit = underlyingStream = createStream()

      override def postStop(): Unit = underlyingStream.close()

      setHandler(out, new OutHandler {
        override def onPull(): Unit = stage.onPull()
      })

      private def onPull(): Unit = {
        val pushCallback = createPushCallback()
        val failStageCallback = createFailStageCallback()
        underlyingStream
          .onHasNext()
          .map(hasNext => pushCallback.invoke(hasNext))(materializer.executionContext)
          .recover { case t => failStageCallback.invoke(t) }(materializer.executionContext)
        ()
      }

      private def createPushCallback(): AsyncCallback[Boolean] = getAsyncCallback[Boolean] {
        hasNext =>
          if (hasNext) {
            Try(underlyingStream.next())
              .map(push(out, _))
              .recover {
                case t =>
                  decider(t) match {
                    case Supervision.Stop => failStage(t)
                    case _                => onPull()
                  }
              }
              .get
          } else {
            completeStage()
          }
      }

      private def createFailStageCallback(): AsyncCallback[Throwable] =
        getAsyncCallback[Throwable](failStage)
    }
  }
}
