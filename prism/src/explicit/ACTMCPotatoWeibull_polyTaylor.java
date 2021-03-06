//==============================================================================
//	
//	Copyright (c) 2018-
//	Authors:
//	* Mario Uhrik <433501@mail.muni.cz> (Masaryk University)
//	
//------------------------------------------------------------------------------
//	
//	This file is part of PRISM.
//	
//	PRISM is free software; you can redistribute it and/or modify
//	it under the terms of the GNU General Public License as published by
//	the Free Software Foundation; either version 2 of the License, or
//	(at your option) any later version.
//	
//	PRISM is distributed in the hope that it will be useful,
//	but WITHOUT ANY WARRANTY; without even the implied warranty of
//	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//	GNU General Public License for more details.
//	
//	You should have received a copy of the GNU General Public License
//	along with PRISM; if not, write to the Free Software Foundation,
//	Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//	
//==============================================================================

package explicit;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;

import ch.obermuhlner.math.big.BigDecimalMath;
import common.BigDecimalUtils;
import common.polynomials.Polynomial;
import common.polynomials.PolynomialReal;
import explicit.rewards.ACTMCRewardsSimple;
import prism.PrismException;

/**
 * See parent class documentation for more basic info. {@link ACTMCPotato}, {@link ACTMCPotato_poly}
 * <br>
 * This extension implements high-precision precomputation
 * of Weibull-distributed potatoes using class BigDecimal.
 * <br>
 * HOW IT'S DONE:
 * Weibull distribution has two parameters, weibullRate and k.
 * First, the data is evaluated without specific distribution parameters.
 * This yields a general polynomial P(t), where t is the firing time.
 * Then, let F(t, weibullRate, k) = P(t) * t^(k-1) * e^(-((t/weibullRate)^k)) * e^(- uniformizationRate * t).
 * However, weibullRate and k are already given as the event parameters,
 * so F becomes expolynomial F(t).
 * Computing Riemann integral from 0 to sufficiently-high n of (F(t) * dt) would yield the desired results,
 * but finding the antiderivative of F(t) is exceedingly difficult, so another approach is needed.
 * Instead, we approximate e^(-((t/weibullRate)^k)) and e^(- uniformizationRate * t) with Taylor series
 * to obtain polynomials T1(t), and T2(t).
 * Instead of using F(t), we use polynomial F'(t) = P(t) * t^(k-1) * T1(t) * T2(t).
 * Lastly, compute Riemann integral from 0 to sufficiently-high n of (F'(t) * dt).
 */
public class ACTMCPotatoWeibull_polyTaylor extends ACTMCPotato_poly
{
	
	/**
	 * Taylor series representation of e^(-((t/weibullRate)^k) - (uniformizationRate * t)).
	 * Computed during a call to {@link ACTMCPotatoWeibull_polyTaylor#computeFoxGlynn()}.
	 */
	private PolynomialReal taylor;
	/**
	 * Number {@code b} such that when computing the Riemann integral
	 * from 0 to b for Weibull distribution in {@link ACTMCPotatoWeibull_polyTaylor#evaluateAntiderivative(Polynomial)},
	 * proper results are yielded.
	 * In other words, computes {@code b} such that it is high enough for the result to be proper, but also
	 * low enough for the result not to be rubbish because of the used precision or degree of the polynomials.
	 * Computed during a call to {@link ACTMCPotatoWeibull_polyTaylor#computeFoxGlynn()}.
	 */
	private BigDecimal integralCeil;
	
	/** {@link ACTMCPotato#ACTMCPotato(ACTMCSimple, GSMPEvent, ACTMCRewardsSimple, BitSet)} */
	public ACTMCPotatoWeibull_polyTaylor(ACTMCSimple actmc, GSMPEvent event, ACTMCRewardsSimple rewards, BitSet target) throws PrismException {
		super(actmc, event, rewards, target);
	}
	
	public ACTMCPotatoWeibull_polyTaylor(ACTMCPotato_poly other) throws PrismException {
		super(other);
	}
	
	@Override
	public void setKappa(BigDecimal kappa) {
		// ACTMCPotatoWeibull usually requires better precision, dependent on the distribution parameters.
		// So, adjust kappa by the possible distribution parameter values.
		int basePrecision = BigDecimalUtils.decimalDigits(kappa);
		int weibullPrecision = 100 + basePrecision + (int)actmc.getMaxExitRate() + (int)event.getSecondParameter() * 5 + (int)(10/event.getSecondParameter())  + (int)event.getFirstParameter()  +
				((int)Math.ceil(Math.log(((event.getFirstParameter() + (int)actmc.getMaxExitRate()) * basePrecision * event.getSecondParameter()))));
		
		BigDecimal weibullKappa = BigDecimalUtils.allowedError(weibullPrecision);
		super.setKappa(weibullKappa);
	}

