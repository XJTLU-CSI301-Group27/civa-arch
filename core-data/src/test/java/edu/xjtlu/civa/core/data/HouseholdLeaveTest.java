package edu.xjtlu.civa.core.data;

import edu.xjtlu.civa.core.ui.ApiError;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public class HouseholdLeaveTest {
    private static final String USER="7e0dd3fb-30d6-4df5-b495-fd067610db79", HOME="8e0dd3fb-30d6-4df5-b495-fd067610db79";
    private static final String MEMBERS="{\"items\":[{\"userId\":\""+USER+"\",\"active\":true,\"version\":7}],\"page\":0,\"size\":100,\"total\":1}";

    @Test public void confirmedLeaveKeepsLoginAndInvalidatesQueuedHouseholdWork() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            server.start();SessionStore sessions=new SessionStore();sessions.installIfCurrent(0,"test-token",USER,HOME);
            BlockingQueue<Runnable> main=new LinkedBlockingQueue<>();ApiCalls calls=new ApiCalls(sessions,main::add);
            AuthRepository auth=new AuthRepository(new RealApiProvider(server.url("/").toString(),sessions),sessions,calls);
            AtomicBoolean left=new AtomicBoolean();server.enqueue(new MockResponse().setBody(MEMBERS));server.enqueue(new MockResponse().setResponseCode(204));
            auth.leaveHousehold(callback(left));assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));run(main);
            var deletion=server.takeRequest(5,TimeUnit.SECONDS);assertNotNull(deletion);
            assertEquals("DELETE",deletion.getMethod());assertEquals("/api/v1/households/"+HOME+"/members/"+USER+"?expectedVersion=7",deletion.getPath());
            Runnable deletionResult=main.poll(5,TimeUnit.SECONDS);assertNotNull(deletionResult);
            AtomicBoolean stale=new AtomicBoolean();calls.deliver(sessions.snapshot().generation,()->stale.set(true));
            deletionResult.run();run(main);assertTrue(left.get());assertFalse(stale.get());assertTrue(sessions.isLoggedIn());assertNull(sessions.currentHouseholdId());
        }
    }

    @Test public void ownerRejectionDoesNotChangeHouseholdOrLogin() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            server.start();SessionStore sessions=new SessionStore();sessions.installIfCurrent(0,"test-token",USER,HOME);
            BlockingQueue<Runnable> main=new LinkedBlockingQueue<>();ApiCalls calls=new ApiCalls(sessions,main::add);
            AuthRepository auth=new AuthRepository(new RealApiProvider(server.url("/").toString(),sessions),sessions,calls);
            AtomicReference<String> failure=new AtomicReference<>();server.enqueue(new MockResponse().setBody(MEMBERS));
            server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"code\":\"OWNER_CANNOT_LEAVE\",\"message\":\"Owner cannot leave\",\"traceId\":\"test-trace\",\"fieldErrors\":[]}"));
            auth.leaveHousehold(new ApiCallback<Void>() {public void onSuccess(Void value){fail("Owner left");}public void onError(ApiError error){failure.set(error.code);}});
            assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));run(main);assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));run(main);
            assertEquals("OWNER_CANNOT_LEAVE",failure.get());assertEquals(HOME,sessions.currentHouseholdId());assertTrue(sessions.isLoggedIn());
        }
    }

    @Test public void lateDeleteCannotClearAnotherAccount() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            server.start();SessionStore sessions=new SessionStore();sessions.installIfCurrent(0,"test-token",USER,HOME);
            BlockingQueue<Runnable> main=new LinkedBlockingQueue<>();ApiCalls calls=new ApiCalls(sessions,main::add);
            AuthRepository auth=new AuthRepository(new RealApiProvider(server.url("/").toString(),sessions),sessions,calls);
            AtomicBoolean delivered=new AtomicBoolean();server.enqueue(new MockResponse().setBody(MEMBERS));server.enqueue(new MockResponse().setResponseCode(204));
            auth.leaveHousehold(callback(delivered));assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));run(main);assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));
            Runnable queued=main.poll(5,TimeUnit.SECONDS);assertNotNull(queued);
            sessions.clear();sessions.installIfCurrent(sessions.snapshot().generation,"another-token","another-user","another-home");queued.run();
            assertFalse(delivered.get());assertEquals("another-home",sessions.currentHouseholdId());
        }
    }

    @Test public void lostDeleteResponseReconcilesWithServerProfile() throws Exception {
        try(MockWebServer server=new MockWebServer()) {
            server.start();SessionStore sessions=new SessionStore();sessions.installIfCurrent(0,"test-token",USER,HOME);
            BlockingQueue<Runnable> main=new LinkedBlockingQueue<>();ApiCalls calls=new ApiCalls(sessions,main::add);
            AuthRepository auth=new AuthRepository(new RealApiProvider(server.url("/").toString(),sessions),sessions,calls);
            AtomicBoolean left=new AtomicBoolean();server.enqueue(new MockResponse().setBody(MEMBERS));
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
            server.enqueue(new MockResponse().setBody("{\"id\":\""+USER+"\",\"username\":\"test\",\"displayName\":\"Test\",\"zoneId\":\"UTC\",\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-01T00:00:00Z\",\"householdId\":null}"));
            auth.leaveHousehold(callback(left));assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));run(main);
            assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));run(main);
            assertEquals("/api/v1/me",server.takeRequest(5,TimeUnit.SECONDS).getPath());run(main);
            assertTrue(left.get());assertNull(sessions.currentHouseholdId());assertTrue(sessions.isLoggedIn());
        }
    }

    private static ApiCallback<Void> callback(AtomicBoolean done){return new ApiCallback<Void>() {public void onSuccess(Void value){done.set(true);}public void onError(ApiError error){fail(error.code);}};}
    private static void run(BlockingQueue<Runnable> main)throws Exception{Runnable value=main.poll(5,TimeUnit.SECONDS);assertNotNull(value);value.run();}
}
