/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2020 Philipp Ruemmer <ph_r@gmx.net>
 *
 * Princess is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * Princess is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Princess.  If not, see <http://www.gnu.org/licenses/>.
 */

package ap.theories.algebra

import ap.basetypes.IdealInt
import ap.parser._
import ap.types.Sort
import ap.util.Debug

/**
 * A Pseudo-ring is a structure with the same operations as a ring, but
 * no guarantee that multiplication satisfies the ring axioms
 */
trait PseudoRing {

  /**
   * Domain of the ring
   */
  val dom : Sort

  /**
   * Conversion of an integer term to a ring term
   */
  def int2ring(s : ITerm) : ITerm

  /**
   * The zero element of this ring
   */
  def zero : ITerm

  /**
   * The one element of this ring
   */
  def one : ITerm

  /**
   * Ring addition
   */
  def plus(s : ITerm, t : ITerm) : ITerm

  /**
   * N-ary sums
   */
  def sum(terms : ITerm*) : ITerm = (zero /: terms)(plus)

  /**
   * Additive inverses
   */
  def minus(s : ITerm) : ITerm

  /**
   * Difference between two terms
   */
  def minus(s : ITerm, t : ITerm) : ITerm = plus(s, minus(t))

  /**
   * Ring multiplication
   */
  def mul(s : ITerm, t : ITerm) : ITerm

  /**
   * N-ary sums
   */
  def product(terms : ITerm*) : ITerm = (one /: terms)(mul)

  /**
   * Addition gives rise to an Abelian group
   */
  def additiveGroup : Group with Abelian = new Group with Abelian {
    val dom = PseudoRing.this.dom
    def zero = PseudoRing.this.zero
    def op(s : ITerm, t : ITerm) = plus(s, t)
    def minus(s : ITerm) = PseudoRing.this.minus(s)

    override def times(num : IdealInt, s : ITerm) : ITerm =
      mul(int2ring(IIntLit(num)), s)
  }

}

/**
 * Rings are structures with both addition and multiplication
 */
trait Ring extends PseudoRing {

  /**
   * Multiplication gives rise to a monoid
   */
  def multiplicativeMonoid : Monoid = new Monoid {
    val dom = Ring.this.dom
    def zero = Ring.this.one
    def op(s : ITerm, t : ITerm) = mul(s, t)
  }

}
