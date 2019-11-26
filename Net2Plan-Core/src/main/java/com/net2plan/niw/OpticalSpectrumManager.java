/*******************************************************************************
 * This program and the accompanying materials
 * are made available under the terms of the MIT License available at
 * https://opensource.org/licenses/MIT
 *******************************************************************************/

package com.net2plan.niw;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.jgrapht.alg.cycle.DirectedSimpleCycles;
import org.jgrapht.alg.cycle.JohnsonSimpleCycles;
import org.jgrapht.graph.DefaultDirectedGraph;

import com.google.common.collect.Sets;
import com.net2plan.interfaces.networkDesign.Net2PlanException;
import com.net2plan.utils.Pair;
import com.net2plan.utils.Triple;


/** This class is used to account for the occupation of the optical spectrum in the network.  
 * The object can be created from an existing network. To make a valid optical design, the user is responsible of 
 * using this object methods to check if routing and spectrum assignments (RSAs) of new lightpaths are valid. Also, this object 
 * includes some methods for simple RSA recommendations, e.g. first-fit assignments
 * (occupy idle and valid resources)
 * Occupation is represented by optical slots, each defined by an integer. The central frequency of optical slot i is 193.1+i*0.0125 THz.
 * All optical slots are supposed to have the same width 12.5 GHz (see WNetConstants)
 *
 */
public class OpticalSpectrumManager
{
	public enum OpticalSignalOccupationType { LEGITIMATESIGNAL , WASTESIGNAL; public boolean isWaste() { return this == OpticalSignalOccupationType.WASTESIGNAL;} public boolean isLegitimate () { return this == OpticalSignalOccupationType.LEGITIMATESIGNAL;  }  };
	
	private class SlotOccupationManager <T>
	{
		final private Map<T,SortedMap<Integer,SortedSet<WLightpath>>> occupation_element_s_ll = new HashMap<> ();
		final private SortedMap<WLightpath,Map<T,SortedSet<Integer>>> occupation_ll_element_s = new TreeMap<> ();
		public SlotOccupationManager() {}
		public void clear () { occupation_element_s_ll.clear(); occupation_ll_element_s.clear(); }
		public Map<T,SortedMap<Integer,SortedSet<WLightpath>>> getFullPerElementOccupationMap () { return Collections.unmodifiableMap(occupation_element_s_ll); }
		public SortedMap<Integer,SortedSet<WLightpath>> getOccupiedSlotIds (T element) 
		{ 
			return Collections.unmodifiableSortedMap(occupation_element_s_ll.getOrDefault(element, new TreeMap<> ())); 	
		}
		public int getNumberOfOccupiedSlotIds (T element) 
		{ 
			return occupation_element_s_ll.getOrDefault(element, new TreeMap<> ()).size(); 	
		}
		public Set<T> getElementsWithAtLeastOneSlotOccupied  () 
		{ 
			return Collections.unmodifiableSet(occupation_element_s_ll.keySet()); 	
		}
	    public SortedSet<Integer> getOccupiedOpticalSlotIds (T element)
	    {
			SortedMap<Integer,SortedSet<WLightpath>> occupiedSlotsPerLightpath = this.occupation_element_s_ll.get(element);
			if(occupiedSlotsPerLightpath == null)
				return new TreeSet<>();
	    	return new TreeSet<> (occupiedSlotsPerLightpath.keySet());
	    }
	    public void allocateOccupation (T element , WLightpath lp , SortedSet<Integer> slotIds)
	    {
	    	if (slotIds.isEmpty()) return;
	    	boolean clashesWithPreviousAllocations = false;
    		SortedMap<Integer,SortedSet<WLightpath>> thisElementInfo = this.occupation_element_s_ll.get(element);
    		if (thisElementInfo == null) { thisElementInfo = new TreeMap<> (); this.occupation_element_s_ll.put(element, thisElementInfo); }
    		for (int slotId : slotIds)
    		{
    			SortedSet<WLightpath> currentCollidingLps = thisElementInfo.get(slotId);
    			if (currentCollidingLps == null) { currentCollidingLps = new TreeSet<> (); thisElementInfo.put(slotId, currentCollidingLps); }
    			if (!currentCollidingLps.isEmpty()) clashesWithPreviousAllocations = true;
    			currentCollidingLps.add(lp);
    		}
    		Map<T,SortedSet<Integer>> alreadyAccountedOccupiedResourcesOfThisType = this.occupation_ll_element_s.get (lp);
    		if (alreadyAccountedOccupiedResourcesOfThisType == null)
    		{
    			alreadyAccountedOccupiedResourcesOfThisType = new HashMap<> ();
    			this.occupation_ll_element_s.put (lp , alreadyAccountedOccupiedResourcesOfThisType);
    		}
    		alreadyAccountedOccupiedResourcesOfThisType.put(element, slotIds);
	    }

	    public void releaseOccupation (WLightpath lp)
	    {
	    	final Map<T,SortedSet<Integer>> occupiedResources = this.occupation_ll_element_s.get(lp);
	    	if (occupiedResources == null) return;
	    	for (Entry<T,SortedSet<Integer>> resource : occupiedResources.entrySet())
	    	{
	    		final T element = resource.getKey();
	    		final SortedSet<Integer> slotIds = resource.getValue();
	    		assert element != null;
	    		SortedMap<Integer,SortedSet<WLightpath>> thisFiberInfo = this.occupation_element_s_ll.get(element);
	    		for (int slotId : slotIds)
	    		{
	    			final SortedSet<WLightpath> thisLpAndOthers = thisFiberInfo.get(slotId);
	    			assert thisLpAndOthers != null;
	    			assert thisLpAndOthers.contains(lp);
	    			thisLpAndOthers.remove(lp);
	    			if (thisLpAndOthers.isEmpty()) 
	    			{
	    				thisFiberInfo.remove(slotId);
	    				if (thisFiberInfo.isEmpty()) this.occupation_element_s_ll.remove(element);
	    			}
	    		}
	    	}
	    	occupation_ll_element_s.remove(lp);
	    }
	};

	public class LightpathSpectrumOccupationInformation
	{
		private final List<WFiber> legitimate_seqLinks;
		private final Optional<Pair<WNode,Integer>> legitimate_addDirlessModule;
		private final Optional<Pair<WNode,Integer>> legitimate_dropDirlessModule;
		private final SortedSet<Integer> occupiedSlots;
		private List<Pair<WNode,Integer>> waste_addDirlessModules = null;
		private List<Pair<WNode,Integer>> waste_dropDirlessModules = null;
		private SortedSet<WFiber> waste_fibers = null;
		public LightpathSpectrumOccupationInformation(List<WFiber> legitimate_seqLinks,
				Optional<Pair<WNode, Integer>> legitimate_addDirlessModule,
				Optional<Pair<WNode, Integer>> legitimate_dropDirlessModule,
				SortedSet<Integer> occupiedSlots) 
		{
			this.legitimate_seqLinks = legitimate_seqLinks;
			this.legitimate_addDirlessModule = legitimate_addDirlessModule;
			this.legitimate_dropDirlessModule = legitimate_dropDirlessModule;
			this.occupiedSlots = occupiedSlots;
		}
		public void resetWasteOccupationInfo () { this.waste_addDirlessModules = null;  this.waste_dropDirlessModules = null; this.waste_fibers = null; }
		public SortedSet<WFiber> getFibersWithWasteSignal () 
		{
			if (waste_fibers == null) this.updateWasteOccupationInfo();
			return Collections.unmodifiableSortedSet(this.waste_fibers);
		}
		public List<Pair<WNode,Integer>> getAddDirectionlessModulesWithWasteSignal () 
		{
			if (waste_fibers == null) this.updateWasteOccupationInfo();
			return Collections.unmodifiableList(this.waste_addDirlessModules);
		}
		public List<Pair<WNode,Integer>> getDropDirectionlessModulesWithWasteSignal () 
		{
			if (waste_fibers == null) this.updateWasteOccupationInfo();
			return Collections.unmodifiableList(this.waste_dropDirlessModules);
		}
		public List<WFiber> getSeqFibersLegitimateSignal () { return Collections.unmodifiableList(this.legitimate_seqLinks); }
		public Optional<Pair<WNode,Integer>> getDirectionlessAddModuleLegitimateSignal () { return this.legitimate_addDirlessModule; }
		public Optional<Pair<WNode,Integer>> getDirectionlessDropModuleLegitimateSignal () { return this.legitimate_dropDirlessModule; }

