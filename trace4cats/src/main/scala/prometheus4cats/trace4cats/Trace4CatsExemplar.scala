package prometheus4cats.trace4cats

import cats.Functor
import prometheus4cats.Exemplar
import trace4cats.Trace
import cats.syntax.functor._
import cats.syntax.show._
import prometheus4cats.trace4cats.Trace4CatsExemplar._
import trace4cats.model.SampleDecision

final class Trace4CatsExemplar[F[_]: Functor: Trace.WithContext](
    traceIdLabelName: Exemplar.LabelName,
    spanIdLabelName: Exemplar.LabelName
) extends Exemplar[F] {
  override def get: F[Option[Exemplar.Labels]] =
    Trace.WithContext[F].context.map { traceContext =>
      traceContext.traceFlags.sampled match {
        case SampleDecision.Drop => None
        case SampleDecision.Include =>
          Exemplar.Labels
            .of(
              traceIdLabelName -> traceContext.traceId.show,
              spanIdLabelName -> traceContext.spanId.show
            )
            .toOption
      }
    }
}

object Trace4CatsExemplar extends Trace4CatsExemplarInstances {
  val DefaultTraceIdLabelName = Exemplar.LabelName("trace_id")
  val DefaultSpanIdLabelName = Exemplar.LabelName("span_id")

  def apply[F[_]: Functor: Trace.WithContext](
      traceIdLabelName: Exemplar.LabelName = DefaultTraceIdLabelName,
      spanIdLabelName: Exemplar.LabelName = DefaultSpanIdLabelName
  ): Trace4CatsExemplar[F] =
    new Trace4CatsExemplar[F](traceIdLabelName, spanIdLabelName)

}

trait Trace4CatsExemplarInstances {
  implicit def trace4catsExemplarInstance[F[_]: Functor: Trace.WithContext]
      : Trace4CatsExemplar[F] = apply()
}
