package elasticsearchdb

import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.action.deletebyquery.{DeleteByQueryAction, DeleteByQueryRequest, DeleteByQueryRequestBuilder}

class ScalaDown0002 {
  def execute(client: TransportClient, indexName: String) =
    new DeleteByQueryRequestBuilder(client, DeleteByQueryAction.INSTANCE)
      .setTypes("kv")
      .setQuery(QueryBuilders.matchAllQuery())
      .get

}