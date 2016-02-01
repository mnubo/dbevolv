package com.mnubo.dbschemas.util

import com.datastax.driver.core.exceptions.{InvalidQueryException, QueryValidationException}
import com.datastax.driver.core.querybuilder.QueryBuilder.select
import com.datastax.driver.core.{ConsistencyLevel, Session, SimpleStatement}

object CassandraUtils {
  def addIdemPotentColumn(session:Session , table:String , column:String , `type`:String)
  {
    val query = select(column)
      .from(table)
      .limit(1)

    try {
      session.execute(query)

      //column already exist!
      dropColumn(session, table, column)
    } catch {
      case ex: InvalidQueryException => ()
      case ex: QueryValidationException => ()
    }

    try {
      addColumn(session, table, column, `type`)
    } catch {
      case ex: InvalidQueryException => ()
      case ex: QueryValidationException => ()
    }
  }

  def dropIdemPotentColumn(session : Session, table : String, column : String)
  {
    try {
      dropColumn(session, table, column)
    } catch {
      case ex: InvalidQueryException => ()
      case ex: QueryValidationException => ()
    }
  }

  private def dropColumn(session : Session, table : String, column : String) =
  {
    session.execute(new SimpleStatement( "alter table " + table + " drop " + column).setConsistencyLevel(ConsistencyLevel.ALL) )
  }

  private def addColumn(session : Session, table : String, column : String, `type` : String )
  {
    session.execute(new SimpleStatement("alter table " + table + " add " + column + " " + `type`).setConsistencyLevel(ConsistencyLevel.ALL))
  }
}
