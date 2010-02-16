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

package ap.terfor.conjunctions

import ap.terfor.linearcombination.LinearCombination
import ap.terfor.arithconj.ArithConj
import ap.terfor.equations.{EquationConj, ReduceWithEqs}
import ap.terfor.preds.{Predicate, Atom, PredConj}
import ap.util.{Debug, FilterIt}

import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.collection.mutable.{Map => MutableMap}

object IterativeClauseMatcher {
  
  private val AC = Debug.AC_CLAUSE_MATCHER
  
  private def executeMatcher(startLit : Atom,
                             negatedStartLit : Boolean,
                             program : List[MatchStatement],
                             litFacts : PredConj,
                             additionalPosLits : Collection[Atom],
                             additionalNegLits : Collection[Atom],
                             mayAlias : (LinearCombination, LinearCombination) => Boolean,
                             contextReducer : ReduceWithConjunction,
                             logger : ComputationLogger,
                             order : TermOrder) : Iterator[Conjunction] = {
    
    val selectedLits = new ArrayBuffer[Atom]
    
    val instances = new scala.collection.mutable.LinkedHashSet[Conjunction]
    
    ////////////////////////////////////////////////////////////////////////////
    
    def exec(program : List[MatchStatement]) : Unit =
      program match {
        
        case List() => // nothing
          
        case SelectLiteral(pred, negative) :: progTail => {
          val selLitsNum = selectedLits.size
          selectedLits += null
          
          val atoms = if (negative)
                        litFacts negativeLitsWithPred pred
                      else
                        litFacts positiveLitsWithPred pred
          
          for (a <- atoms) {
            selectedLits(selLitsNum) = a
            exec(progTail)
          }
          for (a <- if (negative) additionalNegLits else additionalPosLits)
            if (a.pred == pred) {
              selectedLits(selLitsNum) = a
              exec(progTail)
            }
          
          selectedLits reduceToSize selLitsNum
        }
        
        case CheckMayAlias(litNrA, argNrA, litNrB, argNrB) :: progTail =>
          if (mayAlias(selectedLits(litNrA)(argNrA), selectedLits(litNrB)(argNrB)))
            exec(progTail)
        
        case CheckMayAliasUnary(litNr, argNr, lc) :: progTail =>
          if (mayAlias(selectedLits(litNr)(argNr), lc))
            exec(progTail)

        case InstantiateClause(originalClause, matchedLits, quans, arithConj,
                               remainingLits, negConjs) :: progTail => {
          // we generate a system of equations that precisely describes the
          // match conditions
          
          //////////////////////////////////////////////////////////////////////
          Debug.assertInt(IterativeClauseMatcher.AC, matchedLits.size == selectedLits.size)
          //////////////////////////////////////////////////////////////////////
          
          val newEqs = EquationConj(
            (for ((a1, a2) <- (matchedLits.elements zip selectedLits.elements);
                  lc <- a1.unificationConditions(a2, order))
             yield lc) ++
            arithConj.positiveEqs.elements,
            order)
          
          if (!newEqs.isFalse)
            if (logger.isLogging) {
              // If we are logging, we avoid simplifications (which would not
              // be captured in the proof) and rather just substitute terms for
              // the quantified variables. Hopefully this is possible ...
              
              //////////////////////////////////////////////////////////////////
              // Currently, we just assume that all leading quantifiers are
              // existential (is the other case possible at all?)
              Debug.assertInt(IterativeClauseMatcher.AC,
                              quans forall (Quantifier.EX == _))
              //////////////////////////////////////////////////////////////////
              
              val reducer = ReduceWithEqs(newEqs, order)
              val instanceTerms =
                (for (i <- 0 until quans.size)
                 yield reducer(LinearCombination(VariableTerm(i), order))).toList

              //////////////////////////////////////////////////////////////////
              Debug.assertInt(IterativeClauseMatcher.AC,
                              instanceTerms forall (_.variables.isEmpty))
              //////////////////////////////////////////////////////////////////

              val result = originalClause.instantiate(instanceTerms)(order)
              
              if (!contextReducer(result).isFalse) {
                logger.groundInstantiateQuantifier(originalClause.negate,
                                                   instanceTerms, result.negate, order)
                instances += result
              }
              
            } else {
              val newAC = arithConj.updatePositiveEqs(newEqs)(order)
              instances += contextReducer(Conjunction(quans, newAC, remainingLits,
                                                      negConjs, order))
            }
          
          exec(progTail)
        }
        
        case UnifyLiterals(litNrA, litNrB) :: progTail => {
          val eqs = selectedLits(litNrA).unify(selectedLits(litNrB), order)
          if (!eqs.isFalse) {
            if (logger.isLogging) {
              //////////////////////////////////////////////////////////////////
              // We currently only support the case that the first unified
              // literal is the start literal
              // (otherwise, we would have to store more information about
              // polarity of unified predicates)
              Debug.assertInt(AC, litNrA == 0 && litNrB == 1)
              //////////////////////////////////////////////////////////////////
              val (leftNr, rightNr) =
                if (negatedStartLit) (litNrB, litNrA) else (litNrA, litNrB)
              logger.unifyPredicates(selectedLits(leftNr), selectedLits(rightNr),
                                     eqs, order)

              instances += Conjunction.conj(eqs, order)
            } else {
              instances += contextReducer(Conjunction.conj(eqs, order))
            }
          }
        }
        
        case Choice(options) :: progTail => {
          //////////////////////////////////////////////////////////////////////
          // Choice always has to be the last statement in a program
          Debug.assertInt(IterativeClauseMatcher.AC, progTail.isEmpty)
          //////////////////////////////////////////////////////////////////////
          
          for (prog <- options)
            exec(prog)
        }
      }
    
    ////////////////////////////////////////////////////////////////////////////
    
    selectedLits += startLit
    exec(program)
    
    instances.elements
  }
  
