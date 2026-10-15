package dev.mobilecodex.app;

import android.app.Application;
import android.content.Context;
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
public class AgentEngineTest {
    Context context;
    Engine engine;
    final List<JSONObject> calls = Collections.synchronizedList(new ArrayList<>());
    @Before public void setup(){
        context=RuntimeEnvironment.getApplication();
        new File(context.getFilesDir(),"sessions.json").delete();
        context.getSharedPreferences("projects",0).edit().clear().commit();
        context.getSharedPreferences("workspace",0).edit().clear().commit();
        engine=new Engine(context);engine.setTestTransport((m,p)->{throw new AssertionError("Unexpected RPC: "+m);});
    }
    @After public void teardown() throws Exception {
        engine.io.shutdownNow();Field f=Engine.class.getDeclaredField("approvalTimer");f.setAccessible(true);((ScheduledExecutorService)f.get(engine)).shutdownNow();
        new File(context.getFilesDir(),"sessions.json").delete();
    }
    JSONObject host(String method,JSONObject args)throws Exception{
        CompletableFuture<JSONObject> f=new CompletableFuture<>();Method m=Engine.class.getDeclaredMethod("roomHost",String.class,JSONObject.class,Engine.Reply.class);m.setAccessible(true);
        m.invoke(engine,"host."+method,args,(Engine.Reply)(r,e)->{if(e==null)f.complete(r);else f.completeExceptionally(e);});return f.get(5,TimeUnit.SECONDS);
    }
    JSONObject call(String action,JSONObject args)throws Exception{CompletableFuture<JSONObject> f=new CompletableFuture<>();engine.handle(action,args,(r,e)->{if(e==null)f.complete(r);else f.completeExceptionally(e);});return f.get(5,TimeUnit.SECONDS);}
    void fails(Callable<?> action)throws Exception{try{action.call();fail("Operation should fail closed");}catch(ExecutionException expected){assertNotNull(expected.getCause());}}
    void field(String name,Object value)throws Exception{Field f=Engine.class.getDeclaredField(name);f.setAccessible(true);f.set(engine,value);}
    @SuppressWarnings("unchecked") Map<String,JSONObject> leases()throws Exception{Field f=Engine.class.getDeclaredField("roomLeases");f.setAccessible(true);return (Map<String,JSONObject>)f.get(engine);}
    void notify(String method,JSONObject params)throws Exception{
        Method m=Engine.class.getDeclaredMethod("onNotification",String.class,JSONObject.class);m.setAccessible(true);
        engine.io.submit(()->{m.invoke(engine,method,params);return null;}).get(5,TimeUnit.SECONDS);
    }
    void codex(){
        engine.setTestTransport((method,params)->{
            calls.add(obj("method",method,"params",new JSONObject(params.toString())));
            return switch(method){
                case "thread/start"->obj("thread",obj("id","remote-1"));
                case "thread/resume"->obj();
                case "turn/start"->obj("turn",obj("id","turn-"+calls.size()));
                case "turn/interrupt"->obj();
                default->throw new AssertionError(method);
            };
        });
    }
    List<JSONObject> turns(){synchronized(calls){List<JSONObject> out=new ArrayList<>();for(JSONObject c:calls)if(c.optString("method").equals("turn/start"))out.add(c.optJSONObject("params"));return out;}}
    JSONObject waitFor(Callable<Boolean> done)throws Exception{for(int i=0;i<200;i++){JSONObject state=call("state",obj());if(done.call())return state;Thread.sleep(25);}fail("Condition was not reached");return null;}
    static JSONObject agentMessage(JSONObject state,String agent){JSONArray messages=state.optJSONArray("messages");for(int i=messages.length()-1;i>=0;i--)if(agent.equals(messages.optJSONObject(i).optString("agent")))return messages.optJSONObject(i);return null;}

