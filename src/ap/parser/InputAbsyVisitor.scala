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

package ap.parser;

import ap.terfor.ConstantTerm
import ap.terfor.conjunctions.Quantifier
import ap.util.{Debug, Logic, PlainRange}

import scala.collection.mutable.Stack


object CollectingVisitor {
  private val AC = Debug.AC_INPUT_ABSY
}


/**
 * Visitor schema that traverses an expression in depth-first left-first order.
 * For each node, the method <code>preVisit</code> is called when descending
 * and the method <code>postVisit</code> when returning. The visitor works
 * with iteration (not recursion) and is able to deal also with large
 * expressions
 */
abstract class CollectingVisitor[A, R] {

  abstract class PreVisitResult  

  /**
   * Use the same argument for the direct sub-expressions as for this expression
   */
  case object KeepArg extends PreVisitResult
  
  /**
   * Call <code>preVisit</code> again with a different expression and argument
   */
  case class TryAgain(newT : IExpression, newArg : A) extends PreVisitResult
  
  /**
   * Use <code>arg</code> for each of the direct sub-expressions
   */
  case class UniSubArgs(arg : A) extends PreVisitResult
  
  /**
   * Specify the arguments to use for the individual sub-expressions
   */
  case class SubArgs(args : Seq[A]) extends PreVisitResult
  
  /**
   * Skip the call to <code>postVisit</code> and do not visit any of the
   * sub-expressions. Instead, directly return <code>res</code> as result
   */
  case class ShortCutResult(res : R) extends PreVisitResult
  
  def preVisit(t : IExpression, arg : A) : PreVisitResult = KeepArg
  def postVisit(t : IExpression, arg : A, subres : Seq[R]) : R
  
  def visit(expr : IExpression, arg : A) : R = {
    val toVisit = new Stack[IExpression]
    val argsToVisit = new Stack[A]
    val results = new Stack[R]
    
    toVisit push expr
    argsToVisit push arg
    
    while (!toVisit.isEmpty) toVisit.pop match {
      case PostVisit(expr, arg) => {
        var subRes : List[R] = List()
        for (_ <- PlainRange(expr.length)) subRes = results.pop :: subRes
        results push postVisit(expr, arg, subRes)
      }
      
      case expr => {
        val arg = argsToVisit.pop

        preVisit(expr, arg) match {
          case ShortCutResult(res) =>
            // directly push the result, skip the call to postVisit and the
            // recursive calls
            results push res
          
          case TryAgain(newT, newArg) => {
            toVisit push newT
            argsToVisit push newArg
          }
            
          case argModifier => 
            if (expr.length > 0) {
              // recurse
          
              toVisit push PostVisit(expr, arg)
              for (i <- (expr.length - 1) to 0 by -1) toVisit push expr(i)
        
              argModifier match {
                case KeepArg =>
                  for (_ <- PlainRange(expr.length)) argsToVisit push arg
                case UniSubArgs(subArg) =>
                  for (_ <- PlainRange(expr.length)) argsToVisit push subArg
                case SubArgs(subArgs) => {
                  //////////////////////////////////////////////////////////////
                  Debug.assertInt(CollectingVisitor.AC, subArgs.length == expr.length)
                  //////////////////////////////////////////////////////////////
                  for (i <- (expr.length - 1) to 0 by -1) argsToVisit push subArgs(i)
                }
              }
          
            } else {
              // otherwise, we can directly call the postVisit method
          
              results push postVisit(expr, arg, List())
            }
        }
      }
    }
          
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertInt(CollectingVisitor.AC, results.length == 1)
    ////////////////////////////////////////////////////////////////////////////
    results.pop
  }
  
  private case class PostVisit(expr : IExpression, arg : A)
                     extends IExpression
  
}

////////////////////////////////////////////////////////////////////////////////

object VariableShiftVisitor {
  private val AC = Debug.AC_INPUT_ABSY
  
  def apply(t : IExpression, offset : Int, shift : Int) : IExpression =
    new VariableShiftVisitor(offset, shift).visit(t, 0)
  
  def apply(t : IFormula, offset : Int, shift : Int) : IFormula =
    apply(t.asInstanceOf[IExpression], offset, shift).asInstanceOf[IFormula]
  def apply(t : ITerm, offset : Int, shift : Int) : ITerm =
    apply(t.asInstanceOf[IExpression], offset, shift).asInstanceOf[ITerm]
}

class VariableShiftVisitor(offset : Int, shift : Int)
      extends CollectingVisitor[Int, IExpression] {
  //////////////////////////////////////////////////////////////////////////////
  Debug.assertCtor(VariableShiftVisitor.AC, offset >= 0 && offset + shift >= 0)
  //////////////////////////////////////////////////////////////////////////////     

  override def preVisit(t : IExpression, quantifierNum : Int) : PreVisitResult =
    t match {
      case IQuantified(_, _) => UniSubArgs(quantifierNum + 1)
      case _ => KeepArg
    }
  def postVisit(t : IExpression, quantifierNum : Int,
                subres : Seq[IExpression]) : IExpression =
    t match {
      case IVariable(i) =>
        if (i < offset + quantifierNum) t else IVariable(i + shift)
      case _ =>
        t update subres
    }
}