  //////////////////////////////////////////////////////////////////////////////

  private def constructMatcher(startPred : Predicate, negStartLit : Boolean,
                               clauses : Seq[Conjunction],
                               includeAxiomMatcher : Boolean) : List[MatchStatement] = {
    val matchers = new ArrayBuffer[List[MatchStatement]]
    
    if (isPositivelyMatched(startPred) != negStartLit)
      for (clause <- clauses) {
        val startLits = if (negStartLit)
                          clause.predConj.negativeLitsWithPred(startPred)
                        else
                          clause.predConj.positiveLitsWithPred(startPred)
        for (startLit <- startLits)
          matchers += constructMatcher(startLit, negStartLit, clause)
      }
    
    if (includeAxiomMatcher)
      matchers += constructAxiomMatcher(startPred, negStartLit)
    
    combineMatchers(matchers)
  }
  
  //////////////////////////////////////////////////////////////////////////////

  /**
   * Construct a matcher program for the given clause and the given start
   * literal
   */
  private def constructMatcher(startLit : Atom, negStartLit : Boolean,
                               clause : Conjunction) : List[MatchStatement] = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(AC,
                    ((if (negStartLit)
                       clause.predConj.negativeLitsAsSet
                     else
                       clause.predConj.positiveLitsAsSet) contains startLit) &&
                    (clause.quans forall (Quantifier.EX ==)))
    ////////////////////////////////////////////////////////////////////////////
    
    val (matchedLits, remainingLits) = determineMatchedLits(clause.predConj)
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertInt(AC, matchedLits contains startLit)
    ////////////////////////////////////////////////////////////////////////////
    
    val nonStartMatchedLits = matchedLits filter (startLit !=)
    
    val res = new ArrayBuffer[MatchStatement]
    val knownTerms = new HashMap[LinearCombination, ArrayBuffer[(Int, Int)]]

