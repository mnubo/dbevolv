package com.mnubo.dbschemas.util

import java.sql.Connection

import com.mysql.jdbc.exceptions.jdbc4.MySQLSyntaxErrorException

object MySQLUtils {
  def addIdemPotentColumn(connection: Connection, table: String, column: String, columnDefinition: String) = {
    val stmt =
      s"""
         |ALTER TABLE $table ADD COLUMN (
         |$column $columnDefinition
         |)
      """.stripMargin
    try{
      connection.prepareStatement(stmt).execute()
    } catch {
      case ex: MySQLSyntaxErrorException if ex.getMessage == s"Duplicate column name '$column'" => ()
    }
  }

  def dropIdemPotentColumn(connection: Connection, table: String, column: String) = {
    val stmt = s"ALTER TABLE $table DROP COLUMN $column"
    try{
      connection.prepareStatement(stmt).execute()
    } catch {
      case ex : MySQLSyntaxErrorException if ex.getMessage == s"Can't DROP '$column'; check that column/key exists" =>
    }
  }
}