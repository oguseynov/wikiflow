package com.renarde.wikiflow.consumer

import com.typesafe.scalalogging.LazyLogging
import org.apache.spark.sql.functions.{from_json, _}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, SparkSession}

object AnalyticsConsumer extends App with LazyLogging {

  val appName: String = "analytics-consumer-example"

  val spark: SparkSession = SparkSession.builder()
    .appName(appName)
    .config("spark.driver.memory", "5g")
    .master("local[2]")
    .getOrCreate()
  spark.sparkContext.setLogLevel("WARN")

  logger.info("Initializing Structured consumer")

  import spark.implicits._

  val inputStream = spark.readStream
    .format("kafka")
    .option("kafka.bootstrap.servers", "kafka:9092")
    .option("subscribe", "wikiflow-topic")
    .option("startingOffsets", "earliest")
    .load()

  val preparedDS = inputStream.selectExpr("CAST(key AS STRING)", "CAST(value AS STRING)").as[(String, String)]

  val rawData = preparedDS.filter($"value".isNotNull)

  val expectedSchema = new StructType()
    .add(StructField("bot", BooleanType))
    .add(StructField("comment", StringType))
    .add(StructField("id", LongType))
    .add("length", new StructType()
      .add(StructField("new", LongType))
      .add(StructField("old", LongType))
    )
    .add("meta", new StructType()
      .add(StructField("domain", StringType))
      .add(StructField("dt", StringType))
      .add(StructField("id", StringType))
      .add(StructField("offset", LongType))
      .add(StructField("partition", LongType))
      .add(StructField("request_id", StringType))
      .add(StructField("stream", StringType))
      .add(StructField("topic", StringType))
      .add(StructField("uri", StringType))
    )
    .add("minor", BooleanType)
    .add("namespace", LongType)
    .add("parsedcomment", StringType)
    .add("patrolled", BooleanType)
    .add("revision", new StructType()
      .add("new", LongType)
      .add("old", LongType)
    )
    .add("server_name", StringType)
    .add("server_script_path", StringType)
    .add("server_url", StringType)
    .add("timestamp", TimestampType)
    .add("title", StringType)
    .add("type", StringType)
    .add("user", StringType)
    .add("wiki", StringType)

  val parsedData = rawData.select(from_json($"value", expectedSchema).as("data")).select("data.*")

  import spark.implicits._

  val windowedCounts = parsedData.withWatermark("timestamp", "4 minutes")
    .groupBy(
      window($"timestamp", "4 minutes", "2 minutes"),
      $"user", $"meta.topic")
    .count()

  windowedCounts.writeStream
    .foreachBatch { (batchDF: DataFrame, _: Long) =>
      batchDF.persist()
      batchDF.write.mode("append").format("console").save()
      batchDF.write.mode("append").format("delta")
        .option("checkpointLocation", "/storage/analytics/checkpoints")
        .save("/storage/analytics/output")
      batchDF.unpersist()
    }
    .start()

  spark.streams.awaitAnyTermination()
}
