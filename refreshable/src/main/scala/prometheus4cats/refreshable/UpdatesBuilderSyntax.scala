package prometheus4cats.refreshable

import cats.effect.kernel.{MonadCancelThrow, Resource}
import com.permutive.refreshable.Refreshable
import prometheus4cats.MetricFactory

private[refreshable] trait UpdatesBuilderSyntax {
  implicit class InstrumentedUpdatesBuilder[F[_], A](
      builder: Refreshable.UpdatesBuilder[F, A]
  ) {
    def instrumentedResource(name: String, metricFactory: MetricFactory[F])(
        implicit F: MonadCancelThrow[F]
    ): Resource[F, InstrumentedRefreshable[F, A]] =
      InstrumentedRefreshable.Updates.create(builder, name, metricFactory)
  }
}
