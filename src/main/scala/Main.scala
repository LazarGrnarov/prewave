import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import io.circe.syntax.EncoderOps
import sttp.client3.circe._
import sttp.client3.{HttpClientSyncBackend, Identity, SttpBackend, UriContext, basicRequest}
import org.apache.logging.log4j.{LogManager, Logger}
import scala.jdk.CollectionConverters._

import java.nio.file.{Files, Paths, StandardOpenOption}
import java.nio.charset.StandardCharsets
import java.time.Instant

object Main extends App {

  private val logger = LogManager.getLogger(Main.getClass)


  // Our domain models
  case class QueryTermResponse(id: Long, target: Int, text: String, language: String, keepOrder: Boolean)

  case class AlertTerms(id: String, contents: List[AlertTerm], date: Instant, inputType: String)

  case class AlertTerm(text: String, `type`: String, language: String)

  case class MatchedTerm(alertId: String, queryTermId: Long, keepOrder: Boolean)

  implicit val QueryTermDecoder: Decoder[QueryTermResponse] = deriveDecoder[QueryTermResponse]
  implicit val AlertTermDecoder: Decoder[AlertTerm] = deriveDecoder[AlertTerm]
  implicit val AlertTermsDecoder: Decoder[AlertTerms] = deriveDecoder[AlertTerms]
  implicit val MatchedTermDecoder: Decoder[MatchedTerm] = deriveDecoder[MatchedTerm]
  implicit val MatchedTermEncoder: Encoder[MatchedTerm] = deriveEncoder[MatchedTerm]

  // config "object", would be read from the config file in resources
  val numberOfQueryRuns = 10
  // api client config
  val key = "***REMOVED***"
  val testQueryTermUrl = uri"https://services.prewave.ai/adminInterface/api/testQueryTerm?key=$key"
  val testAlertsUrl = uri"https://services.prewave.ai/adminInterface/api/testAlerts?key=$key"


  // Blocking client since we don't really do anything else, so async is rather pointless
  // In a real world implementation this would be some sort of async since there would be much more expensive computations
  // as well as db and/or network calls, so it would make a lot more sense to use some sort of non-blocking client
  val backend: SttpBackend[Identity, Any] = HttpClientSyncBackend()

  run(numberOfQueryRuns).foreach(persistResults)


  private def persistResults(matchedTerms: Set[MatchedTerm]): Unit = {
    val filePath = Paths.get("src/main/resources/matched_terms.json")
    Files.createDirectories(filePath.getParent)

    val matchedSet = Files.readAllLines(filePath)
      .asScala
      .flatMap(s => decode[MatchedTerm](s).toOption)
      .toSet

    // don't save duplicates
    matchedTerms.removedAll(matchedSet).foreach { matchedTerm =>
      val json = matchedTerm.asJson.noSpaces + "\n"
      Files.write(filePath, json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)

      logger.info(s"Appended JSON to ${filePath}")
    }
  }

  private def run(numberOfRuns: Int) = for {
    n <- (1 to numberOfRuns)
  } yield {
    logger.info(s"Starting run $n")
    val response = basicRequest
      .get(testQueryTermUrl)
      .response(asJson[List[QueryTermResponse]])
      .send(backend)

    val queryTerms = response.body match {
      case Left(error) =>
        logger.error(error.getMessage)
        Map.empty[String, Iterable[QueryTermResponse]]
      case Right(terms) => processTerms(terms)
    }

    val queryTermsById = queryTerms.values.flatten.groupBy(c => c.id)

    val alertsResponse = basicRequest
      .get(testAlertsUrl)
      .response(asJson[List[AlertTerms]])
      .send(backend)

    alertsResponse.body match {
      case Left(e) =>
        logger.error(e.getMessage)
        Set.empty[MatchedTerm]
      case Right(alerts) =>
        val matches = checkTerms(queryTerms, alerts).toSet
        val groupedAlerts = alerts.groupBy(_.id)
        matches
          .foreach(m => logger.info(s"${groupedAlerts(m.alertId).flatMap(_.contents.map(_.text)).mkString("")} ->  ${queryTermsById(m.queryTermId).map(_.text).mkString(", ")}"))
        matches
    }
  }


  private def checkTerms(terms: Map[String, Iterable[QueryTermResponse]], alerts: List[AlertTerms]) =
    alerts.flatMap(alert => alert.contents.flatMap(c => checkTerm(terms, c, alert.id)))

  private def checkTerm(termsMap: Map[String, Iterable[QueryTermResponse]], alertTerm: AlertTerm, alertId: String) = {
    termsMap.get(alertTerm.language) match {
      case Some(terms) =>
        val (keepOrder, noOrder) = terms.partition(_.keepOrder)
        keepOrder.filter(q => alertTerm.text.contains(q.text)).map(c => MatchedTerm(alertId = alertId, queryTermId = c.id, keepOrder = true)) ++
          noOrder.flatMap(q => q.text.split(" ").map(c => (q.id, c))).filter { case (_, term) => alertTerm.text.contains(term) }
            .map(c => MatchedTerm(alertId = alertId, queryTermId = c._1, keepOrder = false))
      case None =>
        logger.warn(s"Empty terms list for language ${alertTerm.language}")
        List.empty
    }
  }

  private def processTerms(terms: List[QueryTermResponse]) = {
    terms
      .groupBy(_.text).map { case (_, responses) => responses.head } // filter duplicate terms
      .groupBy(term => term.language) // group by language
  }
}
