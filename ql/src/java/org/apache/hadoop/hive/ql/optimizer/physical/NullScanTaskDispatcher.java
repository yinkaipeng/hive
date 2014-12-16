/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.optimizer.physical;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Stack;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.TableScanOperator;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat;
import org.apache.hadoop.hive.ql.io.OneNullRowInputFormat;
import org.apache.hadoop.hive.ql.lib.DefaultRuleDispatcher;
import org.apache.hadoop.hive.ql.lib.Dispatcher;
import org.apache.hadoop.hive.ql.lib.GraphWalker;
import org.apache.hadoop.hive.ql.lib.Node;
import org.apache.hadoop.hive.ql.lib.NodeProcessor;
import org.apache.hadoop.hive.ql.lib.PreOrderWalker;
import org.apache.hadoop.hive.ql.lib.Rule;
import org.apache.hadoop.hive.ql.optimizer.physical.MetadataOnlyOptimizer.WalkerCtx;
import org.apache.hadoop.hive.ql.parse.ParseContext;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.plan.MapWork;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;
import org.apache.hadoop.hive.ql.plan.PartitionDesc;
import org.apache.hadoop.hive.serde.serdeConstants;
import org.apache.hadoop.hive.serde2.NullStructSerDe;

/**
 * Iterate over all tasks one by one and removes all input paths from task if conditions as
 * defined in rules match.
 */
public class NullScanTaskDispatcher implements Dispatcher {

  private final PhysicalContext physicalContext;
  private final  Map<Rule, NodeProcessor> rules;
  static final Log LOG = LogFactory.getLog(NullScanTaskDispatcher.class.getName());

  public NullScanTaskDispatcher(PhysicalContext context,  Map<Rule, NodeProcessor> rules) {
    super();
    physicalContext = context;
    this.rules = rules;
  }

  private String getAliasForTableScanOperator(MapWork work,
      TableScanOperator tso) {

    for (Map.Entry<String, Operator<? extends OperatorDesc>> entry :
      work.getAliasToWork().entrySet()) {
      if (entry.getValue() == tso) {
        return entry.getKey();
      }
    }

    return null;
  }

  private PartitionDesc changePartitionToMetadataOnly(PartitionDesc desc) {
    if (desc != null) {
      desc.setInputFileFormatClass(OneNullRowInputFormat.class);
      desc.setOutputFileFormatClass(HiveIgnoreKeyTextOutputFormat.class);
      desc.getProperties().setProperty(serdeConstants.SERIALIZATION_LIB,
        NullStructSerDe.class.getName());
    }
    return desc;
  }

  private List<String> getPathsForAlias(MapWork work, String alias) {
    List<String> paths = new ArrayList<String>();

    for (Map.Entry<String, ArrayList<String>> entry : work.getPathToAliases().entrySet()) {
      if (entry.getValue().contains(alias)) {
        paths.add(entry.getKey());
      }
    }

    return paths;
  }

  private void processAlias(MapWork work, ArrayList<String> aliases, String path) {
 
    work.setUseOneNullRowInputFormat(true);
    for (String alias : aliases) {
      // Change the conf for tableScanOp
      TableScanOperator tso = (TableScanOperator) work.getAliasToWork().get(alias);
      tso.getConf().setIsMetadataOnly(true);
      // Change the alias partition desc
      PartitionDesc aliasPartn = work.getAliasToPartnInfo().get(alias);
      changePartitionToMetadataOnly(aliasPartn);
    }

    PartitionDesc partDesc = work.getPathToPartitionInfo().get(path);
    PartitionDesc newPartition = changePartitionToMetadataOnly(partDesc);
    Path fakePath = new Path(physicalContext.getContext().getMRTmpPath()
        + newPartition.getTableName() + encode(newPartition.getPartSpec()));
    work.getPathToPartitionInfo().remove(path);
    work.getPathToPartitionInfo().put(fakePath.getName(), newPartition);
    assert(work.getPathToAliases().remove(path).equals(aliases));
    work.getPathToAliases().put(fakePath.getName(), aliases);
  }

  private void processAlias(MapWork work, HashSet<TableScanOperator> tableScans) {
    ArrayList<String> aliasList = new ArrayList<String>();
    for (TableScanOperator tso : tableScans) {
      // use LinkedHashMap<String, Operator<? extends OperatorDesc>>
      // getAliasToWork()
      String alias = getAliasForTableScanOperator(work, tso);
      aliasList.add(alias);
    }
    // group path alias according to work
    LinkedHashMap<String, ArrayList<String>> candidates = new LinkedHashMap<String, ArrayList<String>>();
    for (String path : work.getPaths()) {
      ArrayList<String> aliases = work.getPathToAliases().get(path);
      if (aliases != null && aliasList.containsAll(aliases)) {
        candidates.put(path, aliases);
      }
    }
    for (Entry<String, ArrayList<String>> entry : candidates.entrySet()) {
      processAlias(work, entry.getValue(), entry.getKey());
    }
  }

  // considered using URLEncoder, but it seemed too much
  private String encode(Map<String, String> partSpec) {
    return partSpec.toString().replaceAll("[:/#\\?]", "_");
  }

  @Override
  public Object dispatch(Node nd, Stack<Node> stack, Object... nodeOutputs)
      throws SemanticException {
    Task<? extends Serializable> task = (Task<? extends Serializable>) nd;

    // create a the context for walking operators
    ParseContext parseContext = physicalContext.getParseContext();
    WalkerCtx walkerCtx = new WalkerCtx();

    for (MapWork mapWork: task.getMapWork()) {
      LOG.debug("Looking at: "+mapWork.getName());
      Collection<Operator<? extends OperatorDesc>> topOperators
        = mapWork.getAliasToWork().values();
      if (topOperators.size() == 0) {
        LOG.debug("No top operators");
        return null;
      }

      LOG.info("Looking for table scans where optimization is applicable");

      // The dispatcher fires the processor corresponding to the closest
      // matching rule and passes the context along
      Dispatcher disp = new DefaultRuleDispatcher(null, rules, walkerCtx);
      GraphWalker ogw = new PreOrderWalker(disp);

      // Create a list of topOp nodes
      ArrayList<Node> topNodes = new ArrayList<Node>();
      // Get the top Nodes for this task
      for (Operator<? extends OperatorDesc>
             workOperator : topOperators) {
        if (parseContext.getTopOps().values().contains(workOperator)) {
          topNodes.add(workOperator);
        }
      }

      Operator<? extends OperatorDesc> reducer = task.getReducer(mapWork);
      if (reducer != null) {
        topNodes.add(reducer);
      }

      ogw.startWalking(topNodes, null);

      LOG.info(String.format("Found %d null table scans",
          walkerCtx.getMetadataOnlyTableScans().size()));
      if (walkerCtx.getMetadataOnlyTableScans().size() > 0)
        processAlias(mapWork, walkerCtx.getMetadataOnlyTableScans());
    }
    return null;
  }
}