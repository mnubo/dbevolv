package com.mnubo.dbschemas

/**
 * A schema description.
 * @tparam COLTYPE the Scala type that descrives a column type. Most of the databases are using either a string or some kind of value object descriptor.
 */
case class Schema[COLTYPE](tables: Map[String, Table[COLTYPE]]) {
  // Make sure the other schema is compatible with this reference schema
  def isCompatibleWith(other: Schema[COLTYPE]) = {
    tables.forall { case (name, table) =>
      other.tables.contains(name) &&
        table.isCompatibleWith(other.tables(name))
    }
  }
}

object Schema {
  def apply[COLTYPE](tables: Iterable[Table[COLTYPE]]): Schema[COLTYPE] =
    Schema[COLTYPE](tables.map(t => t.name -> t).toMap)
}

case class Table[COLTYPE](name: String, columns: Set[Column[COLTYPE]]) {
  // Make sure the other table is compatible with this reference table
  def isCompatibleWith(other: Table[COLTYPE]) =
    name == other.name &&
      columns.forall(other.columns.contains)
}

case class Column[COLTYPE](name: String, `type`: COLTYPE)
