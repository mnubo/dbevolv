package com.mnubo.dbevolv

import com.mnubo.dbevolv.util.Logging
import org.joda.time.format.ISODateTimeFormat

object DatabaseInspector extends Logging {
  private val fmt = ISODateTimeFormat.dateTime()
  def displayHistory(config: DbMigrationConfig) = {
    import config._

    println(s"History of $name:")
    println()
    println("         Version                       Date                           Checksum")
    config
      .connection
      .getInstalledMigrationVersions
      .toSeq
      .sortBy(_.version)
      .foreach { case InstalledVersion(v, date, checksum) =>
        val fmtDate = fmt.print(date)
        println(f"$v%16s   $fmtDate%s   $checksum")
      }
  }

}