		public boolean isWithFiberCyclesInLegitimateSignal ()
		{
			return legitimate_seqLinks.size() != new HashSet<> (legitimate_seqLinks).size();
		}
		public boolean isWithSelfClashing ()
		{
			if (isWithFiberCyclesInLegitimateSignal()) return true;
			final SortedSet<WFiber> wasteFibers = getFibersWithWasteSignal();
			for (WFiber e : getSeqFibersLegitimateSignal()) if (wasteFibers.contains(e)) return true;
			if (getDirectionlessAddModuleLegitimateSignal().isPresent())
				if (getAddDirectionlessModulesWithWasteSignal().contains(getDirectionlessAddModuleLegitimateSignal().get()))
					return true;
			if (getDirectionlessDropModuleLegitimateSignal().isPresent())
				if (getDropDirectionlessModulesWithWasteSignal().contains(getDirectionlessDropModuleLegitimateSignal().get()))
					return true;
			return false;
		}
		
		private void updateWasteOccupationInfo ()
		{
			
		}
	}
	
	
	private WNet wNet;
	final private SlotOccupationManager<WFiber> legitimateSignal_perFiberOccupation = new SlotOccupationManager<>();
	final private SlotOccupationManager<Pair<WNode,Integer>> legitimateSignal_directionlessAddOccupation = new SlotOccupationManager<>();
	final private SlotOccupationManager<Pair<WNode,Integer>> legitimateSignal_directionlessDropOccupation = new SlotOccupationManager<>();
	final private SlotOccupationManager<WFiber> wasteSignal_perFiberOccupation = new SlotOccupationManager<>();
	final private SlotOccupationManager<Pair<WNode,Integer>> wasteSignal_directionlessAddOccupation = new SlotOccupationManager<>();
	final private SlotOccupationManager<Pair<WNode,Integer>> wasteSignal_directionlessDropOccupation = new SlotOccupationManager<>();
	final private SortedMap<WLightpath , LightpathSpectrumOccupationInformation> lightpathsIncluded = new TreeMap<> ();
	
	
	//	
//	final private Map<Pair<WNode,Integer>,SortedMap<Integer,SortedSet<WLightpath>>> directionlessAddOccupation_nm_s_ll = new HashMap<> ();
//	final private Map<Pair<WNode,Integer>,SortedMap<Integer,SortedSet<WLightpath>>> directionlessDropOccupation_nm_s_ll = new HashMap<> ();
//	final private SortedMap<WLightpath,SortedMap<WFiber,SortedSet<Integer>>> occupation_ll_f_s = new TreeMap<> ();
//	final private SortedMap<WLightpath,Triple<WNode,Integer,SortedSet<Integer>>> directionlessAddOccupation_ll_nms = new TreeMap<> ();
//	final private SortedMap<WLightpath,Triple<WNode,Integer,SortedSet<Integer>>> directionlessDropOccupation_ll_nms = new TreeMap<> ();

	private OpticalSpectrumManager (WNet wNet) { this.wNet = wNet; }
	
	/** Creates this object, asociated to a given network
	 * @param net the network
	 * @return see above
	 */
	public static OpticalSpectrumManager createFromRegularLps (WNet net)
    {
		final OpticalSpectrumManager osm = new OpticalSpectrumManager(net);
		osm.resetFromRegularLps(net);
        return osm;
    }

	/** Resets this object, makes it associated to a given network and according to their lightpaths
	 * @param net the network
	 * @return see above
	 */
	public OpticalSpectrumManager resetFromRegularLps (WNet net)
    {
		this.wNet = net;
		this.wasteSignal_perFiberOccupation.clear();
		this.wasteSignal_directionlessAddOccupation.clear();
		this.wasteSignal_directionlessDropOccupation.clear();
		this.legitimateSignal_perFiberOccupation.clear();
		this.legitimateSignal_directionlessAddOccupation.clear();
		this.legitimateSignal_directionlessDropOccupation.clear();
		for (WLightpath lp : net.getLightpaths())
		{
			final Optional<Integer> addDirectionlessModuleIndex = lp.getDirectionlessAddModuleIndexInOrigin();
			final Optional<Integer> dropDirectionlessModuleIndex = lp.getDirectionlessDropModuleIndexInDestination();
			this.allocateOccupationLegitimateSignal(lp, 
					addDirectionlessModuleIndex.isPresent()? Optional.of(Pair.of(lp.getA(), addDirectionlessModuleIndex.get())) : Optional.empty() , 
					dropDirectionlessModuleIndex.isPresent()? Optional.of(Pair.of(lp.getB(), dropDirectionlessModuleIndex.get())) : Optional.empty() , 
							lp.getSeqFibers(), lp.getOpticalSlotIds());
			final Triple<SortedSet<WFiber>,Set<Pair<WNode,Integer>> , Set<Pair<WNode,Integer>>> wasteOccupResources = lp.getResourcesWithWasteSignal();
			this.allocateOccupationWasteSignal(lp, wasteOccupResources.getSecond(), wasteOccupResources.getThird(), wasteOccupResources.getFirst(), lp.getOpticalSlotIds());
		}
        return this;
    }


	/** FA: Returns the set of the optical slots ids that are idle in ALL the fibers provided and also, if given, in the add and drop directionless modules, so they are not occupied by legitimate or waste signals
     * @param wdmLinks the set of fibers
     * @param addNodeDirectionlessBank see above
     * @param dropNodeDirectionlessBank see above
     * @return see above
     */
    public SortedSet<Integer> getAvailableSlotIds (Collection<WFiber> wdmLinks , Optional<Pair<WNode,Integer>> addNodeDirectionlessBank , Optional<Pair<WNode,Integer>> dropNodeDirectionlessBank) 
    {
    	checkSameWNet(wdmLinks);
        if (wdmLinks.isEmpty()) throw new Net2PlanException ("No WDM links");
        final Iterator<WFiber> itLink = wdmLinks.iterator();
        final WFiber firstLink = itLink.next();
        final SortedSet<Integer> validSlotIds = this.getIdleOpticalSlotIds(firstLink);
        while (itLink.hasNext())
            validSlotIds.retainAll(this.getIdleOpticalSlotIds(itLink.next()));
        if (addNodeDirectionlessBank.isPresent())
            validSlotIds.removeAll(this.getOccupiedOpticalSlotIdsInDirectionlessAddModule(addNodeDirectionlessBank.get().getFirst() , addNodeDirectionlessBank.get().getSecond()));
        if (dropNodeDirectionlessBank.isPresent())
            validSlotIds.removeAll(this.getOccupiedOpticalSlotIdsInDirectionlessDropModule(dropNodeDirectionlessBank.get().getFirst() , dropNodeDirectionlessBank.get().getSecond()));
        return validSlotIds;
    }

    /** FA: Given a fiber, returns a map with the occupied optical slot ids, both caused by legitimate signals, mapped to the set of lightpaths that occupy it. 
     * Note that if more than one lightpath occupies a given slot, means that spectrum clashing occurs in that slot   
     * @param fiber the input fiber
     * @return see above
     */
    public SortedMap<Integer,SortedSet<WLightpath>> getOccupiedResources (WFiber fiber , OpticalSignalOccupationType signalOccupationType)
    {
    	checkSameWNet(fiber);
    	return this.legitimateSignal_perFiberOccupation.getOccupiedSlotIds(fiber);
    }

    /** FA: Given a node and the index of the directionless add module, and the type of optical signal of interest (waste or legitimate), returns a map with the occupied optical slot ids, mapped to the set of lightpaths that occupy it.
     * @param node see above
     * @param directionlessModuleIndex  see above
     * @param signalType see above
     * @return see above
     */
    public SortedMap<Integer,SortedSet<WLightpath>> getOccupiedResourcesInDirectionlessAddModule (WNode node , int directionlessModuleIndex , OpticalSignalOccupationType signalType)
    {
    	checkSameWNet(node);
    	final Pair<WNode,Integer> id = Pair.of(node, directionlessModuleIndex);
    	return signalType.isLegitimate()? legitimateSignal_directionlessAddOccupation.getOccupiedSlotIds(id) : wasteSignal_directionlessAddOccupation.getOccupiedSlotIds(id); 
    }