	@Override
	protected void computeFoxGlynn() throws PrismException {
		if (!potatoDTMCComputed) {
			computePotatoDTMC();
		}
		
		if (kappa == null) {
			// Precision must be specified by setKappa()
			throw new PrismException("No precision specified for FoxGlynn!");
		}
		
		BigDecimal fgRate = new BigDecimal(String.valueOf(uniformizationRate), mc); // Compute FoxGlynn only for the uniformization rate
		int fgKappaFactor = (int)Math.pow((event.getFirstParameter() + (event.getFirstParameter() * 1 / Math.exp(event.getSecondParameter())) )
				* (Math.exp((1/event.getSecondParameter()) - 1) + (1 - 1/Math.E)), 2);
		BigDecimal fgKappa = kappa.multiply(BigDecimalUtils.allowedError(fgKappaFactor), mc);
		foxGlynn = new FoxGlynn_BD(fgRate, new BigDecimal(1e-300), new BigDecimal(1e+300), fgKappa);
		if (foxGlynn.getRightTruncationPoint() < 0) {
			throw new PrismException("Overflow in Fox-Glynn computation of the Poisson distribution!");
		}
		int left = foxGlynn.getLeftTruncationPoint();
		int right = foxGlynn.getRightTruncationPoint();
		BigDecimal[] weights = foxGlynn.getWeights();
		
		//Get rid of the e^-lambda part, i.e. divide everything by e^-lambda
		BigDecimal factor = BigDecimalMath.exp(fgRate.negate(), mc);
		for (int i = left; i <= right; i++) {
			weights[i - left] = weights[i - left].divide(factor, mc);
		}
		
		int taylorSize = right + 
				(int)(
				(event.getFirstParameter() +
				(0.5 * event.getFirstParameter() * right / Math.exp(event.getSecondParameter()))
				) * 
				(Math.exp((1/event.getSecondParameter()) - 1) + (1 - 1/Math.E)));
		taylor = computeTaylorSeriesBoth(event.getFirstParameter(), event.getSecondParameter(), taylorSize);
		integralCeil = computeIntegralCeil(event.getFirstParameter(), event.getSecondParameter(), taylor);
		
		foxGlynnComputed = true;
	}

