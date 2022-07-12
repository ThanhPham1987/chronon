package ai.chronon.aggregator.test

import ai.chronon.aggregator.row.RowAggregator
import ai.chronon.aggregator.windowing.TsUtils
import ai.chronon.api.Extensions.WindowOps
import ai.chronon.api.{Row, Window}

class NaiveAggregator(aggregator: RowAggregator,
                      windows: Array[Window],
                      tailHops: Array[Long],
                      headRoundingMillis: Long = 1)
    extends Serializable {

  def aggregate(inputRows: Seq[Row], queries: Seq[Long]): Array[Array[Any]] = {

    // initialize the result - convention is to append the timestamp in the end
    val results: Array[Array[Any]] = Array.fill(queries.length)(Array.fill(aggregator.length)(null))
    if (inputRows == null) return results
    for (inputRow <- inputRows) {
      for (endTimeIndex <- queries.indices) {
        val queryTime = queries(endTimeIndex)
        for (col <- aggregator.indices) {
          val windowStart = TsUtils.round(queryTime - windows(col).millis, tailHops(col))
          if (windowStart <= inputRow.ts && inputRow.ts < TsUtils.round(queryTime, headRoundingMillis)) {
            aggregator.columnAggregators(col).update(results(endTimeIndex), inputRow)
          }
        }
      }
    }
    results
  }
}