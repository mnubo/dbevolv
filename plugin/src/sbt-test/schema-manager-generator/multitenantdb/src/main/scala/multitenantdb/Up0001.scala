package multitenantdb

import org.elasticsearch.client.transport.TransportClient

class Up0001 {
  def execute(client: TransportClient, indexName: String) = {
    if (!client
      .admin
      .indices
      .preparePutMapping(indexName)
      .setType("kv")
      .setSource(
        """
          |{
          |  "kv": {
          |    "properties": {
          |      "k": {"type": "string", "index": "not_analyzed"},
          |      "v": {"type": "string", "index": "not_analyzed"},
          |      "meta": {
          |        "type": "nested",
          |        "properties": {
          |          "category": {"type": "string", "index": "not_analyzed"}
          |        }
          |      }
          |    }
          |  }
          |}
        """.stripMargin)
      .get
      .isAcknowledged)
      throw new Exception(s"Cannot add mappings for kv type in $indexName index.")
  }
}
