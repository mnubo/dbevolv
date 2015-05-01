package com.mnubo.dbschemas

import com.mnubo._
import com.mnubo.app_util.Logging
import org.joda.time.format.ISODateTimeFormat

object DatabaseInspector extends Logging {
  private val fmt = ISODateTimeFormat.dateTime()
  def displayHistory(config: DbMigrationConfig) = {
    import config._

    using(db.openConnection(
      schemaName,
      host,
      port,
      username,
      password,
      name,
      createDatabaseStatement,
      wholeConfig
    )) { connection =>
      println(s"History of $name @ $host:")
      println()
      println("         Version                       Date                    Checksum")
      connection
        .getInstalledMigrationVersions
        .toSeq
        .sortBy(_.version)
        .foreach { case InstalledVersion(v, date, checksum) =>
          val fmtDate = fmt.print(date)
          println(f"$v%16s   $fmtDate%s   $checksum")
        }
    }
  }

}