////////////////////////////////////////////////////////////////////////////////

/**
 * More general visitor for renaming variables. The argument of the visitor
 * methods is a pair <code>(List[Int], Int)</code> that describes how each
 * variable should be shifted: <code>(List(0, 2, -1), 1)</code> specifies that
 * variable 0 stays the same, variable 1 is increased by 2 (renamed to 3),
 * variable 2 is renamed to 1, and all other variables n are renamed to n+1.
 */
object VariablePermVisitor extends CollectingVisitor[IVarShift, IExpression] {

  def apply(t : IExpression, shifts : IVarShift) : IExpression =
    this.visit(t, shifts)

  def apply(t : IFormula, shifts : IVarShift) : IFormula =
    apply(t.asInstanceOf[IExpression], shifts).asInstanceOf[IFormula]
  def apply(t : ITerm, shifts : IVarShift) : ITerm =
    apply(t.asInstanceOf[IExpression], shifts).asInstanceOf[ITerm]

  override def preVisit(t : IExpression, shifts : IVarShift) : PreVisitResult =
    t match {
      case IQuantified(_, _) => UniSubArgs(shifts push 0)
      case _ => KeepArg
    }

  def postVisit(t : IExpression, shifts : IVarShift,
                subres : Seq[IExpression]) : IExpression =
    t match {
      case t : IVariable => shifts(t)
      case _ => t update subres
    }
}

object IVarShift {
  private val AC = Debug.AC_INPUT_ABSY
}

case class IVarShift(prefix : List[Int], defaultShift : Int) {
  //////////////////////////////////////////////////////////////////////////////
  Debug.assertCtor(IVarShift.AC,
                   defaultShift + prefix.length >= 0 &&
                   (prefix.elements.zipWithIndex forall {case (i, j) => i + j >= 0}))
  //////////////////////////////////////////////////////////////////////////////

  def push(n : Int) = IVarShift(n :: prefix, defaultShift)
  
  def pop = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(IVarShift.AC, !prefix.isEmpty)
    ////////////////////////////////////////////////////////////////////////////
    IVarShift(prefix.tail, defaultShift)
  }
  
  def compose(that : IVarShift) : IVarShift = {
    val newPrefix = new scala.collection.mutable.ArrayBuffer[Int]
    for ((o, i) <- that.prefix.elements.zipWithIndex)
      newPrefix += (apply(i + o) - i)
    for (i <- that.prefix.length until (this.prefix.length - that.defaultShift))
      newPrefix += (apply(i + that.defaultShift) - i)
    IVarShift(newPrefix.toList, this.defaultShift + that.defaultShift)
  }
  
  def apply(i : Int) : Int = {
    ////////////////////////////////////////////////////////////////////////////
    Debug.assertPre(IVarShift.AC, i >= 0)
    ////////////////////////////////////////////////////////////////////////////
    i + (if (i < prefix.length) prefix(i) else defaultShift)
  }
  def apply(v : IVariable) : IVariable = {
    val newIndex = apply(v.index)
    if (newIndex == v.index) v else IVariable(newIndex)
  }
}

////////////////////////////////////////////////////////////////////////////////

/**
 * Substitute some of the constants in an expression with arbitrary terms
 */
object ConstantSubstVisitor
       extends CollectingVisitor[Map[ConstantTerm, ITerm], IExpression] {
  def apply(t : IExpression, subst : Map[ConstantTerm, ITerm]) : IExpression =
    ConstantSubstVisitor.visit(t, subst)
  def apply(t : ITerm, subst : Map[ConstantTerm, ITerm]) : ITerm =
    apply(t.asInstanceOf[IExpression], subst).asInstanceOf[ITerm]
  def apply(t : IFormula, subst : Map[ConstantTerm, ITerm]) : IFormula =
    apply(t.asInstanceOf[IExpression], subst).asInstanceOf[IFormula]

  override def preVisit(t : IExpression,
                        subst : Map[ConstantTerm, ITerm]) : PreVisitResult =
    t match {
      case IConstant(c) =>
        ShortCutResult(subst.getOrElse(c, c))
      case IQuantified(_, _) => {
        val newSubst =
          subst transform ((_, value) => VariableShiftVisitor(value, 0, 1))
        UniSubArgs(newSubst)
      }
      case _ => KeepArg
    }

  def postVisit(t : IExpression,
                subst : Map[ConstantTerm, ITerm],
                subres : Seq[IExpression]) : IExpression = t update subres
}

