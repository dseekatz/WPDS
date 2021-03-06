package boomerang.results;

import boomerang.ForwardQuery;
import boomerang.Util;
import boomerang.callgraph.CallerListener;
import boomerang.callgraph.ObservableICFG;
import boomerang.controlflowgraph.ObservableControlFlowGraph;
import boomerang.controlflowgraph.PredecessorListener;
import boomerang.scene.DeclaredMethod;
import boomerang.scene.Field;
import boomerang.scene.IfStatement;
import boomerang.scene.IfStatement.Evaluation;
import boomerang.scene.Method;
import boomerang.scene.Statement;
import boomerang.scene.Val;
import boomerang.scene.jimple.JimpleVal;
import boomerang.solver.AbstractBoomerangSolver;
import boomerang.solver.ForwardBoomerangSolver;
import boomerang.stats.IBoomerangStats;
import boomerang.util.DefaultValueMap;
import boomerang.weights.DataFlowPathWeight;
import boomerang.weights.PathConditionWeight.ConditionDomain;
import com.google.common.base.Stopwatch;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import soot.jimple.IntConstant;
import sync.pds.solver.nodes.GeneratedState;
import sync.pds.solver.nodes.INode;
import sync.pds.solver.nodes.Node;
import wpds.impl.Transition;
import wpds.impl.Weight;
import wpds.impl.WeightedPAutomaton;
import wpds.interfaces.State;
import wpds.interfaces.WPAUpdateListener;

public class ForwardBoomerangResults<W extends Weight> extends AbstractBoomerangResults<W> {

  private final ForwardQuery query;
  private final boolean timedout;
  private final IBoomerangStats<W> stats;
  private Stopwatch analysisWatch;
  private long maxMemory;
  private ObservableICFG<Statement, Method> icfg;
  private Set<Method> visitedMethods;
  private final boolean trackDataFlowPath;
  private final boolean pruneContradictoryDataFlowPath;
  private ObservableControlFlowGraph cfg;
  private boolean pruneImplictFlows;

  public ForwardBoomerangResults(
      ForwardQuery query,
      ObservableICFG<Statement, Method> icfg,
      ObservableControlFlowGraph cfg,
      boolean timedout,
      DefaultValueMap<ForwardQuery, ForwardBoomerangSolver<W>> queryToSolvers,
      IBoomerangStats<W> stats,
      Stopwatch analysisWatch,
      Set<Method> visitedMethods,
      boolean trackDataFlowPath,
      boolean pruneContradictoryDataFlowPath,
      boolean pruneImplictFlows) {
    super(queryToSolvers);
    this.query = query;
    this.icfg = icfg;
    this.cfg = cfg;
    this.timedout = timedout;
    this.stats = stats;
    this.analysisWatch = analysisWatch;
    this.visitedMethods = visitedMethods;
    this.trackDataFlowPath = trackDataFlowPath;
    this.pruneContradictoryDataFlowPath = pruneContradictoryDataFlowPath;
    this.pruneImplictFlows = pruneImplictFlows;
    stats.terminated(query, this);
    this.maxMemory = Util.getReallyUsedMemory();
  }

  public Stopwatch getAnalysisWatch() {
    return analysisWatch;
  }

  public boolean isTimedout() {
    return timedout;
  }

  public Table<Statement, Val, W> getObjectDestructingStatements() {
    AbstractBoomerangSolver<W> solver = queryToSolvers.get(query);
    if (solver == null) {
      return HashBasedTable.create();
    }
    Table<Statement, Val, W> res = asStatementValWeightTable();
    Set<Method> visitedMethods = Sets.newHashSet();
    for (Statement s : res.rowKeySet()) {
      visitedMethods.add(s.getMethod());
    }
    ForwardBoomerangSolver<W> forwardSolver = (ForwardBoomerangSolver) queryToSolvers.get(query);
    Table<Statement, Val, W> destructingStatement = HashBasedTable.create();
    for (Method flowReaches : visitedMethods) {
      for (Statement exitStmt : icfg.getEndPointsOf(flowReaches)) {
        Set<State> escapes = Sets.newHashSet();
        icfg.addCallerListener(
            new CallerListener<Statement, Method>() {
              @Override
              public Method getObservedCallee() {
                return flowReaches;
              }

              @Override
              public void onCallerAdded(Statement callSite, Method m) {
                Method callee = callSite.getMethod();
                if (visitedMethods.contains(callee)) {
                  for (Entry<Val, W> valAndW : res.row(exitStmt).entrySet()) {
                    escapes.addAll(
                        forwardSolver.computeReturnFlow(flowReaches, exitStmt, valAndW.getKey()));
                  }
                }
              }
            });
        if (escapes.isEmpty()) {
          Map<Val, W> row = res.row(exitStmt);
          findLastUsage(exitStmt, row, destructingStatement, forwardSolver);
        }
      }
    }

    return destructingStatement;
  }