	@Override
	protected void computeMeanTimes() throws PrismException {
		if (!foxGlynnComputed) {
			computeFoxGlynn();
		}
		
		int numStates = potatoDTMC.getNumStates();
		
		// Prepare the FoxGlynn data
		int left = foxGlynn.getLeftTruncationPoint();
		int right = foxGlynn.getRightTruncationPoint();
		BigDecimal[] weights_BD = foxGlynn.getWeights().clone();
		BigDecimal totalWeight_BD = foxGlynn.getTotalWeight();
		for (int i = left; i <= right; i++) {
			weights_BD[i - left] = weights_BD[i - left].divide(totalWeight_BD.multiply(new BigDecimal(String.valueOf(uniformizationRate), mc), mc), mc);
		}
		
		for (int entrance : entrances) {
			
			// Prepare solution arrays
			double[] soln = new double[numStates];
			double[] soln2 = new double[numStates];
			double[] result = new double[numStates];
			PolynomialReal[] polynomials = new PolynomialReal[numStates];
			PolynomialReal[] antiderivatives = new PolynomialReal[numStates];
			double[] tmpsoln = new double[numStates];

			// Initialize the solution array by assigning reward 1 to the entrance and 0 to all others.
			// Also, initialize the polynomials.
			for (int i = 0; i < numStates; i++) {
				soln[i] = 0;
				polynomials[i] = new PolynomialReal();
			}
			soln[ACTMCtoDTMC.get(entrance)] = 1;

			// do 0th element of summation (doesn't require any matrix powers), and initialize the coefficients
			result = new double[numStates];
			if (left == 0) {
				for (int i = 0; i < numStates; i++) {
					for (int j = 1; j <= right; ++j) {
						polynomials[i].coeffs.put(new BigDecimal(String.valueOf(j), mc), new BigDecimal(soln[i], mc).multiply(weights_BD[j - left], mc));
					}
				}
			} else {
				for (int i = 0; i < numStates; i++) {
					for (int j = 0; j < left; ++j) {
						polynomials[i].coeffs.put(new BigDecimal(String.valueOf(j), mc), BigDecimal.ZERO);
					}
					for (int j = left; j <= right; ++j) {
						polynomials[i].coeffs.put(new BigDecimal(String.valueOf(j), mc), new BigDecimal(soln[i], mc).divide(new BigDecimal(String.valueOf(uniformizationRate), mc), mc));
					}
				}
			}

			// Start iterations
			int iters = 1;
			while (iters <= right) {
				// Matrix-vector multiply
				potatoDTMC.vmMult(soln, soln2);
				// Swap vectors for next iter
				tmpsoln = soln;
				soln = soln2;
				soln2 = tmpsoln;
				// Add to sum
				if (iters >= left) {
					for (int i = 0; i < numStates; i++) {
						for (int j = iters + 1; j < right; ++j) {
							BigDecimal tmp = polynomials[i].coeffs.get(new BigDecimal(String.valueOf(j), mc)).add(new BigDecimal(soln[i], mc).multiply(weights_BD[j - left], mc), mc);
							polynomials[i].coeffs.put(new BigDecimal(String.valueOf(j), mc), tmp);
						}
					}
				} else {
					for (int i = 0; i < numStates; i++) {
						for (int j = left; j <= right; ++j) {
							BigDecimal tmp = polynomials[i].coeffs.get(new BigDecimal(String.valueOf(j), mc)).add(new BigDecimal(soln[i], mc).divide(new BigDecimal(String.valueOf(uniformizationRate), mc), mc), mc);
							polynomials[i].coeffs.put(new BigDecimal(String.valueOf(j), mc), tmp);
						}
					}
				}
				iters++;
			}
			
			// Store the sol vector using the original indexing for later use.
			Distribution solnDistr = new Distribution();
			for (int ps : potato) {
				double sol = soln[ACTMCtoDTMC.get(ps)];
				if (sol != 0.0) {
					solnDistr.add(ps, sol);
				}
			}
			meanTimesSoln.put(entrance, solnDistr);
			
			//Factor the Taylor series representation into the polynomial
			for (int n = 0; n < numStates ; ++n) {
				polynomials[n].multiply(taylor, mc);
			}
			
			//Multiply the polynomial by t^(k-1)
			for (int n = 0; n < numStates  ; ++n) {
				PolynomialReal tmp = new PolynomialReal();
				BigDecimal kMinusOne = new BigDecimal(String.valueOf(event.getSecondParameter()), mc).subtract(BigDecimal.ONE, mc);
				tmp.coeffs.put(kMinusOne, BigDecimal.ONE);
				polynomials[n].multiply(tmp, mc);
			}
			
			//Compute the antiderivatives of the polynomial
			for (int n = 0; n < numStates ; ++n) {
				antiderivatives[n] = polynomials[n].antiderivative(mc);
			}
			
			// Store the solution polynomials for later use.
			for (int n = 0; n < numStates ; ++n) {
				meanTimesPolynomials.get(entrance).put(DTMCtoACTMC.get(n), antiderivatives[n]);
			}
			
			//Compute the definite integral using the obtained antiderivative
			for (int n = 0; n < numStates ; ++n) {
				result[n] = evaluateAntiderivative(antiderivatives[n], integralCeil).doubleValue();
			}
			
			// Convert the result to a distribution with original indexing and store it.
			Distribution resultDistr = new Distribution();
			for (int ps : potato) {
				double time = result[ACTMCtoDTMC.get(ps)];
				if (time != 0.0) {
					resultDistr.add(ps, time);
				}
			}
			meanTimes.put(entrance, resultDistr);
		}
		meanTimesComputed = true;
	}
	
