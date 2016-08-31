package multitenantdb

import com.mnubo.dbevolv._

import com.typesafe.config.Config

class TenantsRepository(config: Config) extends TenantRepository {
  override def fetchTenants = Seq("greatcustomer", "awesomecustomer")
  override def close() = ()
}