    /** FA: Given a node and the index of the directionless drop module, and the type of optical signal of interest (waste or legitimate), returns a map with the occupied optical slot ids, mapped to the set of lightpaths that occupy it.
     * @param node see above
     * @param directionlessModuleIndex  see above
     * @param signalType see above
     * @return see above
     */
    public SortedMap<Integer,SortedSet<WLightpath>> getOccupiedResourcesInDirectionlessDropModule (WNode node , int directionlessModuleIndex , OpticalSignalOccupationType signalType)
    {
    	checkSameWNet(node);
    	final Pair<WNode,Integer> id = Pair.of(node, directionlessModuleIndex);
    	return signalType.isLegitimate()? legitimateSignal_directionlessDropOccupation.getOccupiedSlotIds(id) : wasteSignal_directionlessDropOccupation.getOccupiedSlotIds(id); 
    }

    /** FA: Given a fiber, returns the set of optical slots occupied by at least one traversing lightpath, in its waste of legitimate signal
     * @param fiber see above
     * @return see above
     */
    public SortedSet<Integer> getOccupiedOpticalSlotIds (WFiber fiber)
    {
    	checkSameWNet(fiber);
    	final SortedSet<Integer> res = legitimateSignal_perFiberOccupation.getOccupiedOpticalSlotIds(fiber);
    	res.addAll(wasteSignal_perFiberOccupation.getOccupiedOpticalSlotIds(fiber));
    	return res;
    }

    
    /** FA: Given a set of fibers and a set of optical slots, returns true if ALL the optical slots are idle in ALL the fibers and if given the add/drop directionless modules
     * @param wdmLinks see above
     * @param addDirectionlessModuleIndex see above
     * @param dropDirectionlessModuleIndex see above
     * @param slotIds see above
     * @return see above
     */
    public boolean isAllocatable (List<WFiber> legitimatePath , Optional<Pair<WNode,Integer>> addDirectionlessModuleIndex , Optional<Pair<WNode,Integer>> dropDirectionlessModuleIndex , SortedSet<Integer> slotIds)
    {
    	checkSameWNet(wdmLinks);
        if (wdmLinks.size() != new HashSet<> (wdmLinks).size()) return false;
        for (WFiber e : wdmLinks)
            if (!this.isOpticalSlotIdsValidAndIdle(e , slotIds))
                return false;
        if (addDirectionlessModuleIndex.isPresent())
        	if (!this.isOpticalSlotIdsValidAndIdleInAddDirectionlessModule(addDirectionlessModuleIndex.get().getFirst(), addDirectionlessModuleIndex.get().getSecond(), slotIds))
        		return false;
        if (dropDirectionlessModuleIndex.isPresent())
        	if (!this.isOpticalSlotIdsValidAndIdleInDropDirectionlessModule(dropDirectionlessModuleIndex.get().getFirst(), dropDirectionlessModuleIndex.get().getSecond(), slotIds))
        		return false;
        return true;
    }

    /** Accounts for the occupation of a lightpath, just for the legitimate signal (waste signal occupation is accounted in other function) updating the information in the spectrum manager
     * @param lp the lightpath
     * @param addNodeDirectionlessBank if added in a directionless module, its index
     * @param dropNodeDirectionlessBank if dropped in a directionless module, its index
     * @param wdmLinks the set of fibers where optical resources are occupied by this lightpath. This is typically the set of 
     * lightpath traversed fibers. In filterless technologies, this may also include other fibers not intentionally traversed, 
     * but where the spectrum is also occupied
     * @param slotIds the optical slot ids
     * @return see above
     */
    public void allocateOccupationLegitimateSignal (WLightpath lp , Optional<Pair<WNode,Integer>> addNodeDirectionlessBank , Optional<Pair<WNode,Integer>> dropNodeDirectionlessBank , Collection<WFiber> wdmLinks , SortedSet<Integer> slotIds)
    {
    	checkSameWNet(wdmLinks);
   	 	checkSameWNet(lp);
    	if (slotIds.isEmpty()) return;
    	for (WFiber fiber : wdmLinks)
    		legitimateSignal_perFiberOccupation.allocateOccupation(fiber, lp, slotIds);
    	if (addNodeDirectionlessBank.isPresent())
    		legitimateSignal_directionlessAddOccupation.allocateOccupation(addNodeDirectionlessBank.get(), lp, slotIds);
    	if (dropNodeDirectionlessBank.isPresent())
    		legitimateSignal_directionlessDropOccupation.allocateOccupation(dropNodeDirectionlessBank.get(), lp, slotIds);
    }

    /** Accounts for the occupation of a lightpath regarding to the waste signal, updating the information in the spectrum manager
     * @param lp the lightpath
     * @param addNodeDirectionlessBank if added in a directionless module, its index
     * @param dropNodeDirectionlessBank if dropped in a directionless module, its index
     * @param wdmLinks the set of fibers where optical resources are occupied by this lightpath. This is typically the set of 
     * lightpath traversed fibers. In filterless technologies, this may also include other fibers not intentionally traversed, 
     * but where the spectrum is also occupied
     * @param slotIds the optical slot ids
     */
    public void allocateOccupationWasteSignal (WLightpath lp , Collection<Pair<WNode,Integer>> addNodeDirectionlessBanks , Collection<Pair<WNode,Integer>> dropNodeDirectionlessBanks , Collection<WFiber> wdmLinks , SortedSet<Integer> slotIds)
    {
    	checkSameWNet(wdmLinks);
   	 	checkSameWNet(lp);
    	if (slotIds.isEmpty()) return;
    	boolean clashesWithPreviousAllocations = false;
    	for (WFiber fiber : wdmLinks)
    		wasteSignal_perFiberOccupation.allocateOccupation(fiber, lp, slotIds);
    	for (Pair<WNode,Integer> dirlessBank : addNodeDirectionlessBanks)
    		wasteSignal_directionlessAddOccupation.allocateOccupation(dirlessBank, lp, slotIds);
    	for (Pair<WNode,Integer> dirlessBank : dropNodeDirectionlessBanks)
    		wasteSignal_directionlessDropOccupation.allocateOccupation(dirlessBank, lp, slotIds);
    }

    
    /** Releases all the optical slots occupied for a given lightpath in this manager
     * @param lp the lightpath
     */
    public void releaseOccupation (WLightpath lp)
    {
    	checkSameWNet(lp);
    	perFiberOccupation.releaseOccupation(lp);
    	directionlessAddOccupation.releaseOccupation(lp);
    	directionlessDropOccupation.releaseOccupation(lp);
    }

