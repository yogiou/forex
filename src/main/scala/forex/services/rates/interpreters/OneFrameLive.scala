package forex.services.rates.interpreters

import cats.effect.Sync
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.either._
import forex.domain.{ Currency, Price, Rate, Timestamp }
import forex.services.rates.Algebra
import forex.services.rates.errors._
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import org.http4s._
import org.http4s.circe.jsonOf
import org.http4s.client.Client

import fs2.text.utf8Decode

import java.time.{ LocalDateTime, OffsetDateTime, ZoneOffset }

/** One-Frame API response item:
  * {"from":"USD","to":"JPY","bid":0.61,"ask":0.82,"price":0.71,"time_stamp":"2019-01-01T00:00:00.000"}
  */
private[interpreters] final case class OneFrameRate(
    from: String,
    to: String,
    price: BigDecimal,
    time_stamp: String
)

private[interpreters] object OneFrameRate {
  implicit val decoder: Decoder[OneFrameRate] = deriveDecoder[OneFrameRate]
}

class OneFrameLive[F[_]: Sync](client: Client[F], baseUrl: String, token: String) extends Algebra[F] {

  private implicit def oneFrameListDecoder: org.http4s.EntityDecoder[F, List[OneFrameRate]] =
    jsonOf[F, List[OneFrameRate]]

  /** Single-pair lookup — delegates to getAll and extracts the one result. */
  override def get(pair: Rate.Pair): F[Error Either Rate] =
    getAll(List(pair)).map(
      _.flatMap(
        _.get(pair).toRight(Error.OneFrameLookupFailed(s"One-Frame returned no rate for $pair"))
      )
    )

  /** Fetches all given pairs in a single One-Frame request (one HTTP call regardless of list size). Returns a map of
    * pair → rate on success, or Left if the request fails. One-Frame accepts multiple `pair=` query parameters in a
    * single call.
    */
  def getAll(pairs: List[Rate.Pair]): F[Error Either Map[Rate.Pair, Rate]] = {
    val pairStrs = pairs.map(p => s"${Currency.show.show(p.from)}${Currency.show.show(p.to)}")
    Uri.fromString(s"$baseUrl/rates").map(_.withQueryParam("pair", pairStrs)) match {
      case Left(e) =>
        Sync[F].pure(Error.OneFrameLookupFailed(e.message).asLeft)
      case Right(uri) =>
        val request = Request[F](Method.GET, uri).putHeaders("token" -> token)
        val useBlock: Response[F] => F[Error Either Map[Rate.Pair, Rate]] = { resp =>
          if (resp.status.isSuccess) {
            resp.as[List[OneFrameRate]].flatMap { list =>
              val pairIndex = pairs.map(p => s"${Currency.show.show(p.from)}${Currency.show.show(p.to)}" -> p).toMap
              val parsed    = list.map(raw => toDomain(raw, pairIndex))
              val errors    = parsed.collect { case Left(e) => e }
              if (errors.nonEmpty)
                Sync[F].pure(Error.OneFrameLookupFailed(errors.map(_.toString).mkString("; ")).asLeft)
              else
                Sync[F].pure(parsed.collect { case Right(r) => r.pair -> r }.toMap.asRight)
            }
          } else {
            resp.body.through(utf8Decode).compile.foldMonoid.map { body =>
              val msg = s"One-Frame ${resp.status}: $body"
              if (resp.status.code == 429 || resp.status.code == 403)
                Error.OneFrameRateLimitExceeded(msg).asLeft
              else
                Error.OneFrameLookupFailed(msg).asLeft
            }
          }
        }
        Sync[F].handleErrorWith(client.run(request).use(useBlock)) { t =>
          Sync[F].pure(Error.OneFrameLookupFailed(t.getMessage).asLeft)
        }
    }
  }

  private def toDomain(raw: OneFrameRate, pairIndex: Map[String, Rate.Pair]): Error Either Rate = {
    val key = s"${raw.from}${raw.to}"
    for {
      pair <- pairIndex.get(key).toRight(Error.OneFrameLookupFailed(s"Unexpected pair from One-Frame: $key"))
      ts <- parseTimestamp(raw.time_stamp)
              .leftMap(msg => Error.OneFrameLookupFailed(s"Invalid One-Frame timestamp: $msg"))
    } yield Rate(pair, Price(raw.price), Timestamp(ts))
  }

  private def parseTimestamp(s: String): Either[String, OffsetDateTime] =
    Either
      .catchNonFatal(OffsetDateTime.parse(s))
      .orElse(Either.catchNonFatal(LocalDateTime.parse(s).atOffset(ZoneOffset.UTC)))
      .leftMap(_.getMessage)
}
