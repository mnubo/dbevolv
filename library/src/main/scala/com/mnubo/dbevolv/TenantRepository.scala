package com.mnubo
package dbevolv

trait TenantRepository extends AutoCloseable{
  def fetchTenants: Seq[String]
}