    def genAliasChecks(a : Atom, litNr : Int) : Unit =
      for ((lc, argNr) <- a.elements.zipWithIndex) {
        for ((oldLitNr, oldArgNr) <- knownTerms.getOrElseUpdate(lc, new ArrayBuffer))
          res += CheckMayAlias(oldLitNr, oldArgNr, litNr, argNr)
        if (lc.variables.isEmpty)
          res += CheckMayAliasUnary(litNr, argNr, lc)
        knownTerms.getOrElseUpdate(lc, new ArrayBuffer) += (litNr, argNr)
      }

    genAliasChecks(startLit, 0)
    
    for ((a, litNr) <- nonStartMatchedLits.elements.zipWithIndex) {
      res += SelectLiteral(a.pred, !isPositivelyMatched(a))
      genAliasChecks(a, litNr + 1)
    }
    
    res += InstantiateClause(clause, Seq(startLit) ++ nonStartMatchedLits,
                             clause.quans, clause.arithConj,
                             remainingLits, clause.negatedConjs)
    
    res.toList
  }
  
  /**
   * Given a conjunction of atoms, determine which atoms are to be matched on
   * facts. The 2nd component of the result are the remaining literals.
   */
  private def determineMatchedLits(conj : PredConj) : (Seq[Atom], PredConj) = {
    val (posMatched, posRemaining) =
      conj.positiveLits partition (isPositivelyMatched(_))
    val (negMatched, negRemaining) =
      conj.negativeLits partition (!isPositivelyMatched(_))

    (posMatched ++ negMatched,
     conj.updateLitsSubset(posRemaining, negRemaining, conj.order))
  }

  /**
   * For a certain atom, determine whether the positive occurrences or the
   * negative occurrences are to be resolved.
   *
   * TODO: generalise this so that for some predicates the positive occurrences,
   * for some predicates the negative occurrences are resolved
   */
  private def isPositivelyMatched(a : Atom) : Boolean = true
  private def isPositivelyMatched(pred : Predicate) : Boolean = true

  object Matchable extends Enumeration {
    val No, ProducesLits, Complete = Value
  }
  
  def isMatchable(c : Conjunction) : Matchable.Value = {
    val (matchLits, remainingLits) = determineMatchedLits(c.predConj)
    if (matchLits.isEmpty)
      Matchable.No
    else if (remainingLits.isTrue && c.negatedConjs.predicates.isEmpty)
      Matchable.Complete
    else
      Matchable.ProducesLits
  }
  
  //////////////////////////////////////////////////////////////////////////////
  
  private def constructAxiomMatcher(pred : Predicate,
                                    negStartLit : Boolean) : List[MatchStatement] = {
    val res = new ArrayBuffer[MatchStatement]

    res += SelectLiteral(pred, !negStartLit)
    for (i <- 0 until pred.arity)
      res += CheckMayAlias(0, i, 1, i)
    
    res += UnifyLiterals(0, 1)
    
    res.toList
  }

  //////////////////////////////////////////////////////////////////////////////
  
  private def combineMatchers(programs : Seq[List[MatchStatement]])
              : List[MatchStatement] =
    // TODO: more efficient implementation
    List(Choice(programs))

  def empty(matchAxioms : Boolean) =
    IterativeClauseMatcher (PredConj.TRUE, NegatedConjunctions.TRUE,
                            matchAxioms,
                            Set(Conjunction.FALSE))

  private def apply(currentFacts : PredConj,
                    currentClauses : NegatedConjunctions,
                    matchAxioms : Boolean,
                    generatedInstances : Set[Conjunction]) =
    new IterativeClauseMatcher(currentFacts, currentClauses, matchAxioms,
                               new HashMap[(Predicate, Boolean), List[MatchStatement]],
                               generatedInstances)
  
}

////////////////////////////////////////////////////////////////////////////////

