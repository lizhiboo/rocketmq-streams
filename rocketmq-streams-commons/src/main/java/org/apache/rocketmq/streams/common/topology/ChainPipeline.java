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
package org.apache.rocketmq.streams.common.topology;

import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Lists;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.rocketmq.streams.common.cache.compress.impl.LongValueKV;
import org.apache.rocketmq.streams.common.channel.source.ISource;
import org.apache.rocketmq.streams.common.component.ComponentCreator;
import org.apache.rocketmq.streams.common.configurable.AbstractConfigurable;
import org.apache.rocketmq.streams.common.configurable.IAfterConfigurableRefreshListener;
import org.apache.rocketmq.streams.common.configurable.IConfigurableService;
import org.apache.rocketmq.streams.common.context.AbstractContext;
import org.apache.rocketmq.streams.common.context.IMessage;
import org.apache.rocketmq.streams.common.interfaces.IStreamOperator;
import org.apache.rocketmq.streams.common.metadata.MetaData;
import org.apache.rocketmq.streams.common.monitor.IMonitor;
import org.apache.rocketmq.streams.common.monitor.group.MonitorCommander;
import org.apache.rocketmq.streams.common.optimization.IHomologousOptimization;
import org.apache.rocketmq.streams.common.optimization.MessageGlobleTrace;
import org.apache.rocketmq.streams.common.optimization.fingerprint.PreFingerprint;
import org.apache.rocketmq.streams.common.topology.model.AbstractStage;
import org.apache.rocketmq.streams.common.topology.model.Pipeline;
import org.apache.rocketmq.streams.common.utils.DipperThreadLocalUtil;
import org.apache.rocketmq.streams.common.utils.MapKeyUtil;
import org.apache.rocketmq.streams.common.utils.PrintUtil;
import org.apache.rocketmq.streams.common.utils.StringUtil;

/**
 * 数据流拓扑结构，包含了source 算子，sink
 */
public class ChainPipeline<T extends IMessage> extends Pipeline<T> implements IAfterConfigurableRefreshListener, Serializable {

    private static final long serialVersionUID = -5189371682717444347L;

    private final transient int duplicateCacheSize = 1000000;
    private transient LongValueKV duplicateCache;
    private transient List<String> duplicateFields;
    private transient int duplicateCacheExpirationTime;

    private transient int homologousExpressionCacheSize = 2000000;
    private transient int preFingerprintCacheSize = 2000000;
    private transient IHomologousOptimization homologousOptimization; //对pipeline执行预编译的优化

    /**
     * 是否自动启动channel
     */
    protected boolean isAutoStart = false;

    /**
     * pipeline状态，0，不启动，1-启动
     */
    protected Integer pipelineStatus;

    protected transient ISource source;

    /**
     * channel对应后续的stageName
     */
    protected List<String> channelNextStageLabel;

    protected transient Map<String, AbstractStage> stageMap = new HashMap<>();

    // protected transient AtomicBoolean initProcessor = new AtomicBoolean(false);

    private String channelName;

    protected MetaData channelMetaData;//数据源输入格式，主要用于日志指纹过滤，如果没有则不做优化

    /**
     * 是否发布，默认为true，关闭发布时，此字段为false，pipeline启动时应判断此字段是否为true，status默认都为1，status为0代表pipeline已被删除
     */
    private boolean isPublish = false;

    protected transient AtomicBoolean hasStart = new AtomicBoolean(false);

    public Integer getPipelineStatus() {
        return pipelineStatus;
    }

    public void setPipelineStatus(Integer pipelineStatus) {
        this.pipelineStatus = pipelineStatus;
    }

    /**
     * 启动一个channel，并给channel应用pipeline
     */

