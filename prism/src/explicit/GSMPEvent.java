//==============================================================================
//	
//	Copyright (c) 2002-
//	Authors:
//	* Dave Parker <david.parker@comlab.ox.ac.uk> (University of Oxford)
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

import java.util.*;

import parser.type.TypeDistribution;
import prism.PrismException;

/**
 * Explicit engine class representing a GSMP event.
 * This class holds all information associated with the event,
 * including the distribution and the entire command.
 */
public class GSMPEvent extends DTMCSimple 
{
	private TypeDistribution distributionType;
	private double firstParameter;
	private double secondParameter;
	private BitSet active;
	/**
	 * Unique identifier String passed over when generated from ast/Event class
	 */
	private String identifier;

	// Constructors

	/**
	 * Constructor: new Event with an unspecified number of states.
	 */
	public GSMPEvent(TypeDistribution distributionType, double firstParameter, double secondParameter, String identifier) {
		super();
		this.distributionType = distributionType;
		this.firstParameter = firstParameter;
		this.secondParameter = secondParameter;
		this.identifier = identifier;
		clearActive();
	}

	/**
	 * Constructor: new Event with fixed number of states.
	 */
	public GSMPEvent(int numStates, TypeDistribution distributionType, double firstParameter, double secondParameter, String identifier) {
		super(numStates);
		this.distributionType = distributionType;
		this.firstParameter = firstParameter;
		this.secondParameter = secondParameter;
		this.identifier = identifier;
		clearActive();
	}

	/**
	 * Copy constructor.
	 */
	public GSMPEvent(GSMPEvent event) {
		super(event);
		this.distributionType = event.distributionType;
		this.firstParameter = event.firstParameter;
		this.secondParameter = event.secondParameter;
		this.identifier = event.getIdentifier();
		clearActive();
		this.active.or(event.active);
	}

	/**
	 * Copy constructor.
	 */
	public GSMPEvent(GSMPEvent event, int permut[]) {
		super(event, permut);
		this.distributionType = event.distributionType;
		this.firstParameter = event.firstParameter;
		this.secondParameter = event.secondParameter;
		this.identifier =  event.getIdentifier();
		clearActive();
		int min = (numStates < permut.length ? numStates : permut.length);
		for (int i = 0; i < min; i++) {
			if (event.isActive(i))
				active.set(permut[i]);
		}
		// this.active.or(event.active);
	}

	// TODO MAJO - I think this is supposed to be some distribution parameter check.
	// TODO MAJO - get rid of it, or update it
	public int getNumberOfSteps(double interval) throws PrismException {
		System.out.println("Delay: " + firstParameter + " interval: " + interval
				+ " res: "
				+ (new Double(Math.floor(firstParameter / interval))).intValue());
        if(firstParameter < interval)
        	throw new PrismException("Delay(" + firstParameter + " is smaller than discretization step(" + interval + " ).");
		return (int) Math.round(firstParameter / interval);
	}

	/**
	 * Add to the probability for a transition.
	 */
	public void addToProbability(int i, int j, double prob) {
		super.addToProbability(i, j, prob);
		setActive(i);
	}

	private void clearActive() {
		active = new BitSet(numStates);
	}

	public void setActive(int state) {
		active.set(state);
	}

	public void setPassive(int state){
		active.clear(state);
		clearState(state);
	}
	
	public void setFirstParameter(double firstParam) {
		this.firstParameter = firstParam;
	}
	
	public void setSecondParameter(double secondParam) {
		this.secondParameter = secondParam;
	}
	
	public void setDistributionType(TypeDistribution distributionType) {
		this.distributionType = distributionType;
	}

	public boolean isActive(int state) {
		return active.get(state);
	}

	public BitSet getActive() {
		return active;
	}

	public String getIdentifier() {
		return identifier;
	}

	public double getFirstParameter() {
		return firstParameter;
	}
	
	public double getSecondParameter() {
		return secondParameter;
	}
	
	public TypeDistribution getDistributionType() {
		return distributionType;
	}

	@Override
	public String toString() {
		String str = "Event[" + getIdentifier() + ", " + distributionType.getTypeString();
		switch (distributionType.getNumParams()) {
		case 1:
			str += "(" + firstParameter + ") ";
			break;
		case 2:
			str += "(" + firstParameter + "," + secondParameter + ") ";
			break;
		default:
			str += "(Unusual number of parameters) ";
		}
		str += ", active=" + active + ", probabilities=" + super.toString() + ']';
		return str;
	}
}