	@Override
	protected void computeMeanDistributions() throws PrismException {
		if (!foxGlynnComputed) {
			computeFoxGlynn();
		}
		
		int numStates = potatoDTMC.getNumStates();
		
		// Prepare the FoxGlynn data
		int left = foxGlynn.getLeftTruncationPoint();
		int right = foxGlynn.getRightTruncationPoint();
		BigDecimal[] weights_BD = foxGlynn.getWeights().clone();
		BigDecimal totalWeight_BD = foxGlynn.getTotalWeight();
		for (int i = left; i <= right; i++) {
			weights_BD[i - left] = weights_BD[i - left].divide(totalWeight_BD, mc);
		}
		
		for (int entrance : entrances) {
			
			// Prepare solution arrays
			double[] initDist = new double[numStates];
			double[] soln;
			double[] soln2 = new double[numStates];
			double[] result = new double[numStates];
			PolynomialReal[] polynomialsBeforeEvent = new PolynomialReal[numStates];
			PolynomialReal[] polynomialsAfterEvent = new PolynomialReal[numStates];
			PolynomialReal[] antiderivatives = new PolynomialReal[numStates];
			double[] tmpsoln = new double[numStates];
			
			// Build the initial distribution for this potato entrance
			for (int s = 0; s < numStates  ; ++s) {
				initDist[s] = 0;
			}
			initDist[ACTMCtoDTMC.get(entrance)] = 1;
			soln = initDist;

			// Initialize the arrays
			for (int i = 0; i < numStates; i++) {
				result[i] = 0.0;
				polynomialsBeforeEvent[i] = new PolynomialReal();
				polynomialsAfterEvent[i] = new PolynomialReal(BigDecimal.ZERO);
			}

			// If necessary, compute the 0th element of summation
			// (doesn't require any matrix powers)
			if (left == 0) {
				for (int i = 0; i < numStates; i++) {
					polynomialsBeforeEvent[i].coeffs.put(BigDecimal.ZERO, new BigDecimal(soln[i], mc).multiply(weights_BD[0], mc));
				}
			} else {

			}

			// Start iterations
			int iters = 1;
			while (iters <= right) {
				// Matrix-vector multiply
				potatoDTMC.vmMult(soln, soln2);
				// Swap vectors for next iter
				tmpsoln = soln;
				soln = soln2;
				soln2 = tmpsoln;
				// Add to sum
				if (iters >= left) {
					for (int i = 0; i < numStates; i++) {
						polynomialsBeforeEvent[i].coeffs.put(new BigDecimal(String.valueOf(iters), mc), new BigDecimal(soln[i], mc).multiply(weights_BD[iters - left], mc));
					}
				} else {

				}
				iters++;
			}
			
			// Store the sol vector using the original indexing for later use.
			Distribution solnDistr = new Distribution();
			for (int ps : potato) {
				double sol = soln[ACTMCtoDTMC.get(ps)];
				if (sol != 0.0) {
					solnDistr.add(ps, sol);
				}
			}
			meanDistributionsSoln.put(entrance, solnDistr);
			
			//Factor the Taylor series representation into the polynomial
			for (int n = 0; n < numStates ; ++n) {
				polynomialsBeforeEvent[n].multiply(taylor, mc);
			}
			
			//Multiply the polynomial by t^(k-1)
			for (int n = 0; n < numStates  ; ++n) {
				PolynomialReal tmp = new PolynomialReal();
				BigDecimal kMinusOne = new BigDecimal(String.valueOf(event.getSecondParameter()), mc).subtract(BigDecimal.ONE, mc);
				tmp.coeffs.put(kMinusOne, BigDecimal.ONE);
				polynomialsBeforeEvent[n].multiply(tmp, mc);
			}
			
			//Compute the antiderivatives of the polynomial
			for (int n = 0; n < numStates ; ++n) {
				antiderivatives[n] = polynomialsBeforeEvent[n].antiderivative(mc);
			}
			
			// Store the solution polynomials for later use.
			for (int n = 0; n < numStates ; ++n) {
				meanDistributionsBeforeEventPolynomials.get(entrance).put(DTMCtoACTMC.get(n), antiderivatives[n]);
			}
			
			//Compute the definite integral using the obtained antiderivative
			for (int n = 0; n < numStates ; ++n) {
				result[n] = evaluateAntiderivative(antiderivatives[n], integralCeil).doubleValue();
			}
			
			// Store the just-before-event result vector for later use by other methods
			Distribution resultBeforeEvent = new Distribution();
			for(int i = 0; i < numStates ; ++i ) {
				resultBeforeEvent.add(DTMCtoACTMC.get(i), result[i]);
			}
			meanDistributionsBeforeEvent.put(entrance, resultBeforeEvent);
			
			//Lastly, the actual event behavior is applied.
			//I.e. if there is some probability that the potatoDTMC would 
			//still be within the potato at the time of the event occurrence,
			//these probabilities must be redistributed into the successor states.
			//using the event-defined distribution on states.
			for (int n = 0; n < numStates  ; ++n) {
				int nIndex = DTMCtoACTMC.get(n);
				if (potato.contains(nIndex)) {
					Distribution distr = event.getTransitions(nIndex);
					Set<Integer> distrSupport = distr.getSupport();
					for ( int successor : distrSupport) {
						polynomialsBeforeEvent[n].multiplyWithScalar(new BigDecimal(distr.get(successor), mc),  mc);
						polynomialsAfterEvent[ACTMCtoDTMC.get(successor)].add(polynomialsBeforeEvent[n], mc);
						polynomialsBeforeEvent[n].multiplyWithScalar(BigDecimal.ONE.divide(new BigDecimal(distr.get(successor), mc), mc),  mc);
					}
				} else {
					polynomialsAfterEvent[n].add(polynomialsBeforeEvent[n], mc);
				}
			}
			
			//Compute the antiderivatives of the polynomial
			for (int n = 0; n < numStates ; ++n) {
				antiderivatives[n] = polynomialsAfterEvent[n].antiderivative(mc);
			}
			
			// Store the solution polynomials for later use.
			for (int n = 0; n < numStates ; ++n) {
				meanDistributionsPolynomials.get(entrance).put(DTMCtoACTMC.get(n), antiderivatives[n]);
			}
			
			//Compute the definite integral using the obtained antiderivative
			for (int n = 0; n < numStates ; ++n) {
				result[n] = evaluateAntiderivative(antiderivatives[n], integralCeil).doubleValue();
			}
			
			// Normalize the result array (it may not sum to 1 due to inaccuracy).
			double probSum = 0;
			for (int succState : successors) {
				probSum += result[ACTMCtoDTMC.get(succState)];
			}
			// Convert the result to a distribution with original indexing and store it.
			Distribution resultDistr = new Distribution();
			for (int succState : successors) {
				double prob = result[ACTMCtoDTMC.get(succState)];
				if (prob != 0.0) {
					resultDistr.add(succState, prob / probSum); 
				}
			}
			meanDistributions.put(entrance, resultDistr);
		}
		meanDistributionsComputed = true;
	}
	
