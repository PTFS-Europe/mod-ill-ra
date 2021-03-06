package org.folio.service.illsupplingagency;

import io.vertx.core.Context;
import io.vertx.core.json.JsonObject;
import org.folio.rest.jaxrs.model.*;
import org.folio.rest.jaxrs.model.supplying_agency_message_storage.request.SupplyingAgencyMessageStorageRequest;
import org.folio.rest.jaxrs.model.supplying_agency_message_storage.response.SupplyingAgencyMessageStorageResponse;
import org.folio.rest.tools.client.interfaces.HttpClientInterface;
import org.folio.service.BaseService;
import org.folio.common.OkapiParams;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.folio.config.Constants.*;

public class IllSupplyingAgencyService extends BaseService {

  private static final String STORAGE_SERVICE = "/ill-ra-storage/";

  public CompletableFuture<SaRequestResponse> sendSupplierRequest(JsonObject submission, Context context, Map<String, String> headers) {
    OkapiParams okapiParams = new OkapiParams(headers);
    HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(CONNECTOR_CONNECT_TIMEOUT))
      .build();
    HttpRequest.Builder request = HttpRequest.newBuilder()
      .uri(URI.create(okapiParams.getUrl() + "/action"))
      .timeout(Duration.ofSeconds(CONNECTOR_RESPONSE_TIMEOUT))
      .POST(HttpRequest.BodyPublishers.ofString(JsonObject.mapFrom(submission).toString()));

    // Add our existing headers
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      request.header(entry.getKey(), entry.getValue());
    }
    // Add additional missing headers
    request.header("Content-type", "application/json");
    request.header("Accept", "application/json");

    HttpRequest builtRequest = request.build();
    // Send the request, receive the response, convert it into a response object
    // then complete the future with it
    CompletableFuture<HttpResponse<String>> future = client.sendAsync(builtRequest, HttpResponse.BodyHandlers.ofString());
    return future.thenApply(apiResponse -> new JsonObject(apiResponse.body()).mapTo(SaRequestResponse.class));
  }

  public CompletableFuture<SupplyingAgencyMessageStorageResponse> storeSupplierMessage(SupplyingAgencyMessageStorageRequest message, String requestId, Context context, Map<String, String> headers) {
    HttpClientInterface client = getHttpClient(headers);
    return handlePostRequest(JsonObject.mapFrom(message), STORAGE_SERVICE + "messages", client, context, headers, logger)
      .thenApply(id -> JsonObject.mapFrom(message.withMessage(id))
          .mapTo(SupplyingAgencyMessageStorageResponse.class))
      .handle((req, t) -> {
        client.closeClient();
        if (Objects.nonNull(t)) {
          throw new CompletionException(t.getCause());
        }
        return req;
      });
  }

  public CompletableFuture<Samss> getSupplierMessages(String requestId, Context context, Map<String, String> headers) {
    CompletableFuture<Samss> future = new CompletableFuture<>();
    HttpClientInterface client = getHttpClient(headers);
    String endpoint = STORAGE_SERVICE + "requests/" + requestId + "/messages";
    handleGetRequest(endpoint, client, headers, logger)
      .thenApply(json -> json.mapTo(Samss.class))
      .handle((messages, t) -> {
        client.closeClient();
        if (Objects.nonNull(t)) {
          future.completeExceptionally(t.getCause());
        } else {
          future.complete(messages);
        }
        return null;
      });
    return future;
  }

  public CompletableFuture<SearchResponse> sendSearch(String query, String connector, int offset, int limit, Map<String, String> headers) {
    HttpClientInterface client = getHttpClient(headers);
    CompletableFuture<SearchResponse> future = new CompletableFuture<>();

    // Add a header specifying the connector module ID
    // that was passed in the querystring
    headers.put("x-okapi-module-id", connector);

    // URLEncode our search terms before passing
    String encodedQuery = "";
    try {
      encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString());
    } catch(UnsupportedEncodingException e) {
      System.out.println(e.getMessage());
    }
    handleGetRequest("/ill-connector/search?query=" + encodedQuery + "&offset=" + offset + "&limit=" + limit, client, headers, logger)
      .thenApply(json -> json.mapTo(SearchResponse.class))
      .handle((searchResponse, t) -> {
        client.closeClient();
        if (Objects.nonNull(t)) {
          future.completeExceptionally(t.getCause());
        }
        future.complete(searchResponse);
        return null;
      })
      .exceptionally(throwable -> {
        client.closeClient();
        future.completeExceptionally(throwable);
        return null;
      });
      return future;
  }

}
