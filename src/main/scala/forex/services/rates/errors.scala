package forex.services.rates

object errors {

  sealed trait Error extends Exception
  object Error {
    final case class OneFrameLookupFailed(msg: String) extends Error

    /** One-Frame returned 429 (or 403 quota); daily request limit exceeded. */
    final case class OneFrameRateLimitExceeded(msg: String) extends Error
  }
}