	@Override
	protected void computeMeanRewards() throws PrismException {
		if (!meanDistributionsComputed) {
			computeMeanDistributions();
		}
		
		int numStates = potatoDTMC.getNumStates();
		
		// Prepare the FoxGlynn data
		int left = foxGlynn.getLeftTruncationPoint();
		int right = foxGlynn.getRightTruncationPoint();
		BigDecimal[] weights_BD = foxGlynn.getWeights().clone();
		BigDecimal totalWeight_BD = foxGlynn.getTotalWeight();
		for (int i = left; i <= right; i++) {
			weights_BD[i - left] = weights_BD[i - left].divide(totalWeight_BD.multiply(new BigDecimal(String.valueOf(uniformizationRate), mc), mc), mc);
		}
		
		// Prepare solution arrays
		double[] soln = new double[numStates];
		double[] soln2 = new double[numStates];
		double[] result = new double[numStates];
		PolynomialReal[] polynomialsBeforeEvent = new PolynomialReal[numStates];
		PolynomialReal[] polynomialsAfterEvent = new PolynomialReal[numStates];
		PolynomialReal[] antiderivatives = new PolynomialReal[numStates];
		double[] tmpsoln = new double[numStates];

		// Initialize the solution array by assigning rewards to the potato states
		// Also initialize the polynomials
		for (int s = 0; s < numStates; s++) {
			int index = DTMCtoACTMC.get(s);
			if (potato.contains(index)) {
				soln[s] = rewards.getMergedStateReward(index);
			} else {
				soln[s] = 0;
			}
			polynomialsBeforeEvent[s] = new PolynomialReal();
		}

		// do 0th element of summation (doesn't require any matrix powers), and initialize the coefficients
		result = new double[numStates];
		if (left == 0) {
			for (int i = 0; i < numStates; i++) {
				for (int j = 1; j <= right; ++j) {
					polynomialsBeforeEvent[i].coeffs.put(new BigDecimal(String.valueOf(j), mc), new BigDecimal(soln[i], mc).multiply(weights_BD[j - left], mc));
				}
			}
		} else {
			for (int i = 0; i < numStates; i++) {
				for (int j = left; j <= right; ++j) {
					polynomialsBeforeEvent[i].coeffs.put(new BigDecimal(String.valueOf(j), mc), new BigDecimal(soln[i], mc).divide(new BigDecimal(String.valueOf(uniformizationRate), mc), mc));
				}
			}
		}

		// Start iterations
		int iters = 1;
		while (iters <= right) {
			// Matrix-vector multiply
			potatoDTMC.mvMult(soln, soln2, null, false);
			// Swap vectors for next iter
			tmpsoln = soln;
			soln = soln2;
			soln2 = tmpsoln;
			// Add to sum
			if (iters >= left) {
				for (int i = 0; i < numStates; i++) {
					for (int j = iters + 1; j < right; ++j) {
						BigDecimal tmp = polynomialsBeforeEvent[i].coeffs.get(new BigDecimal(String.valueOf(j), mc)).add(new BigDecimal(soln[i], mc).multiply(weights_BD[j - left], mc), mc);
						polynomialsBeforeEvent[i].coeffs.put(new BigDecimal(String.valueOf(j), mc), tmp);
					}
				}
			} else {
				for (int i = 0; i < numStates; i++) {
					for (int j = left; j <= right; ++j) {
						BigDecimal tmp = polynomialsBeforeEvent[i].coeffs.get(new BigDecimal(String.valueOf(j), mc)).add(new BigDecimal(soln[i], mc).divide(new BigDecimal(String.valueOf(uniformizationRate), mc), mc), mc);
						polynomialsBeforeEvent[i].coeffs.put(new BigDecimal(String.valueOf(j), mc), tmp);
					}
				}
			}
			iters++;
		}
		
		// Store the sol vector  using the original indexing for later use.
		for (int ps : potato) {
			double sol = soln[ACTMCtoDTMC.get(ps)];
			if (sol != 0.0) {
				meanRewardsSoln.set(ps, sol);
			}
		}
		
		//Factor the Taylor series representation into the polynomial
		for (int n = 0; n < numStates ; ++n) {
			polynomialsBeforeEvent[n].multiply(taylor, mc);
		}
		
		//Multiply the polynomial by t^(k-1)
		for (int n = 0; n < numStates  ; ++n) {
			PolynomialReal tmp = new PolynomialReal();
			BigDecimal kMinusOne = new BigDecimal(String.valueOf(event.getSecondParameter()), mc).subtract(BigDecimal.ONE, mc);
			tmp.coeffs.put(kMinusOne, BigDecimal.ONE);
			polynomialsBeforeEvent[n].multiply(tmp, mc);
		}
		
		//Compute the antiderivatives of the polynomial
		for (int n = 0; n < numStates ; ++n) {
			antiderivatives[n] = polynomialsBeforeEvent[n].antiderivative(mc);
		}
		
		// Store the solution polynomials for later use.
		for (int n = 0; n < numStates ; ++n) {
			meanRewardsBeforeEventPolynomials.put(DTMCtoACTMC.get(n), antiderivatives[n]);
		}
		
		//Compute the definite integral using the obtained antiderivative
		for (int n = 0; n < numStates ; ++n) {
			result[n] = evaluateAntiderivative(antiderivatives[n], integralCeil).doubleValue();
		}
		
		// Store the rewards just before the event behavior using the original indexing.
		for (int entrance : entrances) {
			meanRewardsBeforeEvent.set(entrance, result[ACTMCtoDTMC.get(entrance)]);
		}
		
		//Now that we have the expected rewards for the underlying CTMC behavior,
		//event behavior is applied.
		polynomialsAfterEvent = (PolynomialReal[])getEventRewardsPoly(false);
		antiderivatives = antiderivatives.clone();
		for (int n = 0; n < numStates ; ++n) {
			antiderivatives[n] = new PolynomialReal(antiderivatives[n].coeffs);
			antiderivatives[n].add(polynomialsAfterEvent[n], mc);
		}
		
		// Store the solution polynomials for later use.
		for (int n = 0; n < numStates ; ++n) {
			meanRewardsPolynomials.put(DTMCtoACTMC.get(n), antiderivatives[n]);
		}
		
		//Compute the definite integral using the obtained antiderivative
		for (int n = 0; n < numStates ; ++n) {
			result[n] = evaluateAntiderivative(antiderivatives[n], integralCeil).doubleValue();
		}
		
		// Store the finalized expected rewards using the original indexing.
		for (int entrance : entrances) {
			meanRewards.set(entrance, result[ACTMCtoDTMC.get(entrance)]);
		}
		
		meanRewardsComputed = true;
	}
	