    /** Searches for a first-fit assignment, where in each hop, one fiber is chosen. Given a set of hops (each hop with at least one fiber as an option),
     * optional directionless add and drop module to occupy,  
     * the number of contiguous optical slots needed, 
     * and (optionally) an initial optical slot (so optical slots of lower id are not consiedered), this method searches for 
     * the lowest-id contiguous range of slots that are available in all the indicated fibers and directionless modules. Note that if the set of fibers 
     * passes more than once in the same fiber, no assignment is possible, and Optional.empty is returned
     * @param seqAdjacenciesFibers_ab see above
     * @param directionlessAddModuleAb see above
     * @param directionlessDropModuleAb see above
     * @param directionlessAddModuleBa see above
     * @param directionlessDropModuleBa see above
     * @param numContiguousSlotsRequired see above
     * @param unusableSlots see above
     * @return see above. If no idle range is found, Optional.empty is returned. 
     */
    public Optional<Pair<List<Pair<WFiber,WFiber>> , SortedSet<Integer>>> spectrumAssignment_firstFitForAdjacenciesBidi (Collection<Pair<WNode,WNode>> seqAdjacenciesFibers_ab,
    		Optional<Pair<WNode,Integer>> directionlessAddModuleAb , 
    		Optional<Pair<WNode,Integer>> directionlessDropModuleAb , 
    		Optional<Pair<WNode,Integer>> directionlessAddModuleBa , 
    		Optional<Pair<WNode,Integer>> directionlessDropModuleBa , 
    		int numContiguousSlotsRequired , SortedSet<Integer> unusableSlots)
    {
   	 	assert !seqAdjacenciesFibers_ab.isEmpty();
   	 	assert numContiguousSlotsRequired > 0;
   	 	/* Get valid fibers and first slots ids to return, according to the fibers */
        /* If a fiber is traversed more than once, there is no possible assignment */
   	  final Map<Pair<WNode,WNode> , Pair<SortedSet<Integer> , List<Pair<WFiber,WFiber>>>> mapInfoConsideringAllBidi = new HashMap<> ();
   	  final SortedSet<WFiber> allFibersToCheckRepetitions = new TreeSet<> ();
   	  for (Pair<WNode,WNode> nn : seqAdjacenciesFibers_ab)
   	  {
			  final WNode a = nn.getFirst();
			  final WNode b = nn.getSecond();
			  final SortedSet<WFiber> fibersAb = wNet.getNodePairFibers(a, b);
			  final SortedSet<WFiber> fibersBa = wNet.getNodePairFibers(b , a);
			  if (fibersAb.stream().anyMatch(f->!f.isBidirectional())) throw new Net2PlanException ("All fibers must be bidirectional");
			  if (fibersBa.stream().anyMatch(f->!f.isBidirectional())) throw new Net2PlanException ("All fibers must be bidirectional");
			  final List<Pair<WFiber,WFiber>> abBa = new ArrayList<> ();
			  final SortedSet<Integer> idleOpticalSlotRangesInitialSlots = new TreeSet<> ();
			  for (WFiber ab : fibersAb)
			  {
				  if (allFibersToCheckRepetitions.contains(ab)) throw new Net2PlanException ("A fiber appears more than once in an option");
				  if (allFibersToCheckRepetitions.contains(ab.getBidirectionalPair())) throw new Net2PlanException ("A fiber appears more than once in an option");
				  allFibersToCheckRepetitions.add(ab);
				  allFibersToCheckRepetitions.add(ab.getBidirectionalPair());
				  abBa.add(Pair.of(ab, ab.getBidirectionalPair()));
		   		  SortedSet<Integer> optionsAb = getIdleOpticalSlotRangesInitialSlots(ab, numContiguousSlotsRequired);
		   		  SortedSet<Integer> optionsBa = getIdleOpticalSlotRangesInitialSlots(ab.getBidirectionalPair(), numContiguousSlotsRequired);
		   		  optionsAb.removeAll(unusableSlots);
		   		  optionsBa.removeAll(unusableSlots);
		   		  idleOpticalSlotRangesInitialSlots.addAll(Sets.intersection(optionsAb, optionsBa));
			  }
			  mapInfoConsideringAllBidi.put(nn, Pair.of(idleOpticalSlotRangesInitialSlots, abBa));
   	  }
   	  SortedSet<Integer> validSlotIdsToReturn = null;
   	  for (Pair<WNode,WNode> nn : seqAdjacenciesFibers_ab)
   	  {
           final SortedSet<Integer> validSlotIdsThisHop = mapInfoConsideringAllBidi.get(nn).getFirst();
           if (validSlotIdsToReturn == null) validSlotIdsToReturn = validSlotIdsThisHop; else validSlotIdsToReturn.retainAll(validSlotIdsThisHop);
   	  }

   	  /* Filter out valid options Get valid fibers and first slots ids to return, according to the fibers */
   	  Integer firstSlotToReturn = null;
   	  for (int potentiallyValidFirstSlotId : validSlotIdsToReturn)
   	  {
   		  boolean isOk = true;
   	   	  for (boolean isAb : new boolean [] {true,false})
   	   	  {
   	   	   	  for (boolean isAdd : new boolean [] {true,false})
   	   	   	  {
   	   	   		  final SlotOccupationManager<Pair<WNode,Integer>> manager = isAdd? directionlessAddOccupation : directionlessDropOccupation; 
   	   	   		  final Pair<WNode,Integer> dirlessModule = (isAdd? (isAb? directionlessAddModuleAb : directionlessAddModuleBa) : (isAb? directionlessDropModuleAb : directionlessDropModuleBa)).orElse(null);
   	   	   		  if (dirlessModule == null) continue;
   	   	   		  final SortedMap<Integer,SortedSet<WLightpath>> occupiedSlots = manager.getOccupiedSlotIds(dirlessModule); 
   	   	   		  for (int i = 0; i < numContiguousSlotsRequired ; i ++)
   	   	   			  if (occupiedSlots.containsKey(potentiallyValidFirstSlotId + i)) { isOk = false; break; }
   	   	   	  }
   	   	   	  if (!isOk) break;
   	   	  }
   	   	  if (isOk) { firstSlotToReturn = potentiallyValidFirstSlotId; break; }
   	  }
   	  if (firstSlotToReturn == null) return Optional.empty();
   	  
   	  final SortedSet<Integer> res_rangetoReturn = new TreeSet<> (); for (int i = 0; i < numContiguousSlotsRequired ; i ++) res_rangetoReturn.add(firstSlotToReturn + i);
   	  final List<Pair<WFiber,WFiber>> res_fibersUsed = new ArrayList<> (); 
   	  for (Pair<WNode,WNode> hop_ab :  seqAdjacenciesFibers_ab)
   	  {
   		  final List<Pair<WFiber,WFiber>> fibersThisHop = mapInfoConsideringAllBidi.get(hop_ab).getSecond();
   		  for (Pair<WFiber,WFiber> bidiPair : fibersThisHop)
   		  {
   			  final WFiber ab = bidiPair.getFirst();
   			  final WFiber ba = bidiPair.getSecond();
   			  if (this.isOpticalSlotIdsValidAndIdle(ab, res_rangetoReturn) && this.isOpticalSlotIdsValidAndIdle(ba, res_rangetoReturn))
   			  	{ res_fibersUsed.add(bidiPair); break; }
   		  }
   	  }
   	  assert res_fibersUsed.size() == seqAdjacenciesFibers_ab.size();
   	  return Optional.of(Pair.of(res_fibersUsed , res_rangetoReturn));
    }

    
    /** Searches for a first-fit assignment. Given a set of fibers to occupy, the optional add and drop directionless modules used, the number of contiguous optical slots needed, 
     * and (optionally) an initial optical slot (so optical slots of lower id are not consiedered), this method searches for 
     * the lowest-id contiguous range of slots that are available in all the indicated fibers and directionless modules. Note that if the set of fibers 
     * passes more than once in the same fiber, no assignment is possible, and Optional.empty is returned
     * @param seqFibers see above
     * @param directionlessAddModule see above
     * @param directionlessDropModule see above
     * @param numContiguousSlotsRequired see above
     * @param minimumInitialSlotId see above
     * @return see above. If no idle range is found, Optional.empty is returned. 
     */
    public Optional<SortedSet<Integer>> spectrumAssignment_firstFit(Collection<WFiber> seqFibers, 	
    		Optional<Pair<WNode,Integer>> directionlessAddModule , 
    		Optional<Pair<WNode,Integer>> directionlessDropModule , 
    		int numContiguousSlotsRequired , Optional<Integer> minimumInitialSlotId)
    {
    	checkSameWNet(seqFibers);
        assert !seqFibers.isEmpty();
        assert numContiguousSlotsRequired > 0;
        
        /* If a fiber is traversed more than once, there is no possible assignment */
        if (!(seqFibers instanceof Set)) if (new HashSet<> (seqFibers).size() != seqFibers.size()) return Optional.empty();
        SortedSet<Integer> intersectionValidSlots = getAvailableSlotIds(seqFibers , directionlessAddModule , directionlessDropModule);
        if (minimumInitialSlotId.isPresent())
            intersectionValidSlots = intersectionValidSlots.tailSet(minimumInitialSlotId.get());
        if (intersectionValidSlots.size() < numContiguousSlotsRequired) return Optional.empty();
        
        final LinkedList<Integer> rangeValid = new LinkedList<> ();
        for (int slotId : intersectionValidSlots)
        {
            if (!rangeValid.isEmpty())
                if (rangeValid.getLast() != slotId - 1)
                    rangeValid.clear();
            rangeValid.add(slotId);
            assert rangeValid.size() <= numContiguousSlotsRequired;
            if (rangeValid.size() == numContiguousSlotsRequired) return Optional.of(new TreeSet<> (rangeValid));
        }
        return Optional.empty();
    }

