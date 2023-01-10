package prometheus4cats.fs2kafka

import scala.collection.{immutable, mutable}

object IterableUtils {
  private[fs2kafka] def groupMapReduce[K, A, B](
      col: Iterable[A]
  )(key: A => K)(f: A => B)(reduce: (B, B) => B): immutable.Map[K, B] = {
    val m = mutable.Map.empty[K, B]
    for (elem <- col) {
      val k = key(elem)
      val v =
        m.get(k) match {
          case Some(b) => reduce(b, f(elem))
          case None    => f(elem)
        }
      m.put(k, v)
    }
    m.toMap
  }
}
