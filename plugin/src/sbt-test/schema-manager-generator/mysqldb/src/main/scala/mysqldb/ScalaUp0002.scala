package mysqldb

import java.sql.Connection

class ScalaUp0002 {
  def execute(connection: Connection, dbName: String) = {
    connection.createStatement().execute("INSERT INTO kv (k, v) VALUES ('0002-scala', '0002-scala') ON DUPLICATE KEY UPDATE k = VALUES(k), v = VALUES(v)")
  }
}