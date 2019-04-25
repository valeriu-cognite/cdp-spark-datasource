package com.cognite.spark.datasource

import com.softwaremill.sttp._
import org.apache.spark.sql.{DataFrame, Row}
import org.scalatest.{FlatSpec, Matchers}
import org.apache.spark.sql.functions._
import io.circe.generic.auto._

class AssetsRelationTest extends FlatSpec with Matchers with SparkTest {
  val readApiKey = ApiKeyAuth(System.getenv("TEST_API_KEY_READ"))
  val writeApiKey = ApiKeyAuth(System.getenv("TEST_API_KEY_WRITE"))

  it should "read assets" taggedAs ReadTest in {
    val df = spark.read.format("com.cognite.spark.datasource")
      .option("apiKey", readApiKey.apiKey)
      .option("type", "assets")
      .option("limit", "1000")
      .option("partitions", "1")
      .load()

    df.createTempView("assets")
    val res = spark.sql("select * from assets")
      .collect()
    assert(res.length == 1000)
  }

  it should "read assets with a small batchSize" taggedAs ReadTest in {
    val df = spark.read.format("com.cognite.spark.datasource")
      .option("apiKey", readApiKey.apiKey)
      .option("type", "assets")
      .option("batchSize", "1")
      .option("limit", "10")
      .option("partitions", "1")
      .load()

    assert(df.count() == 10)
  }

  it should "be possible to create assets" taggedAs WriteTest in {
    val assetsTestSource = "assets-relation-test-create"
    val df = spark.read.format("com.cognite.spark.datasource")
      .option("apiKey", writeApiKey.apiKey)
      .option("type", "assets")
      .load()
    df.createOrReplaceTempView("assets")
    cleanupAssets(assetsTestSource)
    retryWhile[Array[Row]](
      spark.sql(s"select * from assets where source = '$assetsTestSource'").collect,
      rows => rows.length > 0)
    spark.sql(
      s"""
        |select null as id,
        |null as path,
        |null as depth,
        |'asset name' as name,
        |null as parentId,
        |'asset description' as description,
        |null as types,
        |null as metadata,
        |'$assetsTestSource' as source,
        |null as sourceId,
        |null as createdTime,
        |null as lastUpdatedTime
      """.stripMargin)
      .write
      .insertInto("assets")
    retryWhile[Array[Row]](
      spark.sql(s"select * from assets where source = '$assetsTestSource'").collect,
      rows => rows.length < 1)
  }

  it should "be possible to copy assets from one tenant to another" taggedAs WriteTest in {
    val assetsTestSource = "assets-relation-test-copy"
    val sourceDf = spark.read.format("com.cognite.spark.datasource")
      .option("apiKey", readApiKey.apiKey)
      .option("type", "assets")
      .load()
    sourceDf.createOrReplaceTempView("source_assets")
    val df = spark.read.format("com.cognite.spark.datasource")
      .option("apiKey", writeApiKey.apiKey)
      .option("type", "assets")
      .load()
    df.createOrReplaceTempView("assets")
    cleanupAssets(assetsTestSource)
    retryWhile[Array[Row]](
      spark.sql(s"select * from assets where source = '$assetsTestSource'").collect,
      rows => rows.length > 0)
    spark.sql(
      s"""
         |select id,
         |path,
         |depth,
         |name,
         |null as parentId,
         |description,
         |null as types,
         |metadata,
         |'$assetsTestSource' as source,
         |id as sourceId,
         |createdTime,
         |null as lastUpdatedTime
         |from source_assets where id = 2675073401706610
      """.stripMargin)
      .write
      .insertInto("assets")
    retryWhile[Array[Row]](
      spark.sql(s"select * from assets where source = '$assetsTestSource'").collect,
      rows => rows.length < 1)
  }

  it should "allow partial updates" taggedAs WriteTest in {
    val sourceDf = spark.read
      .format("com.cognite.spark.datasource")
      .option("apiKey", writeApiKey.apiKey)
      .option("type", "assets")
      .load()
      .where("name = 'upsertTestThree'")
    sourceDf.createTempView("update")
    val wdf = spark.sql("""
        |select 'upsertTestThree' as name, id from update
      """.stripMargin)

    wdf.write
      .format("com.cognite.spark.datasource")
      .option("apiKey", writeApiKey.apiKey)
      .option("type", "assets")
      .option("onconflict", "update")
      .save
  }