class IterativeClauseMatcher private (currentFacts : PredConj,
                                      val clauses : NegatedConjunctions,
                                      matchAxioms : Boolean,
                                      matchers : MutableMap[(Predicate, Boolean),
                                                            List[MatchStatement]],
                                      // All instances that have already been
                                      // generated by this match. This is used
                                      // to prevent redundant instantiation
                                      generatedInstances : Set[Conjunction])
      extends Sorted[IterativeClauseMatcher] {
  
  //////////////////////////////////////////////////////////////////////////////
  Debug.assertCtor(IterativeClauseMatcher.AC,
                   (clauses forall ((c) =>
                        (c.quans forall (Quantifier.EX ==)) &&
                        !(IterativeClauseMatcher
                              .determineMatchedLits(c.predConj) _1).isEmpty)) &&
                   // we assume that FALSE always exists as a generated
                   // instance, because we don't want to generate it at all
                   (generatedInstances contains Conjunction.FALSE))
  //////////////////////////////////////////////////////////////////////////////
  
  private def matcherFor(pred : Predicate, negated : Boolean) : List[MatchStatement] =
    matchers.getOrElseUpdate(
      (pred, negated),
      IterativeClauseMatcher.constructMatcher(pred, negated, clauses, matchAxioms))
  
  def updateFacts(newFacts : PredConj,
                  mayAlias : (LinearCombination, LinearCombination) => Boolean,
                  contextReducer : ReduceWithConjunction,
                  // predicate to distinguish the relevant matches
                  // (e.g., to filter out shielded formulae)
                  isIrrelevantMatch : (Conjunction) => Boolean,
                  logger : ComputationLogger,
                  order : TermOrder) : (Collection[Conjunction], IterativeClauseMatcher) =
    if (currentFacts == newFacts) {
      (List(), this)
    } else {
      val (oldFacts, addedFacts) = newFacts diff currentFacts
    
      val instances = new scala.collection.mutable.LinkedHashSet[Conjunction]
      val additionalPosLits, additionalNegLits = new ArrayBuffer[Atom]
      
      val redundancyTester =
        (c:Conjunction) => !(generatedInstances contains c) && !isIrrelevantMatch(c)
      
      for (negated <- List(false, true))
        for (a <- if (negated) addedFacts.negativeLits else addedFacts.positiveLits) {
          val newInstances =
            IterativeClauseMatcher.executeMatcher(a,
                                                  negated,
                                                  matcherFor(a.pred, negated),
                                                  oldFacts,
                                                  additionalPosLits, additionalNegLits,
                                                  mayAlias,
                                                  contextReducer,
                                                  logger,
                                                  order)
          instances ++= FilterIt(newInstances, redundancyTester)
            
          (if (negated) additionalNegLits else additionalPosLits) += a
        }

      (instances,
       new IterativeClauseMatcher(newFacts, clauses, matchAxioms, matchers,
                                  generatedInstances ++ instances))
    }

  def updateClauses(newClauses : NegatedConjunctions,
                    mayAlias : (LinearCombination, LinearCombination) => Boolean,
                    contextReducer : ReduceWithConjunction,
                    // predicate to distinguish the relevant matches
                    // (e.g., to filter out shielded formulae)
                    isIrrelevantMatch : (Conjunction) => Boolean,
                    logger : ComputationLogger,
                    order : TermOrder) : (Collection[Conjunction], IterativeClauseMatcher) =
    if (clauses == newClauses) {
      (List(), this)
    } else {
      val (oldClauses, addedClauses) = newClauses diff clauses
      val tempMatcher =
        IterativeClauseMatcher(PredConj.TRUE, addedClauses, false, generatedInstances)
    
      val (instances, _) =
        tempMatcher.updateFacts(currentFacts,
                                mayAlias, contextReducer, isIrrelevantMatch,
                                logger, order)
    
      (instances,
       IterativeClauseMatcher(currentFacts, newClauses, matchAxioms,
                              generatedInstances ++ instances))
    }
  
  //////////////////////////////////////////////////////////////////////////////

  /**
   * Remove clauses and cached literals from this matcher that are identified
   * by the given predicate. The removed clauses are returned as the first
   * result component.
   */
  def remove(removePred : (Formula) => Boolean)
             : (Seq[Conjunction], IterativeClauseMatcher) = {
    val (removedClauses, keptClausesSeq) = clauses partition removePred
    val (removedLits, keptLits) = currentFacts partition removePred
    
    if (removedClauses.isEmpty) {
      if (removedLits.isTrue)
        (List(), this)
      else
        (List(),
         new IterativeClauseMatcher(keptLits, clauses, matchAxioms, matchers,
                                    generatedInstances))
    } else {
      val keptClauses = clauses.update(keptClausesSeq, clauses.order)
      (removedClauses,
       new IterativeClauseMatcher(keptLits, keptClauses, matchAxioms, matchers,
                                  generatedInstances))
    }
  }
  
  //////////////////////////////////////////////////////////////////////////////

  /**
   * Reduce the clauses of this matcher. All reducible clauses are removed from
   * the matcher and the reductions are returned
   */
  def reduceClauses(reducer : (Conjunction) => Conjunction, order : TermOrder)
                            : (Seq[Conjunction], IterativeClauseMatcher) = {
    val reducedClauses =
      clauses.update(for (c <- clauses) yield reduceIfNecessary(c, reducer), order)

    val (keptClauses, reductions) = reducedClauses diff clauses
    
    // we also reduce the set of generated instances, because future
    // instantiations will be made modulo the new reducer
    val reducedInstances =
      for (i <- generatedInstances) yield reduceIfNecessary(i, reducer)
    
    (reductions,
     if (keptClauses.size == clauses.size && generatedInstances == reducedInstances)
       // nothing has changed
       this
     else
       // reset the matchers
       IterativeClauseMatcher(currentFacts, keptClauses, matchAxioms, reducedInstances))
  }

  private def reduceIfNecessary(conj : Conjunction,
                                reducer : (Conjunction) => Conjunction) : Conjunction =
    if (conj.constants.isEmpty && conj.groundAtoms.isEmpty) {
      ////////////////////////////////////////////////////////////////////
      // The assumption is that clauses without constants or ground atoms
      // are already fully reduced
      Debug.assertInt(IterativeClauseMatcher.AC, reducer(conj) == conj)
      ////////////////////////////////////////////////////////////////////
      conj
    } else {
      reducer(conj)
    }

  //////////////////////////////////////////////////////////////////////////////
  
  def sortBy(order : TermOrder) : IterativeClauseMatcher =
    if (this isSortedBy order)
      this
    else
      IterativeClauseMatcher(currentFacts sortBy order,
                             clauses sortBy order,
                             matchAxioms,
                             for (i <- generatedInstances) yield (i sortBy order))
  
  def isSortedBy(order : TermOrder) : Boolean =
    (currentFacts isSortedBy order) && (clauses isSortedBy order)
  
  override def toString : String =
    "(" + this.currentFacts + ", " + this.clauses + ")"
  
  /**
   * Only used for assertion purposes
   */
  def factsAreOutdated(actualFacts : PredConj) : Boolean =
    actualFacts != currentFacts
}

////////////////////////////////////////////////////////////////////////////////
// AST for programs that match certain literals in clauses on facts
//
// Such programs can pick predicate literals with certain predicates and
// polarities and check the may-alias relation for the arguments of such
// predicates. Each program starts with an implicitly selected literal (the
// start-literal)

private abstract sealed class MatchStatement

private case class SelectLiteral(pred : Predicate, negative : Boolean)
                   extends MatchStatement

private case class CheckMayAlias(litNrA : Int, argNrA : Int,
                                 litNrB : Int, argNrB : Int)
                   extends MatchStatement

private case class CheckMayAliasUnary(litNr : Int, argNr : Int,
                                      lc : LinearCombination)
                   extends MatchStatement

private case class InstantiateClause(originalClause : Conjunction,
                                     matchedLits : Seq[Atom],
                                     quans : Seq[Quantifier],
                                     arithConj : ArithConj,
                                     remainingLits : PredConj,
                                     negConjs : NegatedConjunctions)
                   extends MatchStatement

private case class UnifyLiterals(litNrA : Int, litNrB : Int)
                   extends MatchStatement
                   
private case class Choice(options : Seq[List[MatchStatement]])
                   extends MatchStatement