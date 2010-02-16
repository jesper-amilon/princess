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

package ap.terfor.conjunctions;

import ap.terfor.arithconj.{ArithConj, ReduceWithAC}
import ap.terfor.equations.EquationConj
import ap.terfor.preds.{PredConj, ReduceWithPredLits}
import ap.terfor.substitutions.Substitution
import ap.util.{Debug, Logic, PlainRange}


object ReduceWithConjunction {
  
  private val AC = Debug.AC_PROPAGATION

  def apply(conj : Conjunction, order : TermOrder) : ReduceWithConjunction = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(AC, (conj isSortedBy order) &&
                    // conjunctions with quantifiers are not supported right now
                    conj.quans.isEmpty)
    ////////////////////////////////////////////////////////////////////////////
    
    new ReduceWithConjunction(ReduceWithAC(conj.arithConj, order),
                              ReduceWithPredLits(conj.predConj, order),
                              order)
  }
  
  /**
   * Recursively reduce an arbitrary conjunction
   */
  private def reduceConj(conj : Conjunction,
                         initialReducer : ReduceWithConjunction,
                         logger : ComputationLogger) : Conjunction =
    if (conj.isTrue)
      conj
    else try {
      val (newArithConj, reducer) =
        if (conj.quans.size > 0)
          initialReducer.passQuantifiers(conj.quans.size)
                        .reduce(conj.arithConj, ComputationLogger.NonLogger)
        else
          initialReducer.reduce(conj.arithConj, logger)
    
      val (newPredConj, reducer2) = reducer.reduce(conj.predConj, logger)

      val newNegConjs =
        conj.negatedConjs.update(for (c <- conj.negatedConjs)
                                   yield reduceConj(c, reducer2),
                                 reducer2.order)
       
      val res = constructConj(conj.quans, newArithConj, newPredConj, newNegConjs,
                              reducer2.order)    
      if ((conj.quans sameElements res.quans) &&
          newArithConj == res.arithConj && newPredConj == res.predConj) {
        res
      } else {
        // it might be necessary to repeat reduction, because new facts became
        // available
        reduceConj(res, initialReducer, logger)
      }
    } catch {
      case FALSE_EXCEPTION => Conjunction.FALSE
    }

  private def reduceConj(conj : Conjunction,
                         initialReducer : ReduceWithConjunction) : Conjunction =
    reduceConj(conj, initialReducer, ComputationLogger.NonLogger)

  private def constructConj(quans : Seq[Quantifier],
                            newArithConj : ArithConj,
                            newPredConj : PredConj,
                            newNegConjs : NegatedConjunctions,
                            order : TermOrder) : Conjunction =
    quans.firstOption match {
      case Some(Quantifier.EX) => {
        var eliminableVars : Set[Term] = Set()
    
        var i : Int = 0
        while (i < quans.size && quans(i) == Quantifier.EX) {
          val variable = VariableTerm(i)
          if (!(newNegConjs.variables contains variable))
            eliminableVars = eliminableVars + variable
          i = i + 1
        }
    
        if (eliminableVars.isEmpty) {
          Conjunction(quans, newArithConj, newPredConj, newNegConjs, order)
        } else {
          val literals =
            Conjunction.conj(Array(newArithConj, newPredConj), order)
          val essentialLits = 
            new LiteralEliminator(literals, eliminableVars, order).eliminate(
                                                   ComputationLogger.NonLogger)
          
          val newConj =
            Conjunction(quans, essentialLits.arithConj, essentialLits.predConj,
                        newNegConjs, order)
          
          if (newConj.quans.firstOption == Some(Quantifier.ALL))
            // iterate because it might be possible to eliminate further
            // quantifiers now
            constructConj(newConj.quans, newConj.arithConj, newConj.predConj,
                          newConj.negatedConjs, order)
          else
            newConj
        }
      }
      
      case Some(Quantifier.ALL)
        if (newArithConj.isLiteral && newPredConj.isTrue && newNegConjs.isTrue) =>
        constructConj(for (q <- quans) yield q.dual,
                      newArithConj.negate, PredConj.TRUE, NegatedConjunctions.TRUE,
                      order).negate
      
      case Some(Quantifier.ALL)
        if (newArithConj.isTrue && newPredConj.isTrue && newNegConjs.size == 1) => {
          val subConj = newNegConjs(0)
          constructConj(subConj.quans ++ (for (q <- quans) yield q.dual),
                        subConj.arithConj, subConj.predConj, subConj.negatedConjs,
                        order).negate
      }
      
      case _ =>
        Conjunction(quans, newArithConj, newPredConj, newNegConjs, order)
    }
  
}