  public Table<Statement, Val, W> asStatementValWeightTable() {
    return asStatementValWeightTable(query);
  }

  private void findLastUsage(
      Statement exitStmt,
      Map<Val, W> row,
      Table<Statement, Val, W> destructingStatement,
      ForwardBoomerangSolver<W> forwardSolver) {
    LinkedList<Statement> worklist = Lists.newLinkedList();
    worklist.add(exitStmt);
    Set<Statement> visited = Sets.newHashSet();
    while (!worklist.isEmpty()) {
      Statement curr = worklist.poll();
      if (!visited.add(curr)) {
        continue;
      }
      boolean valueUsedInStmt = false;
      for (Entry<Val, W> e : row.entrySet()) {
        if (curr.uses(e.getKey())) {
          destructingStatement.put(curr, e.getKey(), e.getValue());
          valueUsedInStmt = true;
        }
      }
      if (!valueUsedInStmt
          &&
          /** Do not continue over CatchStmt */
          !(curr.isIdentityStmt())) {
        cfg.addPredsOfListener(
            new PredecessorListener(curr) {

              @Override
              public void getPredecessor(Statement succ) {
                worklist.add(succ);
              }
            });
      }
    }
  }

  public IBoomerangStats<W> getStats() {
    return stats;
  }

  public Map<Statement, DeclaredMethod> getInvokedMethodOnInstance() {
    Map<Statement, DeclaredMethod> invokedMethodsOnInstance = Maps.newHashMap();
    if (query.stmt().containsInvokeExpr()) {
      invokedMethodsOnInstance.put(query.stmt(), query.stmt().getInvokeExpr().getMethod());
    }
    queryToSolvers
        .get(query)
        .getFieldAutomaton()
        .registerListener(
            new WPAUpdateListener<Field, INode<Node<Statement, Val>>, W>() {

              @Override
              public void onWeightAdded(
                  Transition<Field, INode<Node<Statement, Val>>> t,
                  W w,
                  WeightedPAutomaton<Field, INode<Node<Statement, Val>>, W> aut) {
                if (!t.getLabel().equals(Field.empty()) || t.getStart() instanceof GeneratedState) {
                  return;
                }
                Node<Statement, Val> node = t.getStart().fact();
                Val fact = node.fact();
                Statement curr = node.stmt();
                if (curr.containsInvokeExpr()) {
                  if (curr.getInvokeExpr().isInstanceInvokeExpr()) {
                    Val base = curr.getInvokeExpr().getBase();
                    if (base.equals(fact)) {
                      invokedMethodsOnInstance.put(curr, curr.getInvokeExpr().getMethod());
                    }
                  }
                }
              }
            });
    return invokedMethodsOnInstance;
  }

  public QueryResults getPotentialNullPointerDereferences() {
    // FIXME this should be located nullpointer analysis
    Set<Node<Statement, Val>> res = Sets.newHashSet();
    for (Transition<Field, INode<Node<Statement, Val>>> t :
        queryToSolvers.get(query).getFieldAutomaton().getTransitions()) {
      if (!t.getLabel().equals(Field.empty()) || t.getStart() instanceof GeneratedState) {
        continue;
      }
      Node<Statement, Val> nullPointerNode = t.getStart().fact();
      if (NullPointerDereference.isNullPointerNode(nullPointerNode)
          && queryToSolvers.get(query).getReachedStates().contains(nullPointerNode)) {
        res.add(nullPointerNode);
      }
    }
    Set<AffectedLocation> resWithContext = Sets.newHashSet();
    for (Node<Statement, Val> r : res) {
      // Context context = constructContextGraph(query, r);
      if (trackDataFlowPath) {
        DataFlowPathWeight dataFlowPath = getDataFlowPathWeight(query, r);
        if (isValidPath(dataFlowPath)) {
          List<PathElement> p = transformPath(dataFlowPath.getAllStatements(), r);
          resWithContext.add(new NullPointerDereference(query, r.stmt(), r.fact(), null, null, p));
        }
      } else {
        List<PathElement> dataFlowPath = Lists.newArrayList();
        resWithContext.add(
            new NullPointerDereference(query, r.stmt(), r.fact(), null, null, dataFlowPath));
      }
    }
    QueryResults nullPointerResult =
        new QueryResults(query, resWithContext, visitedMethods, timedout);
    return nullPointerResult;
  }

  private boolean isValidPath(DataFlowPathWeight dataFlowPath) {
    if (!pruneContradictoryDataFlowPath) {
      return true;
    }
    Map<Statement, ConditionDomain> conditions = dataFlowPath.getConditions();
    for (Entry<Statement, ConditionDomain> c : conditions.entrySet()) {
      if (contradiction(c.getKey(), c.getValue(), dataFlowPath.getEvaluationMap())) {
        return false;
      }
    }
    return true;
  }

