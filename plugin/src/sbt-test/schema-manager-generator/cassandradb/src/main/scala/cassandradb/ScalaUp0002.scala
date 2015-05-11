package cassandradb

import com.datastax.driver.core.Session

class ScalaUp0002 {
  def execute(session: Session, dbName: String) = {
    session.execute("INSERT INTO kv (k, v) VALUES ('0002-scala', '0002-scala')")
  }
}