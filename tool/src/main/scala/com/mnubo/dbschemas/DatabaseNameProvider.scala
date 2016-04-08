package com.mnubo.dbschemas

import com.mnubo.app_util.MnuboConfiguration

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
  def computeDatabaseName(schemaLogicalName: String, namespace: Option[String]) = namespace match {
    case None => schemaLogicalName
    case Some(ns) => ns
  }
}

class ZoneAwareDatabaseNameProvider extends DatabaseNameProvider {
  private val mzConfig = MnuboConfiguration.loadMultiZoneConfig(env = Option(System.getenv("ENV")).getOrElse("integration"), configDirectory = ".", doConfigureLogback = false)
  def computeDatabaseName(schemaLogicalName: String, namespace: Option[String]) = namespace match {
    case None => s"${mzConfig.currentZone}_$schemaLogicalName"
    case Some(ns) => s"${mzConfig.currentZone}_${schemaLogicalName}_$ns"
  }
}
