package multitenantdb

import com.mnubo.dbevolv._

import com.typesafe.config.{ConfigFactory, Config}

class TenantConfiguration(config: Config) extends TenantConfigurationProvider {
  private val awesomeConfig = ConfigFactory.parseString("shard_number = 2")
  private val noConfig = ConfigFactory.empty()

  override def configFor(tenant: String) =
    if (tenant == "awesomecustomer") awesomeConfig
    else noConfig

  override def close() = ()
}
