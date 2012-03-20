package com.linkedin.helix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import com.linkedin.helix.util.StringTemplate;

public class ConfigScope
{
  public enum ConfigScopeProperty
  {
    CLUSTER, PARTICIPANT, RESOURCE, PARTITION, CONSTRAINT;
  }

  private static Logger LOG = Logger.getLogger(ConfigScope.class);

  private static final List<ConfigScopeProperty> scopePriority =
      new ArrayList<ConfigScopeProperty>();
  private static final Map<ConfigScopeProperty, Map<ConfigScopeProperty, ConfigScopeProperty>> scopeTransition =
      new HashMap<ConfigScopeProperty, Map<ConfigScopeProperty, ConfigScopeProperty>>();
  private static final StringTemplate template = new StringTemplate();
  static
  {
    // scope priority: CLUSTER > PARTICIPANT > RESOURCE > PARTITION
    scopePriority.add(ConfigScopeProperty.CLUSTER);
    scopePriority.add(ConfigScopeProperty.PARTICIPANT);
    scopePriority.add(ConfigScopeProperty.RESOURCE);
    scopePriority.add(ConfigScopeProperty.PARTITION);

    // scope transition table to check valid inputs
    scopeTransition.put(ConfigScopeProperty.CLUSTER,
                        new HashMap<ConfigScopeProperty, ConfigScopeProperty>());
    scopeTransition.get(ConfigScopeProperty.CLUSTER).put(ConfigScopeProperty.PARTICIPANT,
                                                         ConfigScopeProperty.PARTICIPANT);
    scopeTransition.get(ConfigScopeProperty.CLUSTER).put(ConfigScopeProperty.RESOURCE,
                                                         ConfigScopeProperty.RESOURCE);
    scopeTransition.put(ConfigScopeProperty.RESOURCE,
                        new HashMap<ConfigScopeProperty, ConfigScopeProperty>());
    scopeTransition.get(ConfigScopeProperty.RESOURCE).put(ConfigScopeProperty.PARTITION,
                                                          ConfigScopeProperty.PARTITION);

    // string templates to generate znode path/index
    // @formatter:off
    template.addEntry(ConfigScopeProperty.CLUSTER,
                      2,
                      "/{clusterName}/CONFIGS/CLUSTER/{clusterName}");
    template.addEntry(ConfigScopeProperty.PARTICIPANT,
                      2,
                      "/{clusterName}/CONFIGS/PARTICIPANT/{participantName}");
    template.addEntry(ConfigScopeProperty.RESOURCE,
                      2,
                      "/{clusterName}/CONFIGS/RESOURCE/{resourceName}");
    template.addEntry(ConfigScopeProperty.PARTITION,
                      3,
                      "/{clusterName}/CONFIGS/RESOURCE/{resourceName}|{partitionName}");
    // @formatter:on
  }

  private final String _clusterName;
  private final ConfigScopeProperty _scope;
  private final String _scopeStr;

  ConfigScope(ConfigScopeBuilder configScopeBuilder)
  {
    Map<ConfigScopeProperty, String> scopeMap = configScopeBuilder
        .getScopeMap();
    List<String> keys = new ArrayList<String>();

    ConfigScopeProperty curScope = null;
    for (ConfigScopeProperty scope : scopePriority)
    {
      if (scopeMap.containsKey(scope))
      {
        if (curScope == null && scope == ConfigScopeProperty.CLUSTER)
        {
          keys.add(scopeMap.get(scope));
          curScope = ConfigScopeProperty.CLUSTER;
        } else if (curScope == null)
        {
          throw new IllegalArgumentException("Missing CLUSTER scope. Can't build scope using " + configScopeBuilder);
        } else
        {
          if (!scopeTransition.containsKey(curScope) || !scopeTransition.get(curScope).containsKey(scope))
          {
            throw new IllegalArgumentException("Can't build scope using " + configScopeBuilder);
          }
          keys.add(scopeMap.get(scope));
          curScope = scopeTransition.get(curScope).get(scope);
        }
      }
    }

    if (curScope == ConfigScopeProperty.CLUSTER)
    {
      // append one more {clusterName}
      keys.add(scopeMap.get(ConfigScopeProperty.CLUSTER));
    }

    String scopeStr = template.instantiate(curScope, keys.toArray(new String[0]));

    _clusterName = keys.get(0);
    _scopeStr = scopeStr;
    _scope = curScope;
  }

  public ConfigScopeProperty getScope()
  {
    return _scope;
  }

  public String getClusterName()
  {
    return _clusterName;
  }

  public String getScopeStr()
  {
    return _scopeStr;
  }

  @Override
  public String toString()
  {
    return super.toString() + ": " + _scopeStr;
  }
}