  private DataFlowPathWeight getDataFlowPathWeight(
      ForwardQuery query, Node<Statement, Val> sinkLocation) {
    WeightedPAutomaton<Statement, INode<Val>, W> callAut =
        queryToSolvers.getOrCreate(query).getCallAutomaton();
    // Iterating over whole set to find the matching transition is not the most elegant solution....
    for (Entry<Transition<Statement, INode<Val>>, W> e :
        callAut.getTransitionsToFinalWeights().entrySet()) {
      Transition<Statement, INode<Val>> t = e.getKey();
      if (t.getLabel().equals(Statement.epsilon())) {
        continue;
      }
      if (t.getStart().fact().isLocal()
          && !t.getLabel().getMethod().equals(t.getStart().fact().m())) {
        continue;
      }
      if (t.getStart().fact().equals(sinkLocation.fact())
          && t.getLabel().equals(sinkLocation.stmt())) {
        if (e.getValue() instanceof DataFlowPathWeight) {
          DataFlowPathWeight v = (DataFlowPathWeight) e.getValue();
          return v;
        }
      }
    }
    return null;
  }

  private boolean contradiction(
      Statement ifStmt, ConditionDomain mustBeVal, Map<Val, ConditionDomain> evaluationMap) {
    if (ifStmt.isIfStmt()) {
      IfStatement ifStmt1 = ifStmt.getIfStmt();
      for (Transition<Field, INode<Node<Statement, Val>>> t :
          queryToSolvers.get(query).getFieldAutomaton().getTransitions()) {

        if (!t.getStart().fact().stmt().equals(ifStmt)) {
          continue;
        }
        if (!t.getLabel().equals(Field.empty()) || t.getStart() instanceof GeneratedState) {
          continue;
        }

        Node<Statement, Val> node = t.getStart().fact();
        Val fact = node.fact();
        switch (ifStmt1.evaluate(fact)) {
          case TRUE:
            if (mustBeVal.equals(ConditionDomain.FALSE)) {
              return true;
            }
            break;
          case FALSE:
            if (mustBeVal.equals(ConditionDomain.TRUE)) {
              return true;
            }
        }
      }
      if (pruneImplictFlows) {
        for (Entry<Val, ConditionDomain> e : evaluationMap.entrySet()) {

          if (ifStmt1.uses(e.getKey())) {
            Evaluation eval = null;
            if (e.getValue().equals(ConditionDomain.TRUE)) {
              // Map first to JimpleVal
              eval = ifStmt1.evaluate(new JimpleVal(IntConstant.v(1), e.getKey().m()));
            } else if (e.getValue().equals(ConditionDomain.FALSE)) {
              // Map first to JimpleVal
              eval = ifStmt1.evaluate(new JimpleVal(IntConstant.v(0), e.getKey().m()));
            }
            if (eval != null) {
              if (mustBeVal.equals(ConditionDomain.FALSE)) {
                if (eval.equals(Evaluation.FALSE)) {
                  return true;
                }
              } else if (mustBeVal.equals(ConditionDomain.TRUE)) {
                if (eval.equals(Evaluation.TRUE)) {
                  return true;
                }
              }
            }
          }
        }
      }
    }
    return false;
  }

  private List<PathElement> transformPath(
      List<Node<Statement, Val>> allStatements, Node<Statement, Val> sinkLocation) {
    List<PathElement> res = Lists.newArrayList();
    int index = 0;
    for (Node<Statement, Val> x : allStatements) {
      res.add(new PathElement(x.stmt(), x.fact(), index++));
    }
    // TODO The analysis misses
    if (!allStatements.contains(sinkLocation)) {
      res.add(new PathElement(sinkLocation.stmt(), sinkLocation.fact(), index));
    }

    for (PathElement n : res) {
      LOGGER.trace(
          "Statement: {}, Variable {}, Index {}", n.getStatement(), n.getVariable(), n.stepIndex());
    }
    return res;
  }

  public Context getContext(Node<Statement, Val> node) {
    return constructContextGraph(query, node);
  }

  public boolean containsCallRecursion() {
    for (Entry<ForwardQuery, ForwardBoomerangSolver<W>> e : queryToSolvers.entrySet()) {
      if (e.getValue().getCallAutomaton().containsLoop()) {
        return true;
      }
    }
    return false;
  }

  public boolean containsFieldLoop() {
    for (Entry<ForwardQuery, ForwardBoomerangSolver<W>> e : queryToSolvers.entrySet()) {
      if (e.getValue().getFieldAutomaton().containsLoop()) {
        return true;
      }
    }
    return false;
  }

  public Set<Method> getVisitedMethods() {
    return visitedMethods;
  }

  public long getMaxMemory() {
    return maxMemory;
  }
}