    /** Searches for a first-fit assignment for the two given paths, so optical slots can be different for each. 
     * Given two sets of fibers to occupy (paths), the optinal add/drop modules to occupy in each case, the number of contiguous optical slots needed in each, 
     * this method searches for the two lowest-id contiguous ranges of slots, so the first range is available in the first path,
     * the second range is available in the second path. Note that if any path contains a fiber more than once, no allocation is 
     * possible. Note that if path1 and path2 have common fibers, the optical slots returned will always be disjoint. In contrast, the add modules of the two paths or the drop modules of the two paths could be the same 
     * @param seqFibers_1 see above
     * @param seqFibers_2 see above
     * @param directionlessAddModule_1 see above
     * @param directionlessDropModule_1 see above
     * @param directionlessAddModule_2 see above
     * @param directionlessDropModule_2 see above
     * @param numContiguousSlotsRequired see above
     * @return see above. If no idle range is found, Optional.empty is returned. 
     */
    public Optional<Pair<SortedSet<Integer>,SortedSet<Integer>>> spectrumAssignment_firstFitTwoRoutes(Collection<WFiber> seqFibers_1, Collection<WFiber> seqFibers_2 ,
    		Optional<Pair<WNode,Integer>> directionlessAddModule_1 , 
    		Optional<Pair<WNode,Integer>> directionlessDropModule_1 , 
    		Optional<Pair<WNode,Integer>> directionlessAddModule_2 , 
    		Optional<Pair<WNode,Integer>> directionlessDropModule_2 , 
    		int numContiguousSlotsRequired)
    {
   	 checkSameWNet(seqFibers_1);
   	 checkSameWNet(seqFibers_2);
        /* If a fiber is traversed more than once in any path, there is no possible assignment */
        if (!(seqFibers_1 instanceof Set)) if (new HashSet<> (seqFibers_1).size() != seqFibers_1.size()) return Optional.empty();
        if (!(seqFibers_2 instanceof Set)) if (new HashSet<> (seqFibers_2).size() != seqFibers_2.size()) return Optional.empty();
        final boolean haveLinksInCommon = !Sets.intersection(new HashSet<>(seqFibers_1)  , new HashSet<>(seqFibers_2)).isEmpty();
        if (!haveLinksInCommon)
        {
            final Optional<SortedSet<Integer>> firstRouteInitialSlot = spectrumAssignment_firstFit(seqFibers_1, directionlessAddModule_1 , directionlessDropModule_1 , numContiguousSlotsRequired, Optional.empty());
            if (!firstRouteInitialSlot.isPresent()) return Optional.empty();
            final Optional<SortedSet<Integer>> secondRouteInitialSlot = spectrumAssignment_firstFit(seqFibers_2, directionlessAddModule_2 , directionlessDropModule_2 , numContiguousSlotsRequired, Optional.empty());
            if (!secondRouteInitialSlot.isPresent()) return Optional.empty();
            return Optional.of(Pair.of(firstRouteInitialSlot.get(), secondRouteInitialSlot.get()));
        }

        /* With links in common */
        final SortedSet<Integer> fistPathValidSlots = getAvailableSlotIds(seqFibers_1 , directionlessAddModule_1 , directionlessDropModule_1);
        final SortedSet<Integer> secondPathValidSlots = getAvailableSlotIds(seqFibers_2 , directionlessAddModule_2 , directionlessDropModule_2);
        for(int initialSlot_1 :  fistPathValidSlots)
        {
            if (!isValidOpticalSlotIdRange(fistPathValidSlots, initialSlot_1, numContiguousSlotsRequired)) continue;
            for(int initialSlot_2 :  secondPathValidSlots)
            {
                if (Math.abs(initialSlot_1 - initialSlot_2) < numContiguousSlotsRequired) continue;
                if (!isValidOpticalSlotIdRange(secondPathValidSlots, initialSlot_2, numContiguousSlotsRequired)) continue;
                final SortedSet<Integer> range1 = new TreeSet<> ();
                final SortedSet<Integer> range2 = new TreeSet<> ();
                for (int cont = 0; cont < numContiguousSlotsRequired ; cont ++) { range1.add(initialSlot_1 + cont);  range2.add(initialSlot_2+cont); } 
                return Optional.of(Pair.of(range1, range2));
            }           
        }
        return Optional.empty();
    }

    /** Given a sequence of fibers (i.e. an intended path of a lightpath) and the maximum unreqenerated distance applicable 
     * for it (maximum km that can traverse without OEO regeneration), returns a partitioning of the input sequence of fibers 
     * into one or more OCh paths (List of WFiber), so that each OCh path has a length lower or equal than the maxUnregeneratedDistanceInKm, 
     * and the number of OChs is minimized. 
     * @param seqFibers see above
     * @param maxUnregeneratedDistanceInKm  see above 
     * @return  see above
     */
    public static List<List<WFiber>> getRegenerationPoints (List<WFiber> seqFibers, double maxUnregeneratedDistanceInKm)
    {
        final List<List<WFiber>> res = new ArrayList<> ();
        res.add(new ArrayList<> ());

        double accumDistance = 0;
        for (WFiber fiber : seqFibers)
        {
            final double fiberLengthInKm = fiber.getLengthInKm();
            if (fiberLengthInKm > maxUnregeneratedDistanceInKm)
                throw new Net2PlanException(String.format("Fiber %d is longer (%f km) than the maximum distance without regenerators (%f km)", fiber.getId(), fiberLengthInKm, maxUnregeneratedDistanceInKm));
            accumDistance += fiberLengthInKm;
            if (accumDistance > maxUnregeneratedDistanceInKm)
            {
                res.add(new ArrayList<> (Arrays.asList(fiber)));
                accumDistance = fiberLengthInKm;
            }
            else
                res.get(res.size()-1).add(fiber);
            
        }
        return res;
    }

    /** Prints a report with the occupation (for debugging pruposes)
     * @return see above
     */
    public String printReport ()
    {
        final StringBuffer st = new StringBuffer ();
        final String RETURN = System.getProperty("line.separator");
        st.append("--- PER FIBER INFORMATION ---" + RETURN);
        for (WFiber e : wNet.getFibers().stream().sorted((e1,e2)->Integer.compare(perFiberOccupation.getNumberOfOccupiedSlotIds(e2), perFiberOccupation.getNumberOfOccupiedSlotIds(e1))).collect(Collectors.toList()))
        {
            final SortedMap<Integer,SortedSet<WLightpath>> occupThisLink = perFiberOccupation.getOccupiedSlotIds(e);
            final int numOchSubpaths = occupThisLink.values().stream().flatMap(s->s.stream()).collect(Collectors.toSet()).size();
            final int numOccupSlots = occupThisLink.size();
            final boolean hasClashing = occupThisLink.values().stream().anyMatch(s->s.size() > 1);
            st.append("Link " + e + ". Occup slots: " + numOccupSlots + ", cap: " + e.getNumberOfValidOpticalChannels() + ", num Och subpaths: " + numOchSubpaths + ", clashing: " + hasClashing + RETURN);
        }
        st.append("--- PER DIRECTIONLESS ADD MODULE INFORMATION ---" + RETURN);
        for (Pair<WNode,Integer> e : directionlessAddOccupation.getElementsWithAtLeastOneSlotOccupied().stream().
        		sorted((e1,e2)->{ final int c1 = e1.getFirst().compareTo(e2.getFirst()); if (c1 != 0) return c1; return Integer.compare(e1.getSecond(), e2.getSecond()); }).
        		collect(Collectors.toList()))
        {
            final SortedMap<Integer,SortedSet<WLightpath>> occupThisLink = directionlessAddOccupation.getOccupiedSlotIds(e);
            final int numOchSubpaths = occupThisLink.values().stream().flatMap(s->s.stream()).collect(Collectors.toSet()).size();
            final int numOccupSlots = occupThisLink.size();
            final boolean hasClashing = occupThisLink.values().stream().anyMatch(s->s.size() > 1);
            st.append("Directionless add module: Node " + e.getFirst() + " - Index: " + e.getSecond() + ". Occup slots: " + numOccupSlots + ", num Och subpaths: " + numOchSubpaths + ", clashing: " + hasClashing + RETURN);
        }
        st.append("--- PER DIRECTIONLESS DROP MODULE INFORMATION ---" + RETURN);
        for (Pair<WNode,Integer> e : directionlessDropOccupation.getElementsWithAtLeastOneSlotOccupied().stream().
        		sorted((e1,e2)->{ final int c1 = e1.getFirst().compareTo(e2.getFirst()); if (c1 != 0) return c1; return Integer.compare(e1.getSecond(), e2.getSecond()); }).
        		collect(Collectors.toList()))
        {
            final SortedMap<Integer,SortedSet<WLightpath>> occupThisLink = directionlessDropOccupation.getOccupiedSlotIds(e);
            final int numOchSubpaths = occupThisLink.values().stream().flatMap(s->s.stream()).collect(Collectors.toSet()).size();
            final int numOccupSlots = occupThisLink.size();
            final boolean hasClashing = occupThisLink.values().stream().anyMatch(s->s.size() > 1);
            st.append("Directionless drop module: Node " + e.getFirst() + " - Index: " + e.getSecond() + ". Occup slots: " + numOccupSlots + ", num Och subpaths: " + numOchSubpaths + ", clashing: " + hasClashing + RETURN);
        }
        return st.toString();
    }

