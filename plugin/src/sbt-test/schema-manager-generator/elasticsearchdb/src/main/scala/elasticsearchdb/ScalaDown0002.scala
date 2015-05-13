package elasticsearchdb

import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.index.query.QueryBuilders

class ScalaDown0002 {
  def execute(client: TransportClient, indexName: String) =
    client
      .prepareDeleteByQuery(indexName)
      .setTypes("kv")
      .setQuery(QueryBuilders.matchAllQuery())
      .get
}