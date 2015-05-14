package elasticsearchdb

import org.elasticsearch.client.transport.TransportClient

class ScalaUp0001 {
  def execute(client: TransportClient, indexName: String) = {
    if (!client
      .admin
      .indices
      .preparePutMapping(indexName)
      .setType("kv")
      .setSource(
        "_id",         "path=k",
        "k",           "type=string,index=not_analyzed",
        "v",           "type=string,index=not_analyzed"
      )
      .get
      .isAcknowledged)
      throw new Exception(s"Cannot add mappings for kv type in $indexName index.")
  }
}