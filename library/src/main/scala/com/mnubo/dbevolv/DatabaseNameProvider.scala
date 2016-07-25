package com.mnubo.dbevolv

import com.typesafe.config.Config

/** Computes a database/schema/keyspace out of the schema logical name (the one found in db.conf @ 'schema_name') and the tenant id. For global databases that are not customer specific, the tenant should be None. */
trait DatabaseNameProvider {
  def computeDatabaseName(schemaLogicalName: String, tenant: Option[String], config: Config): String
}

class DefaultDatabaseNameProvider extends DatabaseNameProvider {
  def computeDatabaseName(schemaLogicalName: String, tenant: Option[String], config: Config) = tenant match {
    case None => schemaLogicalName
    case Some(tenantId) => s"${schemaLogicalName}_$tenantId"
  }
}
