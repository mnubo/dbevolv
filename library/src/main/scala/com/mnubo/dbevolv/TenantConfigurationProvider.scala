package com.mnubo.dbevolv

import com.typesafe.config.{Config, ConfigFactory}

trait TenantConfigurationProvider extends AutoCloseable {
  /**
    * Gets a configuration override for the given tenant.
    * @return a non-null Config for the given tenant. If empty or partial, defaults from the db.conf will be applied for this tenant.
    */
  def configFor(tenant: String): Config
}

class TenantEmptyConfigurationProvider(config: Config) extends TenantConfigurationProvider {
  private val noConfig = ConfigFactory.empty()

  override def configFor(tenant: String) = noConfig

  override def close() = ()
}
