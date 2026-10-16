package dev.mobilecodex.app;

import android.app.Application;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.mobilecodex.app.core.Json.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=29, application=Application.class)
public class LiveSessionTest {
    Engine engine;
    List<JSONObject> calls = new ArrayList<>();
    JSONObject voiceCatalog;
    @Before public void setup() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        new File(context.getFilesDir(), "sessions.json").delete();
        context.getSharedPreferences("settings",0).edit().clear().commit();
        engine = new Engine(context);
        field("account",obj("type","chatgpt"));
        voiceCatalog = obj("v1",array("cove","sol"),"defaultV1","cove");
        engine.setTestTransport((method,params) -> {
            calls.add(obj("method",method,"params",new JSONObject(params.toString())));
            return switch(method) {
                case "thread/realtime/listVoices" -> obj("voices",voiceCatalog);
                case "thread/start" -> obj("thread",obj("id","remote-live"));
                case "thread/realtime/start", "thread/realtime/stop", "thread/realtime/appendText", "thread/resume" -> obj();
                default -> throw new AssertionError(method);
            };
        });
    }
    @After public void teardown() throws Exception {
        engine.io.shutdownNow();
        Field f=Engine.class.getDeclaredField("approvalTimer");f.setAccessible(true);((ScheduledExecutorService)f.get(engine)).shutdownNow();
    }
    void field(String name,Object value)throws Exception { Field f=Engine.class.getDeclaredField(name);f.setAccessible(true);f.set(engine,value); }
    JSONObject call(String action,JSONObject params)throws Exception {
        CompletableFuture<JSONObject> result=new CompletableFuture<>();
        engine.handle(action,params,(r,e)->{if(e==null)result.complete(r);else result.completeExceptionally(e);});
        return result.get(5,TimeUnit.SECONDS);
    }
    JSONObject args(String key){return obj("key",key,"sdp","v=0\r\n","voice","cove","language","ko","model","selected-model","effort","high");}
    JSONObject params(String method){return calls.stream().filter(c->method.equals(c.optString("method"))).reduce((a,b)->b).orElseThrow().optJSONObject("params");}
    void notice(String method,JSONObject p)throws Exception {
        Method m=Engine.class.getDeclaredMethod("onNotification",String.class,JSONObject.class);m.setAccessible(true);
        engine.io.submit(()->{m.invoke(engine,method,p);return null;}).get(5,TimeUnit.SECONDS);
    }
    void fails(String action,JSONObject p)throws Exception {try{call(action,p);fail("must reject");}catch(ExecutionException expected){assertNotNull(expected.getCause());}}
    @Test public void voicePreservesTaskModelEffortAndPermissionPolicy()throws Exception {
        call("live.start",args("one"));
        JSONObject thread=params("thread/start"), live=params("thread/realtime/start");
        assertEquals("selected-model",thread.getString("model"));
        assertEquals("high",thread.getJSONObject("config").getString("model_reasoning_effort"));
        assertEquals("workspace-write",thread.getString("sandbox"));
        assertEquals("v3",live.getString("version"));assertEquals("webrtc",live.getJSONObject("transport").getString("type"));
        assertFalse(live.has("model"));assertFalse(live.getBoolean("clientManagedHandoffs"));
        assertTrue(live.getString("prompt").contains("Korean"));
        assertTrue(thread.getJSONArray("dynamicTools").length()>0);
    }
    @Test public void stoppingVoiceDoesNotInterruptTaskAndStaleStopsCannotEndReplacement()throws Exception {
        call("live.start",args("one"));call("live.stop",obj("key","one"));
        call("live.start",args("two"));int count=calls.size();
        call("live.stop",obj("key","one"));assertEquals(count,calls.size());
        assertEquals("two",call("state",obj()).getJSONObject("live").getString("key"));
        assertFalse(calls.stream().anyMatch(c->c.optString("method").equals("turn/interrupt")));
    }
    @Test public void unsupportedVoiceIsRejectedBeforeCreatingThread()throws Exception {
        fails("live.start",args("one").put("voice","invented-voice"));
        assertFalse(calls.stream().anyMatch(c->c.optString("method").equals("thread/start")));
    }
    @Test public void newVoiceIsAcceptedFromV3Catalog()throws Exception {
        voiceCatalog.put("v3",array("cove","hanul","haetsal"));
        call("live.start",args("one").put("voice","hanul"));
        assertEquals("hanul",params("thread/realtime/start").getString("voice"));
    }
    @Test public void v1OnlyVoiceIsRejectedWhenV3CatalogIsPresent()throws Exception {
        voiceCatalog.put("v3",array("cove","hanul","haetsal"));
        fails("live.start",args("one").put("voice","sol"));
        assertFalse(calls.stream().anyMatch(c->c.optString("method").equals("thread/start")));
    }
    @Test public void emptyV3CatalogFallsBackToLegacyVoices()throws Exception {
        voiceCatalog.put("v3",array());
        call("live.start",args("one").put("voice","sol"));
        assertEquals("sol",params("thread/realtime/start").getString("voice"));
    }
    @Test public void activeVoiceBlocksCompetingSendAndPermissionChanges()throws Exception {
        call("live.start",args("one"));
        fails("chat.send",obj("text","other"));fails("permissions.set",obj("mode","danger-full-access"));
        fails("live.text",obj("key","stale","text","hello"));
        call("live.text",obj("key","one","text","hello"));
        assertEquals("remote-live",params("thread/realtime/appendText").getString("threadId"));
    }
    @Test public void canonicalTranscriptTailPersistsAfterStopAndIsDeduplicated()throws Exception {
        call("live.start",args("one"));call("live.stop",obj("key","one"));
        JSONObject event=obj("threadId","remote-live","item",obj("id","speech-1","type","transcriptSegment","role","user","text","remember this"));
        notice("thread/realtime/item/completed",event);notice("thread/realtime/item/completed",event);
        assertEquals(1,call("state",obj()).getJSONArray("messages").length());
        JSONArray disk=new JSONArray(dev.mobilecodex.app.core.Utf8Files.read(new File(RuntimeEnvironment.getApplication().getFilesDir(),"sessions.json").toPath()));
        assertEquals("remember this",disk.getJSONObject(0).getJSONArray("messages").getJSONObject(0).getString("text"));
    }
    @Test public void switchingChatEndsOnlyVoiceAndLateErrorsDoNotCloseAnotherThread()throws Exception {
        call("live.start",args("one"));call("chat.new",obj("workspaceKey",""));
        assertEquals("remote-live",params("thread/realtime/stop").getString("threadId"));
        assertEquals("",call("state",obj()).getJSONObject("live").getString("key"));
    }
    @Test public void protocolFailureDoesNotLeaveActiveVoice()throws Exception {
        call("live.start",args("one"));
        notice("thread/realtime/error",obj("threadId","remote-live","message","not entitled"));
        assertEquals("",call("state",obj()).getJSONObject("live").getString("key"));
        assertFalse(calls.stream().anyMatch(c->c.optString("method").equals("turn/start")));
    }
}
