/*
 * Copyright (c) 1999-2014, Ecole des Mines de Nantes
 * All rights reserved.
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the Ecole des Mines de Nantes nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE REGENTS AND CONTRIBUTORS ``AS IS'' AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE REGENTS AND CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package solver.explanations.strategies;

import solver.Configuration;
import solver.ICause;
import solver.Solver;
import solver.exception.ContradictionException;
import solver.exception.SolverException;
import solver.explanations.BranchingDecision;
import solver.explanations.Deduction;
import solver.explanations.Explanation;
import solver.explanations.ExplanationEngine;
import solver.search.loop.monitors.IMonitorContradiction;
import solver.search.loop.monitors.IMonitorSolution;
import solver.search.strategy.decision.Decision;
import solver.search.strategy.decision.RootDecision;
import util.logger.ILogger;
import util.logger.LoggerFactory;

/**
 * This class describes operations to execute to perform Conflict-based back jumping.
 * I reacts on contradictions, by computing the decision to bracktrack to, and on solutions to by explaining
 * the refutation of the decision that leads to the solution.
 * <br/>
 *
 * @author Charles Prud'homme
 * @since 01/10/12
 */
public class ConflictBasedBackjumping implements IMonitorContradiction, IMonitorSolution {

    static ILogger LOGGER = LoggerFactory.getLogger();
    protected ExplanationEngine mExplanationEngine;
    protected Solver mSolver;
    boolean userE; // set to true, store the last explanation
    protected Explanation lastOne;

    public ConflictBasedBackjumping(ExplanationEngine mExplanationEngine) {
        this.mExplanationEngine = mExplanationEngine;
        this.mSolver = mExplanationEngine.getSolver();
        mSolver.getSearchLoop().plugSearchMonitor(this);
    }

    public Solver getSolver() {
        return mSolver;
    }

    /**
     * By activating the user explanation, the algorithm will only store the explanation
     * related to the last conflict. It has a meaning only when the search is complete and has no solution.
     * In that case, the explanation is not composed of any decisions, and the propagators involved explained
     * why there is no solution to the problem.
     *
     * @param active true or false
     */
    public void activeUserExplanation(boolean active) {
        if (!Configuration.PROP_IN_EXP) {
            throw new SolverException("Configuration.properties should be modified to allow storing propagators in explanations. (PROP_IN_EXP property).");
        }
        userE = active;
    }

    public Explanation getUserExplanation() {
        if (userE) {
            return lastOne;
        } else {
            throw new SolverException("User explanation is not activated (see #activeUserExplanation).");
        }
    }

    @Override
    public void onContradiction(ContradictionException cex) {
        assert (cex.v != null) || (cex.c != null) : this.getClass().getName() + ".onContradiction incoherent state";
        Explanation complete = mExplanationEngine.flatten(cex.explain());
        if (userE) {
            lastOne = complete;
        }
        if (Configuration.PRINT_EXPLANATION && LOGGER.isInfoEnabled()) {
            mExplanationEngine.onContradiction(cex, complete);
        }
        int upto = compute(complete, mSolver.getEnvironment().getWorldIndex());
        mSolver.getSearchLoop().overridePreviousWorld(upto);
        updateVRExplainUponbacktracking(upto, complete, cex.c);
    }

    @Override
    public void onSolution() {
        // we need to prepare a "false" backtrack on this decision
        Decision dec = mSolver.getSearchLoop().getLastDecision();
        while ((dec != RootDecision.ROOT) && (!dec.hasNext())) {
            dec = dec.getPrevious();
        }
        if (dec != RootDecision.ROOT) {
            Explanation explanation = new Explanation();
            Decision d = dec.getPrevious();
            while ((d != RootDecision.ROOT)) {
                if (d.hasNext()) {
                    explanation.add(d.getPositiveDeduction());
                }
                d = d.getPrevious();
            }
            mExplanationEngine.store(dec.getNegativeDeduction(), explanation);
        }
        mSolver.getSearchLoop().overridePreviousWorld(1);
    }

    /**
     * Identify the decision to reconsider, and explain its refutation in the explanation data base
     *
     * @param nworld index of the world to backtrack to
     * @param expl   explanation of the backtrack
     * @param cause  cause of the failure
     */
    protected void updateVRExplainUponbacktracking(int nworld, Explanation expl, ICause cause) {
        Decision dec = mSolver.getSearchLoop().getLastDecision(); // the current decision to undo
        while (dec != RootDecision.ROOT && nworld > 1) {
            dec = dec.getPrevious();
            nworld--;
        }
        if (dec != RootDecision.ROOT) {
            if (!dec.hasNext())
                throw new UnsupportedOperationException("RecorderExplanationEngine.updateVRExplain should get to a POSITIVE decision:" + dec);
            Deduction left = dec.getPositiveDeduction();
            expl.remove(left);
            assert left.getmType() == Deduction.Type.DecLeft;
            BranchingDecision va = (BranchingDecision) left;
            mExplanationEngine.removeLeftDecisionFrom(va.getDecision(), va.getVar());

            Deduction right = dec.getNegativeDeduction();
            mExplanationEngine.store(right, mExplanationEngine.flatten(expl));
        }
        if (Configuration.PRINT_EXPLANATION && LOGGER.isInfoEnabled()) {
            LOGGER.info("::EXPL:: BACKTRACK on " + dec /*+ " (up to " + nworld + " level(s))"*/);
        }
    }

    /**
     * Compute the world to backtrack to
     *
     * @param explanation current explanation
     * @param currentWorldIndex current world index
     * @return the number of world to backtrack to.
     */
    int compute(Explanation explanation, int currentWorldIndex) {
        int dworld = 0;
        if (explanation.nbDeductions() > 0) {
            for (int d = 0; d < explanation.nbDeductions(); d++) {
                Deduction dec = explanation.getDeduction(d);
                if (dec.getmType() == Deduction.Type.DecLeft) {
                    int world = ((BranchingDecision) dec).getDecision().getWorldIndex() + 1;
                    if (world > dworld) {
                        dworld = world;
                    }
                }
            }
        }
        return 1 + (currentWorldIndex - dworld);
    }
}
