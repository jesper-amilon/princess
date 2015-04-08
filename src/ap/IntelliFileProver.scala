/**
 * This file is part of Princess, a theorem prover for Presburger
 * arithmetic with uninterpreted predicates.
 * <http://www.philipp.ruemmer.org/princess.shtml>
 *
 * Copyright (C) 2009-2015 Philipp Ruemmer <ph_r@gmx.net>
 *
 * Princess is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
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

package ap;

import ap.parser.Internal2InputAbsy
import ap.proof.{ConstraintSimplifier, ModelSearchProver}
import ap.proof.tree.ProofTree
import ap.proof.certificates.Certificate
import ap.terfor.conjunctions.{Conjunction, Quantifier, IterativeClauseMatcher}
import ap.parameters.{GlobalSettings, Param}
import ap.util.{Seqs, Debug, Timeout}
import ap.interpolants.{Interpolator, InterpolationContext, ProofSimplifier,
                        ArraySimplifier}

object IntelliFileProver {
  
  private val AC = Debug.AC_MAIN
  
}

/**
 * A prover that decides, depending on the kind of the problem, whether it
 * should try to construct a proof tree or just search for counterexamples
 */
class IntelliFileProver(reader : java.io.Reader,
                        // a timeout in milliseconds
                        timeout : Int,
                        output : Boolean,
                        userDefStoppingCond : => Boolean,
                        settings : GlobalSettings)
      extends AbstractFileProver(reader, output, timeout,
                                 userDefStoppingCond, settings) {

  import Prover._

  lazy val proofResult : ProofResult =
    Timeout.catchTimeout[ProofResult] {
      val (tree, validConstraint) = constructProofTree
      if (validConstraint) {
        if (Seqs.disjoint(tree.closingConstraint.constants,
                          signature.universalConstants))
          ProofWithModel(tree, toIFormula(findModel(tree.closingConstraint)))
        else
          Proof(tree)
      } else if (soundForSat) {
        Invalid(tree)
      } else {
        NoProof(tree)
      }
    } {
      case x : ProofTree => TimeoutProof(x)
      case _ => TimeoutProof(null)
    }
        
  lazy val proofTree : ProofTree = proofResult match {
    case TimeoutProof(t) => t
    case Proof(t) => t
    case ProofWithModel(t, _) => t
    case NoProof(t) => t
    case Invalid(t) => t
  } 

  lazy val modelResult : ModelResult =
    Timeout.catchTimeout[ModelResult] { 
      val model = findModelTimeout.left.get
      if (model.isFalse)
        NoModel
      else
        Model(toIFormula(model))
    } {
      case _ => TimeoutModel
    }

  private def processCert(cert : Certificate) : Certificate = {
    print("Found proof (size " + cert.inferenceCount + ")")
    if (Param.PROOF_SIMPLIFICATION(settings)) {
      print(", simplifying ")
      val simpCert = ProofSimplifier(cert)
      print("(" + simpCert.inferenceCount + ")")
      //-BEGIN-ASSERTION-///////////////////////////////////////////////////////
      Debug.assertInt(IntelliFileProver.AC,
                      simpCert.assumedFormulas subsetOf cert.assumedFormulas)
      //-END-ASSERTION-/////////////////////////////////////////////////////////
      simpCert
    } else {
      cert
    }
  }
    
  private def toIFormula(c : Conjunction) = {
    val raw = Internal2InputAbsy(c, functionEncoder.predTranslation)
    (new ArraySimplifier)(raw)
  }
  
  lazy val counterModelResult : CounterModelResult =
    Timeout.catchTimeout[CounterModelResult] { 
      findCounterModelTimeout match {
        case Left(model) =>
          if (model.isFalse)
            NoCounterModel
          else if (soundForSat)
            CounterModel(toIFormula(model))
          else
            MaybeCounterModel(toIFormula(model))
        case Right(cert) if (!interpolantSpecs.isEmpty) => {
          val finalCert = Console.withOut(Console.err) {
            val c = processCert(cert)
            println(", interpolating ...")
            c
          }

          val interpolants = for (spec <- interpolantSpecs.view) yield {
            val iContext = InterpolationContext(namedParts, spec, order)
            val rawInterpolant =
              Interpolator(finalCert, iContext,
            	   	       Param.ELIMINATE_INTERPOLANT_QUANTIFIERS(settings))
            toIFormula(rawInterpolant)
          }
          NoCounterModelCertInter(finalCert, interpolants)
        }

        case Right(cert) => {
          val finalCert = Console.withOut(Console.err) {
            val c = processCert(cert)
            println("")
            c
          }
          NoCounterModelCert(finalCert)
        }
      }
    } {
      case _ => TimeoutCounterModel
    }

  val result : Prover.Result = {
    // currently, only the ModelSearchProver can construct proofs
    if (Param.PROOF_CONSTRUCTION(goalSettings) && !canUseModelSearchProver)
      throw new Exception ("Currently no proofs can be constructed for the given" +
                           " problem,\nsince it contains existential constants or" +
                           " quantifiers that cannot be\nhandled by unit resolution.\n" +
                           "You might want to use the option -genTotalityAxioms")
      
    if (canUseModelSearchProver) {
      // try to find a countermodel
      counterModelResult
    } else if (!Param.MOST_GENERAL_CONSTRAINT(settings) &&
               (formulaConstants subsetOf signature.existentialConstants) &&
               (formulas forall ((f) => f.predicates.isEmpty)) &&
               (formulaQuantifiers subsetOf Set(Quantifier.EX))) {
      // try to find a model
      modelResult
    } else {
      // try to construct a proof
      proofResult
    }
  }
  
}
