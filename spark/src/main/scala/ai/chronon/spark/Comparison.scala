package ai.chronon.spark

import com.google.gson.Gson
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types.{DoubleType, FloatType, MapType}

import java.util

object Comparison {

  // used for comparison
  def sortedJson(m: Map[String, Any]): String = {
    if (m == null) return null
    val tm = new util.TreeMap[String, Any]()
    m.iterator.foreach { case (key, value) => tm.put(key, value) }
    val gson = new Gson()
    gson.toJson(tm)
  }

  def stringifyMaps(df: DataFrame): DataFrame = {
    try {
      df.sparkSession.udf.register("sorted_json", (m: Map[String, Any]) => sortedJson(m))
    } catch {
      case e: Exception => e.printStackTrace()
    }
    val selects = for (field <- df.schema.fields) yield {
      if (field.dataType.isInstanceOf[MapType]) {
        s"sorted_json(${field.name}) as `${field.name}`"
      } else {
        s"${field.name} as `${field.name}`"
      }
    }
    df.selectExpr(selects: _*)
  }

  // Produces a "comparison" dataframe - given two dataframes that are supposed to have same data
  // The result contains the differing rows of the same key
  def sideBySide(a: DataFrame,
                 b: DataFrame,
                 keys: List[String],
                 aName: String = "a",
                 bName: String = "b"): DataFrame = {

    val prefixedExpectedDf = prefixColumnName(stringifyMaps(a), s"${aName}_")
    val prefixedOutputDf = prefixColumnName(stringifyMaps(b), s"${bName}_")

    val joinExpr = keys
      .map(key => prefixedExpectedDf(s"${aName}_$key") <=> prefixedOutputDf(s"${bName}_$key"))
      .reduce((col1, col2) => col1.and(col2))
    val joined = prefixedExpectedDf.join(
      prefixedOutputDf,
      joinExpr,
      joinType = "full_outer"
    )

    var finalDf = joined
    val comparisonColumns =
      a.schema.fieldNames.toSet.diff(keys.toSet).toList.sorted
    val colOrder =
      keys.map(key => { finalDf(s"${aName}_$key").as(key) }) ++
        comparisonColumns.flatMap { col =>
          List(finalDf(s"${aName}_$col"), finalDf(s"${bName}_$col"))
        }
    // double columns need to be compared approximately
    val doubleCols = a.schema.fields
      .filter(field => field.dataType == DoubleType || field.dataType == FloatType)
      .map(_.name)
      .toSet
    finalDf = finalDf.select(colOrder: _*)
    val comparisonFilters = comparisonColumns
      .flatMap { col =>
        val left = s"${aName}_$col"
        val right = s"${bName}_$col"
        val compareExpression =
          if (doubleCols.contains(col)) {
            s"($left is NOT NULL) AND ($right is NOT NULL) and (abs($left - $right) > 0.00001)"
          } else { s"($left <> $right)" }
        Seq(s"(($left IS NULL AND $right IS NOT NULL) OR ($right IS NULL AND $left IS NOT NULL) OR $compareExpression)")
      }
    println(s"Using comparison filter:\n  ${comparisonFilters.mkString("\n  ")}")
    finalDf.filter(comparisonFilters.mkString(" or "))
  }

  private def prefixColumnName(df: DataFrame, prefix: String): DataFrame = {
    val renamedColumns = df.columns.map(c => { df(c).as(s"$prefix$c") })
    df.select(renamedColumns: _*)
  }
}