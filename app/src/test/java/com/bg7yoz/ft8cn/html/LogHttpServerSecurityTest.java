package com.bg7yoz.ft8cn.html;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class LogHttpServerSecurityTest {
    private LogHttpServer server;

    @Before
    public void setUp() {
        server = new LogHttpServer(null, 0, false);
    }

    @After
    public void tearDown() {
        server.stop();
    }

    @Test
    public void unauthenticatedRequestIsRejected() {
        NanoHTTPD.Response response = server.serve(
                new FakeSession(NanoHTTPD.Method.GET, "/", new HashMap<>(), new HashMap<>()));

        assertEquals(401, response.getStatus().getRequestStatus());
    }

    @Test
    public void destructiveGetIsRejectedEvenWhenAuthenticated() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("token", server.getAccessToken());

        NanoHTTPD.Response response = server.serve(
                new FakeSession(NanoHTTPD.Method.GET, "/delQsl/202608", parameters, new HashMap<>()));

        assertEquals(405, response.getStatus().getRequestStatus());
    }

    @Test
    public void mutationWithoutCsrfIsRejected() {
        Map<String, String> parameters = new HashMap<>();
        parameters.put("token", server.getAccessToken());

        NanoHTTPD.Response response = server.serve(
                new FakeSession(NanoHTTPD.Method.POST, "/delQsl/202608", parameters, new HashMap<>()));

        assertEquals(403, response.getStatus().getRequestStatus());
    }

    @Test
    public void oversizedRequestIsRejectedBeforeParsing() {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-length", Integer.toString(2 * 1024 * 1024 + 1));

        NanoHTTPD.Response response = server.serve(
                new FakeSession(NanoHTTPD.Method.POST, "/importLogData", new HashMap<>(), headers));

        assertEquals(400, response.getStatus().getRequestStatus());
    }

    private final class FakeSession implements NanoHTTPD.IHTTPSession {
        private final NanoHTTPD.Method method;
        private final String uri;
        private final Map<String, String> parameters;
        private final Map<String, String> headers;
        private final NanoHTTPD.CookieHandler cookies;

        FakeSession(
                NanoHTTPD.Method method,
                String uri,
                Map<String, String> parameters,
                Map<String, String> headers
        ) {
            this.method = method;
            this.uri = uri;
            this.parameters = parameters;
            this.headers = headers;
            this.cookies = server.new CookieHandler(headers);
        }

        @Override
        public void execute() {
        }

        @Override
        public NanoHTTPD.CookieHandler getCookies() {
            return cookies;
        }

        @Override
        public Map<String, String> getHeaders() {
            return headers;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public NanoHTTPD.Method getMethod() {
            return method;
        }

        @Override
        public Map<String, String> getParms() {
            return parameters;
        }

        @Override
        public String getQueryParameterString() {
            return null;
        }

        @Override
        public String getUri() {
            return uri;
        }

        @Override
        public void parseBody(Map<String, String> files) throws IOException {
        }
    }
}