    public void startChannel() {
        final String monitorName = createPipelineMonitorName();
        if (isInitSuccess()) {
            if (!hasStart.compareAndSet(false, true)) {
                return;
            }
            final IStreamOperator<T, T> receiver = this;
            IMonitor pipelineMonitorForChannel = DipperThreadLocalUtil.get();
            if (pipelineMonitorForChannel == null) {
                //主要监控channel的启动
                pipelineMonitorForChannel = IMonitor.createMonitor(this);
            }
            String isOpenOptimizationStr = ComponentCreator.getProperties().getProperty("homologous.optimization.switch");
            boolean isOpenOptimization = true;
            if (StringUtil.isNotEmpty(isOpenOptimizationStr)) {
                isOpenOptimization = Boolean.valueOf(isOpenOptimizationStr);
            }
            if (this.homologousOptimization == null && isOpenOptimization) {
                Iterable<IHomologousOptimization> iterable = ServiceLoader.load(IHomologousOptimization.class);
                Iterator<IHomologousOptimization> it = iterable.iterator();
                String homologousExpressionCacheSizeStr = ComponentCreator.getProperties().getProperty("homologous.expression.cache.size");
                if (StringUtil.isNotEmpty(homologousExpressionCacheSizeStr)) {
                    this.homologousExpressionCacheSize = Integer.valueOf(homologousExpressionCacheSizeStr);
                }
                String preFingerprintCacheSizeStr = ComponentCreator.getProperties().getProperty("homologous.pre.fingerprint.cache.size");
                if (StringUtil.isNotEmpty(preFingerprintCacheSizeStr)) {
                    this.preFingerprintCacheSize = Integer.valueOf(preFingerprintCacheSizeStr);
                }
                if (it.hasNext()) {
                    this.homologousOptimization = it.next();
                    this.homologousOptimization.optimizate(Lists.newArrayList(this), this.homologousExpressionCacheSize, this.preFingerprintCacheSize);
                }
            }

            try {
                AtomicLong COUNT = new AtomicLong(0);
                Long startTime = System.currentTimeMillis();
                Boolean isPrintPipelineQPS = ComponentCreator.getPropertyBooleanValue("pipeline.qps.print");
                source.start((IStreamOperator<T, T>) (message, context) -> {
                    //每条消息一个，监控整个链路
                    IMonitor pipelineMonitorForStage = context.startMonitor(monitorName);
                    pipelineMonitorForStage.setType(IMonitor.TYPE_DATAPROCESS);
                    message.getHeader().setPiplineName(this.getConfigureName());
                    //在正式执行逻辑之前， 基于同源的优化策略先进行计算
                    if (this.homologousOptimization != null) {
                        this.homologousOptimization.calculate(message, context);
                    }
                    //然后再执行正式逻辑，测试正式逻辑遇到表达是计算，会先从头部信息上去找，如果找到就直接返回，如果没有才进行正式的计算
                    T t = receiver.doMessage(message, context);
                    pipelineMonitorForStage.endMonitor();
                    if (isPrintPipelineQPS) {
                        long count = COUNT.incrementAndGet();
                        long gap = (System.currentTimeMillis() - startTime) / 1000;
                        if (gap == 0) {
                            gap = 1;
                        }
                        if (count % 1000 == 0) {
                            double qps = (double) count / (double) gap;
                            System.out.println("qps is " + qps + ",the count is " + COUNT.get());
                        }
                    }

                    MonitorCommander.getInstance().finishMonitor(pipelineMonitorForStage.getName(), pipelineMonitorForStage);
                    return t;
                });
            } catch (Exception e) {
                e.printStackTrace();
                //已经输出到sime的日志文件不需要再输出dipper.log
                this.setInitSuccess(false);
                //pipeline启动失败日志输出
                pipelineMonitorForChannel.occureError(e, pipelineMonitorForChannel.getName() + " pipeline startup error", e.getMessage());
            }
        } else {
            LOG.error("channel init failure, so can not start channel");
        }

    }

