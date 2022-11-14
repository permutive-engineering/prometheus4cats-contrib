package prometheus4cats.refreshable

import cats.effect.MonadCancelThrow
import cats.effect.kernel.Resource
import com.permutive.refreshable.Refreshable
import prometheus4cats.MetricFactory

private[refreshable] trait RefreshableBuilderSyntax {
  implicit class InstrumentedRefreshableBuilder[F[_], A](
      builder: Refreshable.RefreshableBuilder[F, A]
  ) {
    def instrumentedResource(name: String, metricFactory: MetricFactory[F])(
        implicit F: MonadCancelThrow[F]
    ): Resource[F, InstrumentedRefreshable[F, A]] =
      InstrumentedRefreshable.create(builder, name, metricFactory)
  }
}
