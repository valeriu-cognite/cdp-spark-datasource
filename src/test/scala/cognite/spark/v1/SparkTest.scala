package cognite.spark.v1

import java.io.IOException

import com.codahale.metrics.Counter
import com.cognite.sdk.scala.common.{ApiKeyAuth, Auth}
import org.apache.spark.sql.SparkSession
import org.scalatest.Tag
import org.apache.spark.datasource.MetricsSource

import scala.concurrent.{ExecutionContext, TimeoutException}
import scala.concurrent.duration._
import scala.util.Random
import cats.effect.{IO, Timer}
import cats.implicits._
import com.cognite.sdk.scala.v1._

object ReadTest extends Tag("ReadTest")
object WriteTest extends Tag("WriteTest")
object GreenfieldTest extends Tag("GreenfieldTest")

trait SparkTest extends CdpConnector {
  implicit lazy val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  val writeApiKey = System.getenv("TEST_API_KEY_WRITE")
  implicit val writeApiKeyAuth: ApiKeyAuth = ApiKeyAuth(writeApiKey)
  val writeClient = Client("cdp-spark-datasource-test")(writeApiKeyAuth, implicitly)

  val readApiKey = System.getenv("TEST_API_KEY_READ")
  implicit val readApiKeyAuth: ApiKeyAuth = ApiKeyAuth(readApiKey)
  val readClient = Client("cdp-spark-datasource-test")(readApiKeyAuth, implicitly)

  val spark: SparkSession = SparkSession
    .builder()
    .master("local[*]")
    .config("spark.ui.enabled", "false")
    // https://medium.com/@mrpowers/how-to-cut-the-run-time-of-a-spark-sbt-test-suite-by-40-52d71219773f
    .config("spark.sql.shuffle.partitions", "1")
    .config("spark.app.id", this.getClass.getName + math.floor(math.random * 1000).toLong.toString)
    .getOrCreate()

  // scalastyle:off cyclomatic.complexity
  def retryWithBackoff[A](ioa: IO[A], initialDelay: FiniteDuration, maxRetries: Int): IO[A] = {
    val exponentialDelay = (Constants.DefaultMaxBackoffDelay / 2).min(initialDelay * 2)
    val randomDelayScale = (Constants.DefaultMaxBackoffDelay / 2).min(initialDelay * 2).toMillis
    val nextDelay = Random.nextInt(randomDelayScale.toInt).millis + exponentialDelay
    ioa.handleErrorWith {
      case exception @ (_: TimeoutException | _: IOException) =>
        if (maxRetries > 0) {
          IO.sleep(initialDelay) *> retryWithBackoff(ioa, nextDelay, maxRetries - 1)
        } else {
          IO.raiseError(exception)
        }
      case error => IO.raiseError(error)
    }
  }
  // scalastyle:on cyclomatic.complexity

  def retryWhile[A](action: => A, shouldRetry: A => Boolean): A =
    retryWithBackoff(
      IO {
        val actionValue = action
        if (shouldRetry(actionValue)) {
          throw new TimeoutException("Retry")
        }
        actionValue
      },
      Constants.DefaultInitialRetryDelay,
      Constants.DefaultMaxRetries
    ).unsafeRunSync()

  def getDefaultConfig(auth: Auth): RelationConfig =
    RelationConfig(
      auth,
      Some(Constants.DefaultBatchSize),
      None,
      Constants.DefaultPartitions,
      Constants.DefaultMaxRetries,
      false,
      "",
      Constants.DefaultBaseUrl,
      OnConflict.ABORT,
      spark.sparkContext.applicationId,
      Constants.DefaultParallelismPerPartition
    )

  def getNumberOfRowsRead(metricsPrefix: String, resourceType: String): Long =
    MetricsSource
      .metricsMap(s"$metricsPrefix.$resourceType.read")
      .value
      .asInstanceOf[Counter]
      .getCount
}