    private String createDuplicateKey(IMessage message) {
        List<String> duplicateValues = Lists.newArrayList();
        for (String field : duplicateFields) {
            duplicateValues.add(message.getMessageBody().getString(field));
        }
        return StringUtil.createMD5Str(String.join("", duplicateValues));
    }

    private String createPipelineMonitorName() {
        return MapKeyUtil.createKeyBySign(".", getType(), getNameSpace(), getConfigureName());
    }

    /**
     * 可以替换某个阶段的阶段，而不用配置的阶段
     *
     * @param t
     * @param context
     * @param replaceStage
     * @return
     */
    @Override
    protected T doMessageInner(T t, AbstractContext context, AbstractStage... replaceStage) {
        if (this.duplicateCache != null && this.duplicateFields != null && !this.duplicateFields.isEmpty() && !t.getHeader().isSystemMessage()) {
            String duplicateKey = createDuplicateKey(t);
            Long cacheTime = this.duplicateCache.get(duplicateKey);
            Long currentTime = System.currentTimeMillis();
            if (cacheTime != null && currentTime - cacheTime < this.duplicateCacheExpirationTime) {
                context.breakExecute();
                return t;
            } else {
                this.duplicateCache.put(duplicateKey, currentTime);
                if (this.duplicateCache.getSize() > duplicateCacheSize) {
                    this.duplicateCache = new LongValueKV(this.duplicateCacheSize);
                }
            }
        }
        if (!t.getHeader().isSystemMessage()) {
            MessageGlobleTrace.joinMessage(t);//关联全局监控器
        }

        if (!isTopology()) {
            return super.doMessageInner(t, context, replaceStage);
        }
        context.setMessage(t);
        doNextStages(context, getMsgSourceName(), channelName, this.channelNextStageLabel, null);
        return t;
    }

    protected boolean isTopology(List<String> nextStageLabel) {
        if (nextStageLabel == null || nextStageLabel.size() == 0) {
            return false;
        }
        return true;
    }

    public boolean isTopology() {
        return isTopology(this.channelNextStageLabel);
    }

    public void doNextStages(AbstractContext context, String msgPrewSourceName, String currentLable,
        List<String> nextStageLabel, String prewSQLNodeName) {

        if (!isTopology(nextStageLabel)) {
            return;
        }

        String oriMsgPrewSourceName = msgPrewSourceName;
        List<String> currentStageLables = nextStageLabel;
        int size = currentStageLables.size();
        for (String lable : currentStageLables) {
            AbstractContext copyContext = context;
            if (size > 1) {
                copyContext = context.copy();
            }
            T msg = (T) copyContext.getMessage();
            AbstractStage oriStage = stageMap.get(lable);
            if (oriStage == null) {
                if (stages != null && stages.size() > 0) {
                    synchronized (this) {
                        oriStage = stageMap.get(lable);
                        if (oriStage == null) {
                            createStageMap();
                            oriStage = stageMap.get(lable);
                        }
                    }
                }
                if (oriStage == null) {
                    LOG.warn("expect stage named " + lable + ", but the stage is not exist");
                    continue;
                }
            }
            AbstractStage stage = oriStage;
            if (filterByPreFingerprint(msg, copyContext, currentLable, lable)) {
                continue;
            }

            //boolean needFlush = needFlush(msg);
            if (StringUtil.isNotEmpty(oriMsgPrewSourceName)) {
                msg.getHeader().setMsgRouteFromLable(oriMsgPrewSourceName);
            }
            /**
             * 主要用于调试，这里进入一个新的sqlnode 了
             */
            //if (isNewSQLNode(stage, prewSQLNodeName) & msg.getHeader().isSystemMessage() == false) {
            //    if (LOG.isDebugEnabled()) {
            //        LOG.debug(msg.getHeader().getTraceId() + " " + prewSQLNodeName + "->" + stage.getOwnerSqlNodeTableName());
            //
            //    }
            //}
            boolean isContinue = executeStage(stage, msg, copyContext);

            if (!isContinue) {
                /**
                 * 只要执行到了window分支都不应该被过滤
                 */
                if (stage.isAsyncNode() && !msg.getHeader().isSystemMessage()) {
                    MessageGlobleTrace.finishPipeline(msg);
                }
                continue;
            } else {
                if (stage instanceof ChainStage) {
                    ChainStage chainStage = (ChainStage) stage;
                    String msgSourceName = chainStage.getMsgSourceName();
                    if (StringUtil.isNotEmpty(msgSourceName)) {
                        msgPrewSourceName = msgSourceName;
                    }

                }
                List<String> labels = stage.doRoute(msg);
                if (labels == null || labels.size() == 0) {
                    if (!msg.getHeader().isSystemMessage()) {
                        MessageGlobleTrace.finishPipeline(msg);
                    }
                    continue;
                }
                doNextStages(copyContext, msgPrewSourceName, stage.getLabel(), labels, stage.getOwnerSqlNodeTableName());
            }
        }
    }

