package org.isatools.tablib.export.graph2tab.minflow;

import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.isatools.tablib.export.graph2tab.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * TODO: Comment me!
 *
 * <dl><dt>date</dt><dd>Aug 25, 2011</dd></dl>
 * @author brandizi
 *
 */
class FlowInitialiser
{
	private final FlowManager flowMgr = new FlowManager ();
	private final Set<Node> nodes;
	
	private SortedSet<Node> startNodes = new TreeSet<Node> ();
	private Set<Node> endNodes = new HashSet<Node> ();

	private boolean isInitialised = false;

	protected final Logger log = LoggerFactory.getLogger ( this.getClass () );

	FlowInitialiser ( Set<Node> nodes ) 
	{
		this.nodes = nodes;
	}
	
	Set<Node> getNodes ()
	{
		return nodes;
	}

	SortedSet<Node> getStartNodes ()
	{
		if ( !isInitialised ) initFlow ();
		return startNodes;
	}
	
	Set<Node> getEndNodes () 
	{
		if ( !isInitialised ) initFlow ();
		
		endNodes = new HashSet<Node> ();
		for ( Node n: nodes ) getEndNodes ( n );
		return endNodes;
	}
	
	private void getEndNodes ( Node node ) 
	{
		Set<Node> outs = node.getOutputs ();
		
		if ( outs.isEmpty () ) {
			endNodes.add ( node );
			return;
		}
		
		for ( Node out: outs ) 
			getEndNodes ( out );
		
		return;
	}

	FlowManager getFlowManager ()
	{
		if ( !isInitialised ) initFlow ();
		return flowMgr;
	}
	
	private void initFlow ()
	{
		if ( isInitialised ) return;
		
		isInitialised = true; // tell getEndNode() to go ahead
		
		Deque<Node> reviewNodes = new LinkedList<Node> ();
		for ( Node n: getEndNodes () ) initFlowLeft ( n, reviewNodes );
		while ( !reviewNodes.isEmpty () ) initFlowRight ( reviewNodes.pop () );		
	}

	
	private void initFlowLeft ( Node node, Deque<Node> reviewNodes )
	{
		SortedSet<Node> ins = node.getInputs (), outs = node.getOutputs ();
		int nins = ins.size (), nouts = outs.size ();
		
		// Rare case of isolated node, nothing to do.
		if ( nins == 0 && nouts == 0 ) return;

		if ( nins == 0 ) {
			// Source, end of leftward travel
			startNodes.add ( node );
			return;
		}	
			
		if ( nouts == 0 )
		{
			// Sink, load all your incoming edges with 1, unless they've already been already loaded by another path 
			for ( Node nin: ins )
				if ( flowMgr.getFlow ( nin, node ) == 0 )
					flowMgr.updateFlow ( nin, node, 1 );

			// Then continue leftward
			for ( Node nin: ins ) initFlowLeft ( nin, reviewNodes );
			return;
		}

		// nins != nouts and it's a middle node, let's work to the flow loading propagation 
		
		// First, saturate the incoming edges with the minimum flow, unless this was already done in another path
		//
		if ( log.isDebugEnabled () ) log.trace ( "Loading Inputs for '" + node + "'" );
		boolean flowChanged = false;
		for ( Node nin: ins )
			if ( flowMgr.getFlow ( nin, node ) == 0 ) {
				flowMgr.updateFlow ( nin, node, 1 );
				flowChanged = true;
		}
		
		// Then, let's see what deficit we have at the node 
		//
		int deficit = flowMgr.getDeficit ( node );
		if ( log.isDebugEnabled () ) log.trace ( "Working deficit of " + deficit + " for '" + node + "'" );
		
		// If nothing happened and the node is balanced, we don't have to go ahead with this path, all the left graph
		// won't change anyway
		if ( !flowChanged && deficit == 0 ) return;
		
		if ( deficit < 0 )
		{
			// We cannot balance the left graph with the flow coming from the right (there isn't enough, at least so far), 
			// so let's review this later, in a right-ward walk (via calls to setInitialFlowRight ())
			reviewNodes.push ( node );
		}
		else if ( deficit > 0 )
		{
			// Distribute the excess of output over the inputs. Try to do an even distribution, in order to maximise the 
			// probability that we have a minimum flow as soon as the initialisation is finished.
			//
			if ( log.isDebugEnabled () ) log.trace ( "Distributing excess of output for '" + node + "'" );
			int dquota = deficit / nins, rquota = deficit % nins;
			for ( Node nin: ins )
			{
				flowMgr.increaseFlow ( nin, node, dquota );
				if ( rquota-- > 0 ) flowMgr.increaseFlow ( nin, node, 1 );
			}
		}
		// else if deficit == 0 after input loading, propagate the right change(s) to the full left graph

		// Propagate the above flow changes to the left
		for ( Node nin: ins ) initFlowLeft ( nin, reviewNodes );
		
	}

	private void initFlowRight ( Node node )
	{
		int deficit = flowMgr.getDeficit ( node );
		
		// 0 means It could't initially be balanced (with the flow accumulated up to the point where it was added to 
		// reviewNodes), but then it was by some other paths. We don't need to continue toward right from this 
		// particular node, if there is still some unbalanced node on its right graph, it will be dealt with by a call
		// that picks up the node from reviewNodes (in initFlow() )
		if ( deficit == 0 ) return;
		
		// We have a formal proof that this doesn't happen at this point, but this is the real world, let's check
		// to be really sure
		if ( deficit > 0 )
			throw new IllegalStateException ( 
				"Internal error, I found a node with deficit > 0 during the rightward phase of initialisation, this should " +
				"never happen, likely there is a bug"
			);
		
		
		// deficit < 0
		// Distribute the excess of inputs over the outputs, so that it spreads toward the sinks and the node is balanced
		//
		
		Set<Node> outs = node.getOutputs ();
		int nouts = outs.size ();
		if ( nouts == 0 )
			// Sink, we've finished and it's normal that deficit < 0 here 
			return; 
		
		deficit = -deficit;
		if ( log.isDebugEnabled () ) log.trace ( "Distributing excess of input " + deficit + " for '" + node + "'" );

		// Same approach as above
		int dquota = deficit / nouts, rquota = deficit % nouts;
		for ( Node nout: outs )
		{
			flowMgr.increaseFlow ( node, nout, dquota );
			if ( rquota-- > 0 ) flowMgr.increaseFlow ( node, nout, 1 );
		}

		// Propagate the above changes to the right
		for ( Node nout: outs ) initFlowRight ( nout );
	}

}
