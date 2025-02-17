/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi

import org.apache.hudi.index.columnstats.ColumnStatsIndexHelper
import org.apache.hudi.testutils.HoodieClientTestBase
import org.apache.spark.sql.catalyst.expressions.{Expression, Not}
import org.apache.spark.sql.functions.{col, lower}
import org.apache.spark.sql.hudi.DataSkippingUtils
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Column, HoodieCatalystExpressionUtils, Row, SparkSession}
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.{Arguments, MethodSource}

import java.sql.Timestamp
import scala.collection.JavaConverters._

// NOTE: Only A, B columns are indexed
case class IndexRow(
  file: String,

  // Corresponding A column is LongType
  A_minValue: Long = -1,
  A_maxValue: Long = -1,
  A_num_nulls: Long = -1,

  // Corresponding B column is StringType
  B_minValue: String = null,
  B_maxValue: String = null,
  B_num_nulls: Long = -1,

  // Corresponding B column is TimestampType
  C_minValue: Timestamp = null,
  C_maxValue: Timestamp = null,
  C_num_nulls: Long = -1
) {
  def toRow: Row = Row(productIterator.toSeq: _*)
}

class TestDataSkippingUtils extends HoodieClientTestBase with SparkAdapterSupport {

  val exprUtils: HoodieCatalystExpressionUtils = sparkAdapter.createCatalystExpressionUtils()

  var spark: SparkSession = _

  @BeforeEach
  override def setUp(): Unit = {
    initSparkContexts()
    spark = sqlContext.sparkSession
  }

  val indexedCols: Seq[String] = Seq("A", "B", "C")
  val sourceTableSchema: StructType =
    StructType(
      Seq(
        StructField("A", LongType),
        StructField("B", StringType),
        StructField("C", TimestampType),
        StructField("D", VarcharType(32))
      )
    )

  val indexSchema: StructType =
    ColumnStatsIndexHelper.composeIndexSchema(
      sourceTableSchema.fields.toSeq
        .filter(f => indexedCols.contains(f.name))
        .asJava
    )

  @ParameterizedTest
  @MethodSource(
    Array(
        "testBasicLookupFilterExpressionsSource",
        "testAdvancedLookupFilterExpressionsSource",
        "testCompositeFilterExpressionsSource"
    ))
  def testLookupFilterExpressions(sourceExpr: String, input: Seq[IndexRow], output: Seq[String]): Unit = {
    val resolvedExpr: Expression = exprUtils.resolveExpr(spark, sourceExpr, sourceTableSchema)
    val lookupFilter = DataSkippingUtils.translateIntoColumnStatsIndexFilterExpr(resolvedExpr, indexSchema)

    val indexDf = spark.createDataFrame(input.map(_.toRow).asJava, indexSchema)

    val rows = indexDf.where(new Column(lookupFilter))
      .select("file")
      .collect()
      .map(_.getString(0))
      .toSeq

    assertEquals(output, rows)
  }

  @ParameterizedTest
  @MethodSource(Array("testStringsLookupFilterExpressionsSource"))
  def testStringsLookupFilterExpressions(sourceExpr: Expression, input: Seq[IndexRow], output: Seq[String]): Unit = {
    val resolvedExpr = exprUtils.resolveExpr(spark, sourceExpr, sourceTableSchema)
    val lookupFilter = DataSkippingUtils.translateIntoColumnStatsIndexFilterExpr(resolvedExpr, indexSchema)

    val spark2 = spark
    import spark2.implicits._

    val indexDf = spark.createDataset(input)

    val rows = indexDf.where(new Column(lookupFilter))
      .select("file")
      .collect()
      .map(_.getString(0))
      .toSeq

    assertEquals(output, rows)
  }
}

object TestDataSkippingUtils {
  def testStringsLookupFilterExpressionsSource(): java.util.stream.Stream[Arguments] = {
    java.util.stream.Stream.of(
      arguments(
        col("B").startsWith("abc").expr,
        Seq(
          IndexRow("file_1", 0, 0, 0, "aba", "adf", 1), // may contain strings starting w/ "abc"
          IndexRow("file_2", 0, 0, 0, "adf", "azy", 0),
          IndexRow("file_3", 0, 0, 0, "aaa", "aba", 0)
        ),
        Seq("file_1")),
      arguments(
        Not(col("B").startsWith("abc").expr),
        Seq(
          IndexRow("file_1", 0, 0, 0, "aba", "adf", 1), // may contain strings starting w/ "abc"
          IndexRow("file_2", 0, 0, 0, "adf", "azy", 0),
          IndexRow("file_3", 0, 0, 0, "aaa", "aba", 0),
          IndexRow("file_4", 0, 0, 0, "abc123", "abc345", 0) // all strings start w/ "abc"
        ),
        Seq("file_1", "file_2", "file_3")),
      arguments(
        // Composite expression
        Not(lower(col("B")).startsWith("abc").expr),
        Seq(
          IndexRow("file_1", 0, 0, 0, "ABA", "ADF", 1), // may contain strings starting w/ "ABC" (after upper)
          IndexRow("file_2", 0, 0, 0, "ADF", "AZY", 0),
          IndexRow("file_3", 0, 0, 0, "AAA", "ABA", 0),
          IndexRow("file_4", 0, 0, 0, "ABC123", "ABC345", 0) // all strings start w/ "ABC" (after upper)
        ),
        Seq("file_1", "file_2", "file_3"))
    )
  }