    @Test public void chatAgentHasNoToolsAndCannotEscalateItsLease()throws Exception{
        leases().put("one",obj("roomId","one","mode","chat","projectKey",""));
        assertEquals(0,host("tools",obj("roomId","one")).getJSONArray("tools").length());
        fails(()->host("tool",obj("roomId","one","mode","write","projectKey","","name","mobile_create","arguments",obj("path","bad.txt","content","bad"))));
        fails(()->host("tool",obj("roomId","one","name","mobile_read","arguments",obj("path","secret"))));
        leases().remove("one");
        fails(()->host("tools",obj("roomId","one")));
    }
    @Test public void answeringAgentExcludesTerminalPermissionsAndUpdates()throws Exception{
        leases().put("one",obj("roomId","one","mode","chat","projectKey",""));
        fails(()->call("terminal.run",obj("command","echo bad")));
        fails(()->call("permissions.set",obj("mode","danger-full-access")));
        assertFalse(engine.canInstallUpdate());
        leases().remove("one");assertTrue(engine.canInstallUpdate());
    }
    @Test public void removedRoomGithubAndProActionsAreRejected()throws Exception{
        for(String action:List.of("rooms.list","rooms.create","rooms.send","sync.status","sync.login.start","sync.apply","chat.pro.prepare","chat.pro.complete"))
            fails(()->call(action,obj()));
        fails(()->call("chat.send",obj("text","hello","consultPro",true)));
        assertFalse(dev.mobilecodex.app.core.ToolCatalog.all().toString().contains("mobile_consult_pro"));
    }
    @Test public void readOnlyCatalogExcludesMutationsAndCommand()throws Exception{
        Method m=Engine.class.getDeclaredMethod("roomTools",String.class,boolean.class);m.setAccessible(true);
        JSONArray tools=(JSONArray)m.invoke(engine,"read",true);
        Set<String> names=new HashSet<>();for(int i=0;i<tools.length();i++)names.add(tools.getJSONObject(i).getString("name"));
        assertTrue(names.contains("mobile_read"));assertTrue(names.contains("mobile_phone_screen"));assertFalse(names.contains("mobile_create"));assertFalse(names.contains("mobile_exec"));assertFalse(names.contains("mobile_phone_action"));
    }
    @Test public void agentApprovalWithoutTheAnsweringRunIsRefused()throws Exception{
        assertFalse(host("approve",obj("runId","stale","memberId","claude","title","mobile_create","detail","{}")).getBoolean("allow"));
    }
    @Test public void packagedAgentAssetsAccessibleButRuntimeScriptsNotWebExposed(){
        var assets=context.getAssets();
        assertEquals(200,MainActivity.packagedUiResponse(assets,android.net.Uri.parse("https://appassets.androidplatform.net/agents-ui.js")).getStatusCode());
        assertEquals(200,MainActivity.packagedUiResponse(assets,android.net.Uri.parse("https://appassets.androidplatform.net/agents.css")).getStatusCode());
        assertEquals(403,MainActivity.packagedUiResponse(assets,android.net.Uri.parse("https://appassets.androidplatform.net/rooms-ui.js")).getStatusCode());
        assertEquals(403,MainActivity.packagedUiResponse(assets,android.net.Uri.parse("https://appassets.androidplatform.net/rooms/provider-runtime.py")).getStatusCode());
    }
    @Test public void inviteValidatesAgentAndPermission()throws Exception{
        fails(()->call("chat.agents.set",obj("agents",array(obj("id","codex")))));
        fails(()->call("chat.agents.set",obj("agents",array(obj("id","claude","mode","allow-all")))));
        fails(()->call("chat.agents.set",obj("agents",new JSONArray())));
        assertEquals(0,call("state",obj()).getJSONArray("agents").length());
    }
    @Test public void draftInviteJoinsFirstMessageAndCodexAnswersFirstWithGroupContext()throws Exception{
        field("account",obj("type","chatgpt"));codex();
        JSONObject draft=call("chat.agents.set",obj("agents",array(obj("id","claude","model","opus","effort","high","mode","chat"))));
        assertEquals("claude",draft.getJSONArray("agents").getJSONObject(0).getString("id"));
        call("chat.send",obj("text","안녕하세요","model","gpt-test","effort","medium"));JSONObject sent=call("state",obj());
        JSONObject turn=turns().get(0);
        JSONArray input=turn.getJSONArray("input");
        assertTrue(input.getJSONObject(0).getString("text").startsWith("[그룹 대화]"));
        assertTrue(input.getJSONObject(0).getString("text").contains("@claude (Claude)"));
        assertEquals("안녕하세요",input.getJSONObject(1).getString("text"));
        assertEquals("안녕하세요",sent.getJSONArray("messages").getJSONObject(0).getString("text"));
        assertTrue(sent.getBoolean("busy"));
        assertEquals("codex",sent.getJSONObject("agentRun").getString("current"));
        assertEquals("claude",sent.getJSONObject("agentRun").getJSONArray("queue").getString(0));
        assertEquals(1,sent.getJSONArray("agents").length());

        // Codex finishes; Claude takes the next turn. The CLI runner is unavailable under
        // Robolectric, so the turn fails visibly and the order still ends.
        notify("item/completed",obj("threadId","remote-1","turnId",turn.optString("id"),"item",obj("type","agentMessage","id","codex-reply","text","제 의견입니다.")));
        notify("turn/completed",obj("threadId","remote-1","turn",obj("id","turn-2","status","completed")));
        JSONObject[] last=new JSONObject[1];
        JSONObject done=waitFor(()->{JSONObject s=call("state",obj());JSONObject m=agentMessage(s,"claude");last[0]=s;return m!=null&&"error".equals(m.optString("status"))&&!s.getBoolean("busy");});
        JSONObject claude=agentMessage(done,"claude");
        assertEquals("Claude",claude.getString("agentName"));
        assertFalse(claude.optString("error").isBlank());
        assertTrue(done.isNull("agentRun"));
        assertTrue(leases().isEmpty());
    }
    @Test public void mentionOnlyAgentSkipsCodexWhichReadsItOnItsNextTurn()throws Exception{
        field("account",obj("type","chatgpt"));codex();
        call("chat.send",obj("text","시작","model","gpt-test"));
        notify("turn/completed",obj("threadId","remote-1","turn",obj("id","turn-2","status","completed")));
        call("chat.agents.set",obj("agents",array(obj("id","gemini","model","g","mode","chat"))));
        int before=turns().size();
        call("chat.send",obj("text","@제미나이야 너만 답해줘","model","gpt-test"));
        assertEquals(before,turns().size());
        waitFor(()->{JSONObject m=agentMessage(call("state",obj()),"gemini");return m!=null&&!"streaming".equals(m.optString("status"))&&!call("state",obj()).getBoolean("busy");});
        call("chat.send",obj("text","이제 모두","model","gpt-test"));
        JSONObject next=turns().get(turns().size()-1);
        String context=next.getJSONArray("input").getJSONObject(0).getString("text");
        assertTrue(context.contains("[사용자] @제미나이야 너만 답해줘"));
        assertFalse(context.contains("[사용자] 시작"));
        assertEquals("이제 모두",next.getJSONArray("input").getJSONObject(1).getString("text"));
    }
    @Test public void stopEndsTheAnsweringOrderAndRemovedAgentLeavesTheGroup()throws Exception{
        field("account",obj("type","chatgpt"));codex();
        call("chat.agents.set",obj("agents",array(obj("id","claude","model","opus","mode","chat"),obj("id","gemini","model","g","mode","chat"))));
        call("chat.send",obj("text","의견 주세요","model","gpt-test"));JSONObject sent=call("state",obj());
        assertEquals(2,sent.getJSONObject("agentRun").getJSONArray("queue").length());
        call("chat.stop",obj());
        JSONObject stopped=call("state",obj());
        assertTrue(stopped.isNull("agentRun"));
        assertTrue(calls.stream().anyMatch(c->c.optString("method").equals("turn/interrupt")));
        notify("turn/completed",obj("threadId","remote-1","turn",obj("id","turn-2","status","interrupted")));
        assertNull(agentMessage(call("state",obj()),"claude"));

        call("chat.agents.set",obj("agents",array(obj("id","claude","enabled",false),obj("id","gemini","enabled",false))));
        call("chat.send",obj("text","혼자","model","gpt-test"));JSONObject alone=call("state",obj());
        JSONArray input=turns().get(turns().size()-1).getJSONArray("input");
        assertEquals("혼자",input.getJSONObject(0).getString("text"));
        assertTrue(alone.isNull("agentRun"));
        assertFalse(alone.getJSONArray("agents").getJSONObject(0).getBoolean("enabled"));
    }
    @Test public void retiredProBridgeIsDisabledOnlyWhenAnOldConfigDeclaresIt()throws Exception{
        // Codex 0.162 refuses to start on `-c mcp_servers.X.enabled=false` when X has no transport.
        File config=File.createTempFile("codex-config",".toml");
        try{
            dev.mobilecodex.app.core.Utf8Files.write(config.toPath(),"cli_auth_credentials_store = \"file\"\napproval_policy = \"on-request\"\n");
            assertFalse(Engine.retiredProBridgeConfigured(config));
            dev.mobilecodex.app.core.Utf8Files.write(config.toPath(),"model = \"x\"\n[mcp_servers.mobile_codex_pro]\ncommand = \"python3\"\n");
            assertTrue(Engine.retiredProBridgeConfigured(config));
            dev.mobilecodex.app.core.Utf8Files.write(config.toPath(),"mcp_servers.\"mobile_codex_pro\".command = \"python3\"\n");
            assertTrue(Engine.retiredProBridgeConfigured(config));
            dev.mobilecodex.app.core.Utf8Files.write(config.toPath(),"# mobile_codex_pro was removed\n");
            assertFalse(Engine.retiredProBridgeConfigured(config));
        }finally{config.delete();}
        assertFalse(Engine.retiredProBridgeConfigured(new File(context.getFilesDir(),"missing.toml")));
    }
    @Test public void agentCharacterPackIsKeptPerAgentAndCanBeCleared()throws Exception{
        JSONObject invited=call("chat.agents.set",obj("agents",array(obj("id","claude","model","opus","mode","chat","character","gptchan"))));
        assertEquals("gptchan",invited.getJSONArray("agents").getJSONObject(0).getString("character"));
        JSONObject gemini=call("chat.agents.set",obj("agents",array(obj("id","gemini","model","g","mode","chat"))));
        assertEquals("",gemini.getJSONArray("agents").getJSONObject(1).getString("character"));
        JSONObject changed=call("chat.agents.set",obj("agents",array(obj("id","claude","character","astrachan"))));
        assertEquals("astrachan",changed.getJSONArray("agents").getJSONObject(0).getString("character"));
        assertEquals("opus",changed.getJSONArray("agents").getJSONObject(0).getString("model"));
        JSONObject cleared=call("chat.agents.set",obj("agents",array(obj("id","claude","character",""))));
        assertEquals("",cleared.getJSONArray("agents").getJSONObject(0).getString("character"));
        JSONObject oversized=call("chat.agents.set",obj("agents",array(obj("id","claude","character","x".repeat(121)))));
        assertEquals("",oversized.getJSONArray("agents").getJSONObject(0).getString("character"));
    }
}
