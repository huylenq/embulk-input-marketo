package org.embulk.input.marketo.rest;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.embulk.EmbulkTestRuntime;
import org.embulk.input.marketo.exception.MarketoAPIException;
import org.embulk.input.marketo.model.MarketoError;
import org.embulk.input.marketo.model.MarketoResponse;
import org.embulk.spi.DataException;
import org.embulk.util.retryhelper.jetty92.Jetty92RetryHelper;
import org.embulk.util.retryhelper.jetty92.Jetty92SingleRequester;
import org.embulk.util.retryhelper.jetty92.StringJetty92ResponseEntityReader;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by tai.khuu on 9/21/17.
 */
public class MarketoBaseRestClientTest
{
    private static final String IDENTITY_END_POINT = "identityEndPoint";

    private static final int MARKETO_LIMIT_INTERVAL_MILIS = 1000;

    private MarketoBaseRestClient marketoBaseRestClient;

    private Jetty92RetryHelper mockJetty92;

    @Rule
    public EmbulkTestRuntime runtime = new EmbulkTestRuntime();
    @Before
    public void prepare()
    {
        mockJetty92 = Mockito.mock(Jetty92RetryHelper.class);
        marketoBaseRestClient = new MarketoBaseRestClient("identityEndPoint", "clientId", "clientSecret", MARKETO_LIMIT_INTERVAL_MILIS, mockJetty92);
    }

    @Test
    public void testGetAccessToken()
    {
        Mockito.when(mockJetty92.requestWithRetry(Mockito.any(StringJetty92ResponseEntityReader.class), Mockito.any(Jetty92SingleRequester.class))).thenReturn("{\n" +
                "    \"access_token\": \"access_token\",\n" +
                "    \"token_type\": \"bearer\",\n" +
                "    \"expires_in\": 3599,\n" +
                "    \"scope\": \"tai@treasure-data.com\"\n" +
                "}");
        String accessToken = marketoBaseRestClient.getAccessToken();
        Assert.assertEquals("access_token", accessToken);
    }

    @Test
    public void testGetAccessTokenRequester()
    {
        ArgumentCaptor<Jetty92SingleRequester> jetty92SingleRequesterArgumentCaptor = ArgumentCaptor.forClass(Jetty92SingleRequester.class);
        Mockito.when(mockJetty92.requestWithRetry(Mockito.any(StringJetty92ResponseEntityReader.class), jetty92SingleRequesterArgumentCaptor.capture())).thenReturn("{\"access_token\": \"access_token\"}");
        String accessToken = marketoBaseRestClient.getAccessToken();
        Assert.assertEquals("access_token", accessToken);
        Jetty92SingleRequester value = jetty92SingleRequesterArgumentCaptor.getValue();
        HttpClient client = Mockito.mock(HttpClient.class);
        Response.Listener listener = Mockito.mock(Response.Listener.class);
        Request mockRequest = Mockito.mock(Request.class);
        Mockito.when(client.newRequest(Mockito.eq(IDENTITY_END_POINT + MarketoRESTEndpoint.ACCESS_TOKEN.getEndpoint()))).thenReturn(mockRequest);
        Request request1 = Mockito.mock(Request.class);
        Mockito.when(mockRequest.method(Mockito.eq(HttpMethod.GET))).thenReturn(request1);
        value.requestOnce(client, listener);
        Mockito.verify(request1, Mockito.times(1)).param(Mockito.eq("client_id"), Mockito.eq("clientId"));
        Mockito.verify(request1, Mockito.times(1)).param(Mockito.eq("client_secret"), Mockito.eq("clientSecret"));
        Mockito.verify(request1, Mockito.times(1)).param(Mockito.eq("grant_type"), Mockito.eq("client_credentials"));
        Assert.assertTrue(value.toRetry(createHttpResponseException(502)));
    }
    @Test
    public void testGetAccessTokenWithError()
    {
        ArgumentCaptor<Jetty92SingleRequester> jetty92SingleRequesterArgumentCaptor = ArgumentCaptor.forClass(Jetty92SingleRequester.class);
        Mockito.when(mockJetty92.requestWithRetry(Mockito.any(StringJetty92ResponseEntityReader.class), jetty92SingleRequesterArgumentCaptor.capture())).thenReturn("{\n" +
                "    \"error\": \"invalid_client\",\n" +
                "    \"error_description\": \"Bad client credentials\"\n" +
                "}");
        try {
            marketoBaseRestClient.getAccessToken();
        }
        catch (DataException ex) {
            Assert.assertEquals("Bad client credentials", ex.getMessage());
            return;
        }
        Assert.fail();
    }