////////////////////////////////////////////////////////////////////////////////

object SymbolCollector {
  def variables(t : IExpression) : scala.collection.Set[IVariable] = {
    val c = new SymbolCollector
    c.visit(t, 0)
    c.variables
  }
  def constants(t : IExpression) : scala.collection.Set[ConstantTerm] = {
    val c = new SymbolCollector
    c.visit(t, 0)
    c.constants
  }
}

class SymbolCollector extends CollectingVisitor[Int, Unit] {
  val variables = new scala.collection.mutable.HashSet[IVariable]
  val constants = new scala.collection.mutable.HashSet[ConstantTerm]

  override def preVisit(t : IExpression, boundVars : Int) : PreVisitResult =
    t match {
      case _ : IQuantified => UniSubArgs(boundVars + 1)
      case _ => super.preVisit(t, boundVars)
    }

  def postVisit(t : IExpression, boundVars : Int, subres : Seq[Unit]) : Unit =
    t match {
      case IVariable(i) if (i >= boundVars) =>
        variables += IVariable(i - boundVars)
      case IConstant(c) =>
        constants += c
      case _ => // nothing
    }
}

////////////////////////////////////////////////////////////////////////////////

object Context {
  def apply[A](a : A) : Context[A] = Context(List(), +1, a)
}

case class Context[A](quans : List[Quantifier], polarity : Int, a : A) {
  def togglePolarity = Context(quans, -polarity, a)
  def noPolarity = Context(quans, 0, a)
  def push(q : Quantifier) = Context(q :: quans, polarity, a)
  def apply(newA : A) = Context(quans, polarity, newA)
}

abstract class ContextAwareVisitor[A, R] extends CollectingVisitor[Context[A], R] {

  override def preVisit(t : IExpression, arg : Context[A]) : PreVisitResult =
    t match {
      case INot(_) => UniSubArgs(arg.togglePolarity)
      case IBinFormula(IBinJunctor.Eqv, _, _) => UniSubArgs(arg.noPolarity)
      case IQuantified(quan, _) => {
        val actualQuan = if (arg.polarity < 0) quan.dual else quan
        UniSubArgs(arg push actualQuan)
      }
      case _ => UniSubArgs(arg) // a subclass might have overridden this method
                                // and substituted a different context
    }

}

////////////////////////////////////////////////////////////////////////////////

/**
 * Push negations down to the atoms in a formula
 */
object Transform2NNF extends CollectingVisitor[Boolean, IExpression] {
  import IExpression._
  import IBinJunctor._
  
  def apply(f : IFormula) : IFormula =
    this.visit(f, false).asInstanceOf[IFormula]
    
  override def preVisit(t : IExpression, negate : Boolean) : PreVisitResult =
    t match {
      case INot(f) => TryAgain(f, !negate)  // eliminate negations
      case IBinFormula(Eqv, _, _) => SubArgs(List(negate, false))
      case t@IBoolLit(b) => ShortCutResult(if (negate) !b else t)
      case LeafFormula(t) => ShortCutResult(if (negate) !t else t)
      case _ : IFormula => KeepArg
      case _ : ITerm => ShortCutResult(t)
    }
  
  def postVisit(t : IExpression, negate : Boolean,
                subres : Seq[IExpression]) : IExpression =
    if (negate) t match {
      case IBinFormula(Eqv, _, _) | _ : ITrigger | _ : INamedPart =>
        t update subres
      case IBinFormula(And, _, _) =>
        subres(0).asInstanceOf[IFormula] | subres(1).asInstanceOf[IFormula]
      case IBinFormula(Or, _, _) =>
        subres(0).asInstanceOf[IFormula] & subres(1).asInstanceOf[IFormula]
      case IQuantified(quan, _) =>
        IQuantified(quan.dual, subres(0).asInstanceOf[IFormula])
    } else {
      t.asInstanceOf[IFormula] update subres
    }
}

////////////////////////////////////////////////////////////////////////////////

/**
 * Turn a formula <code> f1 &lowast; f2 &lowast; ... &lowast; fn </code>
 * (where <code>&lowast;</code> is some binary operator) into
 * <code>List(f1, f2, ..., fn)</code>
 */
object LineariseVisitor
       extends CollectingVisitor[IBinJunctor.Value, List[IFormula]] {
  def apply(t : IFormula, op : IBinJunctor.Value) = this.visit(t, op)
         
  override def preVisit(t : IExpression, op : IBinJunctor.Value) : PreVisitResult =
    t match {
      case IBinFormula(`op`, _, _) => KeepArg
      case t : IFormula => ShortCutResult(List(t))
    }

  def postVisit(t : IExpression, op : IBinJunctor.Value,
                subres : Seq[List[IFormula]]) : List[IFormula] =
    for (l <- subres.toList; x <- l) yield x
}