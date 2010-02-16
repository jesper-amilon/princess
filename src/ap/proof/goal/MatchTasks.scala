/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2009 Philipp Ruemmer <ph_r@gmx.net>
 *
 * Princess is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Princess is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Princess.  If not, see <http://www.gnu.org/licenses/>.
 */

package ap.proof.goal

import ap.proof.tree.{ProofTree, ProofTreeFactory}
import ap.terfor.conjunctions.Conjunction
import ap.util.Debug

private object MatchFunctions {
  def updateMatcher(goal : Goal,
                    ptf : ProofTreeFactory,
                    eager : Boolean) : ProofTree = {
    val order = goal.order
    val voc = goal.vocabulary

    val oldMatcher = goal.compoundFormulas quantifierClauses eager
    
    val collector = goal.getInferenceCollector
    val (instances, newMatcher) =
      oldMatcher.updateFacts(goal.facts.predConj,
                             goal.mayAlias,
                             goal.reduceWithFacts,
                             (voc.constantFreedom.isShielded(_, voc.bindingContext)),
                             collector, order)

    val newCF = goal.compoundFormulas.updateQuantifierClauses(eager, newMatcher)
    val newTasks = for (f <- instances; t <- goal.formulaTasks(f)) yield t
    
    ptf.updateGoal(newCF, newTasks, collector.getCollection, goal)
  }
}

////////////////////////////////////////////////////////////////////////////////

case object EagerMatchTask extends EagerTask {
  def apply(goal : Goal, ptf : ProofTreeFactory) : ProofTree =
    MatchFunctions.updateMatcher(goal, ptf, true)
}

////////////////////////////////////////////////////////////////////////////////

object LazyMatchTask {
  
  private val AC = Debug.AC_CLAUSE_MATCHER
  
  def addTask(goal : Goal) : Seq[PrioritisedTask] =
    if (goal.tasks.taskInfos.containsLazyMatchTask)
      List()
    else
      List(new LazyMatchTask (goal.age))
}

class LazyMatchTask(age : Int) extends PrioritisedTask {
  
  val priority : Int = -500 + age
  
  def updateTask(goal : Goal, factCollector : Conjunction => unit)
                : Seq[PrioritisedTask] = List(this)

  def apply(goal : Goal, ptf : ProofTreeFactory) : ProofTree = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(LazyMatchTask.AC,
                    !(goal.compoundFormulas.eagerQuantifiedClauses factsAreOutdated
                                                           goal.facts.predConj))
    ////////////////////////////////////////////////////////////////////////////
    MatchFunctions.updateMatcher(goal, ptf, false)
  }
  
  override def toString = "LazyMatchTask(" + priority + ")"
}