	/**
	 * Computes the Taylor series representation of e^(-((t/weibullRate)^k)) where t is unknown
	 * @param wRate Weibull Rate/scale (first) parameter
	 * @param wK Weibull shape (second) parameter
	 * @param i integer >1 of how many elements of the series to include. Internally incremented by wRate.
	 * @return polynomial that is the Taylor series representation of e^(-((t/weibullRate)^k))
	 */
	@Deprecated
	private PolynomialReal computeTaylorSeriesWeibull(double wRate, double wK, int i) {
		BigDecimal rateBD = new BigDecimal(String.valueOf(wRate), mc);
		BigDecimal kBD = new BigDecimal(String.valueOf(wK), mc);
		i = i + (int)wRate * 5;
		if (wK < 1) {
			i = i + (int)(30/wK);
		} else {
			i = i + (int)(wK * 3);
		}
		
		BigDecimal powerElem = BigDecimalMath.pow(BigDecimal.ONE.divide(rateBD, mc), kBD, mc).negate();
		PolynomialReal taylor = new PolynomialReal();
		
		BigDecimal revFact = BigDecimal.ONE; 
		BigDecimal power = BigDecimal.ONE;
		BigDecimal augment = BigDecimal.ONE;
		for (int n = 0; n <= i; ++n) {
			BigDecimal exponent = new BigDecimal(String.valueOf(n), mc).multiply(kBD, mc);
			taylor.coeffs.put(exponent, augment);
			
			revFact = revFact.divide(new BigDecimal(String.valueOf(n), mc).add(BigDecimal.ONE, mc), mc);
			power = power.multiply(powerElem, mc);
			augment = revFact.multiply(power, mc);
		}
		
		return taylor;
	}
	
	/**
	 * Computes the Taylor series representation of e^(- uniformizationRate * t) where t is unknown
	 * @param i integer >1 of how many elements of the series to include
	 * @return polynomial that is the Taylor series representation of e^(- uniformizationRate * t)
	 */
	@Deprecated
	private PolynomialReal computeTaylorSeriesPoisson(int i) {
		BigDecimal powerElem = new BigDecimal(String.valueOf(uniformizationRate), mc).negate();
		PolynomialReal taylor = new PolynomialReal();
		
		BigDecimal revFact = BigDecimal.ONE; 
		BigDecimal power = BigDecimal.ONE;
		BigDecimal augment = BigDecimal.ONE;
		for (int n = 0; n <= i; ++n) {
			taylor.coeffs.put(new BigDecimal(String.valueOf(n), mc), augment);
			
			revFact = revFact.divide(new BigDecimal(n+1, mc), mc);
			power = power.multiply(powerElem, mc);
			augment = revFact.multiply(power, mc);
		}
		
		return taylor;
	}
	
