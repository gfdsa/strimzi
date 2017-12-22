/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.enmasse.barnabas.operator.topic;

import io.enmasse.barnabas.operator.topic.zk.Zk;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Session extends AbstractVerticle {

    private final static Logger logger = LoggerFactory.getLogger(Session.class);

    private final Config config;
    private final DefaultKubernetesClient kubeClient;

    private ControllerAssignedKafkaImpl kafka;
    private AdminClient adminClient;
    private K8sImpl k8s;
    private Operator operator;
    private Watch topicCmWatch;
    private TopicsWatcher tw;
    private TopicConfigsWatcher tcw;
    private volatile boolean stopped = false;

    public Session(DefaultKubernetesClient kubeClient, Config config) {
        this.kubeClient = kubeClient;
        this.config = config;
    }

    /**
     * Stop the operator.
     */
    public void stop() {
        this.stopped = true;
        logger.info("Stopping");
        logger.debug("Stopping kube watch");
        topicCmWatch.close();
        logger.debug("Stopping zk watches");
        tw.stop();
        tcw.stop();
        // TODO wait for inflight to "empty"
        adminClient.close(1, TimeUnit.MINUTES);
        logger.info("Stopped");
    }

    @Override
    public void start() {
        Properties adminClientProps = new Properties();
        adminClientProps.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.get(Config.KAFKA_BOOTSTRAP_SERVERS));
        this.adminClient = AdminClient.create(adminClientProps);
        this.kafka = new ControllerAssignedKafkaImpl(adminClient, vertx, config);

        // app=barnabas and kind=topic
        // or app=barnabas, kind=topic, cluster=my-cluster if we need to scope it to a cluster
        LabelPredicate cmPredicate = new LabelPredicate("kind", "topic",
                "app", "barnabas");

        this.k8s = new K8sImpl(vertx, kubeClient, cmPredicate);

        this.operator = new Operator(vertx, kafka, k8s, cmPredicate);

        ZkTopicStore topicStore = new ZkTopicStore(vertx);

        this.operator.setTopicStore(topicStore);

        this.tw = new TopicsWatcher(operator);
        this.tcw = new TopicConfigsWatcher(operator);
        Zk zk = Zk.create(vertx, config.get(Config.ZOOKEEPER_CONNECT), this.config.get(Config.ZOOKEEPER_SESSION_TIMEOUT_MS).intValue());
        final Handler<AsyncResult<Zk>> zkConnectHandler = ar -> {
            tw.start(ar.result());
            tcw.start(ar.result());
        };
        zk.disconnectionHandler(ar -> {
            // reconnect if we got disconnected
            if (ar.result() != null) {
                zk.connect(zkConnectHandler);
            }
        }).connect(zkConnectHandler);

        Thread configMapThread = new Thread(() -> {
            Session.this.topicCmWatch = kubeClient.configMaps().watch(new Watcher<ConfigMap>() {
                public void eventReceived(Action action, ConfigMap configMap) {
                    if (stopped) {
                        return;
                    }
                    ObjectMeta metadata = configMap.getMetadata();
                    Map<String, String> labels = metadata.getLabels();
                    if (cmPredicate.test(configMap)) {
                        String name = metadata.getName();
                        logger.info("ConfigMap watch received event {} on map {} with labels {}", action, name, labels);
                        logger.info("ConfigMap {} was created {}", name, metadata.getCreationTimestamp());
                        switch (action) {
                            case ADDED:
                                operator.onConfigMapAdded(configMap, ar -> {});
                                break;
                            case MODIFIED:
                                operator.onConfigMapModified(configMap, ar -> {});
                                break;
                            case DELETED:
                                operator.onConfigMapDeleted(configMap, ar -> {});
                                break;
                            case ERROR:
                                logger.error("Watch received action=ERROR for ConfigMap " + name);
                        }
                    }
                }

                public void onClose(KubernetesClientException e) {
                    // TODO reconnect, unless shutting down
                }
            });
        }, "configmap-watcher");
        logger.debug("Starting {}", configMapThread);
        configMapThread.start();

        // Reconcile initially
        reconcileTopics();
        // And periodically after that
        vertx.setPeriodic(this.config.get(Config.FULL_RECONCILIATION_INTERVAL_MS),
                (timerId) -> {
                    if (stopped) {
                        vertx.cancelTimer(timerId);
                        return;
                    }
                    reconcileTopics();
                });
    }

    private void reconcileTopics() {
        kafka.listTopics(arx -> {
            if (arx.succeeded()) {
                Set<String> kafkaTopics = arx.result();
                // First reconcile the topics in kafka
                for (String name : kafkaTopics) {
                    TopicName topicName = new TopicName(name);
                    // TODO need to check inflight
                    // Reconciliation
                    k8s.getFromName(topicName.asMapName(), ar -> {
                        ConfigMap cm = ar.result();
                        operator.reconcile(cm, topicName);
                    });
                }

                // Then those in k8s which aren't in kafka
                k8s.listMaps(ar -> {
                    List<ConfigMap> configMaps = ar.result();
                    Map<String, ConfigMap> configMapsMap = configMaps.stream().collect(Collectors.toMap(
                            cm -> cm.getMetadata().getName(),
                            cm -> cm));
                    configMapsMap.keySet().removeAll(kafkaTopics);
                    for (ConfigMap cm : configMapsMap.values()) {
                        TopicName topicName = new TopicName(cm);
                        operator.reconcile(cm, topicName);
                    }

                    // Finally those in private store which we've not dealt with so far...
                    // TODO ^^
                });
            }
        });
    }
}