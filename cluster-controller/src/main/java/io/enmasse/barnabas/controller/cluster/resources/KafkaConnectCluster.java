package io.enmasse.barnabas.controller.cluster.resources;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.extensions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class KafkaConnectCluster extends AbstractCluster {

    // Port configuration
    private final int restApiPort = 8083;
    private final String restApiPortName = "rest-api";

    // Kafka Connect configuration
    private String bootstrapServers = DEFAULT_BOOTSTRAP_SERVERS;
    private String groupId = DEFAULT_GROUP_ID;
    private String keyConverter = DEFAULT_KEY_CONVERTER;
    private Boolean keyConverterSchemasEnable = DEFAULT_KEY_CONVERTER_SCHEMAS_EXAMPLE;
    private String valueConverter = DEFAULT_VALUE_CONVERTER;
    private Boolean valueConverterSchemasEnable = DEFAULT_VALUE_CONVERTER_SCHEMAS_EXAMPLE;
    private int configStorageReplicationFactor = DEFAULT_CONFIG_STORAGE_REPLICATION_FACTOR;
    private int offsetStorageReplicationFactor = DEFAULT_OFFSET_STORAGE_REPLICATION_FACTOR;
    private int statusStorageReplicationFactor = DEFAULT_STATUS_STORAGE_REPLICATION_FACTOR;

    // Configuration defaults
    private static String DEFAULT_IMAGE = "enmasseproject/kafka-connect:latest";
    private static int DEFAULT_REPLICAS = 3;
    private static int DEFAULT_HEALTHCHECK_DELAY = 60;
    private static int DEFAULT_HEALTHCHECK_TIMEOUT = 5;

    // Kafka Connect configuration defaults
    private static String DEFAULT_BOOTSTRAP_SERVERS = "kafka:9092";
    private static String DEFAULT_GROUP_ID = "connect-cluster";
    private static String DEFAULT_KEY_CONVERTER = "org.apache.kafka.connect.json.JsonConverter";
    private static Boolean DEFAULT_KEY_CONVERTER_SCHEMAS_EXAMPLE = true;
    private static String DEFAULT_VALUE_CONVERTER = "org.apache.kafka.connect.json.JsonConverter";
    private static Boolean DEFAULT_VALUE_CONVERTER_SCHEMAS_EXAMPLE = true;
    private static int DEFAULT_CONFIG_STORAGE_REPLICATION_FACTOR = 3;
    private static int DEFAULT_OFFSET_STORAGE_REPLICATION_FACTOR = 3;
    private static int DEFAULT_STATUS_STORAGE_REPLICATION_FACTOR = 3;

    // Configuration keys
    private static String KEY_IMAGE = "image";
    private static String KEY_REPLICAS = "nodes";
    private static String KEY_HEALTHCHECK_DELAY = "healthcheck-delay";
    private static String KEY_HEALTHCHECK_TIMEOUT = "healthcheck-timeout";

    // Kafka Connect configuration keys
    private static String KEY_BOOTSTRAP_SERVERS = "KAFKA_CONNECT_BOOTSTRAP_SERVERS";
    private static String KEY_GROUP_ID = "KAFKA_CONNECT_GROUP_ID";
    private static String KEY_KEY_CONVERTER = "KAFKA_CONNECT_KEY_CONVERTER";
    private static String KEY_KEY_CONVERTER_SCHEMAS_EXAMPLE = "KAFKA_CONNECT_KEY_CONVERTER_SCHEMAS_ENABLE";
    private static String KEY_VALUE_CONVERTER = "KAFKA_CONNECT_VALUE_CONVERTER";
    private static String KEY_VALUE_CONVERTER_SCHEMAS_EXAMPLE = "KAFKA_CONNECT_VALUE_CONVERTER_SCHEMAS_ENABLE";
    private static String KEY_CONFIG_STORAGE_REPLICATION_FACTOR = "KAFKA_CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR";
    private static String KEY_OFFSET_STORAGE_REPLICATION_FACTOR = "KAFKA_CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR";
    private static String KEY_STATUS_STORAGE_REPLICATION_FACTOR = "KAFKA_CONNECT_STATUS_STORAGE_REPLICATION_FACTOR";

    private KafkaConnectCluster(String namespace, String name) {
        super(namespace, name);
        this.image = DEFAULT_IMAGE;
        this.replicas = DEFAULT_REPLICAS;
        this.healthCheckPath = "/";
        this.healthCheckTimeout = DEFAULT_HEALTHCHECK_TIMEOUT;
        this.healthCheckInitialDelay = DEFAULT_HEALTHCHECK_DELAY;
    }

    public static KafkaConnectCluster fromConfigMap(ConfigMap cm) {
        KafkaConnectCluster kafkaConnect = new KafkaConnectCluster(cm.getMetadata().getNamespace(), cm.getMetadata().getName());
        kafkaConnect.setLabels(cm.getMetadata().getLabels());

        kafkaConnect.setReplicas(Integer.parseInt(cm.getData().getOrDefault(KEY_REPLICAS, String.valueOf(DEFAULT_REPLICAS))));
        kafkaConnect.setImage(cm.getData().getOrDefault(KEY_IMAGE, DEFAULT_IMAGE));
        kafkaConnect.setHealthCheckInitialDelay(Integer.parseInt(cm.getData().getOrDefault(KEY_HEALTHCHECK_DELAY, String.valueOf(DEFAULT_HEALTHCHECK_DELAY))));
        kafkaConnect.setHealthCheckTimeout(Integer.parseInt(cm.getData().getOrDefault(KEY_HEALTHCHECK_TIMEOUT, String.valueOf(DEFAULT_HEALTHCHECK_TIMEOUT))));

        kafkaConnect.setBootstrapServers(cm.getData().getOrDefault(KEY_BOOTSTRAP_SERVERS, DEFAULT_BOOTSTRAP_SERVERS));
        kafkaConnect.setGroupId(cm.getData().getOrDefault(KEY_GROUP_ID, DEFAULT_GROUP_ID));
        kafkaConnect.setKeyConverter(cm.getData().getOrDefault(KEY_KEY_CONVERTER, DEFAULT_KEY_CONVERTER));
        kafkaConnect.setKeyConverterSchemasEnable(Boolean.parseBoolean(cm.getData().getOrDefault(KEY_KEY_CONVERTER_SCHEMAS_EXAMPLE, String.valueOf(DEFAULT_KEY_CONVERTER_SCHEMAS_EXAMPLE))));
        kafkaConnect.setValueConverter(cm.getData().getOrDefault(KEY_VALUE_CONVERTER, DEFAULT_VALUE_CONVERTER));
        kafkaConnect.setValueConverterSchemasEnable(Boolean.parseBoolean(cm.getData().getOrDefault(KEY_VALUE_CONVERTER_SCHEMAS_EXAMPLE, String.valueOf(DEFAULT_VALUE_CONVERTER_SCHEMAS_EXAMPLE))));
        kafkaConnect.setConfigStorageReplicationFactor(Integer.parseInt(cm.getData().getOrDefault(KEY_CONFIG_STORAGE_REPLICATION_FACTOR, String.valueOf(DEFAULT_CONFIG_STORAGE_REPLICATION_FACTOR))));
        kafkaConnect.setOffsetStorageReplicationFactor(Integer.parseInt(cm.getData().getOrDefault(KEY_OFFSET_STORAGE_REPLICATION_FACTOR, String.valueOf(DEFAULT_OFFSET_STORAGE_REPLICATION_FACTOR))));
        kafkaConnect.setStatusStorageReplicationFactor(Integer.parseInt(cm.getData().getOrDefault(KEY_STATUS_STORAGE_REPLICATION_FACTOR, String.valueOf(DEFAULT_STATUS_STORAGE_REPLICATION_FACTOR))));

        return kafkaConnect;
    }

    // This is currently not needed. But the similar function for statefulsets is used partially. Should we remove this?
    // Or might we need to use it in the future?
    public static KafkaConnectCluster fromDeployment(Deployment dep) {
        KafkaConnectCluster kafkaConnect =  new KafkaConnectCluster(dep.getMetadata().getNamespace(), dep.getMetadata().getName());

        kafkaConnect.setLabels(dep.getMetadata().getLabels());
        kafkaConnect.setReplicas(dep.getSpec().getReplicas());
        kafkaConnect.setImage(dep.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        kafkaConnect.setHealthCheckInitialDelay(dep.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getInitialDelaySeconds());
        kafkaConnect.setHealthCheckInitialDelay(dep.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getTimeoutSeconds());

        Map<String, String> vars = dep.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream().collect(
                Collectors.toMap(EnvVar::getName, EnvVar::getValue));

        kafkaConnect.setBootstrapServers(vars.getOrDefault(KEY_BOOTSTRAP_SERVERS, DEFAULT_BOOTSTRAP_SERVERS));
        kafkaConnect.setGroupId(vars.getOrDefault(KEY_GROUP_ID, DEFAULT_GROUP_ID));
        kafkaConnect.setKeyConverter(vars.getOrDefault(KEY_KEY_CONVERTER, DEFAULT_KEY_CONVERTER));
        kafkaConnect.setKeyConverterSchemasEnable(Boolean.parseBoolean(vars.getOrDefault(KEY_KEY_CONVERTER_SCHEMAS_EXAMPLE, String.valueOf(DEFAULT_KEY_CONVERTER_SCHEMAS_EXAMPLE))));
        kafkaConnect.setValueConverter(vars.getOrDefault(KEY_VALUE_CONVERTER, DEFAULT_VALUE_CONVERTER));
        kafkaConnect.setValueConverterSchemasEnable(Boolean.parseBoolean(vars.getOrDefault(KEY_VALUE_CONVERTER_SCHEMAS_EXAMPLE, String.valueOf(DEFAULT_VALUE_CONVERTER_SCHEMAS_EXAMPLE))));
        kafkaConnect.setConfigStorageReplicationFactor(Integer.parseInt(vars.getOrDefault(KEY_CONFIG_STORAGE_REPLICATION_FACTOR, String.valueOf(DEFAULT_CONFIG_STORAGE_REPLICATION_FACTOR))));
        kafkaConnect.setOffsetStorageReplicationFactor(Integer.parseInt(vars.getOrDefault(KEY_OFFSET_STORAGE_REPLICATION_FACTOR, String.valueOf(DEFAULT_OFFSET_STORAGE_REPLICATION_FACTOR))));
        kafkaConnect.setStatusStorageReplicationFactor(Integer.parseInt(vars.getOrDefault(KEY_STATUS_STORAGE_REPLICATION_FACTOR, String.valueOf(DEFAULT_STATUS_STORAGE_REPLICATION_FACTOR))));

        return kafkaConnect;
    }

    public ClusterDiffResult diff(Deployment dep)  {
        ClusterDiffResult diff = new ClusterDiffResult();

        if (replicas > dep.getSpec().getReplicas()) {
            log.info("Diff: Expected replicas {}, actual replicas {}", replicas, dep.getSpec().getReplicas());
            diff.setScaleUp(true);
        }
        else if (replicas < dep.getSpec().getReplicas()) {
            log.info("Diff: Expected replicas {}, actual replicas {}", replicas, dep.getSpec().getReplicas());
            diff.setScaleDown(true);
        }

        if (!getLabelsWithName().equals(dep.getMetadata().getLabels()))    {
            log.info("Diff: Expected labels {}, actual labels {}", getLabelsWithName(), dep.getMetadata().getLabels());
            diff.setDifferent(true);
        }

        if (!image.equals(dep.getSpec().getTemplate().getSpec().getContainers().get(0).getImage())) {
            log.info("Diff: Expected image {}, actual image {}", image, dep.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
            diff.setDifferent(true);
            diff.setRollingUpdate(true);
        }

        Map<String, String> vars = dep.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream().collect(
                Collectors.toMap(EnvVar::getName, EnvVar::getValue));

        if (!bootstrapServers.equals(vars.getOrDefault(KEY_BOOTSTRAP_SERVERS, DEFAULT_BOOTSTRAP_SERVERS))
                || !groupId.equals(vars.getOrDefault(KEY_GROUP_ID, DEFAULT_GROUP_ID))
                || !keyConverter.equals(vars.getOrDefault(KEY_KEY_CONVERTER, DEFAULT_KEY_CONVERTER))
                || keyConverterSchemasEnable != Boolean.parseBoolean(vars.getOrDefault(KEY_KEY_CONVERTER_SCHEMAS_EXAMPLE, String.valueOf(DEFAULT_KEY_CONVERTER_SCHEMAS_EXAMPLE)))
                || !valueConverter.equals(vars.getOrDefault(KEY_VALUE_CONVERTER, DEFAULT_VALUE_CONVERTER))
                || keyConverterSchemasEnable != Boolean.parseBoolean(vars.getOrDefault(KEY_VALUE_CONVERTER_SCHEMAS_EXAMPLE, String.valueOf(DEFAULT_VALUE_CONVERTER_SCHEMAS_EXAMPLE)))
                || configStorageReplicationFactor != Integer.parseInt(vars.getOrDefault(KEY_CONFIG_STORAGE_REPLICATION_FACTOR, String.valueOf(DEFAULT_CONFIG_STORAGE_REPLICATION_FACTOR)))
                || offsetStorageReplicationFactor != Integer.parseInt(vars.getOrDefault(KEY_OFFSET_STORAGE_REPLICATION_FACTOR, String.valueOf(DEFAULT_OFFSET_STORAGE_REPLICATION_FACTOR)))
                || statusStorageReplicationFactor != Integer.parseInt(vars.getOrDefault(KEY_STATUS_STORAGE_REPLICATION_FACTOR, String.valueOf(DEFAULT_STATUS_STORAGE_REPLICATION_FACTOR)))
                ) {
            log.info("Diff: Kafka Connect options changed");
            diff.setDifferent(true);
            diff.setRollingUpdate(true);
        }

        if (healthCheckInitialDelay != dep.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getInitialDelaySeconds()
                || healthCheckTimeout != dep.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getTimeoutSeconds()) {
            log.info("Diff: Kafka Connect healthcheck timing changed");
            diff.setDifferent(true);
            diff.setRollingUpdate(true);
        }

        return diff;
    }

    public Service generateService() {

        return createService("ClusterIP",
                Collections.singletonList(createServicePort(restApiPortName, restApiPort, restApiPort, "TCP")));
    }

    public Deployment generateDeployment() {

        return createDeployment(
                Collections.singletonList(createContainerPort(restApiPortName, restApiPort, "TCP")),
                createHttpProbe(healthCheckPath, restApiPortName, healthCheckInitialDelay, healthCheckTimeout),
                createHttpProbe(healthCheckPath, restApiPortName, healthCheckInitialDelay, healthCheckTimeout));
    }

    public Deployment patchDeployment(Deployment dep) {

        return patchDeployment(dep,
                createHttpProbe(healthCheckPath, restApiPortName, healthCheckInitialDelay, healthCheckTimeout),
                createHttpProbe(healthCheckPath, restApiPortName, healthCheckInitialDelay, healthCheckTimeout));
    }

    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(new EnvVarBuilder().withName(KEY_BOOTSTRAP_SERVERS).withValue(bootstrapServers).build());
        varList.add(new EnvVarBuilder().withName(KEY_GROUP_ID).withValue(groupId).build());
        varList.add(new EnvVarBuilder().withName(KEY_KEY_CONVERTER).withValue(keyConverter).build());
        varList.add(new EnvVarBuilder().withName(KEY_KEY_CONVERTER_SCHEMAS_EXAMPLE).withValue(String.valueOf(keyConverterSchemasEnable)).build());
        varList.add(new EnvVarBuilder().withName(KEY_VALUE_CONVERTER).withValue(valueConverter).build());
        varList.add(new EnvVarBuilder().withName(KEY_VALUE_CONVERTER_SCHEMAS_EXAMPLE).withValue(String.valueOf(valueConverterSchemasEnable)).build());
        varList.add(new EnvVarBuilder().withName(KEY_CONFIG_STORAGE_REPLICATION_FACTOR).withValue(String.valueOf(configStorageReplicationFactor)).build());
        varList.add(new EnvVarBuilder().withName(KEY_OFFSET_STORAGE_REPLICATION_FACTOR).withValue(String.valueOf(offsetStorageReplicationFactor)).build());
        varList.add(new EnvVarBuilder().withName(KEY_STATUS_STORAGE_REPLICATION_FACTOR).withValue(String.valueOf(statusStorageReplicationFactor)).build());

        return varList;
    }

    protected void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    protected void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    protected void setKeyConverter(String keyConverter) {
        this.keyConverter = keyConverter;
    }

    protected void setKeyConverterSchemasEnable(Boolean keyConverterSchemasEnable) {
        this.keyConverterSchemasEnable = keyConverterSchemasEnable;
    }

    protected void setValueConverter(String valueConverter) {
        this.valueConverter = valueConverter;
    }

    protected void setValueConverterSchemasEnable(Boolean valueConverterSchemasEnable) {
        this.valueConverterSchemasEnable = valueConverterSchemasEnable;
    }

    protected void setConfigStorageReplicationFactor(int configStorageReplicationFactor) {
        this.configStorageReplicationFactor = configStorageReplicationFactor;
    }

    protected void setOffsetStorageReplicationFactor(int offsetStorageReplicationFactor) {
        this.offsetStorageReplicationFactor = offsetStorageReplicationFactor;
    }

    protected void setStatusStorageReplicationFactor(int statusStorageReplicationFactor) {
        this.statusStorageReplicationFactor = statusStorageReplicationFactor;
    }
}