  it should "support some more partial updates" taggedAs WriteTest in {
    val source = "spark-assets-test"

    val sourceDf = spark.read
      .format("com.cognite.spark.datasource")
      .option("apiKey", readApiKey.apiKey)
      .option("type", "assets")
      .load()
    sourceDf.createTempView("sourceAssets")

    val destinationDf = spark.read
      .format("com.cognite.spark.datasource")
      .option("apiKey", writeApiKey.apiKey)
      .option("type", "assets")
      .load()
    destinationDf.createTempView("destinationAssets")

    // Cleanup assets
    cleanupAssets(source)
    val oldAssetsFromTestDf = retryWhile[Array[Row]](
      spark.sql(s"select * from destinationAssets where source = '$source'").collect,
      df => df.length > 0)
    assert(oldAssetsFromTestDf.length == 0)

    // Post new events
    spark
      .sql(s"""
                 |select id,
                 |path,
                 |depth,
                 |name,
                 |null as parentId,
                 |null as types,
                 |description,
                 |metadata,
                 |"$source" as source,
                 |sourceId,
                 |createdTime,
                 |lastUpdatedTime
                 |from sourceAssets
                 |limit 100
     """.stripMargin)
      .select(destinationDf.columns.map(col): _*)
      .write
      .insertInto("destinationAssets")

    // Check if post worked
    val assetsFromTestDf = retryWhile[Array[Row]](
      spark.sql(s"select * from destinationAssets where source = '$source'").collect,
      df => df.length < 100)
    assert(assetsFromTestDf.length == 100)

    val description = "spark-testing-description"

    // Update assets
    spark
      .sql(s"""
                 |select '$description' as description,
                 |id from destinationAssets
                 |where source = '$source'
     """.stripMargin)
      .write
      .format("com.cognite.spark.datasource")
      .option("apiKey", writeApiKey.apiKey)
      .option("type", "assets")
      .option("onconflict", "update")
      .save

    // Check if update worked
    val assetsWithNewNameDf = retryWhile[Array[Row]](
      spark.sql(s"select * from destinationAssets where description = '$description'").collect,
      df => df.length < 100)
    assert(assetsFromTestDf.length == 100)
  }

  it should "succesfully read and write asset types" taggedAs WriteTest in {
    // Clean up old test data
    val source = "assets-relation-test-asset-types"
    cleanupAssets(source)

    // Get the id of the pump asset generated by scripts/createAssetType.py
    val project = getProject(writeApiKey, Constants.DefaultMaxRetries, Constants.DefaultBaseUrl)
    val getTypesUrl = uri"https://api.cognitedata.com/api/0.6/projects/$project/assets/types"
    case class Type(id: Long, name: String, description: String, fields: Seq[Field])
    case class Field(id: Long, name: String, description: String, valueType: String)
    val assetTypesResponse =
      getJson[Data[Items[Type]]](writeApiKey, getTypesUrl, Constants.DefaultMaxRetries)
        .unsafeRunSync()

    val pumpAssetType = assetTypesResponse.data.items.filter(_.name == "pump").head
    val pumpAssetTypeId = pumpAssetType.id
    val pumpAssetvalueTypesMap = pumpAssetType.fields.map(f => (f.valueType, f.id)).toMap

    // Create an asset of the pump asset type
    val assetTypesJsonString =
      s"""{
         |"id": null,
         |"path": [0],
         |"depth": 1,
         |"name": "typeTest",
         |"description": "This is just a test asset for testing asset types",
         |"types": [
         |{
         |    "id": $pumpAssetTypeId,
         |    "fields": [
         |        {
         |            "id": ${pumpAssetvalueTypesMap("Long")},
         |            "value": "965486583218"
         |        },
         |        {
         |            "id": ${pumpAssetvalueTypesMap("Boolean")},
         |            "value": "False"
         |        },
         |        {
         |            "id": ${pumpAssetvalueTypesMap("String")},
         |            "value": "Asset types testing"
         |        },
         |        {
         |            "id": ${pumpAssetvalueTypesMap("Double")},
         |            "value": "12912.12"
         |        }
         |    ]
         |}
         |],
         |"source": "$source",
         |"sourceId": "Some sixth source",
         |"createdTime": null,
         |"lastUpdatedTime": null
         |}
      """.stripMargin

    import spark.implicits._
    val assetTypesDataSet =
      spark.createDataset(assetTypesJsonString :: Nil)
    val assetTypesDataFrame = spark.read.json(assetTypesDataSet)

    assetTypesDataFrame
      .write
      .format("com.cognite.spark.datasource")
      .option("apiKey", writeApiKey.apiKey)
      .option("type", "assets")
      .save()

    // Read the data back to a Spark DataFrame
    val assetsDf = spark.read
      .format("com.cognite.spark.datasource")
      .option("apiKey", writeApiKey.apiKey)
      .option("type", "assets")
      .option("limit", "1000")
      .option("partitions", "1")
      .load()
      .filter(s"size(types) > 0")

    // Check that data has all the valueTypes
    val valueTypes = assetsDf
      .select(explode(col("types")))
      .select(explode(col("col.fields")))
      .select("col.valueType", "col.id")
      .rdd
      .map(r => (r(0).toString, r(1).toString.toLong))
      .collect()

    assert(Set("Double", "Boolean", "String", "Long").subsetOf(valueTypes.map(_._1).toSet))

  }

  def cleanupAssets(source: String): Unit = {
    import AssetsRelation.fieldDecoder // overwrite the implicit derived decoder from circe
    val project = getProject(writeApiKey, Constants.DefaultMaxRetries, Constants.DefaultBaseUrl)

    val assets = get[AssetsItem](
      writeApiKey,
      uri"https://api.cognitedata.com/api/0.6/projects/$project/assets?source=$source",
      batchSize = 1000,
      limit = None,
      maxRetries = 10)

    val assetIdsChunks = assets.map(_.id).grouped(1000)
    val AssetIdNotFound = "^(Asset ids not found).+".r
    for (assetIds <- assetIdsChunks) {
      try {
        post(
          writeApiKey,
          uri"https://api.cognitedata.com/api/0.6/projects/$project/assets/delete",
          assetIds,
          10
        ).unsafeRunSync()
      } catch {
        case CdpApiException(_, 400, AssetIdNotFound(_)) => // ignore exceptions about already deleted items
      }
    }
  }
}