    /**
     * 是否进入一个新的sql node
     *
     * @param stage
     * @param prewSQLNodeName
     * @return
     */
    protected boolean isNewSQLNode(AbstractStage stage, String prewSQLNodeName) {
        if (prewSQLNodeName == null) {
            return true;
        }
        if (stage.getOwnerSqlNodeTableName().equals(prewSQLNodeName)) {
            return false;
        }
        return true;
    }

    @Override
    protected boolean executeStage(final AbstractStage stage, T t, AbstractContext context) {
        IMonitor stageMonitor = context.createChildrenMonitor(createPipelineMonitorName(), stage);

        try {
            boolean isContinue = super.executeStage(stage, t, context);
            stageMonitor.setResult(isContinue);
            stageMonitor.endMonitor();
            if (stageMonitor.isSlow()) {
                stageMonitor.setSampleData(context).put("stage_info", createStageInfo(stage));
            }
            return isContinue;
        } catch (Exception e) {
            e.printStackTrace();
            //优化日志量
            //            LOG.error("execute stage error " + stage.getConfigureName(), e);
            stageMonitor.occureError(e, "execute stage error " + stage.getConfigureName(), e.getMessage());
            stageMonitor.setSampleData(context).put("stage_info", createStageInfo(stage));
            return false;
        }
    }

    protected boolean filterByPreFingerprint(IMessage t, AbstractContext context, String sourceName, String nextLable) {
        PreFingerprint preFingerprint = getPreFingerprint(sourceName, nextLable);
        if (preFingerprint != null) {
            boolean isFilter = preFingerprint.filterByLogFingerprint(t);
            if (isFilter) {
                context.breakExecute();
                return true;
            }
        }
        return false;
    }

    protected JSONObject createStageInfo(AbstractStage stage) {
        JSONObject jsonObject = null;
        //        if (stage instanceof ChainStage) {
        //            jsonObject = new JSONObject();
        //            ChainStage chainStage = (ChainStage) stage;
        //            return chainStage.toJsonObject();
        //            //String entityName = chainStage.getEntityName();
        //            ////todo 需要改写
        //            //if (creatorService != null && StringUtil.isNotEmpty(entityName)) {
        //            //    IConfigurableCreator creator = creatorService.getCreator(
        //            //        entityName);
        //            //    if(creator!=null){
        //            //        String configures = creator.print(stage);
        //            //        jsonObject.put("stageDetail", configures);
        //            //    }
        //            //
        //            //}
        //        }
        return jsonObject;
    }

    public ChainPipeline addChainStage(ChainStage chainStage) {
        addStage(chainStage);
        return this;
    }

    public ISource getSource() {
        return source;
    }

    public void setSource(ISource source) {
        this.source = source;
        if (getNameSpace() == null) {
            setNameSpace(source.getNameSpace());
        }
        channelName = source.getConfigureName();
    }

