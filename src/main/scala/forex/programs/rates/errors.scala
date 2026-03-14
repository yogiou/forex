package forex.programs.rates

import forex.services.rates.errors.{ Error => RatesServiceError }

object errors {

  sealed trait Error extends Exception
  object Error {
    final case class RateLookupFailed(msg: String) extends Error

    /** One-Frame daily limit exceeded (429/403). */
    final case class RateLimitExceeded(msg: String) extends Error
  }

  def toProgramError(error: RatesServiceError): Error = error match {
    case RatesServiceError.OneFrameLookupFailed(msg)      => Error.RateLookupFailed(msg)
    case RatesServiceError.OneFrameRateLimitExceeded(msg) => Error.RateLimitExceeded(msg)
  }
}
