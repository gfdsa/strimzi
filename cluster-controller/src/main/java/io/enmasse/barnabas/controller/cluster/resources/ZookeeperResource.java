package io.enmasse.barnabas.controller.cluster.resources;

import io.enmasse.barnabas.controller.cluster.K8SUtils;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.api.model.extensions.StatefulSetBuilder;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.shareddata.Lock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class ZookeeperResource extends AbstractResource {
    private static final Logger log = LoggerFactory.getLogger(ZookeeperResource.class.getName());

    private final String headlessName;

    private final int clientPort = 2181;
    private final String clientPortName = "clients";
    private final int clusteringPort = 2888;
    private final String clusteringPortName = "clustering";
    private final int leaderElectionPort = 3888;
    private final String leaderElectionPortName = "leader-election";
    private final String mounthPath = "/var/lib/zookeeper";
    private final String volumeName = "zookeeper-storage";

    // Number of replicas
    private int replicas = DEFAULT_REPLICAS;

    // Docker image configuration
    private String image = DEFAULT_IMAGE;

    private String healthCheckScript = "/opt/zookeeper/zookeeper_healthcheck.sh";
    private int healthCheckTimeout = DEFAULT_HEALTHCHECK_TIMEOUT;
    private int healthCheckInitialDelay = DEFAULT_HEALTHCHECK_DELAY;

    // Zookeeper configuration
    // N/A

    // Configuration defaults
    private static String DEFAULT_IMAGE = "enmasseproject/zookeeper:latest";
    private static int DEFAULT_REPLICAS = 3;
    private static int DEFAULT_HEALTHCHECK_DELAY = 15;
    private static int DEFAULT_HEALTHCHECK_TIMEOUT = 5;

    // Zookeeper configuration defaults
    // N/A

    // Configuration keys
    private static String KEY_IMAGE = "zookeeper-image";
    private static String KEY_REPLICAS = "zookeeper-nodes";
    private static String KEY_HEALTHCHECK_DELAY = "zookeeper-healthcheck-delay";
    private static String KEY_HEALTHCHECK_TIMEOUT = "zookeeper-healthcheck-timeout";

    // Zookeeper configuration keys
    private static String KEY_ZOOKEEPER_NODE_COUNT = "ZOOKEEPER_NODE_COUNT";

    private ZookeeperResource(String name, String namespace, Vertx vertx, K8SUtils k8s) {
        super(namespace, name, new ResourceId("zookeeper", name), vertx, k8s);
        this.headlessName = name + "-headless";
    }

    public void setLabels(Map<String, String> newLabels) {
        if (newLabels.containsKey("kind") && newLabels.get("kind").equals("kafka")) {
            newLabels.put("kind", "zookeeper");
        }

        super.setLabels(newLabels);
    }

    public static ZookeeperResource fromConfigMap(ConfigMap cm, Vertx vertx, K8SUtils k8s) {
        String name = cm.getMetadata().getName() + "-zookeeper";
        ZookeeperResource zk = new ZookeeperResource(name, cm.getMetadata().getNamespace(), vertx, k8s);

        zk.setLabels(cm.getMetadata().getLabels());

        zk.setReplicas(Integer.parseInt(cm.getData().getOrDefault(KEY_REPLICAS, String.valueOf(DEFAULT_REPLICAS))));
        zk.setImage(cm.getData().getOrDefault(KEY_IMAGE, DEFAULT_IMAGE));
        zk.setHealthCheckInitialDelay(Integer.parseInt(cm.getData().getOrDefault(KEY_HEALTHCHECK_DELAY, String.valueOf(DEFAULT_HEALTHCHECK_DELAY))));
        zk.setHealthCheckTimeout(Integer.parseInt(cm.getData().getOrDefault(KEY_HEALTHCHECK_TIMEOUT, String.valueOf(DEFAULT_HEALTHCHECK_TIMEOUT))));

        return zk;
    }

    public static ZookeeperResource fromStatefulSet(StatefulSet ss, Vertx vertx, K8SUtils k8s) {
        String name = ss.getMetadata().getName() + "-zookeeper";
        ZookeeperResource zk =  new ZookeeperResource(name, ss.getMetadata().getNamespace(), vertx, k8s);

        zk.setLabels(ss.getMetadata().getLabels());
        zk.setReplicas(ss.getSpec().getReplicas());
        zk.setImage(ss.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        zk.setHealthCheckInitialDelay(ss.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getInitialDelaySeconds());
        zk.setHealthCheckInitialDelay(ss.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getTimeoutSeconds());

        return zk;
    }

    public void create(Handler<AsyncResult<Void>> handler) {
        vertx.sharedData().getLockWithTimeout(getLockName(), LOCK_TIMEOUT, res -> {
            if (res.succeeded()) {
                Lock lock = res.result();
                if (!exists()) {
                    vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                            future -> {
                                log.info("Creating Zookeeper {}", name);

                                try {
                                    k8s.createService(namespace, generateService());
                                    k8s.createService(namespace, generateHeadlessService());
                                    k8s.createStatefulSet(namespace, generateStatefulSet());
                                    future.complete();
                                }
                                catch (Exception e) {
                                    log.error("Caught exceptoion: {}", e.toString());
                                    future.fail(e);
                                }
                            }, false, res2 -> {
                                if (res2.succeeded()) {
                                    log.info("Zookeeper cluster created {}", name);
                                    lock.release();
                                    handler.handle(Future.succeededFuture());
                                } else {
                                    log.error("Failed to create Zookeeper cluster {}", name);
                                    lock.release();
                                    handler.handle(Future.failedFuture("Failed to create Zookeeper cluster"));
                                }
                            });
                }
                else {
                    log.info("Zookeeper cluster {} seems to already exist", name);
                    lock.release();
                    handler.handle(Future.succeededFuture());
                }
            } else {
                log.error("Failed to acquire lock to create Zookeeper cluster {}", name);
                handler.handle(Future.failedFuture("Failed to acquire lock to create Zookeeper cluster"));
            }
        });
    }

    public void delete(Handler<AsyncResult<Void>> handler) {
        vertx.sharedData().getLockWithTimeout(getLockName(), LOCK_TIMEOUT, res -> {
            if (res.succeeded()) {
                Lock lock = res.result();
                if (atLeastOneExists()) {
                    vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                            future -> {
                                log.info("Deleting Zookeeper {}", name);

                                try {
                                    k8s.deleteService(namespace, name);
                                    k8s.deleteStatefulSet(namespace, name);
                                    k8s.deleteService(namespace, headlessName);
                                }
                                catch (Exception e) {
                                    log.error("Caught exceptoion: {}", e.toString());
                                    future.fail(e);
                                }
                                future.complete();
                            }, false, res2 -> {
                                if (res2.succeeded()) {
                                    log.info("Zookeeper cluster {} delete", name);
                                    lock.release();
                                    handler.handle(Future.succeededFuture());
                                } else {
                                    log.error("Failed to delete Zookeeper cluster {}", name);
                                    lock.release();
                                    handler.handle(Future.failedFuture("Failed to delete Zookeeper cluster"));
                                }
                            });
                }
                else {
                    log.info("Zookeeper cluster {} seems to not exist anymore", name);
                    lock.release();
                    handler.handle(Future.succeededFuture());
                }
            } else {
                log.error("Failed to acquire lock to delete Zookeeper cluster {}", name);
                handler.handle(Future.failedFuture("Failed to acquire lock to delete Zookeeper cluster"));
            }
        });
    }

    private Service generateService() {
        Service svc = new ServiceBuilder()
                .withNewMetadata()
                .withName(name)
                .withLabels(getLabelsWithName())
                .endMetadata()
                .withNewSpec()
                .withType("ClusterIP")
                .withSelector(getLabelsWithName())
                .withPorts(k8s.createServicePort(clientPortName, clientPort, clientPort))
                .endSpec()
                .build();

        return svc;
    }

    private Service generateHeadlessService() {
        Service svc = new ServiceBuilder()
                .withNewMetadata()
                .withName(headlessName)
                .withLabels(getLabelsWithName(headlessName))
                .endMetadata()
                .withNewSpec()
                .withType("ClusterIP")
                .withClusterIP("None")
                .withSelector(getLabelsWithName())
                .withPorts(k8s.createServicePort(clientPortName, clientPort, clientPort))
                .withPorts(k8s.createServicePort(clusteringPortName, clusteringPort, clusteringPort))
                .withPorts(k8s.createServicePort(leaderElectionPortName, leaderElectionPort, leaderElectionPort))
                .endSpec()
                .build();

        return svc;
    }

    private StatefulSet generateStatefulSet() {
        Container container = new ContainerBuilder()
                .withName(name)
                .withImage(image)
                .withEnv(new EnvVarBuilder().withName(KEY_ZOOKEEPER_NODE_COUNT).withValue(Integer.toString(replicas)).build())
                .withVolumeMounts(k8s.createVolumeMount(volumeName, mounthPath))
                .withPorts(k8s.createContainerPort(clientPortName, clientPort),
                        k8s.createContainerPort(clusteringPortName, clusteringPort),
                        k8s.createContainerPort(leaderElectionPortName, leaderElectionPort))
                .withLivenessProbe(k8s.createExecProbe(healthCheckScript, healthCheckInitialDelay, healthCheckTimeout))
                .withReadinessProbe(k8s.createExecProbe(healthCheckScript, healthCheckInitialDelay, healthCheckTimeout))
                .build();

        StatefulSet statefulSet = new StatefulSetBuilder()
                .withNewMetadata()
                .withName(name)
                .withLabels(getLabelsWithName())
                .endMetadata()
                .withNewSpec()
                .withServiceName(headlessName)
                .withReplicas(replicas)
                .withSelector(new LabelSelectorBuilder().withMatchLabels(getLabelsWithName()).build())
                .withNewTemplate()
                .withNewMetadata()
                .withName(name)
                .withLabels(getLabelsWithName())
                .endMetadata()
                .withNewSpec()
                .withContainers(container)
                .withVolumes(k8s.createEmptyDirVolume(volumeName))
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();

        return statefulSet;
    }

    private String getLockName() {
        return "zookeeper::lock::" + name;
    }

    public void setReplicas(int replicas) {
        this.replicas = replicas;
    }

    public boolean exists() {
        return k8s.statefulSetExists(namespace, name) && k8s.serviceExists(namespace, name) && k8s.serviceExists(namespace, headlessName);
    }

    public boolean atLeastOneExists() {
        return k8s.statefulSetExists(namespace, name) || k8s.serviceExists(namespace, name) || k8s.serviceExists(namespace, headlessName);
    }

    public void setImage(String image) {
        this.image = image;
    }

    public void setHealthCheckScript(String healthCheckScript) {
        this.healthCheckScript = healthCheckScript;
    }

    public void setHealthCheckTimeout(int healthCheckTimeout) {
        this.healthCheckTimeout = healthCheckTimeout;
    }

    public void setHealthCheckInitialDelay(int healthCheckInitialDelay) {
        this.healthCheckInitialDelay = healthCheckInitialDelay;
    }
}