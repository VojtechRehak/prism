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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import prism.ModelType;

/**
 * Read-only explicit-state representation of an ACTMC suitable for CTMC-based model checking methods.
 * ACTMCs are constructed from viable GSMPs during GSMP model-checking.
 * This is done to enable usage of more effective model-checking algorithms,
 * and to reduce the amount of events.
 * <br>
 * ACTMCs are GSMPs where at most one non-exponential event is active in any given state.
 * In addition, all exponential events of the previous GSMP are merged
 * into the CTMC transition matrix and then removed.
 */
public class ACTMCSimple extends CTMCSimple
{
	/** Mapping of non-exponential events onto states in which they are active. */
	protected Map<Integer, GSMPEvent> events = new HashMap<Integer, GSMPEvent>();
	
	// TODO MAJO - figure out how to use the GSMPRewards class for ACTMCs.
	// 			   It would probably be necessary to create a new reward mapping.

	/**
	 * Constructor from an already created GSMP.
	 * 
	 */
	public ACTMCSimple(GSMPSimple gsmp) {
		super();
		initialise(gsmp.getNumStates());
		copyFrom(gsmp);
		this.statesList = gsmp.getStatesList();
		/* //TODO MAJO - implement
		this.events = new HashMap<Integer, GSMPEvent>(gsmp.getNumEvents());
		List<GSMPEvent> tmp = gsmp.getEventList();
		for (int i = 0; i < tmp.size(); ++i) {
			this.events.put(tmp.get(i).getIdentifier(), new GSMPEvent(tmp.get(i)));
		}
		*/
	}
	
	@Override
	public ModelType getModelType() {
		return ModelType.GSMP;
	}

	public Map<Integer, GSMPEvent> getEventMap() {
		return events;
	}
	
	public List<GSMPEvent> getEventList() {
		return (new ArrayList<GSMPEvent>(events.values()));
	}

	/**
	 * @return The total number of non-exponential events within the ACTMC
	 */
	public int getNumEvents() {
		return events.size();
	}
	
	public GSMPEvent getActiveEvent(int state) {
		return events.get(state);
	}
	
	@Override
	public String toString() {
 		String str =  "ACTMC with " + getNumEvents() + " events:";
 		List<GSMPEvent> events = getEventList();
		for (int i = 0; i < events.size(); i++) {
			str += "\n" + events.get(i);
		}
		str += "Underlying CTMC :\n" + super.toString();
 		return str;
	}
}
