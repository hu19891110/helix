package com.linkedin.clustermanager.alerts;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.log4j.Logger;

import com.linkedin.clustermanager.ClusterDataAccessor;
import com.linkedin.clustermanager.ClusterManager;
import com.linkedin.clustermanager.ClusterManagerException;
import com.linkedin.clustermanager.PropertyType;
import com.linkedin.clustermanager.ZNRecord;
import com.linkedin.clustermanager.controller.stages.ClusterDataCache;
import com.linkedin.clustermanager.controller.stages.StatsAggregationStage;
import com.linkedin.clustermanager.model.HealthStat;

public class StatsHolder {

	private static final Logger logger = Logger
		    .getLogger(StatsHolder.class.getName());

	public static final String VALUE_NAME = "value";
    public static final String TIMESTAMP_NAME = "timestamp";
	
	ClusterDataAccessor _accessor;
	ClusterDataCache _cache;
	Map<String, Map<String, String>> _statMap;
	
	public StatsHolder(ClusterManager manager)
	{
		_accessor = manager.getDataAccessor();
		_cache = new ClusterDataCache();
	}
	
	public void refreshStats()
	{
		_cache.refresh(_accessor);
		_statMap = _cache.getPersistentStats();
		//TODO: confirm this a good place to init the _statMap when null
		if (_statMap == null) {
			_statMap = new HashMap<String, Map<String, String>>();
		}
	}
	
	public void persistStats() 
	{
		//XXX: Am I using _accessor too directly here?
		ZNRecord statsRec = _accessor.getProperty(PropertyType.PERSISTENTSTATS);
		if (statsRec == null) {
			statsRec = new ZNRecord("PersistentStats"); //TODO: fix naming of this record, if it matters
		}
		statsRec.setMapFields(_statMap);
		 boolean retVal = _accessor.setProperty(PropertyType.PERSISTENTSTATS, statsRec);
		 logger.debug("persistStats retVal: "+retVal);
	}
	
	public Iterator<String> getAllStats() 
	{
		return null;
	}

	/*
	 * TODO: figure out pre-conditions here.  I think not allowing anything to be null on input
	 */
	public Map<String,String> mergeStats(String statName, Map<String,String> existingStat, 
			Map<String,String> incomingStat) throws ClusterManagerException
	{
		if (existingStat == null) {
			throw new ClusterManagerException("existing stat for merge is null");
		}
		if (incomingStat == null) {
			throw new ClusterManagerException("incoming stat for merge is null");
		}
		//get agg type and arguments, then get agg object
		String aggTypeStr = ExpressionParser.getAggregatorStr(statName);
		String[] aggArgs = ExpressionParser.getAggregatorArgs(statName);
		Aggregator agg = ExpressionParser.getAggregator(aggTypeStr);
		//XXX: some of below lines might fail with null exceptions
		
		//get timestamps, values out of zk maps
		String existingTime = existingStat.get(TIMESTAMP_NAME);
		String existingVal = existingStat.get(VALUE_NAME);
		String incomingTime = incomingStat.get(TIMESTAMP_NAME);
		String incomingVal = incomingStat.get(VALUE_NAME);
		//parse values into tuples, if the values exist.  else, tuples are null
		Tuple<String> existingTimeTuple = (existingTime != null) ? Tuple.fromString(existingTime) : null;
		Tuple<String> existingValueTuple = (existingVal != null) ? Tuple.fromString(existingVal) : null;
		Tuple<String> incomingTimeTuple = (incomingTime != null) ? Tuple.fromString(incomingTime) : null;
		Tuple<String> incomingValueTuple = (incomingVal != null) ? Tuple.fromString(incomingVal) : null;
		
		//dp merge
		agg.merge(existingValueTuple, incomingValueTuple, existingTimeTuple, 
				incomingTimeTuple, aggArgs);
		//put merged tuples back in map
		Map<String,String> mergedMap = new HashMap<String,String>();
		if (existingTimeTuple.size() == 0) {
			throw new ClusterManagerException("merged time tuple has size zero");
		}
		if (existingValueTuple.size() == 0) {
			throw new ClusterManagerException("merged value tuple has size zero");
		}
		
		mergedMap.put(TIMESTAMP_NAME, existingTimeTuple.toString());
		mergedMap.put(VALUE_NAME,  existingValueTuple.toString());
		return mergedMap;
	}
	
	/*
	 * Find all persisted stats this stat matches.  Update those stats.
	 * An incoming stat can match multiple stats exactly (if that stat has multiple agg types)
	 * An incoming stat can match multiple wildcard stats
	 */
	
	//need to do a time check here!
	
	public void applyStat(String incomingStatName, Map<String,String> statFields)
	{
		//TODO: consider locking stats here
		refreshStats();
		
		Map<String,Map<String,String>> pendingAdds = new HashMap<String,Map<String,String>>();
		
		//traverse through all persistent stats
		for (String key : _statMap.keySet()) {
			//exact match on stat and stat portion of persisted stat, just update
			if (ExpressionParser.isExactMatch(key, incomingStatName)) {
				Map<String,String> mergedStat = mergeStats(key, _statMap.get(key), statFields);
				//update in place, no problem with hash map
				_statMap.put(key, mergedStat);
			}
			//wildcard match
			else if (ExpressionParser.isWildcardMatch(key, incomingStatName)) {
				//make sure incoming stat doesn't already exist, either in previous round or this round
				//form new key (incomingStatName with agg type from the wildcarded stat)
				String statToAdd = ExpressionParser.getWildcardStatSubstitution(key, incomingStatName);
				//if the stat already existed in _statMap, we have/will apply it as an exact match
				//if the stat was added this round to pendingAdds, no need to recreate (it would have same value)
				if (! _statMap.containsKey(statToAdd) && ! pendingAdds.containsKey(statToAdd)) {					
					//add this stat to persisted stats
					Map<String,String> mergedStat = mergeStats(statToAdd, getEmptyStat(), statFields);
					//add to pendingAdds so we don't mess up ongoing traversal of _statMap
					pendingAdds.put(statToAdd, mergedStat);
				}
			}
		}
		_statMap.putAll(pendingAdds);
		persistStats();
	}
	
	//add parsing of stat (or is that in expression holder?)  at least add validate
	public void addStat(String exp) throws Exception
	{
		refreshStats(); //get current stats

		String[] parsedStats = ExpressionParser.getBaseStats(exp);

		for (String stat : parsedStats) {
			if (_statMap.containsKey(stat)) {
				logger.debug("Stat "+stat+" already exists; not adding");
				continue;
			}		
			_statMap.put(stat, getEmptyStat()); //add new stat to map
		}
		persistStats(); //save stats
				 
	}
	
	public Map<String, String> getEmptyStat() 
	{
		Map<String, String> statFields = new HashMap<String, String>();
		statFields.put(TIMESTAMP_NAME, "");
		statFields.put(VALUE_NAME, "");
		return statFields;
	}
}
