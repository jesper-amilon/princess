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

package ap.util;

object LazyMappedSet {
  
  private val AC = Debug.AC_SET_UTILS
  
}

/**
 * Transform a set by applying a given injective function to all of its
 * arguments.
 */
class LazyMappedSet[A,B] (oriSet : scala.collection.Set[A],
                          mapping : (A) => B,
                          unmapping : PartialFunction[B,A])
      extends scala.collection.Set[B] {

  def size : Int = oriSet.size
  
  def contains(x : B) : Boolean =
    if (unmapping isDefinedAt x) {
      val oriX = unmapping(x)
      //-BEGIN-ASSERTION-///////////////////////////////////////////////////////
      Debug.assertInt(LazyMappedSet.AC, mapping(oriX) == x)
      //-END-ASSERTION-/////////////////////////////////////////////////////////
      oriSet contains oriX
    } else {
      false
    }
  
  def elements : Iterator[B] = for (x <- oriSet.elements) yield {
    val mappedX = mapping(x)
    //-BEGIN-ASSERTION-/////////////////////////////////////////////////////////
    Debug.assertInt(LazyMappedSet.AC, (unmapping isDefinedAt mappedX) &&
                                      unmapping(mappedX) == x)
    //-END-ASSERTION-///////////////////////////////////////////////////////////  
    mappedX
  }
  
}
