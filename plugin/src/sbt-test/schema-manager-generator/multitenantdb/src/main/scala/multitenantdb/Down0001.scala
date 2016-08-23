package multitenantdb

import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.action.admin.indices.mapping.delete.DeleteMappingRequest

class Down0001 {
  def execute(client: TransportClient, indexName: String) = {
    if (!client
      .admin
      .indices
      .deleteMapping(new DeleteMappingRequest(indexName).types("kv"))
      .get
      .isAcknowledged)
      throw new Exception("Cannot delete kv type")
  }
}