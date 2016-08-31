package elasticsearchdb

import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.index.query.QueryBuilders

class ScalaUp0002 {
  def execute(client: TransportClient, indexName: String) =
    client
      .prepareIndex(indexName, "kv", "0002-scala")
      .setSource(
        "k", "0002-scala".asInstanceOf[Any],
        "v", "0002-scala".asInstanceOf[Any])
      .get
}