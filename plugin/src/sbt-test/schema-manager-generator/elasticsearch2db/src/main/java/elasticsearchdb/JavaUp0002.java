package elasticsearchdb;

import org.elasticsearch.client.transport.TransportClient;

public class JavaUp0002 {
  public void execute(TransportClient client, String indexName) throws Exception {
    client
        .prepareIndex(indexName, "kv", "0002-java")
        .setSource(
            "k", "0002-java",
            "v", "0002-java")
        .get();
  }
}