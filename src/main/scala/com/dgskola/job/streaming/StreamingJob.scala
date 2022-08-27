package com.dgskola.job.streaming

import com.dgskola.wrapper.SparkSessionWrapper
import org.apache.spark.sql.execution.datasources.jdbc.JDBCOptions
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

object StreamingJob extends App with SparkSessionWrapper {

  val currentDirectory = new java.io.File(".").getCanonicalPath
  val kafkaReaderConfig = KafkaReaderConfig("localhost:29092", "dbserver1.inventory.orders")
  val jdbcConfig = JDBCConfig(url = "jdbc:postgresql://localhost:5432/postgres")
  new StreamingJobExecutor(spark, kafkaReaderConfig, currentDirectory + "/checkpoint/job", jdbcConfig).execute()
}

case class JDBCConfig(url: String, user: String = "postgres", password: String = "postgres", tableName: String = "orders_it")

case class KafkaReaderConfig(kafkaBootstrapServers: String, topics: String, startingOffsets: String = "latest")

case class StreamingJobConfig(checkpointLocation: String, kafkaReaderConfig: KafkaReaderConfig)

class StreamingJobExecutor(spark: SparkSession, kafkaReaderConfig: KafkaReaderConfig, checkpointLocation: String, jdbcConfig: JDBCConfig) {

  def execute(): Unit = {
    // read data from kafka and parse them
    val transformDF = read()
      .selectExpr("CAST(key AS STRING) as key", "CAST(value AS STRING) as value", "topic")

    transformDF
      .writeStream
      .option("checkpointLocation", checkpointLocation)
      .foreachBatch { (batchDF: DataFrame, _: Long) => {
        batchDF.write
          .format("jdbc")
          .option("url", jdbcConfig.url)
          .option("user", jdbcConfig.user)
          .option("password", jdbcConfig.password)
          .option("driver", "org.postgresql.Driver")
          .option(JDBCOptions.JDBC_TABLE_NAME, jdbcConfig.tableName)
          .option("stringtype", "unspecified")
          .mode(SaveMode.Append)
          .save()
      }
      }.start()
      .awaitTermination()
  }

  def read(): DataFrame = {
    spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaReaderConfig.kafkaBootstrapServers)
      .option("subscribe", kafkaReaderConfig.topics)
      .option("startingOffsets", kafkaReaderConfig.startingOffsets)
      .load()
  }
}