  def testBasicLookupFilterExpressionsSource(): java.util.stream.Stream[Arguments] = {
    java.util.stream.Stream.of(
      // TODO cases
      //    A = null
      arguments(
        "A = 0",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0)
        ),
        Seq("file_2")),
      arguments(
        "0 = A",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0)
        ),
        Seq("file_2")),
      arguments(
        "A != 0",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", 0, 0, 0) // Contains only 0s
        ),
        Seq("file_1", "file_2")),
      arguments(
        "0 != A",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", 0, 0, 0) // Contains only 0s
        ),
        Seq("file_1", "file_2")),
      arguments(
        "A < 0",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0)
        ),
        Seq("file_2", "file_3")),
      arguments(
        "0 > A",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0)
        ),
        Seq("file_2", "file_3")),
      arguments(
        "A > 0",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0)
        ),
        Seq("file_1", "file_2")),
      arguments(
        "0 < A",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0)
        ),
        Seq("file_1", "file_2")),
      arguments(
        "A <= -1",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0)
        ),
        Seq("file_2", "file_3")),
      arguments(
        "-1 >= A",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0)
        ),
        Seq("file_2", "file_3")),
      arguments(
        "A >= 1",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0)
        ),
        Seq("file_1", "file_2")),
      arguments(
        "1 <= A",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0)
        ),
        Seq("file_1", "file_2")),
      arguments(
        "A is null",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 1)
        ),
        Seq("file_2")),
      arguments(
        "A is not null",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 1)
        ),
        Seq("file_1")),
      arguments(
        "A in (0, 1)",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0)
        ),
        Seq("file_1", "file_2")),
      arguments(
        "A not in (0, 1)",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0),
          IndexRow("file_4", 0, 0, 0), // only contains 0
          IndexRow("file_5", 1, 1, 0) // only contains 1
        ),
        Seq("file_1", "file_2", "file_3")),
      arguments(
        // Value expression containing expression, which isn't a literal
        "A = int('0')",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0)
        ),
        Seq("file_2")),
      arguments(
        // Value expression containing reference to the other attribute (column), fallback
        "A = D",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0)
        ),
        Seq("file_1", "file_2", "file_3"))
    )
  }

  def testAdvancedLookupFilterExpressionsSource(): java.util.stream.Stream[Arguments] = {
    java.util.stream.Stream.of(
      arguments(
        // Filter out all rows that contain either A = 0 OR A = 1
        "A != 0 AND A != 1",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0),
          IndexRow("file_4", 0, 0, 0), // only contains 0
          IndexRow("file_5", 1, 1, 0) // only contains 1
        ),
        Seq("file_1", "file_2", "file_3")),
      arguments(
        // This is an equivalent to the above expression
        "NOT(A = 0 OR A = 1)",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0),
          IndexRow("file_4", 0, 0, 0), // only contains 0
          IndexRow("file_5", 1, 1, 0) // only contains 1
        ),
        Seq("file_1", "file_2", "file_3")),

      arguments(
        // Filter out all rows that contain A = 0 AND B = 'abc'
      "A != 0 OR B != 'abc'",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0),
          IndexRow("file_4", 0, 0, 0, "abc", "abc", 0), // only contains A = 0, B = 'abc'
          IndexRow("file_5", 0, 0, 0, "abc", "abc", 0) // only contains A = 0, B = 'abc'
        ),
        Seq("file_1", "file_2", "file_3")),
      arguments(
        // This is an equivalent to the above expression
        "NOT(A = 0 AND B = 'abc')",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0),
          IndexRow("file_4", 0, 0, 0, "abc", "abc", 0), // only contains A = 0, B = 'abc'
          IndexRow("file_5", 0, 0, 0, "abc", "abc", 0) // only contains A = 0, B = 'abc'
        ),
        Seq("file_1", "file_2", "file_3")),

      arguments(
        // Queries contains expression involving non-indexed column D
        "A = 0 AND B = 'abc' AND D IS NULL",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0),
          IndexRow("file_4", 0, 0, 0, "aaa", "xyz", 0) // might contain A = 0 AND B = 'abc'
        ),
        Seq("file_4")),

      arguments(
        // Queries contains expression involving non-indexed column D
        "A = 0 OR B = 'abc' OR D IS NULL",
        Seq(
          IndexRow("file_1", 1, 2, 0),
          IndexRow("file_2", -1, 1, 0),
          IndexRow("file_3", -2, -1, 0),
          IndexRow("file_4", 0, 0, 0, "aaa", "xyz", 0) // might contain B = 'abc'
        ),
        Seq("file_1", "file_2", "file_3", "file_4"))
    )
  }

  def testCompositeFilterExpressionsSource(): java.util.stream.Stream[Arguments] = {
    java.util.stream.Stream.of(
      arguments(
        "date_format(C, 'MM/dd/yyyy') = '03/06/2022'",
        Seq(
          IndexRow("file_1",
            C_minValue = new Timestamp(1646711448000L), // 03/07/2022
            C_maxValue = new Timestamp(1646797848000L), // 03/08/2022
            C_num_nulls = 0),
          IndexRow("file_2",
            C_minValue = new Timestamp(1646625048000L), // 03/06/2022
            C_maxValue = new Timestamp(1646711448000L), // 03/07/2022
            C_num_nulls = 0)
        ),
        Seq("file_2")),
      arguments(
        "'03/06/2022' = date_format(C, 'MM/dd/yyyy')",
        Seq(
          IndexRow("file_1",
            C_minValue = new Timestamp(1646711448000L), // 03/07/2022
            C_maxValue = new Timestamp(1646797848000L), // 03/08/2022
            C_num_nulls = 0),
          IndexRow("file_2",
            C_minValue = new Timestamp(1646625048000L), // 03/06/2022
            C_maxValue = new Timestamp(1646711448000L), // 03/07/2022
            C_num_nulls = 0)
        ),
        Seq("file_2")),
      arguments(
        "'03/06/2022' != date_format(C, 'MM/dd/yyyy')",
        Seq(
          IndexRow("file_1",
            C_minValue = new Timestamp(1646711448000L), // 03/07/2022
            C_maxValue = new Timestamp(1646797848000L), // 03/08/2022
            C_num_nulls = 0),
          IndexRow("file_2",
            C_minValue = new Timestamp(1646625048000L), // 03/06/2022
            C_maxValue = new Timestamp(1646625048000L), // 03/06/2022
            C_num_nulls = 0)
        ),
        Seq("file_1")),
      arguments(
        "date_format(C, 'MM/dd/yyyy') != '03/06/2022'",
        Seq(
          IndexRow("file_1",
            C_minValue = new Timestamp(1646711448000L), // 03/07/2022
            C_maxValue = new Timestamp(1646797848000L), // 03/08/2022
            C_num_nulls = 0),
          IndexRow("file_2",
            C_minValue = new Timestamp(1646625048000L), // 03/06/2022
            C_maxValue = new Timestamp(1646625048000L), // 03/06/2022
            C_num_nulls = 0)
        ),
        Seq("file_1")),
      arguments(
        "date_format(C, 'MM/dd/yyyy') < '03/07/2022'",
        Seq(
          IndexRow("file_1",
            C_minValue = new Timestamp(1646711448000L), // 03/07/2022
            C_maxValue = new Timestamp(1646797848000L), // 03/08/2022
            C_num_nulls = 0),
          IndexRow("file_2",
            C_minValue = new Timestamp(1646625048000L), // 03/06/2022
            C_maxValue = new Timestamp(1646711448000L), // 03/07/2022
            C_num_nulls = 0)
        ),
        Seq("file_2")),
      arguments(
        "'03/07/2022' > date_format(C, 'MM/dd/yyyy')",
        Seq(
          IndexRow("file_1",
            C_minValue = new Timestamp(1646711448000L), // 03/07/2022
            C_maxValue = new Timestamp(1646797848000L), // 03/08/2022
            C_num_nulls = 0),
          IndexRow("file_2",
            C_minValue = new Timestamp(1646625048000L), // 03/06/2022
            C_maxValue = new Timestamp(1646711448000L), // 03/07/2022
            C_num_nulls = 0)
        ),
        Seq("file_2")),
      arguments(
        "'03/07/2022' < date_format(C, 'MM/dd/yyyy')",
        Seq(
          IndexRow("file_1",
            C_minValue = new Timestamp(1646711448000L), // 03/07/2022
            C_maxValue = new Timestamp(1646797848000L), // 03/08/2022
            C_num_nulls = 0),
          IndexRow("file_2",
            C_minValue = new Timestamp(1646625048000L), // 03/06/2022
            C_maxValue = new Timestamp(1646711448000L), // 03/07/2022
            C_num_nulls = 0)
        ),
        Seq("file_1")),
      arguments(
        "date_format(C, 'MM/dd/yyyy') > '03/07/2022'",
        Seq(
          IndexRow("file_1",
            C_minValue = new Timestamp(1646711448000L), // 03/07/2022
            C_maxValue = new Timestamp(1646797848000L), // 03/08/2022
            C_num_nulls = 0),
          IndexRow("file_2",
            C_minValue = new Timestamp(1646625048000L), // 03/06/2022
            C_maxValue = new Timestamp(1646711448000L), // 03/07/2022
            C_num_nulls = 0)
        ),
        Seq("file_1")),
      arguments(
        "date_format(C, 'MM/dd/yyyy') <= '03/06/2022'",
        Seq(
          IndexRow("file_1",
            C_minValue = new Timestamp(1646711448000L), // 03/07/2022
            C_maxValue = new Timestamp(1646797848000L), // 03/08/2022
            C_num_nulls = 0),
          IndexRow("file_2",
            C_minValue = new Timestamp(1646625048000L), // 03/06/2022
            C_maxValue = new Timestamp(1646711448000L), // 03/07/2022
            C_num_nulls = 0)
        ),
        Seq("file_2")),
      arguments(
        "'03/06/2022' >= date_format(C, 'MM/dd/yyyy')",
        Seq(
          IndexRow("file_1",
            C_minValue = new Timestamp(1646711448000L), // 03/07/2022
            C_maxValue = new Timestamp(1646797848000L), // 03/08/2022
            C_num_nulls = 0),
          IndexRow("file_2",
            C_minValue = new Timestamp(1646625048000L), // 03/06/2022
            C_maxValue = new Timestamp(1646711448000L), // 03/07/2022
            C_num_nulls = 0)
        ),
        Seq("file_2")),
      arguments(
        "'03/08/2022' <= date_format(C, 'MM/dd/yyyy')",
        Seq(
          IndexRow("file_1",
            C_minValue = new Timestamp(1646711448000L), // 03/07/2022
            C_maxValue = new Timestamp(1646797848000L), // 03/08/2022
            C_num_nulls = 0),
          IndexRow("file_2",
            C_minValue = new Timestamp(1646625048000L), // 03/06/2022
            C_maxValue = new Timestamp(1646711448000L), // 03/07/2022
            C_num_nulls = 0)
        ),
        Seq("file_1")),
      arguments(
        "date_format(C, 'MM/dd/yyyy') >= '03/08/2022'",
        Seq(
          IndexRow("file_1",
            C_minValue = new Timestamp(1646711448000L), // 03/07/2022
            C_maxValue = new Timestamp(1646797848000L), // 03/08/2022
            C_num_nulls = 0),
          IndexRow("file_2",
            C_minValue = new Timestamp(1646625048000L), // 03/06/2022
            C_maxValue = new Timestamp(1646711448000L), // 03/07/2022
            C_num_nulls = 0)
        ),
        Seq("file_1")),
      arguments(
        "date_format(C, 'MM/dd/yyyy') IN ('03/08/2022')",
        Seq(
          IndexRow("file_1",
            C_minValue = new Timestamp(1646711448000L), // 03/07/2022
            C_maxValue = new Timestamp(1646797848000L), // 03/08/2022
            C_num_nulls = 0),
          IndexRow("file_2",
            C_minValue = new Timestamp(1646625048000L), // 03/06/2022
            C_maxValue = new Timestamp(1646711448000L), // 03/07/2022
            C_num_nulls = 0)
        ),
        Seq("file_1")),
      arguments(
        "date_format(C, 'MM/dd/yyyy') NOT IN ('03/06/2022')",
        Seq(
          IndexRow("file_1",
            C_minValue = new Timestamp(1646711448000L), // 03/07/2022
            C_maxValue = new Timestamp(1646797848000L), // 03/08/2022
            C_num_nulls = 0),
          IndexRow("file_2",
            C_minValue = new Timestamp(1646625048000L), // 03/06/2022
            C_maxValue = new Timestamp(1646625048000L), // 03/06/2022
            C_num_nulls = 0)
        ),
        Seq("file_1")),
      arguments(
        // Should be identical to the one above
        "date_format(to_timestamp(B, 'yyyy-MM-dd'), 'MM/dd/yyyy') NOT IN ('03/06/2022')",
        Seq(
          IndexRow("file_1",
            B_minValue = "2022-03-07", // 03/07/2022
            B_maxValue = "2022-03-08", // 03/08/2022
            B_num_nulls = 0),
          IndexRow("file_2",
            B_minValue = "2022-03-06", // 03/06/2022
            B_maxValue = "2022-03-06", // 03/06/2022
            B_num_nulls = 0)
        ),
        Seq("file_1"))

    )
  }
}
