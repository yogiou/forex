package forex.http
package rates

import java.time.OffsetDateTime

import forex.domain.Currency.show
import forex.domain.Rate.Pair
import forex.domain._
import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{ deriveConfiguredDecoder, deriveConfiguredEncoder }

object Protocol {

  implicit val configuration: Configuration = Configuration.default.withSnakeCaseMemberNames

  /** JSON body for API errors (descriptive for downstream clients). */
  final case class ApiError(message: String, code: Option[String] = None)

  final case class GetApiRequest(
      from: Currency,
      to: Currency
  )

  final case class GetApiResponse(
      from: Currency,
      to: Currency,
      price: Price,
      timestamp: Timestamp
  )

  implicit val currencyEncoder: Encoder[Currency] =
    Encoder.instance[Currency](show.show _ andThen Json.fromString)

  implicit val currencyDecoder: Decoder[Currency] =
    Decoder.decodeString.emap(s => Currency.fromStringOption(s).toRight(s"Unsupported currency: $s"))

  implicit val pairEncoder: Encoder[Pair] =
    deriveConfiguredEncoder[Pair]
  implicit val pairDecoder: Decoder[Pair] =
    deriveConfiguredDecoder[Pair]

  implicit val offsetDateTimeDecoder: Decoder[OffsetDateTime] =
    Decoder.decodeString.emap(s => scala.util.Try(OffsetDateTime.parse(s)).toEither.left.map(_.getMessage))

  implicit val priceDecoder: Decoder[Price] =
    Decoder.decodeBigDecimal.map(Price.apply).or(deriveConfiguredDecoder[Price])
  implicit val timestampDecoder: Decoder[Timestamp] =
    Decoder.decodeString
      .emap(s => scala.util.Try(OffsetDateTime.parse(s)).toEither.left.map(_.getMessage).map(Timestamp.apply))
      .or(deriveConfiguredDecoder[Timestamp])

  implicit val rateEncoder: Encoder[Rate] =
    deriveConfiguredEncoder[Rate]
  implicit val rateDecoder: Decoder[Rate] =
    deriveConfiguredDecoder[Rate]

  implicit val responseEncoder: Encoder[GetApiResponse] =
    deriveConfiguredEncoder[GetApiResponse]
  implicit val responseDecoder: Decoder[GetApiResponse] =
    deriveConfiguredDecoder[GetApiResponse]

  implicit val apiErrorEncoder: Encoder[ApiError] =
    deriveConfiguredEncoder[ApiError]
  implicit val apiErrorDecoder: Decoder[ApiError] =
    deriveConfiguredDecoder[ApiError]
}