    @Test
    public void testDoPost() throws Exception
    {
        MarketoBaseRestClient spy = Mockito.spy(marketoBaseRestClient);
        spy.doPost("target", Maps.<String, String>newHashMap(), new ImmutableListMultimap.Builder<String, String>().build(), "test_content", new StringJetty92ResponseEntityReader(10));
        Mockito.verify(spy, Mockito.times(1)).doRequest(Mockito.anyString(), Mockito.eq(HttpMethod.POST), Mockito.any(Map.class), Mockito.any(Multimap.class), Mockito.any(StringContentProvider.class), Mockito.any(StringJetty92ResponseEntityReader.class));
    }

    @Test
    public void testDoGet() throws Exception
    {
        MarketoBaseRestClient spy = Mockito.spy(marketoBaseRestClient);
        spy.doGet("target", Maps.<String, String>newHashMap(), new ImmutableListMultimap.Builder<String, String>().build(), new StringJetty92ResponseEntityReader(10));
        Mockito.verify(spy, Mockito.times(1)).doRequest(Mockito.anyString(), Mockito.eq(HttpMethod.GET), Mockito.any(Map.class), Mockito.any(Multimap.class), Mockito.isNull(ContentProvider.class), Mockito.any(StringJetty92ResponseEntityReader.class));
    }

    @Test
    public void testDoRequestRequester() throws Exception
    {
        MarketoBaseRestClient spy = Mockito.spy(marketoBaseRestClient);
        StringContentProvider contentProvider = new StringContentProvider("Content", StandardCharsets.UTF_8);
        ArgumentCaptor<Jetty92SingleRequester> jetty92SingleRequesterArgumentCaptor = ArgumentCaptor.forClass(Jetty92SingleRequester.class);

        MarketoResponse<Object> expectedMarketoResponse = new MarketoResponse<>();

        Mockito.when(mockJetty92.requestWithRetry(Mockito.any(MarketoResponseJetty92EntityReader.class), jetty92SingleRequesterArgumentCaptor.capture())).thenReturn(expectedMarketoResponse);
        Mockito.when(mockJetty92.requestWithRetry(Mockito.any(StringJetty92ResponseEntityReader.class), Mockito.any(Jetty92SingleRequester.class))).thenReturn("{\"access_token\": \"access_token\"}");

        String target = "target";
        HashMap<String, String> headers = Maps.<String, String>newHashMap();
        headers.put("testHeader1", "testHeaderValue1");

        ImmutableListMultimap<String, String> build = new ImmutableListMultimap.Builder<String, String>().put("param", "param1").build();

        MarketoResponse<Object> marketoResponse = spy.doRequest(target, HttpMethod.POST, headers, build, contentProvider, new MarketoResponseJetty92EntityReader<Object>(10));

        HttpClient client = Mockito.mock(HttpClient.class);
        Response.Listener listener = Mockito.mock(Response.Listener.class);
        Request mockRequest = Mockito.mock(Request.class);
        Mockito.when(client.newRequest(Mockito.eq(target))).thenReturn(mockRequest);

        Mockito.when(mockRequest.method(Mockito.eq(HttpMethod.POST))).thenReturn(mockRequest);
        Jetty92SingleRequester jetty92SingleRequester = jetty92SingleRequesterArgumentCaptor.getValue();
        jetty92SingleRequester.requestOnce(client, listener);

        Assert.assertEquals(expectedMarketoResponse, marketoResponse);

        Mockito.verify(mockRequest, Mockito.times(1)).header(Mockito.eq("testHeader1"), Mockito.eq("testHeaderValue1"));
        Mockito.verify(mockRequest, Mockito.times(1)).header(Mockito.eq("Authorization"), Mockito.eq("Bearer access_token"));
        Mockito.verify(mockRequest, Mockito.times(1)).param(Mockito.eq("param"), Mockito.eq("param1"));
        Mockito.verify(mockRequest, Mockito.times(1)).content(Mockito.eq(contentProvider), Mockito.eq("application/json"));
    }

