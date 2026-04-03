package forex.config

import cats.effect.Sync
import fs2.Stream
import pureconfig.ConfigSource
import pureconfig.generic.auto._

object Config {

  /** @param path
    *   the property path inside the default configuration
    */
  def stream[F[_]: Sync](path: String): Stream[F, ApplicationConfig] =
    Stream.eval(
      Sync[F].delay {
        val config = ConfigSource.default.at(path).loadOrThrow[ApplicationConfig]
        require(
          config.oneframe.cacheTtlMinutes >= 2 && config.oneframe.cacheTtlMinutes <= 5,
          s"oneframe.cache-ttl-minutes must be between 2 and 5 (was ${config.oneframe.cacheTtlMinutes}). " +
            "Below 2: 1440/day exceeds One-Frame's 1000/day limit. Above 5: violates the 5-minute freshness requirement."
        )
        config
      }
    )
}
