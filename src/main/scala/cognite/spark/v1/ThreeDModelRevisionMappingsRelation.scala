package cognite.spark.v1

import cats.effect.IO
import cognite.spark.v1.SparkSchemaHelper._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Row, SQLContext}
import com.cognite.sdk.scala.v1.{GenericClient, ThreeDAssetMapping}
import org.apache.spark.sql.sources.Filter
import fs2.Stream

class ThreeDModelRevisionMappingsRelation(config: RelationConfig, modelId: Long, revisionId: Long)(
    val sqlContext: SQLContext)
    extends SdkV1Relation[ThreeDAssetMapping, String](config, "3dmodelrevisionmappings") {
  override def schema: StructType = structType[ThreeDAssetMapping]

  override def toRow(t: ThreeDAssetMapping): Row = asRow(t)

  override def uniqueId(a: ThreeDAssetMapping): String = a.nodeId.toString + a.assetId.toString

  override def getStreams(filters: Array[Filter])(
      client: GenericClient[IO, Nothing],
      limit: Option[Int],
      numPartitions: Int): Seq[Stream[IO, ThreeDAssetMapping]] =
    Seq(client.threeDAssetMappings(modelId, revisionId).list(limit))
}
