package com.mnubo
package dbevolv

import com.typesafe.config.Config

trait TenantRepository {
  def fetchTenants: Seq[String]
}

class TestTenantsRepository(config: Config) extends TenantRepository {
  def fetchTenants: Seq[String] = Seq("cars", "cows", "printers")
}
