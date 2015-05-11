package cassandradb;

import com.datastax.driver.core.Session;

public class JavaUp0002 {
  public void execute(Session session, String dbName) throws java.sql.SQLException {
    session
      .execute("INSERT INTO kv (k, v) VALUES ('0002-java', '0002-java')");
  }
}