    @Override
    public void doProcessAfterRefreshConfigurable(IConfigurableService configurableService) {
        createStageMap();
        ISource source = configurableService.queryConfigurable(ISource.TYPE, channelName);
        this.source = source;
        for (AbstractStage stage : getStages()) {
            stage.setPipeline(this);
            if (stage instanceof IAfterConfigurableRefreshListener) {
                if (!stage.isInitSuccess() && !this.isInitSuccess()) {
                    this.setInitSuccess(false);
                    return;
                }
                IAfterConfigurableRefreshListener afterConfigurableRefreshListener = (IAfterConfigurableRefreshListener) stage;

                afterConfigurableRefreshListener.doProcessAfterRefreshConfigurable(configurableService);

            }
        }

        if (source != this.source && this.source != null) {
            this.hasStart.set(false);
            this.source = source;
            startChannel();
        }

        if (source instanceof AbstractConfigurable) {
            AbstractConfigurable abstractConfigurable = (AbstractConfigurable) source;
            if (!abstractConfigurable.isInitSuccess() && this.isInitSuccess()) {
                this.setInitSuccess(false);
                return;
            }
        }

        //修改发布状态为true或设置自动启动，需要调用startChannel
        if ((isAutoStart || isPublish()) && isInitSuccess()) {
            startChannel();
        }

        //增加去重的逻辑
        String duplicateFieldNameStr = ComponentCreator.getProperties().getProperty(getConfigureName() + ".duplicate.fields.names");
        if (duplicateFieldNameStr != null && !duplicateFieldNameStr.isEmpty()) {
            this.duplicateFields = Lists.newArrayList();
            this.duplicateFields.addAll(Arrays.asList(duplicateFieldNameStr.split(";")));
        }
        if (this.duplicateCache == null && this.duplicateFields != null) {
            this.duplicateCache = new LongValueKV(this.duplicateCacheSize);
        }
        String duplicateCacheExpirationStr = ComponentCreator.getProperties().getProperty(getConfigureName() + ".duplicate.expiration.time");
        if (duplicateCacheExpirationStr != null && !duplicateCacheExpirationStr.isEmpty()) {
            this.duplicateCacheExpirationTime = Integer.parseInt(duplicateCacheExpirationStr);
        } else {
            this.duplicateCacheExpirationTime = 86400000;
        }

    }

    public Map<String, AbstractStage> createStageMap() {
        for (AbstractStage stage : getStages()) {
            stageMap.put(stage.getLabel(), stage);
            stage.setPipeline(this);
        }
        return stageMap;
    }

    public boolean isAutoStart() {
        return isAutoStart;
    }

    public void setAutoStart(boolean autoStart) {
        isAutoStart = autoStart;
    }

    public List<String> getChannelNextStageLabel() {
        return channelNextStageLabel;
    }

    public void setChannelNextStageLabel(List<String> channelNextStageLabel) {
        this.channelNextStageLabel = channelNextStageLabel;
    }

    @Override
    public String toString() {
        String LINE = PrintUtil.LINE;
        StringBuilder sb = new StringBuilder();
        sb.append("###namespace=").append(getNameSpace()).append("###").append(LINE);
        if (source != null) {
            sb.append(source.toString()).append(LINE);
        }
        if (stages != null) {
            for (AbstractStage stage : stages) {
                sb.append(stage.toString());
            }

        }
        return sb.toString();
    }

    @Override
    public void destroy() {
        if (source != null) {
            source.destroy();
        }
        super.destroy();
    }

    public Map<String, AbstractStage> getStageMap() {
        return stageMap;
    }

    public Boolean getHasStart() {
        return hasStart.get();
    }

    public boolean isPublish() {
        return isPublish;
    }

    public void setPublish(boolean publish) {
        isPublish = publish;
    }

    public MetaData getChannelMetaData() {
        return channelMetaData;
    }

    public void setChannelMetaData(MetaData channelMetaData) {
        this.channelMetaData = channelMetaData;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String channelName) {
        this.channelName = channelName;
    }
}
