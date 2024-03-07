/**
 * Copyright © 2016-2024 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.housekeeper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.TestPropertySource;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.rule.engine.metadata.TbGetAttributesNode;
import org.thingsboard.rule.engine.metadata.TbGetAttributesNodeConfiguration;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EventInfo;
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.alarm.AlarmSeverity;
import org.thingsboard.server.common.data.event.EventType;
import org.thingsboard.server.common.data.event.LifecycleEvent;
import org.thingsboard.server.common.data.housekeeper.HousekeeperTaskType;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.id.RuleNodeId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.kv.AttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseAttributeKvEntry;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.StringDataEntry;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.msg.TbNodeConnectionType;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.page.TimePageLink;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleChainMetaData;
import org.thingsboard.server.common.data.rule.RuleChainType;
import org.thingsboard.server.common.data.rule.RuleNode;
import org.thingsboard.server.controller.AbstractControllerTest;
import org.thingsboard.server.dao.alarm.AlarmService;
import org.thingsboard.server.dao.attributes.AttributesService;
import org.thingsboard.server.dao.event.EventService;
import org.thingsboard.server.dao.housekeeper.HousekeeperService;
import org.thingsboard.server.dao.rule.RuleChainService;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.server.dao.timeseries.TimeseriesService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DaoSqlTest
@TestPropertySource(properties = {
        "transport.http.enabled=true"
})
public class HousekeeperServiceTest extends AbstractControllerTest {

    @SpyBean
    private HousekeeperService housekeeperService;
    @Autowired
    private EventService eventService;
    @Autowired
    private TimeseriesService timeseriesService;
    @Autowired
    private AttributesService attributesService;
    @Autowired
    private RuleChainService ruleChainService;
    @Autowired
    private AlarmService alarmService;

    private TenantId tenantId;

    private static final String TELEMETRY_KEY = "telemetry1";
    private static final String ATTRIBUTE_KEY = "_attribute1";
    private static final String KV_VALUE = "ewfewfwef";

    @Before
    public void setUp() throws Exception {
        loginTenantAdmin();
        this.tenantId = super.tenantId;
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void whenDeviceIsDeleted_thenCleanUpRelatedData() throws Exception {
        Device device = createDevice("test", "test");
        createRelatedData(device.getId());

        doDelete("/api/device/" + device.getId()).andExpect(status().isOk());

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verifyNoRelatedData(device.getId());
        });
    }

    @Test
    public void whenRuleChainIsDeleted_thenCleanUpRelatedData() throws Exception {
        RuleChainMetaData ruleChainMetaData = createRuleChain();
        RuleChainId ruleChainId = ruleChainMetaData.getRuleChainId();
        RuleNodeId ruleNode1Id = ruleChainMetaData.getNodes().get(0).getId();
        RuleNodeId ruleNode2Id = ruleChainMetaData.getNodes().get(1).getId();
        createRelatedData(ruleChainId);
        createRelatedData(ruleNode1Id);
        createRelatedData(ruleNode2Id);

        doDelete("/api/ruleChain/" + ruleChainId).andExpect(status().isOk());

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verifyNoRelatedData(ruleNode1Id);
            verifyNoRelatedData(ruleNode2Id);
            verifyNoRelatedData(ruleChainId);
        });
    }

    @Test
    public void whenUserIsDeleted_thenCleanUpRelatedData() throws Exception {
        Device device = createDevice("test", "test");
        UserId userId = customerUserId;
        createRelatedData(userId);
        Alarm alarm = Alarm.builder()
                .type("test")
                .tenantId(tenantId)
                .originator(device.getId())
                .severity(AlarmSeverity.MAJOR)
                .build();
        alarm = doPost("/api/alarm", alarm, Alarm.class);
        alarm = doPost("/api/alarm/" + alarm.getId() + "/assign/" + userId, "", Alarm.class);
        assertThat(alarm.getAssigneeId()).isEqualTo(userId);
        assertThat(alarmService.findAlarmIdsByAssigneeId(tenantId, userId, new PageLink(100)).getData()).isNotEmpty();

        doDelete("/api/user/" + userId).andExpect(status().isOk());

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verifyNoRelatedData(userId);
        });
        assertThat(alarmService.findAlarmById(tenantId, alarm.getId()).getAssigneeId()).isNull();
    }

    @Test
    public void whenTenantIsDeleted_thenDeleteAllEntitiesAndCleanUpRelatedData() throws Exception {
        loginDifferentTenant();
        tenantId = differentTenantId;

        createRelatedData(tenantId);

        Device device = createDevice("test", "test");
        createRelatedData(device.getId());

        RuleChainMetaData ruleChainMetaData = createRuleChain();
        RuleChainId ruleChainId = ruleChainMetaData.getRuleChainId();
        RuleNodeId ruleNode1Id = ruleChainMetaData.getNodes().get(0).getId();
        RuleNodeId ruleNode2Id = ruleChainMetaData.getNodes().get(1).getId();
        createRelatedData(ruleChainId);
        createRelatedData(ruleNode1Id);
        createRelatedData(ruleNode2Id);

        UserId userId = savedDifferentTenantUser.getId();
        createRelatedData(userId);

        loginSysAdmin();
        deleteDifferentTenant();

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            verifyNoRelatedData(device.getId());
            verifyNoRelatedData(ruleNode1Id);
            verifyNoRelatedData(ruleNode2Id);
            verifyNoRelatedData(ruleChainId);
            verifyNoRelatedData(userId);
            verifyNoRelatedData(tenantId);
        });
    }

    private void createRelatedData(EntityId entityId) throws Exception {
        createTelemetry(entityId);
        for (String scope : List.of(DataConstants.SERVER_SCOPE, DataConstants.SHARED_SCOPE, DataConstants.CLIENT_SCOPE)) {
            createAttribute(entityId, scope, scope + ATTRIBUTE_KEY);
        }
        createEvent(entityId);
    }

    private void verifyNoRelatedData(EntityId entityId) throws Exception {
        List<HousekeeperTaskType> expectedTaskTypes = List.of(HousekeeperTaskType.DELETE_TELEMETRY, HousekeeperTaskType.DELETE_ATTRIBUTES, HousekeeperTaskType.DELETE_EVENTS, HousekeeperTaskType.DELETE_ENTITY_ALARMS);
        for (HousekeeperTaskType taskType : expectedTaskTypes) {
            verify(housekeeperService).submitTask(argThat(task -> task.getTaskType() == taskType && task.getEntityId().equals(entityId)));
        }

        assertThat(getLatestTelemetry(entityId)).isNull();
        assertThat(getTimeseriesHistory(entityId)).isEmpty();
        for (String scope : List.of(DataConstants.SERVER_SCOPE, DataConstants.SHARED_SCOPE, DataConstants.CLIENT_SCOPE)) {
            assertThat(getAttribute(entityId, scope, scope + ATTRIBUTE_KEY)).isNull();
        }
        assertThat(getEvents(entityId)).isEmpty();
    }

    private void createAttribute(EntityId entityId, String scope, String key) throws Exception {
        attributesService.save(tenantId, entityId, scope, new BaseAttributeKvEntry(System.currentTimeMillis(), new StringDataEntry(key, KV_VALUE))).get();
    }

    private void createTelemetry(EntityId entityId) throws Exception {
        timeseriesService.save(tenantId, entityId, new BasicTsKvEntry(System.currentTimeMillis(), new StringDataEntry(TELEMETRY_KEY, KV_VALUE))).get();
    }

    private void createEvent(EntityId entityId) {
        LifecycleEvent event = LifecycleEvent.builder()
                .tenantId(tenantId)
                .entityId(entityId.getId())
                .serviceId("test")
                .lcEventType("test")
                .success(true)
                .build();
        eventService.saveAsync(event);
        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> !getEvents(entityId).isEmpty());

    }

    private TsKvEntry getLatestTelemetry(EntityId entityId) throws Exception {
        return timeseriesService.findLatest(tenantId, entityId, HousekeeperServiceTest.TELEMETRY_KEY).get().orElse(null);
    }

    private List<TsKvEntry> getTimeseriesHistory(EntityId entityId) throws Exception {
        return timeseriesService.findAll(tenantId, entityId, List.of(new BaseReadTsKvQuery(HousekeeperServiceTest.TELEMETRY_KEY, 0, System.currentTimeMillis(), 10, "DESC"))).get();
    }

    private AttributeKvEntry getAttribute(EntityId entityId, String scope, String key) throws Exception {
        return attributesService.find(tenantId, entityId, scope, key).get().orElse(null);
    }

    private List<EventInfo> getEvents(EntityId entityId) {
        return eventService.findEvents(tenantId, entityId, EventType.LC_EVENT, new TimePageLink(100)).getData()
                .stream().filter(event -> Optional.ofNullable(event.getBody()).map(body -> body.get("event"))
                        .map(JsonNode::asText).orElse("").equals("test"))
                .collect(Collectors.toList());
    }

    private RuleChainMetaData createRuleChain() {
        RuleChain ruleChain = new RuleChain();
        ruleChain.setTenantId(tenantId);
        ruleChain.setName("Test");
        ruleChain.setType(RuleChainType.CORE);
        ruleChain.setDebugMode(true);
        ruleChain.setConfiguration(JacksonUtil.newObjectNode().set("a", new TextNode("b")));
        ruleChain = ruleChainService.saveRuleChain(ruleChain);
        RuleChainId ruleChainId = ruleChain.getId();

        RuleChainMetaData metaData = new RuleChainMetaData();
        metaData.setRuleChainId(ruleChainId);

        RuleNode ruleNode1 = new RuleNode();
        ruleNode1.setName("Simple Rule Node 1");
        ruleNode1.setType(org.thingsboard.rule.engine.metadata.TbGetAttributesNode.class.getName());
        ruleNode1.setConfigurationVersion(TbGetAttributesNode.class.getAnnotation(org.thingsboard.rule.engine.api.RuleNode.class).version());
        ruleNode1.setDebugMode(true);
        TbGetAttributesNodeConfiguration configuration1 = new TbGetAttributesNodeConfiguration();
        configuration1.setServerAttributeNames(Collections.singletonList("serverAttributeKey1"));
        ruleNode1.setConfiguration(JacksonUtil.valueToTree(configuration1));

        RuleNode ruleNode2 = new RuleNode();
        ruleNode2.setName("Simple Rule Node 2");
        ruleNode2.setType(org.thingsboard.rule.engine.metadata.TbGetAttributesNode.class.getName());
        ruleNode2.setConfigurationVersion(TbGetAttributesNode.class.getAnnotation(org.thingsboard.rule.engine.api.RuleNode.class).version());
        ruleNode2.setDebugMode(true);
        TbGetAttributesNodeConfiguration configuration2 = new TbGetAttributesNodeConfiguration();
        configuration2.setServerAttributeNames(Collections.singletonList("serverAttributeKey2"));
        ruleNode2.setConfiguration(JacksonUtil.valueToTree(configuration2));

        metaData.setNodes(Arrays.asList(ruleNode1, ruleNode2));
        metaData.setFirstNodeIndex(0);
        metaData.addConnectionInfo(0, 1, TbNodeConnectionType.SUCCESS);
        ruleChainService.saveRuleChainMetaData(tenantId, metaData, Function.identity());
        return ruleChainService.loadRuleChainMetaData(tenantId, ruleChainId);
    }

}
