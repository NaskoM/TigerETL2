package analytics.tiger.agents.spark2

import analytics.tiger.ETL.{DummyDelta, defaultErrorEmailer, dummyDelta, etlType, prepareEtl}
import analytics.tiger.{etlMessage, utils}
import dispatch.Http
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession
import scalikejdbc._

import scala.util.{Failure, Success, Try}

/**
  * Created by atanas on 10/10/2017.
  */
object Orsp {

  // create Spark context with Spark configuration
  val sc = new SparkConf().setAppName("OrspProject")
  if (sc.getOption("spark.master").isEmpty) sc.setMaster("local[2]")
  val spark = SparkSession.builder.config(sc).getOrCreate()

  val urlToDF = (urlStr: String) => {
    import dispatch._
    import scala.concurrent.ExecutionContext.Implicits.global
    val req = dispatch.url(urlStr)
    val res = Http(req OK as.String).apply()
    import spark.implicits._
    spark.read.json(spark.createDataset(res :: Nil))
  }

  val etl: etlType[DummyDelta] = delta => session => {
    implicit val sess = session
    import spark.implicits._
    val result = for (
      res1 <- Try{
        sql"DELETE FROM ANALYTICS.ORSP_PROJECT".update().apply()
        val data = urlToDF("https://orsp.broadinstitute.org/api/projects")
          .filter(_.getAs[String]("type") != "Consent Group")
          .map(row => Seq(row.getAs[String]("key"), row.getAs[String]("label"), row.getAs[String]("type"), row.getAs[String]("status"), row.getAs[String]("description"), row.getAs[String]("url")))
          .collect()
        data.foreach(sql"INSERT INTO ANALYTICS.ORSP_PROJECT VALUES(?,?,?,?,?,?)".bind(_: _*).update().apply())
        s"ANALYTICS.ORSP_PROJECT: ${data.size}"
      };
      res2 <- Try{
        sql"DELETE FROM ANALYTICS.ORSP_CONSENT".update().apply()
        val data = urlToDF("https://orsp.broadinstitute.org/api/consents")
          .map(row => Seq(row.getAs[String]("key"), row.getAs[String]("label"), row.getAs[String]("dataUseRestriction"), row.getAs[String]("url")))
          .collect()
        data.foreach(sql"INSERT INTO ANALYTICS.ORSP_CONSENT VALUES(?,?,?,?)".bind(_: _*).update().apply())
        s"ANALYTICS.ORSP_CONSENT: ${data.size}"
      };
      res3 <- Try{
        sql"DELETE FROM ANALYTICS.ORSP_SAMPLE_COLLECTION".update().apply()
        val df = urlToDF("https://orsp.broadinstitute.org/api/samples")

        val data1 = df
          .map(row => Seq(row.getAs[String]("sampleCollection"), row.getAs[String]("sampleCollectionName")))
          .distinct()
          .collect()
        data1.foreach(sql"INSERT INTO ANALYTICS.ORSP_SAMPLE_COLLECTION VALUES(?,?)".bind(_: _*).update().apply())

        sql"DELETE FROM ANALYTICS.ORSP_SAMPLE_STAR".update().apply()
        val data2 = df
          .map(row => Seq(row.getAs[String]("sampleCollection"), row.getAs[String]("project"), row.getAs[String]("consent")))
          .collect()
        data2.foreach(sql"INSERT INTO ANALYTICS.ORSP_SAMPLE_STAR VALUES(?,?,?)".bind(_: _*).update().apply())

        s"ANALYTICS.ORSP_SAMPLE_COLLECTION: ${data1.size}\nANALYTICS.ORSP_SAMPLE_STAR: ${data2.size}"
      }
    ) yield s"$res1\n$res2\n$res3"
    Seq((delta, result match {
      case Success(s) => Right(s)
      case Failure(e) => Left(etlMessage(e.getMessage))
    }))
  }

  def main(args: Array[String]) {
    val agentName = utils.objectName(this)
    val etlPlan = prepareEtl(agentName, dummyDelta, etl)()
    val res = utils.AnalyticsEtlDB.apply(etlPlan)
    spark.close()
    Http.shutdown()
    defaultErrorEmailer(agentName)(res)
  }

}