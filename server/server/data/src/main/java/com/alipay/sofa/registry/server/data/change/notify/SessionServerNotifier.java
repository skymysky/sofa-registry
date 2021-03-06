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
package com.alipay.sofa.registry.server.data.change.notify;

import com.alipay.remoting.Connection;
import com.alipay.sofa.registry.common.model.CommonResponse;
import com.alipay.sofa.registry.common.model.dataserver.Datum;
import com.alipay.sofa.registry.common.model.sessionserver.DataChangeRequest;
import com.alipay.sofa.registry.log.Logger;
import com.alipay.sofa.registry.log.LoggerFactory;
import com.alipay.sofa.registry.remoting.CallbackHandler;
import com.alipay.sofa.registry.remoting.Channel;
import com.alipay.sofa.registry.remoting.Server;
import com.alipay.sofa.registry.remoting.exchange.Exchange;
import com.alipay.sofa.registry.server.data.bootstrap.DataServerConfig;
import com.alipay.sofa.registry.server.data.cache.DatumCache;
import com.alipay.sofa.registry.server.data.change.DataSourceTypeEnum;
import com.alipay.sofa.registry.server.data.remoting.sessionserver.SessionServerConnectionFactory;
import com.alipay.sofa.registry.timer.AsyncHashedWheelTimer;
import com.alipay.sofa.registry.timer.AsyncHashedWheelTimer.TaskFailedCallback;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Notify session DataChangeRequest,if fail get result callback retry
 *
 * @author qian.lqlq
 * @version $Id: SessionServerNotifier.java, v 0.1 2018-03-09 15:32 qian.lqlq Exp $
 */
public class SessionServerNotifier implements IDataChangeNotifier {

    private static final Logger            LOGGER = LoggerFactory
                                                      .getLogger(SessionServerNotifier.class);

    private AsyncHashedWheelTimer          asyncHashedWheelTimer;

    @Autowired
    private DataServerConfig               dataServerBootstrapConfig;

    @Autowired
    private Exchange                       boltExchange;

    @Autowired
    private SessionServerConnectionFactory sessionServerConnectionFactory;

    public SessionServerNotifier() {
        ThreadFactoryBuilder threadFactoryBuilder = new ThreadFactoryBuilder();
        threadFactoryBuilder.setDaemon(true);
        asyncHashedWheelTimer = new AsyncHashedWheelTimer(threadFactoryBuilder.setNameFormat(
            "Registry-SessionServerNotifier-WheelTimer").build(), 100, TimeUnit.MILLISECONDS, 1024,
            threadFactoryBuilder.setNameFormat("Registry-SessionServerNotifier-WheelExecutor-%d")
                .build(), new TaskFailedCallback() {
                @Override
                public void executionRejected(Throwable e) {
                    LOGGER.error("executionRejected: " + e.getMessage(), e);
                }

                @Override
                public void executionFailed(Throwable e) {
                    LOGGER.error("executionFailed: " + e.getMessage(), e);
                }
            });
    }

    @Override
    public Set<DataSourceTypeEnum> getSuitableSource() {
        Set<DataSourceTypeEnum> set = new HashSet<>();
        set.add(DataSourceTypeEnum.PUB);
        set.add(DataSourceTypeEnum.SYNC);
        return set;
    }

    @Override
    public void notify(Datum datum, Long lastVersion) {
        DataChangeRequest request = new DataChangeRequest(datum.getDataInfoId(),
            datum.getDataCenter(), datum.getVersion());
        List<Connection> connections = sessionServerConnectionFactory.getConnections();
        for (Connection connection : connections) {
            doNotify(new NotifyCallback(connection, request));
        }
    }

    private void doNotify(NotifyCallback notifyCallback) {
        Connection connection = notifyCallback.connection;
        DataChangeRequest request = notifyCallback.request;
        try {
            //check connection active
            if (!connection.isFine()) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER
                        .info(String
                            .format(
                                "connection from sessionserver(%s) is not fine, so ignore notify, retryTimes=%s,request=%s",
                                connection.getRemoteAddress(), notifyCallback.retryTimes, request));
                }
                return;
            }
            Server sessionServer = boltExchange.getServer(dataServerBootstrapConfig.getPort());
            sessionServer.sendCallback(sessionServer.getChannel(connection.getRemoteAddress()),
                request, notifyCallback, dataServerBootstrapConfig.getRpcTimeout());
        } catch (Exception e) {
            LOGGER.error(String.format(
                "invokeWithCallback failed: sessionserver(%s),retryTimes=%s, request=%s",
                connection.getRemoteAddress(), notifyCallback.retryTimes, request), e);
            onFailed(notifyCallback);
        }
    }

    /**
     * on failed, retry if necessary
     */
    private void onFailed(NotifyCallback notifyCallback) {
        DataChangeRequest request = notifyCallback.request;
        Connection connection = notifyCallback.connection;
        notifyCallback.retryTimes++;

        if (notifyCallback.retryTimes <= dataServerBootstrapConfig.getNotifySessionRetryTimes()) {
            this.asyncHashedWheelTimer.newTimeout(timeout -> {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(String.format("retrying notify sessionserver(%s), retryTimes=%s, request=%s",
                            connection.getRemoteAddress(), notifyCallback.retryTimes, request));
                }
                //check version, if it's fall behind, stop retry
                long currentVersion = DatumCache.get(request.getDataCenter(), request.getDataInfoId()).getVersion();
                if (request.getVersion() == currentVersion) {
                    doNotify(notifyCallback);
                } else {
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info(String.format(
                                "current version change %s, retry version is %s, stop retry! retryTimes=%s, request=%s",
                                currentVersion, request.getVersion(), notifyCallback.retryTimes, request));
                    }
                }
            }, getDelayTimeForRetry(notifyCallback.retryTimes), TimeUnit.MILLISECONDS);
        } else {
            LOGGER.error(
                    String.format("retryTimes have exceeded! stop retry! retryTimes=%s, sessionServer(%s), request=%s",
                            notifyCallback.retryTimes, connection.getRemoteAddress(), request));
        }
    }

    private long getDelayTimeForRetry(int retryTimes) {
        long initialSleepTime = TimeUnit.MILLISECONDS.toMillis(dataServerBootstrapConfig
            .getNotifySessionRetryFirstDelay());
        long increment = TimeUnit.MILLISECONDS.toMillis(dataServerBootstrapConfig
            .getNotifySessionRetryIncrementDelay());
        long result = initialSleepTime + (increment * (retryTimes - 1));
        return result >= 0L ? result : 0L;
    }

    private class NotifyCallback implements CallbackHandler {

        private int               retryTimes = 0;
        private Connection        connection;
        private DataChangeRequest request;

        public NotifyCallback(Connection connection, DataChangeRequest request) {
            this.connection = connection;
            this.request = request;
        }

        @Override
        public void onCallback(Channel channel, Object message) {
            CommonResponse result = (CommonResponse) message;
            if (result != null && !result.isSuccess()) {
                LOGGER
                    .error(String
                        .format(
                            "response not success when notify sessionserver(%s), retryTimes=%s, request=%s, response=%s",
                            connection.getRemoteAddress(), retryTimes, request, result));
                onFailed(this);
            }
        }

        @Override
        public void onException(Channel channel, Throwable e) {
            LOGGER.error(String.format(
                "exception when notify sessionserver(%s), retryTimes=%s, request=%s",
                connection.getRemoteAddress(), retryTimes, request), e);
            onFailed(this);
        }

    }

}