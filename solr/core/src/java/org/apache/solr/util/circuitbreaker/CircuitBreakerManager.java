/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.util.circuitbreaker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.google.common.annotations.VisibleForTesting;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.PluginInfo;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.util.SolrPluginUtils;

/**
 * Manages all registered circuit breaker instances. Responsible for a holistic view
 * of whether a circuit breaker has tripped or not.
 *
 * There are two typical ways of using this class's instance:
 * 1. Check if any circuit breaker has triggered -- and know which circuit breaker has triggered.
 * 2. Get an instance of a specific circuit breaker and perform checks.
 *
 * It is a good practice to register new circuit breakers here if you want them checked for every
 * request.
 *
 * NOTE: The current way of registering new default circuit breakers is minimal and not a long term
 * solution. There will be a follow up with a SIP for a schema API design.
 */
public class CircuitBreakerManager {
  // Class private to potentially allow "family" of circuit breakers to be enabled or disabled
  private final boolean enableCircuitBreakerManager;

  private final List<CircuitBreaker> circuitBreakerList = new ArrayList<>();

  public CircuitBreakerManager(final boolean enableCircuitBreakerManager) {
    this.enableCircuitBreakerManager = enableCircuitBreakerManager;
  }

  public void register(CircuitBreaker circuitBreaker) {
    circuitBreakerList.add(circuitBreaker);
  }

  public void deregisterAll() {
    circuitBreakerList.clear();
  }
  /**
   * Check and return circuit breakers that have triggered
   * @return CircuitBreakers which have triggered, null otherwise.
   */
  public List<CircuitBreaker> checkTripped() {
    List<CircuitBreaker> triggeredCircuitBreakers = null;

    if (enableCircuitBreakerManager) {
      for (CircuitBreaker circuitBreaker : circuitBreakerList) {
        if (circuitBreaker.isEnabled() &&
            circuitBreaker.isTripped()) {
          if (triggeredCircuitBreakers == null) {
            triggeredCircuitBreakers = new ArrayList<>();
          }

          triggeredCircuitBreakers.add(circuitBreaker);
        }
      }
    }

    return triggeredCircuitBreakers;
  }

  /**
   * Returns true if *any* circuit breaker has triggered, false if none have triggered.
   *
   * <p>
   * NOTE: This method short circuits the checking of circuit breakers -- the method will
   * return as soon as it finds a circuit breaker that is enabled and has triggered.
   * </p>
   */
  public boolean checkAnyTripped() {
    if (enableCircuitBreakerManager) {
      for (CircuitBreaker circuitBreaker : circuitBreakerList) {
        if (circuitBreaker.isEnabled() &&
            circuitBreaker.isTripped()) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Construct the final error message to be printed when circuit breakers trip.
   *
   * @param circuitBreakerList Input list for circuit breakers.
   * @return Constructed error message.
   */
  public static String toErrorMessage(List<CircuitBreaker> circuitBreakerList) {
    StringBuilder sb = new StringBuilder();

    for (CircuitBreaker circuitBreaker : circuitBreakerList) {
      sb.append(circuitBreaker.getErrorMessage());
      sb.append("\n");
    }

    return sb.toString();
  }

  /**
   * Register default circuit breakers and return a constructed CircuitBreakerManager
   * instance which serves the given circuit breakers.
   *
   * Any default circuit breakers should be registered here.
   */
  @Deprecated
  public static CircuitBreakerManager build(PluginInfo pluginInfo) {
    return build(pluginInfo, null);
  }

  /**
   * TODO
   */
  public static CircuitBreakerManager build(PluginInfo pluginInfo, SolrResourceLoader solrResourceLoader) {
    boolean enabled = pluginInfo == null ? false : Boolean.parseBoolean(pluginInfo.attributes.getOrDefault("enabled", "false"));
    CircuitBreakerManager circuitBreakerManager = new CircuitBreakerManager(enabled);

    circuitBreakerManager.init(pluginInfo, solrResourceLoader);

    return circuitBreakerManager;
  }

  /**
   * Initialize with circuit breakers defined in the configuration
   */
  public void init(PluginInfo pluginInfo, SolrResourceLoader solrResourceLoader) {
    final HashMap<String, CircuitBreaker> namedCBs = new HashMap<>();

    if (solrResourceLoader != null) {
      final String dotClass = ".class";
      for (int idx = 0; idx < pluginInfo.initArgs.size(); ++idx) {
        final String key = pluginInfo.initArgs.getName(idx);
        final Object val = pluginInfo.initArgs.getVal(idx);
        if (key.endsWith(dotClass)) {
          final String cbName = key.substring(0, key.length() - dotClass.length());
          final String cbClassName = (String)val;
          namedCBs.put(cbName, solrResourceLoader.newInstance(cbClassName, CircuitBreaker.class));
        }
      }

      for (String name : namedCBs.keySet()) {
        final CircuitBreaker cb = namedCBs.get(name);
        final String argPrefix = name + ".";

        final HashMap<String,Object> params = new HashMap<>();

        for (int idx = 0; idx < pluginInfo.initArgs.size(); ++idx) {
          final String key = pluginInfo.initArgs.getName(idx);
          final Object val = pluginInfo.initArgs.getVal(idx);
          if (key.startsWith(argPrefix) && !key.endsWith(dotClass)) {
            params.put(key.substring(argPrefix.length()), val);
          }
        }

        SolrPluginUtils.invokeSetters(cb, params.entrySet());
        register(cb);
      }
    }
  }

  @VisibleForTesting
  public static CircuitBreaker.CircuitBreakerConfig buildCBConfig(PluginInfo pluginInfo) {
    boolean enabled = false;
    boolean cpuCBEnabled = false;
    boolean memCBEnabled = false;
    int memCBThreshold = 100;
    int cpuCBThreshold = 100;


    if (pluginInfo != null) {
      NamedList<?> args = pluginInfo.initArgs;

      enabled = Boolean.parseBoolean(pluginInfo.attributes.getOrDefault("enabled", "false"));

      if (args != null) {
        cpuCBEnabled = Boolean.parseBoolean(args._getStr("cpuEnabled", "false"));
        memCBEnabled = Boolean.parseBoolean(args._getStr("memEnabled", "false"));
        memCBThreshold = Integer.parseInt(args._getStr("memThreshold", "100"));
        cpuCBThreshold = Integer.parseInt(args._getStr("cpuThreshold", "100"));
      }
    }

    return new CircuitBreaker.CircuitBreakerConfig(enabled, memCBEnabled, memCBThreshold, cpuCBEnabled, cpuCBThreshold);
  }

  public boolean isEnabled() {
    return enableCircuitBreakerManager;
  }

  @VisibleForTesting
  public List<CircuitBreaker> getRegisteredCircuitBreakers() {
    return circuitBreakerList;
  }
}