class ReduceWithConjunction private (private val acReducer : ReduceWithAC,
                                     private val predReducer : ReduceWithPredLits,
                                     private val order : TermOrder) {

  def passQuantifiers(num : Int) : ReduceWithConjunction = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ReduceWithConjunction.AC, num >= 0)
    ////////////////////////////////////////////////////////////////////////////
    if (num == 0)
      this
    else
      new ReduceWithConjunction(acReducer passQuantifiers num,
                                predReducer passQuantifiers num,
                                order)
  }

  def addArithConj(ac : ArithConj) : ReduceWithConjunction = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ReduceWithConjunction.AC, ac isSortedBy order)
    ////////////////////////////////////////////////////////////////////////////

    if (ac.isEmpty)
      this
    else
      new ReduceWithConjunction(acReducer addArithConj ac, predReducer, order)
  }

  def apply(conj : Conjunction) : Conjunction =
    apply(conj, ComputationLogger.NonLogger)

  def apply(conj : Conjunction, logger : ComputationLogger) : Conjunction = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ReduceWithConjunction.AC, conj isSortedBy order)
    ////////////////////////////////////////////////////////////////////////////
      
    val res = ReduceWithConjunction.reduceConj(conj, this, logger)
    ////////////////////////////////////////////////////////////////////////////
    // we demand that the reducer is a projection (repeated application does not
    // change the result anymore)
    Debug.assertPostFast(ReduceWithConjunction.AC,
                         ReduceWithConjunction.reduceConj(res, this) == res)
    ////////////////////////////////////////////////////////////////////////////
    res
  }

  def apply(conjs : NegatedConjunctions) : NegatedConjunctions = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ReduceWithConjunction.AC, conjs isSortedBy order)
    ////////////////////////////////////////////////////////////////////////////

    val res = conjs.update(for (c <- conjs)
                           yield (ReduceWithConjunction.reduceConj(c, this)),
                           order)
    ////////////////////////////////////////////////////////////////////////////
    // we demand that the reducer is a projection (repeated application does not
    // change the result anymore)
    Debug.assertPostFast(ReduceWithConjunction.AC,
                         Logic.forall(for (c <- res.elements) yield (this(c) == c)))
    ////////////////////////////////////////////////////////////////////////////
    res
  }

  def apply(conj : EquationConj) : EquationConj = acReducer(conj)
  def apply(conj : ArithConj) : ArithConj = acReducer(conj)
  
  private def replaceAC(newAC : ReduceWithAC) : ReduceWithConjunction =
    if (newAC == this.acReducer)
      this
    else
      new ReduceWithConjunction(newAC, predReducer, order)
  
  private def replacePred(newPred : ReduceWithPredLits) : ReduceWithConjunction =
    if (newPred == this.predReducer)
      this
    else
      new ReduceWithConjunction(acReducer, newPred, order)
  
  private def reduce(ac : ArithConj,
                     logger : ComputationLogger) : (ArithConj, ReduceWithConjunction) = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ReduceWithConjunction.AC, ac isSortedBy order)
    ////////////////////////////////////////////////////////////////////////////
    val (newArithConj, newACReducer) = acReducer.reduceAndAdd(ac, logger)
    if (newArithConj.isFalse) throw FALSE_EXCEPTION
    (newArithConj, this replaceAC newACReducer)
  }
  
  private def reduce(conj : PredConj,
                     logger : ComputationLogger) : (PredConj, ReduceWithConjunction) = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(ReduceWithConjunction.AC, conj isSortedBy order)
    ////////////////////////////////////////////////////////////////////////////
    val redConj = predReducer(acReducer(conj, logger))
    if (redConj.isFalse) throw FALSE_EXCEPTION
    (redConj, this replacePred (predReducer addLits redConj))
  }
  
}

private object FALSE_EXCEPTION extends Exception

private class LiteralEliminator(literals : Conjunction,
                                uniVariables : Set[Term],
                                order : TermOrder)
              extends ConjunctEliminator(literals, uniVariables, order) {
  
  protected def nonUniversalElimination(f : Conjunction) =
    throw new UnsupportedOperationException
  
  protected def universalElimination(eliminatedConstant : ConstantTerm,
                  witness : (Substitution, TermOrder) => Substitution) : Unit = {
    // nothing ... we do not need any witness information at this point
  }

  protected def eliminationCandidates(literals : Conjunction) : Iterator[Term] =
    uniVariables.elements
  
}