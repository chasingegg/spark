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
package org.apache.spark.sql.catalyst.analysis

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.RuleExecutor
import org.apache.spark.sql.types._

class ResolveUnionSuite extends AnalysisTest {
  test("Resolve Union") {
    val table1 = LocalRelation(
      AttributeReference("i", IntegerType)(),
      AttributeReference("u", DecimalType.SYSTEM_DEFAULT)(),
      AttributeReference("b", ByteType)(),
      AttributeReference("d", DoubleType)())
    val table2 = LocalRelation(
      AttributeReference("u", DecimalType.SYSTEM_DEFAULT)(),
      AttributeReference("b", ByteType)(),
      AttributeReference("d", DoubleType)(),
      AttributeReference("i", IntegerType)())
    val table3 = LocalRelation(
      AttributeReference("u", DecimalType.SYSTEM_DEFAULT)(),
      AttributeReference("d", DoubleType)(),
      AttributeReference("i", IntegerType)())
    val table4 = LocalRelation(
      AttributeReference("u", DecimalType.SYSTEM_DEFAULT)(),
      AttributeReference("i", IntegerType)())

    val rules = Seq(ResolveUnion)
    val analyzer = new RuleExecutor[LogicalPlan] {
      override val batches = Seq(Batch("Resolution", Once, rules: _*))
    }

    // By name resolution
    val union1 = Union(table1 :: table2 :: Nil, true, false)
    val analyzed1 = analyzer.execute(union1)
    val projected1 =
      Project(Seq(table2.output(3), table2.output(0), table2.output(1), table2.output(2)), table2)
    val expected1 = Union(table1 :: projected1 :: Nil)
    comparePlans(analyzed1, expected1)

    // Allow missing column
    val union2 = Union(table1 :: table3 :: Nil, true, true)
    val analyzed2 = analyzer.execute(union2)
    val nullAttr1 = Alias(Literal(null, ByteType), "b")()
    val projected2 =
      Project(Seq(table2.output(3), table2.output(0), nullAttr1, table2.output(2)), table3)
    val expected2 = Union(table1 :: projected2 :: Nil)
    comparePlans(analyzed2, expected2)

    // Allow missing column + Allow missing column
    val union3 = Union(union2 :: table4 :: Nil, true, true)
    val analyzed3 = analyzer.execute(union3)
    val nullAttr2 = Alias(Literal(null, DoubleType), "d")()
    val projected3 =
      Project(Seq(table2.output(3), table2.output(0), nullAttr1, nullAttr2), table4)
    val expected3 = Union(table1 :: projected2 :: projected3 :: Nil)
    comparePlans(analyzed3, expected3)
  }

  test("SPARK-37865: add alias when the first child of union has duplicate columns") {
    val table1 = LocalRelation(
      AttributeReference("a", IntegerType)(),
      AttributeReference("b", IntegerType)())
    val table2 = LocalRelation(
      AttributeReference("a", IntegerType)(),
      AttributeReference("b", IntegerType)())

    val rules = Seq(ResolveUnion)
    val analyzer = new RuleExecutor[LogicalPlan] {
      override val batches = Seq(Batch("Resolution", Once, rules: _*))
    }
    // only the first child of union has duplicate attributes
    val left1 = Project(Seq(table1.output(0), table1.output(0)), table1)
    val right1 = Project(Seq(table2.output(0), table2.output(1)), table2)
    val union1 = Union(left1 :: right1 :: Nil)
    val analyzed1 = analyzer.execute(union1)
    val expectedLeft1 = Project(
      Seq(
        Alias(table1.output(0), table1.output(0).name)(),
        Alias(table1.output(0), table1.output(0).name)()),
      left1)
    val expected1 = Union(expectedLeft1 :: right1 :: Nil)
    comparePlans(analyzed1, expected1)

    // only the second child of union has duplicate attributes
    val left2 = Project(Seq(table1.output(0), table1.output(1)), table1)
    val right2 = Project(Seq(table2.output(0), table2.output(0)), table2)

    val union2 = Union(left2 :: right2 :: Nil)
    val analyzed2 = analyzer.execute(union2)
    val expected2 = Union(left2 :: right2 :: Nil)
    comparePlans(analyzed2, expected2)

    // both sides of union has duplicate attributes
    val left3 = Project(Seq(table1.output(0), table1.output(0)), table1)
    val right3 = Project(Seq(table2.output(1), table2.output(1)), table2)

    val union3 = Union(left3 :: right3 :: Nil)
    val analyzed3 = analyzer.execute(union3)
    val expectedLeft3 = Project(
      Seq(
        Alias(table1.output(0), table1.output(0).name)(),
        Alias(table1.output(0), table1.output(0).name)()),
      left3)
    val expected3 = Union(expectedLeft3 :: right3 :: Nil)
    comparePlans(analyzed3, expected3)
  }
}
