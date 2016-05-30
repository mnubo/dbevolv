package com.mnubo.dbevolv

import com.mnubo.app_util.MnuboConfiguration

/** Computes a database/schema/keyspace out of the schema logical name (the one found in db.conf @ 'schema_name') and the tenant id. For global databases that are not customer specific, the tenant should be None. */
trait DatabaseNameProvider {
  def computeDatabaseName(schemaLogicalName: String, tenant: Option[String]): String
}

class DefaultDatabaseNameProvider extends DatabaseNameProvider {
  def computeDatabaseName(schemaLogicalName: String, tenant: Option[String]) = tenant match {
    case None => schemaLogicalName
    case Some(ns) => s"${schemaLogicalName}_$ns"
  }
}

class LegacyDatabaseNameProvider extends DatabaseNameProvider {
  def computeDatabaseName(schemaLogicalName: String, tenant: Option[String]) = tenant match {
    case None => schemaLogicalName
    case Some(ns) => ns
  }
}

class ZoneAwareDatabaseNameProvider extends DatabaseNameProvider {
  def defaultEnv = "integration"
  private val mzConfig = MnuboConfiguration.loadMultiZoneConfig(env = Option(System.getenv("ENV")).getOrElse(defaultEnv), configDirectory = ".", doConfigureLogback = false)
  def computeDatabaseName(schemaLogicalName: String, tenant: Option[String]) = tenant match {
    case None => s"${mzConfig.currentZone}_$schemaLogicalName"
    case Some(ns) => s"${mzConfig.currentZone}_${schemaLogicalName}_$ns"
  }
}

object ZoneAwareDatabaseNameProvider {
  def forProd() = new ZoneAwareDatabaseNameProvider
  def forSandbox() = new ZoneAwareDatabaseNameProvider{
    override def defaultEnv = "integration-sandbox"
  }
}
