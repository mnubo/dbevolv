package com.mnubo.dbschemas

import com.mnubo.app_util.Logging

/**
 * A schema description.
 * @tparam COLTYPE the Scala type that descrives a column type. Most of the databases are using either a string or some kind of value object descriptor.
 */
case class Schema[COLTYPE](tables: Map[String, Table[COLTYPE]]) extends Logging {
  // Make sure the other schema is compatible with this reference schema
  def isCompatibleWith(other: Schema[COLTYPE]) = {
    tables.forall { case (name, table) =>
      if (!other.tables.contains(name))
        log.error(s"The schema does not contain the table ${table.name}")

      other.tables.contains(name) &&
        table.isCompatibleWith(other.tables(name))
    }
  }
}

object Schema {
  def apply[COLTYPE](tables: Iterable[Table[COLTYPE]]): Schema[COLTYPE] =
    Schema[COLTYPE](tables.map(t => t.name -> t).toMap)
}

case class Table[COLTYPE](name: String, columns: Set[Column[COLTYPE]]) extends Logging {
  // Make sure the other table is compatible with this reference table
  def isCompatibleWith(other: Table[COLTYPE]) = {
    columns.filterNot(other.columns.contains).foreach { c =>
      log.error(s"Table $name does not contain a column $c")
    }
    name == other.name &&
      columns.forall(other.columns.contains)
  }
}

case class Column[COLTYPE](name: String, `type`: COLTYPE) {
  override def toString() = s"$name (type = ${`type`})"
}
