package com.neko.mcbot.agentcore.loop;

import com.google.gson.JsonObject;
import com.neko.mcbot.agentcore.llm.*;
import com.neko.mcbot.agentcore.loop.ToolExecutor.ToolOutcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopWorldGoalTest {
    @Test void invalidDrawingRecoveryStillChecksBodyIdentityAndDoesNotSurviveCancellation() {
        var f=new Fixture();
        var foreign=WorldGoalTest.body(); foreign.addProperty("body_id","another-body");
        f.pending=CompletableFuture.completedFuture(new ToolOutcome(false,"INVALID_BUILD_TARGET:bed",foreign));
        f.goal(false);
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED),f.finishes);
        assertTrue(f.endings.getLast().contains("BODY_IDENTITY_CHANGED"));
        var next=new Fixture();
        var delayed=new CompletableFuture<ToolOutcome>(); next.pending=delayed;
        next.goal(false);
        int requests=next.responses.size();
        next.loop.cancelTask(81);
        delayed.complete(new ToolOutcome(false,"INVALID_BUILD_TARGET:late",WorldGoalTest.body()));
        assertEquals(requests,next.responses.size());
        next.loop.submit(82,"ordinary answer");
        next.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.CANCELLED,AgentLoop.TaskStatus.COMPLETED),next.finishes);
    }

    @Test void anInvalidBuildingDraftIsCorrectableBeforeRegistrationButCannotFallBackToAnItemGoal() {
        var f=new Fixture();
        f.pending=CompletableFuture.completedFuture(new ToolOutcome(false,
                "INVALID_BUILD_TARGET:bed_pair_missing_or_inconsistent",WorldGoalTest.body()));
        f.goal(true);
        assertTrue(f.finishes.isEmpty(),"a drawing rejected before registration must remain editable");
        assertNull(f.loop.registeredGoal());
        assertFalse(f.receipt().ok());
        assertTrue(f.receipt().content().startsWith("INVALID_BUILD_TARGET:"));
        f.tool("set_goal",WorldGoalTest.json("{\"item\":\"dirt\",\"count\":1}"));
        assertFalse(f.receipt().ok());
        assertNull(f.loop.registeredGoal());
        f.goal(true);
        assertTrue(f.receipt().ok(),f.receipt().content());
        assertEquals(WorldGoalTest.building(),f.loop.registeredGoal());
        f.state=WorldGoalTest.buildState(20); f.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.COMPLETED),f.finishes);
    }

    static final class Fixture implements ChatEngine, ToolExecutor, AgentLoop.Listener {
        final List<CompletableFuture<AssistantTurn>> responses = new ArrayList<>();
        final List<AgentLoop.TaskStatus> finishes = new ArrayList<>();
        final List<String> endings = new ArrayList<>(), checkpoints = new ArrayList<>(), executed = new ArrayList<>();
        JsonObject state = WorldGoalTest.buildState(0);
        CompletableFuture<ToolOutcome> pending;
        final AgentLoop loop;
        Fixture() { this(40); }
        Fixture(int steps) {
            loop = new AgentLoop(this,List.of(),this,new AgentLoop.Config(steps,3,5),this,()->"system",1_000_000);
            loop.submit(81,"complete the frozen target");
        }
        @Override public CompletableFuture<AssistantTurn> chat(String system,List<Msg> history,List<ToolSpec> tools) {
            var future = new CompletableFuture<AssistantTurn>(); responses.add(future); return future;
        }
        @Override public CompletableFuture<ToolOutcome> observe(long id) {
            var body = state.deepCopy(); body.remove("goal_evidence");
            return CompletableFuture.completedFuture(new ToolOutcome(true,"fresh body",body));
        }
        @Override public CompletableFuture<ToolOutcome> observe(long id,JsonObject goal) {
            if (pending != null) { var result=pending; pending=null; return result; }
            return CompletableFuture.completedFuture(new ToolOutcome(true,"fresh target",state));
        }
        @Override public CompletableFuture<ToolOutcome> execute(String name,String args) {
            executed.add(name); return CompletableFuture.completedFuture(new ToolOutcome(true,"actual"));
        }
        @Override public void onTaskFinished(long id,AgentLoop.TaskStatus status,String text) { finishes.add(status); endings.add(text); }
        @Override public void onGoalCheckpoint(long id,String facts) { assertEquals(81,id); checkpoints.add(facts); }
        void tool(String name,JsonObject args) {
            responses.getLast().complete(new AssistantTurn("",List.of(new ToolCall("c"+responses.size(),name,args.toString())),0,0,-1,"tool_calls"));
        }
        void goal(boolean longTerm) {
            var request = WorldGoalTest.building();
            if (longTerm) request.addProperty("lifecycle","long_term");
            tool("set_goal",request);
        }
        void reply() { responses.getLast().complete(new AssistantTurn("finished",List.of(),0,0,-1,"stop")); }
        Msg.Tool receipt() { return (Msg.Tool)loop.conversation().history().getLast(); }
    }
    @Test void buildingNeedsIndependentWholeTargetVerificationAndCannotReuseLocalToolSuccess() {
        var f=new Fixture(); f.goal(false); assertTrue(f.receipt().ok(), f.receipt().content());
        f.state=WorldGoalTest.buildState(16); f.reply();
        assertTrue(f.finishes.isEmpty());
        f.state=WorldGoalTest.buildState(20); f.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.COMPLETED), f.finishes);
    }
    @Test void changingOnlyActualBuildingCellsAdvancesStagesWhileTickAndUnrelatedVersionsDoNot() {
        var f=new Fixture(); f.goal(true);
        for(int matched:List.of(1,2,3)) { f.state=WorldGoalTest.buildState(matched); f.reply(); }
        assertEquals(3,f.checkpoints.size());
        assertTrue(f.finishes.isEmpty());
        f.state.addProperty("game_tick",999); f.reply();
        f.state.getAsJsonObject("goal_evidence").addProperty("observation_key","irrelevant-material-and-terrain-version");
        f.reply();
        assertTrue(f.finishes.isEmpty());
        f.loop.close();
    }
    @Test void frozenWorldGoalRejectsItemGoalDefaultGatherAndChangedRevisionButAllowsMaterialSubtasks() {
        var f=new Fixture(); f.goal(false);
        f.tool("set_goal",WorldGoalTest.json("{\"item\":\"diamond\",\"count\":1}")); assertFalse(f.receipt().ok());
        f.tool("gather",WorldGoalTest.json("{\"item\":\"diamond\",\"count\":1}")); assertFalse(f.receipt().ok());
        var other=WorldGoalTest.building(); other.addProperty("expected_revision","b".repeat(64));
        f.tool("set_goal",other); assertFalse(f.receipt().ok());
        f.tool("gather",WorldGoalTest.json("{\"item\":\"dirt\",\"count\":4,\"scope\":\"subtask\"}"));
        assertEquals(List.of("gather"),f.executed);
        f.loop.close();
    }
    @Test void registrationAndFinalVerificationDiscardCancelledOrDeadBodyResults() {
        for(boolean registration:List.of(true,false)) {
            var f=new Fixture();
            if(!registration) f.goal(true);
            var waiting=new CompletableFuture<ToolOutcome>(); f.pending=waiting;
            if(registration) f.goal(true); else f.reply();
            int requests=f.responses.size();
            f.loop.cancelTask(81);
            waiting.complete(new ToolOutcome(true,"late",WorldGoalTest.buildState(20)));
            assertEquals(requests,f.responses.size());
            assertEquals(List.of(AgentLoop.TaskStatus.CANCELLED),f.finishes);
        }
        var f=new Fixture(); f.goal(true); f.state.addProperty("alive",false); f.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED),f.finishes);
    }
    @Test void incompleteFinalEvidenceFailsAndDoesNotFallBackToOldSatisfaction() {
        var f=new Fixture(); f.state=WorldGoalTest.buildState(20); f.goal(false);
        f.state.getAsJsonObject("goal_evidence").addProperty("spatial_stable",false);
        f.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED),f.finishes);
        assertTrue(f.endings.getLast().startsWith("STATE_UNAVAILABLE:"));
    }
    @Test void incompleteRegistrationFailsBeforeAModelCanClaimUnverifiedCompletion() {
        var f=new Fixture(); f.state.remove("goal_evidence"); f.goal(false);
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED),f.finishes);
        assertEquals(1,f.responses.size());
        assertTrue(f.endings.getLast().startsWith("STATE_UNAVAILABLE:"));
    }
    @Test void rejectedRevisionCannotFallBackToUnverifiedCompletionOrAnItemGoal() {
        var f=new Fixture();
        f.pending=CompletableFuture.completedFuture(new ToolOutcome(false,
                "TARGET_REVISION_CHANGED:recheck the drawing",WorldGoalTest.body()));
        f.goal(false);
        assertTrue(f.finishes.isEmpty());
        assertFalse(f.receipt().ok());
        assertTrue(f.receipt().content().startsWith("TARGET_REVISION_CHANGED:"));
        assertNull(f.loop.registeredGoal());
        f.tool("set_goal",WorldGoalTest.json("{\"item\":\"dirt\",\"count\":1}"));
        assertFalse(f.receipt().ok(),"an item subtask cannot replace the rejected building condition");
        assertNull(f.loop.registeredGoal());
        f.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED),f.finishes);
        assertTrue(f.endings.getLast().startsWith("GOAL_NOT_REGISTERED:"),f.endings.toString());
    }
    @Test void revisionRetryStillRejectsChangedBodiesAndDoesNotLeakIntoTheNextTask() {
        var f=new Fixture();
        var other=WorldGoalTest.body(); other.addProperty("body_id","other-body");
        f.pending=CompletableFuture.completedFuture(new ToolOutcome(false,
                "TARGET_REVISION_CHANGED:recheck the drawing",other));
        f.goal(false);
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED),f.finishes);
        assertTrue(f.endings.getLast().contains("BODY_IDENTITY_CHANGED"),f.endings.toString());
        f.loop.close();
        var next=new Fixture();
        next.pending=CompletableFuture.completedFuture(new ToolOutcome(false,
                "TARGET_REVISION_CHANGED:recheck the drawing",WorldGoalTest.body()));
        next.goal(false);
        next.loop.cancelTask(81);
        next.loop.submit(82,"ordinary answer");
        next.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.CANCELLED,AgentLoop.TaskStatus.COMPLETED),next.finishes);
    }
    @Test void stageBudgetCannotBeChangedAndCompletionStillPrecedesTheLastStageLimit() {
        var f=new Fixture();
        var request=WorldGoalTest.building(); request.addProperty("lifecycle","long_term"); request.addProperty("max_runs",1);
        f.tool("set_goal",request);
        var expanded=request.deepCopy(); expanded.addProperty("max_runs",16);
        f.tool("set_goal",expanded); assertFalse(f.receipt().ok());
        f.state=WorldGoalTest.buildState(20); f.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.COMPLETED),f.finishes);
    }
    @Test void longBuildingStagesDoNotReopenTheModelStepBudgetAndGoalFactsSurviveContextClear() {
        var f=new Fixture(4); f.goal(true);
        f.loop.conversation().clear();
        for(int matched:List.of(1,2,3)) { f.state=WorldGoalTest.buildState(matched); f.reply(); }
        assertEquals(4,f.responses.size());
        assertEquals(List.of(AgentLoop.TaskStatus.FAILED),f.finishes);
        assertTrue(f.endings.getLast().contains("步数超限"));
        assertTrue(f.checkpoints.getFirst().contains(WorldGoalTest.REVISION));
    }
    @Test void originalConditionRemainsQueryableAfterHistoryIsLostAndCannotBeMutatedOrReRegisteredByQuery() {
        var f=new Fixture(); f.goal(true);
        var original=WorldGoalTest.building();
        for(int i=0;i<12;i++) f.loop.conversation().add(new Msg.Assistant("unrelated observation ".repeat(200),List.of()));
        f.loop.conversation().compact((system,history,tools)->CompletableFuture.completedFuture(
                new AssistantTurn("only the revision was summarized",List.of(),0,0,-1,"stop"))).join();
        assertEquals("only the revision was summarized",f.loop.conversation().summary());
        assertEquals(original,f.loop.registeredGoal(),"the summary is not the retained design's source");
        f.loop.conversation().clear();
        var snapshot=f.loop.registeredGoal();
        assertEquals(original,snapshot);
        snapshot.getAsJsonObject("target").remove("fill");
        assertEquals(original,f.loop.registeredGoal());
        f.tool("set_goal",WorldGoalTest.json("{\"operation\":\"query\"}"));
        assertTrue(f.receipt().ok(),f.receipt().content());
        assertTrue(f.receipt().content().contains(original.toString()));
        assertTrue(f.receipt().content().contains("mc.build()"));
        assertTrue(f.executed.isEmpty(),"the query does not dispatch a game action or another registration");
        f.tool("set_goal",WorldGoalTest.json("{\"operation\":\"query\",\"target\":{}}"));
        assertFalse(f.receipt().ok());
        assertEquals(original,f.loop.registeredGoal());
        f.loop.cancelTask(81);
        assertNull(f.loop.registeredGoal(),"a finished task must not expose the previous task's target");
    }
    @Test void deliveryCanPrepareMaterialsOutOfReachAndMustReturnForRealCompletion() {
        var f=new Fixture(); f.state=WorldGoalTest.deliveryState(0,0);
        var request=WorldGoalTest.delivery("additional"); request.addProperty("lifecycle","long_term");
        f.tool("set_goal",request); assertTrue(f.receipt().ok(),f.receipt().content());
        var far=WorldGoalTest.body(); far.addProperty("x",40);
        far.addProperty("goal_evidence_unavailable",true); far.addProperty("goal_evidence_reason","OUT_OF_REACH");
        f.state=far; f.reply();
        assertTrue(f.finishes.isEmpty());
        assertEquals(1,f.checkpoints.size());
        f.state=WorldGoalTest.deliveryState(3,3); f.reply();
        assertEquals(List.of(AgentLoop.TaskStatus.COMPLETED),f.finishes);
    }
}
