package com.mnubo.dbschemas

/** Computes a database/schema/keyspace out of the schema logical name (the one found in db.conf @ 'schema_name') and the namespace. For global databases that are not customer specific, the namespace should be None. */
trait DatabaseNameProvider {
  def computeDatabaseName(schemaLogicalName: String, namespace: Option[String]): String
}

class DefaultDatabaseNameProvider extends DatabaseNameProvider {
  def computeDatabaseName(schemaLogicalName: String, namespace: Option[String]) = namespace match {
    case None => schemaLogicalName
    case Some(ns) => s"${schemaLogicalName}_$ns"
  }
}

class LegacyDatabaseNameProvider extends DatabaseNameProvider {
  private val default = new DefaultDatabaseNameProvider

  def computeDatabaseName(schemaLogicalName: String, namespace: Option[String]) = namespace match {
    case Some(ns) if ns == "connectedevice" => ns
    case Some(ns) if ns == "vanhawks" => ns
    case _ => default.computeDatabaseName(schemaLogicalName, namespace)
  }
}