    /** Returns true if the design is ok respect to spectrum occupation: no optical slot in any fiber nor directionless module is occupied by more than one 
     * lightpath, and no optical slot in a fiber is outside the valid range for that fiber
     * @return see above
     */
    public boolean isSpectrumOccupationOk ()
    {
        for (Entry<WFiber,SortedMap<Integer,SortedSet<WLightpath>>> occup_e : perFiberOccupation.getFullPerElementOccupationMap ().entrySet())
        {
            final WFiber e = occup_e.getKey();
            assert e.isBidirectional();
            final SortedMap<Integer,SortedSet<WLightpath>> occup = occup_e.getValue();
            if (!e.getValidOpticalSlotIds().containsAll(occup.keySet())) return false;
            for (Set<WLightpath> rs : occup.values())
                if (rs.size() != 1) return false;
        }       
        for (boolean isAdd : new boolean [] {true,false})
        {
        	final Map<Pair<WNode,Integer>,SortedMap<Integer,SortedSet<WLightpath>>> mapOccup = isAdd? directionlessAddOccupation.getFullPerElementOccupationMap() : directionlessDropOccupation.getFullPerElementOccupationMap();
            for (Entry<Pair<WNode,Integer>,SortedMap<Integer,SortedSet<WLightpath>>> occup_e : mapOccup.entrySet())
            {
                final SortedMap<Integer,SortedSet<WLightpath>> occup = occup_e.getValue();
                for (Set<WLightpath> rs : occup.values())
                    if (rs.size() != 1) return false;
            }       
        }
        return true;
    }

    /** Returns true if the design is ok respect to spectrum occupation for that lightpath: all optical slots occupied in the fiber are valid,
     *  and with no clashing with other lightpaths in the fibers nor in the directionless add/drop modules (if any)
     * @param lp see above
     * @return see above
     */
    public boolean isSpectrumOccupationOk (WLightpath lp)
    {
	   	 for (WFiber e : lp.getSeqFibers())
	   	 {
				 if (!e.getValidOpticalSlotIds().containsAll(lp.getOpticalSlotIds())) return false;
				 final SortedMap<Integer,SortedSet<WLightpath>> occup_e = getOccupiedResources(e);
				 for (int s : lp.getOpticalSlotIds())
	   		 {
					 final SortedSet<WLightpath> occupThisSlos = occup_e.getOrDefault(s , new TreeSet<> ());
					 assert occupThisSlos.contains(lp);
					 if (occupThisSlos.size() > 1) return false;
	   		 }
	   	 }
	   	 for (boolean isAdd : new boolean [] { true , false} )
	   	 {
	   		 final Integer dirlessIndex = (isAdd? lp.getDirectionlessAddModuleIndexInOrigin() : lp.getDirectionlessDropModuleIndexInDestination()).orElse(null);
	   		 if (dirlessIndex == null) continue;
	   		 final Pair<WNode,Integer> e = Pair.of(isAdd? lp.getA() : lp.getB(), dirlessIndex); 
			 final SortedMap<Integer,SortedSet<WLightpath>> occup_e = isAdd? directionlessAddOccupation.getOccupiedSlotIds(e) : directionlessDropOccupation.getOccupiedSlotIds(e);
			 for (int s : lp.getOpticalSlotIds())
			 {
				 final SortedSet<WLightpath> occupThisSlos = occup_e.getOrDefault(s , new TreeSet<> ());
				 assert occupThisSlos.contains(lp);
				 if (occupThisSlos.size() > 1) return false;
			 }
	   	 }
        return true;
    }
    
//    /** Checks if the optical slot occupation --
//     */
//    public void checkNetworkSlotOccupation () // PABLO
//    {
//        for (Entry<WFiber,SortedMap<Integer,SortedSet<WLightpath>>> occup_e : occupation_f_s_ll.entrySet())
//        {
//            final WFiber e = occup_e.getKey();
//            assert e.isBidirectional();
//            final SortedMap<Integer,SortedSet<WLightpath>> occup = occup_e.getValue();
//            if (!e.getValidOpticalSlotIds().containsAll(occup.keySet())) throw new Net2PlanException ("The optical slots occupied at link " + e + " are outside the valid range");
//            for (Set<WLightpath> rs : occup.values())
//                if (rs.size() != 1) throw new Net2PlanException ("The optical slots occupation is not correct");
//        }       
//    }
    
    private static boolean isValidOpticalSlotIdRange (SortedSet<Integer> validSlots , int initialSlot , int numContiguousSlots)
    {
        for (int cont = 0; cont < numContiguousSlots ; cont ++)
            if (!validSlots.contains(initialSlot + cont)) return false;
        return true;
    }

	/** FA: Returns the optical slots that are usable (valid and idle, not occupied by waste or legitimate signal) in the given fiber
	 * @param wdmLink see above
	 * @return  see above
	 */
	public SortedSet<Integer> getIdleOpticalSlotIds (WFiber wdmLink)
	{
		checkSameWNet(wdmLink);
		final SortedSet<Integer> res = wdmLink.getValidOpticalSlotIds();
		res.removeAll(getOccupiedOpticalSlotIds(wdmLink));
		return res;
	}

	/** FA: Returns the optical slots that are occupied (by waste or legitimate signals) in the given directionless add module
	 * @param node see above
	 * @param directionlessModuleIndex see above
	 * @return see above
	 */
	public SortedSet<Integer> getOccupiedOpticalSlotIdsInDirectionlessAddModule (WNode node , int directionlessModuleIndex)
	{
		checkSameWNet(node);
		final SortedSet<Integer> res = new TreeSet<> (getOccupiedResourcesInDirectionlessAddModule(node, directionlessModuleIndex , OpticalSignalOccupationType.LEGITIMATESIGNAL).keySet());
		res.addAll(getOccupiedResourcesInDirectionlessAddModule(node, directionlessModuleIndex , OpticalSignalOccupationType.WASTESIGNAL).keySet());
		return res;
	}

	/** FA: Returns the optical slots that are occupied (by waste or legitimate signals) in the given directionless add module
	 * @param node see above
	 * @param directionlessModuleIndex see above
	 * @return see above
	 */
	public SortedSet<Integer> getOccupiedOpticalSlotIdsInDirectionlessDropModule (WNode node , int directionlessModuleIndex)
	{
		checkSameWNet(node);
		final SortedSet<Integer> res = new TreeSet<> (getOccupiedResourcesInDirectionlessDropModule(node, directionlessModuleIndex , OpticalSignalOccupationType.LEGITIMATESIGNAL).keySet());
		res.addAll(getOccupiedResourcesInDirectionlessDropModule(node, directionlessModuleIndex , OpticalSignalOccupationType.WASTESIGNAL).keySet());
		return res;
	}


	/** FA: Returns the optical slots where there is wavelength clashing, i.e. a LEGITIMATE lightpath signal is occupying it, together with the waste or legitimate signal of this or other lightpath
	 * @param wdmLink see above
	 * @return  see above
	 */
	public SortedSet<Integer> getClashingOpticalSlotIds (WFiber wdmLink)
	{
		checkSameWNet(wdmLink);
		final SortedSet<Integer> res = new TreeSet<> ();
		final SortedMap<Integer,SortedSet<WLightpath>> legitimate = getOccupiedResources (wdmLink , OpticalSignalOccupationType.LEGITIMATESIGNAL);
		final SortedMap<Integer,SortedSet<WLightpath>> waste = getOccupiedResources (wdmLink , OpticalSignalOccupationType.WASTESIGNAL);
		for (Entry<Integer,SortedSet<WLightpath>> entryOfLegitimateOccupation : legitimate.entrySet())
		{
			final int slotId = entryOfLegitimateOccupation.getKey();
			if (entryOfLegitimateOccupation.getValue().isEmpty()) continue; 
			if (entryOfLegitimateOccupation.getValue().size() > 1) { res.add(slotId); continue; }
			if (!waste.getOrDefault(slotId, new TreeSet<> ()).isEmpty())
				res.add(slotId);
		}
		return res;
	}

