package prometheus4cats.fs2kafka

import scala.collection.{immutable, mutable}

object IterableUtils {
  private[fs2kafka] def groupMapReduce[K, A, B](
      col: Iterable[A]
  )(key: A => K)(f: A => B)(reduce: (B, B) => B): immutable.Map[K, B] =
    col.groupMapReduce(key)(f)(reduce)
}
