@@mysqldb.ScalaUp0002;

INSERT INTO kv (k, v)
VALUES ('0002-sql', '0002-sql')
ON DUPLICATE KEY UPDATE
  k = VALUES(k),
  v = VALUES(v);

@@mysqldb.JavaUp0002;
