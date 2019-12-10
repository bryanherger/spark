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

package org.apache.spark.sql.jdbc

import java.sql.{Connection, Types}
import java.util.Locale

import org.apache.spark.sql.execution.datasources.jdbc.{JDBCOptions, JdbcUtils}
import org.apache.spark.sql.types._

private object VerticaDialect extends JdbcDialect {

  override def canHandle(url: String): Boolean =
    url.toLowerCase(Locale.ROOT).startsWith("jdbc:vertica")

  override def getCatalystType(
      sqlType: Int, typeName: String, size: Int, md: MetadataBuilder): Option[DataType] = {
    if (sqlType == Types.REAL) {
      Some(FloatType)
    } else if (sqlType == Types.SMALLINT) {
      Some(ShortType)
    } else if (sqlType == Types.BIT && typeName.equals("bit") && size != 1) {
      Some(BinaryType)
    } else if (sqlType == Types.OTHER) {
      Some(StringType)
    } else None
  }

  private def toCatalystType(
      typeName: String,
      precision: Int,
      scale: Int): Option[DataType] = typeName match {
    case "bool" => Some(BooleanType)
    case "bit" => Some(BinaryType)
    case "smallint" | "int" | "integer" | "bigint" => Some(IntegerType)
    case "float" => Some(FloatType)
    case "money" | "float8" => Some(DoubleType)
    case "text" | "varchar" | "char" | "cidr" | "inet" | "json" | "jsonb" | "uuid" =>
      Some(StringType)
    case "bytea" | "varbinary" => Some(BinaryType)
    case "timestamp" | "timestamptz" | "time" | "timetz" => Some(TimestampType)
    case "date" => Some(DateType)
    case "numeric" | "decimal" if precision > 0 => Some(DecimalType.bounded(precision, scale))
    case "numeric" | "decimal" =>
      // SPARK-26538: handle numeric without explicit precision and scale.
      Some(DecimalType. SYSTEM_DEFAULT)
    case _ => None
  }

  override def getJDBCType(dt: DataType): Option[JdbcType] = dt match {
    case StringType => Some(JdbcType("VARCHAR", Types.CHAR))
    case BinaryType => Some(JdbcType("VARBINARY", Types.BINARY))
    case BooleanType => Some(JdbcType("BOOLEAN", Types.BOOLEAN))
    case FloatType => Some(JdbcType("FLOAT", Types.FLOAT))
    case DoubleType => Some(JdbcType("FLOAT", Types.FLOAT))
    case ShortType | ByteType => Some(JdbcType("INTEGER", Types.INTEGER))
    case t: DecimalType => Some(
      JdbcType(s"NUMERIC(${t.precision},${t.scale})", java.sql.Types.NUMERIC))
    case _ => None
  }

  override def getTableExistsQuery(table: String): String = {
    s"SELECT 1 FROM $table LIMIT 1"
  }

  override def isCascadingTruncateTable(): Option[Boolean] = Some(false)

  /**
   * The SQL query used to truncate a table. For Postgres, the default behaviour is to
   * also truncate any descendant tables. As this is a (possibly unwanted) side-effect,
   * the Postgres dialect adds 'ONLY' to truncate only the table in question
   * @param table The table to truncate
   * @param cascade Whether or not to cascade the truncation. Default value is the value of
   *                isCascadingTruncateTable(). Cascading a truncation will truncate tables
    *               with a foreign key relationship to the target table. However, it will not
    *               truncate tables with an inheritance relationship to the target table, as
    *               the truncate query always includes "ONLY" to prevent this behaviour.
   * @return The SQL query to use for truncating a table
   */
  override def getTruncateQuery(
      table: String,
      cascade: Option[Boolean] = isCascadingTruncateTable): String = {
    cascade match {
      case Some(true) => s"TRUNCATE TABLE $table CASCADE"
      case _ => s"TRUNCATE TABLE $table"
    }
  }

  override def beforeFetch(connection: Connection, properties: Map[String, String]): Unit = {
    super.beforeFetch(connection, properties)

    // According to the postgres jdbc documentation we need to be in autocommit=false if we actually
    // want to have fetchsize be non 0 (all the rows).  This allows us to not have to cache all the
    // rows inside the driver when fetching.
    //
    // See: https://jdbc.postgresql.org/documentation/head/query.html#query-with-cursor
    //
    if (properties.getOrElse(JDBCOptions.JDBC_BATCH_FETCH_SIZE, "0").toInt > 0) {
      connection.setAutoCommit(false)
    }
  }

}