	/**
	 * Computes the Taylor series representation of e^(-((t/weibullRate)^k) - uniformizationRate * t) where t is unknown
	 * @param wRate Weibull Rate/scale (first) parameter
	 * @param wK Weibull shape (second) parameter
	 * @param i integer >1 of how many elements of the series to include.
	 * @return polynomial that is the Taylor series representation of e^(-((t/weibullRate)^k) - uniformizationRate * t)
	 */
	private PolynomialReal computeTaylorSeriesBoth(double wRate, double wK, int i) {
		BigDecimal rateBD = new BigDecimal(String.valueOf(wRate), mc);
		BigDecimal kBD = new BigDecimal(String.valueOf(wK), mc);
		
		BigDecimal powerElemWeibull = BigDecimalMath.pow(BigDecimal.ONE.divide(rateBD, mc), kBD, mc).negate();
		BigDecimal powerElemPoisson = new BigDecimal(String.valueOf(uniformizationRate), mc).negate();
		PolynomialReal taylor = new PolynomialReal();
		
		BigDecimal revFact = BigDecimal.ONE; 
		
		//Precomputation of frequently used values
		List<BigDecimal> weibullCoeffs = new ArrayList<BigDecimal>();
		List<BigDecimal> poissonCoeffs = new ArrayList<BigDecimal>();
		BigDecimal weibullCoeff = BigDecimal.ONE;
		BigDecimal poissonCoeff = BigDecimal.ONE;
		for (int n = 0; n <= i + 3; ++n) {
			weibullCoeffs.add(weibullCoeff);
			weibullCoeff = weibullCoeff.multiply(powerElemWeibull, mc);
			
			poissonCoeffs.add(poissonCoeff);
			poissonCoeff = poissonCoeff.multiply(powerElemPoisson, mc);
		}
		
		for (int n = 0; n <= i; ++n) {
			List<BigDecimal> binomialLine = Polynomial.binomialLine(new BigInteger(String.valueOf(n)));
			
			for (int k = 0; k < binomialLine.size() ; ++k) {
				BigDecimal binomial = binomialLine.get(k);
				int weibullExponent = (binomialLine.size() - 1) - k;
				int poissonExponent = k;
				
				BigDecimal exponent = kBD.multiply(new BigDecimal(weibullExponent, mc),  mc).add(new BigDecimal(poissonExponent, mc));
				
				weibullCoeff = weibullCoeffs.get(weibullExponent);
				poissonCoeff = poissonCoeffs.get(poissonExponent);
				BigDecimal coeff = binomial.multiply(weibullCoeff, mc).multiply(poissonCoeff, mc).multiply(revFact, mc);
				
				BigDecimal currentCoeff = taylor.coeffs.get(exponent);
				if (currentCoeff == null) {
					currentCoeff = BigDecimal.ZERO;
				}
				taylor.coeffs.put(exponent, coeff.add(currentCoeff, mc));
			}
			revFact = revFact.divide(new BigDecimal(String.valueOf(n), mc).add(BigDecimal.ONE, mc), mc);
		}
		
		return taylor;
	}
	
	/**
	 * Evaluates the given antiderivative to compute the definite (Riemann) integral.
	 * <br>
	 * In other words, this actually does the F(b)-F(a) part of Riemann integral required for this specific distribution.
	 * @param antiderivative Polynomial
	 * @param Value up to which to perform the integration
	 * @return result BigDecimal number, actually the mean time,distribution or reward for given entrance and state
	 */
	private BigDecimal evaluateAntiderivative(PolynomialReal antiderivative, BigDecimal b) {
		BigDecimal a = BigDecimal.ZERO;
		BigDecimal aVal = antiderivative.value(a, mc);
		BigDecimal bVal = antiderivative.value(b, mc);
		BigDecimal firstFactor = new BigDecimal(String.valueOf(event.getSecondParameter()), mc).divide(new BigDecimal(String.valueOf(event.getFirstParameter()), mc), mc);
		BigDecimal secondFactor = BigDecimal.ONE.divide(BigDecimalMath.pow(new BigDecimal(String.valueOf(event.getFirstParameter()), mc), new BigDecimal(String.valueOf(event.getSecondParameter()), mc).subtract(BigDecimal.ONE, mc), mc), mc);
		BigDecimal totalFactor = firstFactor.multiply(secondFactor, mc);
		
		BigDecimal res = bVal.subtract(aVal, mc).multiply(totalFactor, mc);
		return res;
	}
	
