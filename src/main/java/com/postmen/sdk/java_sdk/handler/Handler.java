package com.postmen.sdk.java_sdk.handler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.http.json.JsonHttpContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.Beta;
import com.google.gson.Gson;
import com.postmen.sdk.java_sdk.config.Config;
import com.postmen.sdk.java_sdk.exception.PostmenException;
import com.postmen.sdk.java_sdk.model.MapResponse;
import com.postmen.sdk.java_sdk.model.Response;
import com.postmen.sdk.java_sdk.util.PostmenUrl;

public class Handler {
	private final int numberOfRetries = 5;
	
	private HttpTransport HTTPTRANSPORT;
	private JsonFactory JSONFACTORY;
	private HttpRequestFactory requestFactory;
	private Gson gson;
	
	private HttpHeaders headers;
	private Config config;
	
	private RateLimit rateLimit;
	private Logger logger;
	
	private PostmenUnsuccesfulResponseHandler responseHandler;
	
	private PostmenSleeper postmenSleeper;
	
	public Handler(Config config) {
		this.config = config;
		initClasses();
		initHeaders();
		initializeRequestFactory();
		responseHandler = new PostmenUnsuccesfulResponseHandler(postmenSleeper, new ExpBackOff(), numberOfRetries);
		
	}
	
	private void initClasses() {
		rateLimit = new RateLimit();
		postmenSleeper = new PostmenSleeper();
		gson = new Gson();
		JSONFACTORY = new JacksonFactory();
		headers = new HttpHeaders();
		logger = LoggerFactory.getLogger(Handler.class);
	}
	
	private void initHeaders(){
		headers.setContentType("application/json");
		headers.set("postmen-api-key", config.getApiKey());
		headers.set("x-postmen-agent", "java-sdk-1.0.0");
		headers.set("connection", "keep-alive");
	}
	
	private void initializeRequestFactory() {
		if(config.getProxyUrl() != null) {
			Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(config.getProxyUrl(), config.getProxyPort()));
			HTTPTRANSPORT = new NetHttpTransport.Builder().setProxy(proxy).build();
		} else {
			HTTPTRANSPORT = new NetHttpTransport();
		}
		requestFactory = HTTPTRANSPORT.createRequestFactory(new HttpRequestInitializer() {
			public void initialize(HttpRequest request) {
				request.setParser(new JsonObjectParser(JSONFACTORY));
				request.setHeaders(headers);
				request.setUnsuccessfulResponseHandler(new RateLimitHttpUnsuccesfulResponseHandler(postmenSleeper, rateLimit, new ExpBackOff()));
				request.setInterceptor(new RateLimitExecuteInterceptor(rateLimit, postmenSleeper));
				request.setNumberOfRetries(numberOfRetries);
			}
		});
	}
	
	public <T extends Response> T call(String method, PostmenUrl endpoint, Object body, Class<T> type) throws IOException {
		return execute(requestFactory, method, endpoint, body, type);
	}
	
	public String callAndParseAsString(String method, PostmenUrl endpoint, Object body) throws IOException {
		MapResponse response = execute(requestFactory, method, endpoint, body, MapResponse.class);
		return gson.toJson(response);
	}
	
	public MapResponse callAndParseAsMap(String method, PostmenUrl endpoint, Object body) throws IOException {
		return execute(requestFactory, method, endpoint, body, MapResponse.class);
	}
	
	// master method
	public <T extends Response> T execute(HttpRequestFactory requestFactory, String method, PostmenUrl endpoint, Object body, Class<T> type) throws IOException {
		HttpContent content = null;
		if (body != null) {
			content = new JsonHttpContent(JSONFACTORY, body);
		}
		HttpResponse response = null;
		HttpRequest request = null;
		boolean retry = false;
		T res = null;
		do {
			request = requestFactory.buildRequest(method, endpoint, content);
			response = request.execute();
			rateLimit.setRateLimit(response.getHeaders());
			res = response.parseAs(type);
			retry = responseHandler.handleResponse(request, res, config.isRetry());
			System.out.println(retry);
		} while (retry);
		
		if(res.getMeta().getCode() > 200) {
			throw new PostmenException(res);
		}
		return res;
	}
	
	@Beta
	public <T extends Response> Future<T> executeAysnc(
			final String method, final PostmenUrl endpoint, final Object body, final Class<T> type
			) throws InterruptedException {
		
		Callable<T> req = new Callable<T>() {
			public T call() throws IOException {
				return execute(requestFactory, method, endpoint, body, type);
			}
		};
		
		ExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		Future<T> f = executor.submit(req);
		executor.awaitTermination(1, TimeUnit.MINUTES);
		return f;
	}
	
	public void setResponseHandler(PostmenUnsuccesfulResponseHandler responseHandler) {
		this.responseHandler = responseHandler;
	}
	
	public PostmenUnsuccesfulResponseHandler getResponseHandler() {
		return responseHandler;
	}
}