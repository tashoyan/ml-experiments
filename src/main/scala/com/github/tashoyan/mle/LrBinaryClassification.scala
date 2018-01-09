package com.github.tashoyan.mle

import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.ml.feature.{OneHotEncoder, StringIndexer, VectorAssembler}
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.ml.{Pipeline, PipelineStage}
import org.apache.spark.sql.types.{DoubleType, StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, SparkSession}

/*
* https://docs.databricks.com/spark/latest/mllib/binary-classification-mllib-pipelines.html
* */
object LrBinaryClassification extends App {

  val spark: SparkSession = SparkSession.builder()
    .master("local[*]")
    .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    .config("spark.kryo.unsafe", "true")
    .getOrCreate()
  spark.sparkContext.setLogLevel("WARN")

  val adultSchema: StructType = StructType(
    StructField("age", DoubleType) ::
      StructField("workclass", StringType) ::
      StructField("fnlwgt", DoubleType) ::
      StructField("education", StringType) ::
      StructField("education_num", DoubleType) ::
      StructField("marital_status", StringType) ::
      StructField("occupation", StringType) ::
      StructField("relationship", StringType) ::
      StructField("race", StringType) ::
      StructField("sex", StringType) ::
      StructField("capital_gain", DoubleType) ::
      StructField("capital_loss", DoubleType) ::
      StructField("hours_per_week", DoubleType) ::
      StructField("native_country", StringType) ::
      StructField("income", StringType) :: Nil
  )
  val adult: DataFrame = spark.read
    .schema(adultSchema)
    .option("mode", "FAILFAST")
    .csv(this.getClass.getResource("adult.data.csv").toString)

  val categoricalColumns: Array[String] = Array(
    "workclass",
    "education",
    "marital_status",
    "occupation",
    "relationship",
    "race",
    "sex",
    "native_country"
  )
  def getVectorColumn(column: String): String = column + "Vector"
  val categoricalStages: Array[PipelineStage] = categoricalColumns.flatMap { column =>
    val indexedColumn = column + "Index"
    val stringIndexer = new StringIndexer()
      .setInputCol(column)
      .setOutputCol(indexedColumn)
      .setHandleInvalid("error")
    val encoder = new OneHotEncoder()
      .setInputCol(indexedColumn)
      .setOutputCol(getVectorColumn(column))
    Iterable(stringIndexer, encoder)
  }

  val numericColumns = Array(
    "age",
    "fnlwgt",
    "education_num",
    "capital_gain",
    "capital_loss",
    "hours_per_week"
  )
  val featureColumns = categoricalColumns.map(getVectorColumn) ++ numericColumns
  val featureStage = new VectorAssembler()
    .setInputCols(featureColumns)
    .setOutputCol("features")

  val labelStage = new StringIndexer()
    .setInputCol("income")
    .setOutputCol("label")

  val transformPipeline = new Pipeline()
    .setStages(categoricalStages ++ Array(featureStage, labelStage))
  val transformModel = transformPipeline.fit(adult)
  val transformedAdult = transformModel.transform(adult)

  val Array(trainAdult, testAdult) = transformedAdult.randomSplit(Array(0.7, 0.3))
  println(s"Train data size: ${trainAdult.count()}, test data size: ${testAdult.count()}")

  val logisticRegression = new LogisticRegression()
    .setFeaturesCol("features")
    .setLabelCol("label")
    .setRegParam(1.0)
    .setElasticNetParam(0.5)
    .setMaxIter(10)
  val logisticRegressionModel = logisticRegression.fit(trainAdult)
  val lrPredictedAdult = logisticRegressionModel.transform(testAdult)
  val incorrectPredictions = lrPredictedAdult.select("label", "prediction")
    .where("label != prediction")
  println("Incorrect predictions: " + incorrectPredictions.count())

  val evaluator = new BinaryClassificationEvaluator()
    .setRawPredictionCol("rawPrediction")
    .setLabelCol("label")
  val metric = evaluator.evaluate(lrPredictedAdult)
  println("Evaluator metric " + evaluator.getMetricName + " = " + metric)

  val paramGrid = new ParamGridBuilder()
    .addGrid(logisticRegression.regParam, Array(0.0, 0.5, 2.0))
    .addGrid(logisticRegression.elasticNetParam, Array(0.0, 0.5, 1.0))
    .addGrid(logisticRegression.maxIter, Array(1, 5, 10))
    .build()
  val crossValidator = new CrossValidator()
    .setEstimator(logisticRegression)
    .setEstimatorParamMaps(paramGrid)
    .setEvaluator(evaluator)
    .setNumFolds(5)
  val validatedModel = crossValidator.fit(trainAdult)
  val validatedPredictedAdult = validatedModel.transform(testAdult)
  val validatedIncorrectPredictions = validatedPredictedAdult.select("label", "prediction")
    .where("label != prediction")
  println("[After cross-validation] Incorrect predictions: " + validatedIncorrectPredictions.count())

  val validatedMetric = evaluator.evaluate(validatedPredictedAdult)
  println("[After cross-validation] Evaluator metric " + evaluator.getMetricName + " = " + validatedMetric)
  println("[After cross-validation] Best model:\n" + validatedModel.bestModel.explainParams())
}