	/** FA: Returns the optical slots where there is wavelength clashing in the directionless add module, i.e. a LEGITIMATE lightpath signal is occupying it, together with the waste or legitimate signal of this or other lightpath
	 * @param wdmLink see above
	 * @return  see above
	 */
	public SortedSet<Integer> getClashingOpticalSlotIdsInDirectionlessAddModule (WNode node , int addDirectionlessModule)
	{
		checkSameWNet(node);
		final SortedSet<Integer> res = new TreeSet<> ();
		final SortedMap<Integer,SortedSet<WLightpath>> legitimate = getOccupiedResourcesInDirectionlessAddModule (node , addDirectionlessModule , OpticalSignalOccupationType.LEGITIMATESIGNAL);
		final SortedMap<Integer,SortedSet<WLightpath>> waste = getOccupiedResourcesInDirectionlessAddModule (node , addDirectionlessModule , OpticalSignalOccupationType.WASTESIGNAL);
		for (Entry<Integer,SortedSet<WLightpath>> entryOfLegitimateOccupation : legitimate.entrySet())
		{
			final int slotId = entryOfLegitimateOccupation.getKey();
			if (entryOfLegitimateOccupation.getValue().isEmpty()) continue; 
			if (entryOfLegitimateOccupation.getValue().size() > 1) { res.add(slotId); continue; }
			if (!waste.getOrDefault(slotId, new TreeSet<> ()).isEmpty())
				res.add(slotId);
		}
		return res;
	}

	/** FA: Returns the optical slots where there is wavelength clashing in the directionless drop module, i.e. a LEGITIMATE lightpath signal is occupying it, together with the waste or legitimate signal of this or other lightpath
	 * @param wdmLink see above
	 * @return  see above
	 */
	public SortedSet<Integer> getClashingOpticalSlotIdsInDirectionlessDropModule (WNode node , int dropDirectionlessModule)
	{
		checkSameWNet(node);
		final SortedSet<Integer> res = new TreeSet<> ();
		final SortedMap<Integer,SortedSet<WLightpath>> legitimate = getOccupiedResourcesInDirectionlessDropModule (node , dropDirectionlessModule , OpticalSignalOccupationType.LEGITIMATESIGNAL);
		final SortedMap<Integer,SortedSet<WLightpath>> waste = getOccupiedResourcesInDirectionlessDropModule (node , dropDirectionlessModule , OpticalSignalOccupationType.WASTESIGNAL);
		for (Entry<Integer,SortedSet<WLightpath>> entryOfLegitimateOccupation : legitimate.entrySet())
		{
			final int slotId = entryOfLegitimateOccupation.getKey();
			if (entryOfLegitimateOccupation.getValue().isEmpty()) continue; 
			if (entryOfLegitimateOccupation.getValue().size() > 1) { res.add(slotId); continue; }
			if (!waste.getOrDefault(slotId, new TreeSet<> ()).isEmpty())
				res.add(slotId);
		}
		return res;
	}


	/** FA: Returns the number optical slots where there is wavelength clashing, i.e. 1) the legitimate signal of two or more lightpaths is using it, or 2) the legitimate signal of one lightpath and the waste signal of the same or any other lightpath is using it
	 * @param wdmLink see above
	 * @return  see above
	 */
	public int getNumberOfClashingOpticalSlotIds (WFiber wdmLink)
	{
		checkSameWNet(wdmLink);
		return getClashingOpticalSlotIds (wdmLink).size();
	}
	
	/** FA: Returns the optical slots that are usable (valid and idle, not occupied by waste or legitimate signals of any lightpath) in the given fiber
	 * @param wdmLink see above
	 * @param numContiguousSlots see above
	 * @return  see above
	 */
	public SortedSet<Integer> getIdleOpticalSlotRangesInitialSlots (WFiber wdmLink , int numContiguousSlots)
	{
		final SortedSet<Integer> idleSlots = getIdleOpticalSlotIds (wdmLink);
		final SortedSet<Integer> res = new TreeSet<> ();
		for (int s : idleSlots)
		{
			boolean ok = true;
			for (int cont = 0; cont < numContiguousSlots ; cont ++)
				if (!idleSlots.contains(s+cont)) { ok = false; break; }
			if (ok) res.add(s);
		}
		return res;
	}

		
	/** FA: Indicates if the optical slots are usable (valid and idle, not occupied by waste or legitimate optical signals of any lightpath) in the given fiber
	 * @param wdmLink see above
	 * @param slotsIds see above
	 * @return see above
	 */
	public boolean isOpticalSlotIdsValidAndIdle (WFiber wdmLink , SortedSet<Integer> slotsIds)
	{
		checkSameWNet(wdmLink);
		return getIdleOpticalSlotIds(wdmLink).containsAll(slotsIds);
	}
	
	/** FA: Indicates if the optical slots are usable (valid and idle, not occupied by waste or legitimate optical signals of any lightpath) in the given add directionless module index
	 * @param node see above
	 * @param directionlessModuleIndex see above
	 * @param slotsIds see above
	 * @return see above
	 */
	public boolean isOpticalSlotIdsValidAndIdleInAddDirectionlessModule (WNode node , int directionlessModuleIndex , SortedSet<Integer> slotsIds)
	{
		checkSameWNet(node);
		return getOccupiedOpticalSlotIdsInDirectionlessAddModule(node, directionlessModuleIndex).stream().allMatch(slot->!slotsIds.contains(slot));
	}
	
	/** FA: Indicates if the optical slots are usable (valid and idle, not occupied by waste or legitimate optical signals of any lightpath) in the given drop directionless module index
	 * @param node see above
	 * @param directionlessModuleIndex see above
	 * @param slotsIds see above
	 * @return see above
	 */
	public boolean isOpticalSlotIdsValidAndIdleInDropDirectionlessModule (WNode node , int directionlessModuleIndex , SortedSet<Integer> slotsIds)
	{
		checkSameWNet(node);
		return getOccupiedOpticalSlotIdsInDirectionlessDropModule(node, directionlessModuleIndex).stream().allMatch(slot->!slotsIds.contains(slot));
	}
	

	
	/** For the provided collection of fibers, indicates the minimum and maximum optical slot id that is valid for all the 
     * fibers in the collection
     * @param wdmLinks see above
     * @return see above
     */
    public static Pair<Integer,Integer> getMinimumAndMaximumValidSlotIdsInTheGrid (Collection<WFiber> wdmLinks)
    {
        if (wdmLinks.isEmpty()) throw new Net2PlanException ("No WDM links");
        int min = Integer.MIN_VALUE;
        int max = Integer.MAX_VALUE;
        for (WFiber wdmLink : wdmLinks)
        {
        	final Pair<Integer,Integer> minMax = wdmLink.getMinMaxValidSlotId();
            min = Math.max(min, minMax.getFirst());
            max = Math.min(max, minMax.getSecond());
        }
        return Pair.of(min, max);
    }

    /** Returns a list with the lasing loops in the network. These are loops of traversed fibers, where the signal would propagate indefinitely without being blocked by 
     * any optical switch. This occurs e.g. in rings where all the nodes are filterless. 
    * @return see above
    */
   public List<List<WFiber>> getUnavoidableLasingLoops ()
    {
		final DefaultDirectedGraph<WFiber , Object> graphFiberToFiberPropagation = new DefaultDirectedGraph<WFiber , Object>(Object.class);
     	 for (WFiber fiber : wNet.getFibers())
     		 graphFiberToFiberPropagation.addVertex(fiber);

     	 for (WNode node : wNet.getNodes())
     	 {
     		 final IOadmArchitecture type = node.getOpticalSwitchingArchitecture();
     		 for (WFiber inFiber : node.getIncomingFibers())
     		 {
     			 final SortedSet<WFiber> outFibersProp = type.getOutFibersUnavoidablePropagationFromInputFiber(inFiber);
     			 for (WFiber propFiber : outFibersProp)
     				 graphFiberToFiberPropagation.addEdge(inFiber , propFiber);
     		 }
     	 }
		final DirectedSimpleCycles<WFiber,Object> cycleDetector = new JohnsonSimpleCycles<> (graphFiberToFiberPropagation); 
   	return cycleDetector.findSimpleCycles();
    }
    
