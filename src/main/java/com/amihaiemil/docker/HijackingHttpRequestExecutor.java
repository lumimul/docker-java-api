package com.amihaiemil.docker;

import org.apache.http.HttpClientConnection;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.ProtocolException;
import org.apache.http.ProtocolVersion;
import org.apache.http.entity.AbstractHttpEntity;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpRequestExecutor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

public class HijackingHttpRequestExecutor extends HttpRequestExecutor {

    public static final String HIJACKED_INPUT_ATTRIBUTE = "com.amihaiemil.docker.hijackedInput";

    private static final int DEFAULT_WAIT_FOR_CONTINUE = 3000;

    HijackingHttpRequestExecutor(final int waitForContinue) {
        super(waitForContinue);
    }

    @Override
    public HttpResponse execute(
            final HttpRequest request,
            final HttpClientConnection conn,
            final HttpContext context) throws IOException, HttpException {
        Objects.requireNonNull(request, "HTTP request");
        Objects.requireNonNull(conn, "Client connection");
        Objects.requireNonNull(context, "HTTP context");

        InputStream hijackedInput = (InputStream) context.getAttribute(HIJACKED_INPUT_ATTRIBUTE);
        if (hijackedInput != null) {
            return executeHijacked(request, conn, context, hijackedInput);
        }

        return super.execute(request, conn, context);
    }

    private HttpResponse executeHijacked(
            final HttpRequest request,
            final HttpClientConnection conn,
            final HttpContext context,
            final InputStream hijackedInput
    ) throws HttpException, IOException {
        try {

            HttpResponse response = null;
            context.setAttribute(HttpCoreContext.HTTP_CONNECTION, conn);
            context.setAttribute(HttpCoreContext.HTTP_REQ_SENT, Boolean.FALSE);
            conn.sendRequestHeader(request);
            if (request instanceof HttpEntityEnclosingRequest) {
                conn.sendRequestEntity((HttpEntityEnclosingRequest) request);
                if (conn.isResponseAvailable(DEFAULT_WAIT_FOR_CONTINUE)) {
                    response = conn.receiveResponseHeader();
                    if (canResponseHaveBody(request, response)) {
                        conn.receiveResponseEntity(response);
                    }
                    final int status = response.getStatusLine().getStatusCode();
                    if (status != HttpStatus.SC_SWITCHING_PROTOCOLS) {
                        closeConnection(conn);
                        throw new ProtocolException("Expected 101 Switching Protocols, got: " + status);
                    }
                    Thread thread = new Thread(() -> {
                        try {
                            HttpEntityEnclosingRequest fakeRequest = new BasicHttpEntityEnclosingRequest("POST", "/");
                            fakeRequest.setHeader(HttpHeaders.CONTENT_LENGTH, "" + Long.MAX_VALUE);
                            fakeRequest.setEntity(new HijackedEntity(hijackedInput));
                            conn.sendRequestEntity(fakeRequest);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                    thread.setName(HijackingHttpRequestExecutor.class.getName() + System.identityHashCode(request));
                    thread.setDaemon(true);
                    thread.start();
                }

            }
            conn.flush();
            // 101 -> 200
            if (response != null) {
                response.setStatusCode(200);
                conn.receiveResponseEntity(response);
            }
            return response;

        } catch (final HttpException | IOException | RuntimeException ex) {
            closeConnection(conn);
            throw ex;
        }
    }

    private void closeConnection(final HttpClientConnection conn) {
        try {
            conn.close();
        } catch (final IOException ignore) {
        }
    }

    private static class HijackedEntity extends AbstractHttpEntity {

        private final InputStream inStream;

        HijackedEntity(InputStream inStream) {
            this.inStream = inStream;
        }

        @Override
        public void writeTo(OutputStream outStream) throws IOException {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = inStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, read);
                outStream.flush();
            }
        }

        @Override
        public InputStream getContent() {
            return inStream;
        }

        @Override
        public boolean isStreaming() {
            return true;
        }

        @Override
        public boolean isRepeatable() {
            return false;
        }

        @Override
        public long getContentLength() {
            return -1;
        }
    }
}
