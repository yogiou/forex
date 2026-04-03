package forex.services.rates

object errors {

  sealed trait Error
  object Error {
    final case class OneFrameLookupFailed(msg: String) extends Error

    /** One-Frame returned 429 (or 403 quota); daily request limit exceeded. */
    final case class OneFrameRateLimitExceeded(msg: String) extends Error

    /** Cached rate exists but is older than the freshness limit. */
    final case class StaleRate(msg: String) extends Error

    /** Cache has not been populated yet (e.g. first refresh hasn't completed). */
    final case class CacheNotReady(msg: String) extends Error
  }
}