    /** Given a contigous path, candidate to be assigned to a unicast lightpath, computes
     *
     * 1) the fibers where the signal would propagate (this includes e.g. fibers where signal propagates if traversing filterless nodes),
     * 2) the list of cycles occurring (if any) that would result in laser loops,
     * 3) an indication if the path is multipath-free. A lightpath is not multipath free when any fiber in the
     * legitimate path is receiving the output power of the signal more than once, coming from different paths, and thus destroying the signal.
     *
     *
    * @param links the sequence of fibers to be traversed. Must be a contigous path.
    * @return see above
    */
   public static Triple<SortedSet<WFiber>,List<List<WFiber>>,Boolean> getPropagatingFibersLasingLoopsAndIsMultipathOk (List<WFiber> links)
    {
//	   Idea:
//		   - Devuelva solo waste
//		   - Multipath ok es que no haya un waste mio solape con legitimate mio
//		   - Incluya Pair<WNode,Integer> de drop modules en non-directionless, ocupados
//		   - El add module ocupado se supone que es iunicamente el de origen
		   
	   
	   
	   
   	 if (links.isEmpty()) throw new Net2PlanException ("The path is empty");
   	 if (getContinousSequenceOfNodes(links).stream().allMatch(n->n.getOpticalSwitchingArchitecture().isNeverCreatingWastedSpectrum())) return Triple.of(new TreeSet<> (links), new  ArrayList<> (), true);
   	 
   	 final WFiber dummyFiberAdd = WFiber.createDummyFiber(0);
   	 final WFiber dummyFiberDrop = WFiber.createDummyFiber(1);
   	 
   	 /* Construct a graph starting from add fiber. Stop when all fibers have been processed */
   	 final SortedSet<WFiber> fibersPendingToProcess = new TreeSet<> ();
   	 fibersPendingToProcess.add(dummyFiberAdd);
   	 final SortedSet<WFiber> fibersAlreadyProcessed = new TreeSet<> ();
   	 final DefaultDirectedGraph<WFiber , Object> propagationGraph = new DefaultDirectedGraph<> (Object.class);
		 propagationGraph.addVertex(dummyFiberAdd);
   	 while (!fibersPendingToProcess.isEmpty())
   	 {
   		 final WFiber fiberToProcess = fibersPendingToProcess.first();
   		 if (fibersAlreadyProcessed.contains(fiberToProcess)) { fibersPendingToProcess.remove(fiberToProcess); continue; }
   		 assert propagationGraph.containsVertex(fiberToProcess);
   		 /* process the fiber */
   		 if (fiberToProcess.equals(dummyFiberAdd))
   		 {
   			 /* Add lightpath dummy fiber */
   			 final WNode addNode = links.get(0).getA();
      		 for (WFiber propFiber : addNode.getOpticalSwitchingArchitecture().getOutFibersIfAddToOutputFiber(links.get(0)))
      		 {
      			 if (!propagationGraph.containsVertex(propFiber)) propagationGraph.addVertex(propFiber);
      			 propagationGraph.addEdge(dummyFiberAdd, propFiber);
      			 fibersPendingToProcess.add(propFiber);
      		 }
   		 } else if (fiberToProcess.equals(dummyFiberDrop))
   		 {
   			 /* Drop lightpath dummy fiber => do nothing */
   		 } else
   		 {
   			 final WNode switchNode = fiberToProcess.getB();
      		 for (WFiber propFiber : switchNode.getOpticalSwitchingArchitecture().getOutFibersUnavoidablePropagationFromInputFiber(fiberToProcess))
      		 {
      			 if (!propagationGraph.containsVertex(propFiber)) propagationGraph.addVertex(propFiber);
      			 propagationGraph.addEdge(fiberToProcess, propFiber);
      			 fibersPendingToProcess.add(propFiber);
      		 }
      		 final int indexOfFiberInPath = links.indexOf(fiberToProcess);
   			 final boolean isExpress = indexOfFiberInPath >= 0 && (indexOfFiberInPath < links.size()-1);
   			 final boolean isDrop = indexOfFiberInPath == links.size() - 1;
   			 if (isExpress)
   			 {
      			 final WFiber inFiber = links.get(indexOfFiberInPath); assert inFiber.equals(fiberToProcess);
      			 final WFiber outFiber = links.get(indexOfFiberInPath + 1);
      			 final WNode expressNode = outFiber.getA();
      			 assert expressNode.equals(inFiber.getB());
         		 for (WFiber propFiber : expressNode.getOpticalSwitchingArchitecture().getOutFibersIfExpressFromInputToOutputFiber(inFiber , outFiber))
         		 {
         			 if (!propagationGraph.containsVertex(propFiber)) propagationGraph.addVertex(propFiber);
         			 propagationGraph.addEdge(inFiber, propFiber);
         			 fibersPendingToProcess.add(propFiber);
         		 }
   			 } else if (isDrop)
   			 {
      			 if (!propagationGraph.containsVertex(dummyFiberDrop)) propagationGraph.addVertex(dummyFiberDrop);
      			 propagationGraph.addEdge(fiberToProcess, dummyFiberDrop);
      			 fibersPendingToProcess.add(dummyFiberDrop);
   			 }
   		 }
   		fibersAlreadyProcessed.add (fiberToProcess);
   		 fibersPendingToProcess.remove(fiberToProcess);
   	 }
   	 
   	 if (!propagationGraph.containsVertex(dummyFiberDrop)) throw new Net2PlanException ("The signal of this lightpath is not reaching the drop node");

   	 final SortedSet<WFiber> propagatedNonDummyFibers = propagationGraph.vertexSet().stream().filter(e->!e.equals(dummyFiberDrop) && !e.equals(dummyFiberAdd)).collect(Collectors.toCollection(TreeSet::new));
   	 
   	 boolean multipathFree = links.stream().allMatch(v->propagationGraph.incomingEdgesOf(v).size() == 1);
   	 multipathFree &= propagationGraph.incomingEdgesOf(dummyFiberDrop).size() == 1;
   	 final DirectedSimpleCycles<WFiber,Object> cycleDetector = new JohnsonSimpleCycles<> (propagationGraph); 
   	 final List<List<WFiber>> lasingCycles = cycleDetector.findSimpleCycles();
   	 return Triple.of(propagatedNonDummyFibers , lasingCycles, multipathFree);
    }
    
    
    private void checkSameWNet (WAbstractNetworkElement...abstractNetworkElements)
    {
   	 for (WAbstractNetworkElement e : abstractNetworkElements) if (e.getNetPlan() != this.wNet.getNetPlan()) throw new Net2PlanException ("Different wNet object");
    }
    private void checkSameWNet(Collection<? extends WAbstractNetworkElement> abstractNetworkElements)
    {
   	 for (WAbstractNetworkElement e : abstractNetworkElements) if (e.getNetPlan() != this.wNet.getNetPlan()) throw new Net2PlanException ("Different wNet object");
    }

    private static boolean isContinuousUnicastPath (List<WFiber> path) { WFiber prevLink = null; for (WFiber e : path) { if (prevLink != null) { if (!prevLink.getB().equals(e.getA())) return false; } prevLink = e; } return true; }
    private static boolean isPassingSameNodeMoreThanOnce (List<WFiber> path) { final SortedSet<WNode> nodes = new TreeSet<> (); nodes.add(path.get(0).getA()); for (WFiber e : path) { if (nodes.contains(e.getB())) return true; nodes.add(e.getB()); } return false; }
    private static List<WNode> getContinousSequenceOfNodes (List<WFiber> path) { if (path.isEmpty()) return new ArrayList<> (); final List<WNode> res = new ArrayList<> (path.size() + 1); res.add(path.get(0).getA()); for (WFiber e : path) { if (!e.getA ().equals(res.get(res.size()-1))) throw new Net2PlanException ("Not contiguous"); res.add(e.getB()); }  return res; }


    
    
    
}