	/**
	 * Computes number {@code b} such that when computing the Riemann integral
	 * from 0 to b for Weibull distribution in {@link ACTMCPotatoWeibull_polyTaylor#evaluateAntiderivative(Polynomial)},
	 * proper results are yielded.
	 * In other words, computes {@code b} such that it is high enough for the result to be proper, but also
	 * low enough for the result not to be rubbish because of the used precision or degree of the polynomials.
	 * @param wRate Weibull Rate/scale (first) parameter
	 * @param wK Weibull shape (second) parameter
	 * @param taylor - taylor series polynomial to be used
	 * @return BigDecimal {@code b}
	 */
	private BigDecimal computeIntegralCeil(double wRate, double wK, PolynomialReal taylor) throws PrismException {
		BigDecimal wRateBD = new BigDecimal(String.valueOf(wRate), mc);
		BigDecimal wKBD = new BigDecimal(String.valueOf(wK), mc);
		
		//Make a hard copy of the taylor series
		PolynomialReal taylorTmp = new PolynomialReal(taylor);
		
		//Multiply the Taylor series polynomial by t^(k-1) // TODO MAJO - not necessary, but doesn't hurt
	    PolynomialReal tmp = new PolynomialReal();
		BigDecimal kMinusOne = wKBD.subtract(BigDecimal.ONE, mc);
		tmp.coeffs.put(kMinusOne, BigDecimal.ONE);
		taylorTmp.multiply(tmp, mc);
		
		//Factor in the rest // TODO MAJO - not necessary, but doesn't hurt
		BigDecimal firstFactor = wKBD.divide(wRateBD, mc);
		BigDecimal secondFactor = BigDecimal.ONE.divide(BigDecimalMath.pow(wRateBD, wKBD.subtract(BigDecimal.ONE, mc), mc), mc);
		BigDecimal totalFactor = firstFactor.multiply(secondFactor, mc);
		taylorTmp.multiplyWithScalar(totalFactor, mc);
		
		//compute the integral of the Taylor series
		PolynomialReal antiderivative = taylorTmp.antiderivative(mc);
		
		// find the optimal b
		BigDecimal b = BigDecimal.ZERO;
		BigDecimal increment = new BigDecimal("5.0", mc).multiply(wRateBD, mc).divide(wKBD,  mc);
		BigDecimal prob = BigDecimal.ZERO;
		BigDecimal lastProb;
		BigDecimal diff = BigDecimal.ZERO;
		BigDecimal lastDiff;
		int iters = 0;
		do { //upward slope search
			b = b.add(increment.divide(BigDecimal.TEN.multiply(BigDecimal.TEN, mc), mc), mc);
			lastProb = prob;
			prob = antiderivative.value(b, mc);
			lastDiff = diff;
			diff = prob.subtract(lastProb, mc);
			if (diff.compareTo(lastDiff) < 0) {
				break;
			}
		} while (prob.compareTo(lastProb) > 0);
		diff = BigDecimal.ONE;
		do { //downward slope search, low accuracy
			b = b.add(increment, mc);
			lastProb = prob;
			prob = antiderivative.value(b, mc);
			lastDiff = diff;
			diff = prob.subtract(lastProb, mc);
			if (diff.compareTo(lastDiff) > 0) {
				break;
			}
			++iters;
		} while (prob.compareTo(lastProb) > 0);
		b = b.subtract(increment, mc);
		prob = lastProb;
		diff = lastDiff;
		if (iters >= 2) {
			b = b.subtract(increment, mc);
			lastProb = prob;
			prob = antiderivative.value(b, mc);
			diff = BigDecimal.ONE;
		}
		iters = 0;
		increment = increment.divide(BigDecimal.TEN, mc);
		do { //downward slope search, medium accuracy
			b = b.add(increment, mc);
			lastProb = prob;
			prob = antiderivative.value(b, mc);
			lastDiff = diff;
			diff = prob.subtract(lastProb, mc);
			if (diff.compareTo(lastDiff) > 0) {
				break;
			}
			++iters;
		} while (prob.compareTo(lastProb) > 0);
		b = b.subtract(increment, mc);
		prob = lastProb;
		diff = lastDiff;
		if (iters >= 2) {
			b = b.subtract(increment, mc);
			lastProb = prob;
			prob = antiderivative.value(b, mc);
			diff = BigDecimal.ONE;
		}
		iters = 0;
		increment = increment.divide(BigDecimal.TEN, mc);
		do { //downward slope search, high accuracy
			b = b.add(increment, mc);
			lastProb = prob;
			prob = antiderivative.value(b, mc);
			lastDiff = diff;
			diff = prob.subtract(lastProb, mc);
			if (diff.compareTo(lastDiff) > 0) {
				break;
			}
			++iters;
		} while (prob.compareTo(lastProb) > 0);
		b = b.subtract(increment, mc);
		prob = lastProb;
		diff = lastDiff;
		if (iters >= 2) {
			b = b.subtract(increment, mc);
			lastProb = prob;
			prob = antiderivative.value(b, mc);
			diff = BigDecimal.ONE;
		}
		iters = 0;
		increment = increment.divide(BigDecimal.TEN, mc);
		do { //downward slope search, very high accuracy
			b = b.add(increment, mc);
			lastProb = prob;
			prob = antiderivative.value(b, mc);
			lastDiff = diff;
			diff = prob.subtract(lastProb, mc);
			if (diff.compareTo(lastDiff) > 0) {
				break;
			}
			++iters;
		} while (prob.compareTo(lastProb) > 0);
			
		return b.subtract(increment, mc);
	}

}