    @Test
    public void testDoRequesterRetry() throws Exception
    {
        MarketoBaseRestClient spy = Mockito.spy(marketoBaseRestClient);
        ArgumentCaptor<Jetty92SingleRequester> jetty92SingleRequesterArgumentCaptor = ArgumentCaptor.forClass(Jetty92SingleRequester.class);

        Mockito.when(mockJetty92.requestWithRetry(Mockito.any(MarketoResponseJetty92EntityReader.class), jetty92SingleRequesterArgumentCaptor.capture())).thenReturn(new MarketoResponse<>());
        Mockito.when(mockJetty92.requestWithRetry(Mockito.any(StringJetty92ResponseEntityReader.class), Mockito.any(Jetty92SingleRequester.class))).thenReturn("{\"access_token\": \"access_token\"}");

        spy.doRequest("", HttpMethod.POST, null, null, null, new MarketoResponseJetty92EntityReader<Object>(10));

        HttpClient client = Mockito.mock(HttpClient.class);
        Response.Listener listener = Mockito.mock(Response.Listener.class);
        Request mockRequest = Mockito.mock(Request.class);
        Mockito.when(client.newRequest(Mockito.anyString())).thenReturn(mockRequest);

        Mockito.when(mockRequest.method(Mockito.eq(HttpMethod.POST))).thenReturn(mockRequest);

        Jetty92SingleRequester jetty92SingleRequester = jetty92SingleRequesterArgumentCaptor.getValue();
        jetty92SingleRequester.requestOnce(client, listener);
        Assert.assertTrue(jetty92SingleRequester.toRetry(createHttpResponseException(502)));

        Assert.assertFalse(jetty92SingleRequester.toRetry(createHttpResponseException(400)));

        Assert.assertFalse(jetty92SingleRequester.toRetry(createMarketoAPIException("ERR", "ERR")));
        Assert.assertTrue(jetty92SingleRequester.toRetry(createMarketoAPIException("606", "")));
        Assert.assertTrue(jetty92SingleRequester.toRetry(createMarketoAPIException("615", "")));
        Assert.assertTrue(jetty92SingleRequester.toRetry(createMarketoAPIException("602", "")));

        Mockito.verify(mockJetty92, Mockito.times(2)).requestWithRetry(Mockito.any(StringJetty92ResponseEntityReader.class), Mockito.any(Jetty92SingleRequester.class));
    }

    private HttpResponseException createHttpResponseException(int statusCode)
    {
        HttpResponseException exception = Mockito.mock(HttpResponseException.class);
        Response response = Mockito.mock(Response.class);
        Mockito.when(exception.getResponse()).thenReturn(response);
        Mockito.when(response.getStatus()).thenReturn(statusCode);
        return exception;
    }

    private MarketoAPIException createMarketoAPIException(String code, String error)
    {
        MarketoError marketoError = new MarketoError();
        marketoError.setCode(code);
        marketoError.setMessage(error);
        return new MarketoAPIException(Lists.newArrayList(marketoError));
    }
}
