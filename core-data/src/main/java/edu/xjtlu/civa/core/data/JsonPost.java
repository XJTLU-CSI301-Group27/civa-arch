package edu.xjtlu.civa.core.data;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Minimal HTTPS JSON POST for app-private integrations that are not part of the shared API
 * contract (the on-device nutrition estimate currently calls an external provider directly).
 * Feature modules must never open their own connections, so the transport lives here.
 */
public final class JsonPost {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build();

    private JsonPost() { }

    public static final class Result {
        public final int status;
        public final String body;

        Result(int status, String body) { this.status = status; this.body = body; }
    }

    public static final class Failure extends Exception {
        public Failure(String message, Throwable cause) { super(message, cause); }
    }

    /** One cancellable request; a call may only be executed once. */
    public static final class Call {
        private volatile okhttp3.Call call;
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
            okhttp3.Call inFlight = call;
            if (inFlight != null) inFlight.cancel();
        }

        public Result execute(String url, String bearerToken, String jsonBody) throws Failure {
            if (cancelled) throw new Failure("Cancelled", null);
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + bearerToken)
                    .post(RequestBody.create(jsonBody, JSON))
                    .build();
            okhttp3.Call running = CLIENT.newCall(request);
            call = running;
            if (cancelled) { running.cancel(); throw new Failure("Cancelled", null); }
            try (Response response = running.execute()) {
                ResponseBody body = response.body();
                if (body == null) throw new Failure("Empty response", null);
                String text = body.string();
                if (text.length() > MAX_RESPONSE_BYTES) throw new Failure("Response too large", null);
                return new Result(response.code(), text);
            } catch (IOException error) {
                if (cancelled) throw new Failure("Cancelled", error);
                throw new Failure("Transport failure", error);
            } finally {
                call = null;
            }
        }
    }
}
