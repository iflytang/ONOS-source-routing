package org.test.sourceRouting;

import org.apache.felix.scr.annotations.*;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.floodlightpof.protocol.OFMatch20;
import org.onosproject.floodlightpof.protocol.action.OFAction;
import org.onosproject.floodlightpof.protocol.table.OFFlowTable;
import org.onosproject.floodlightpof.protocol.table.OFTableType;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceAdminService;
import org.onosproject.net.flow.*;
import org.onosproject.net.flow.criteria.Criteria;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.instructions.DefaultPofActions;
import org.onosproject.net.flow.instructions.DefaultPofInstructions;
import org.onosproject.net.table.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by tsf on 6/9/17.
 *
 * @Descriptiton test to ping a linear topology (h1-s1-s2-s3-h2) based mininet
 *               uncomment annotations, then run TestPing.
 *
 * @Test ping successfully with matching DIP field.
 */

@Component(immediate = true)
public class TestPing {

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowTableService flowTableService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowTableStore flowTableStore;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY )
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceAdminService deviceAdminService;

    private final Logger log = LoggerFactory.getLogger(getClass());

    private ApplicationId appId;
    private int globalTableId;
    private byte smallTableId;
    protected List<DeviceId> deviceIdList = getDeviceId();

    @Activate
    public void activate() {
//        appId = coreService.registerApplication("onos.test.sourceRouting");
        log.info("Test Ping Started.");
//        handlePortStatus();   // enable ports
        try{
            Thread.currentThread().sleep(100);
        } catch(Exception e) {
            System.out.println(e);
        }
//        sendPofFlowTables();
        try{
            Thread.currentThread().sleep(100);
        } catch(Exception e) {
            System.out.println(e);
        }

//        sendPofFlowRules();   // uncomment it without other class running if you want to testPing
    }

    @Deactivate
    public void deactivate() {
        log.info("Test Ping Stopped.");
//        removePofFlowRules();
    }

    public List<DeviceId> getDeviceId() {
        DeviceId deviceId1 = DeviceId.deviceId("pof:0000000000000001");
        DeviceId deviceId2 = DeviceId.deviceId("pof:0000000000000002");
        DeviceId deviceId3 = DeviceId.deviceId("pof:0000000000000003");
        List<DeviceId> deviceIdList = new ArrayList<DeviceId>();
        deviceIdList.add(deviceId1);
        deviceIdList.add(deviceId2);
        deviceIdList.add(deviceId3);

        return deviceIdList;
    }

    public void handlePortStatus() {
        for(DeviceId deviceId:deviceIdList) {
            deviceAdminService.changePortState(deviceId, PortNumber.portNumber(1), true);
            deviceAdminService.changePortState(deviceId, PortNumber.portNumber(2), true);
        }
        log.info("enable ports ok.");
    }

    public void sendPofFlowTables() {

        for(DeviceId deviceId:deviceIdList) {
            globalTableId = flowTableStore.getNewGlobalFlowTableId(deviceId, OFTableType.OF_MM_TABLE);
            smallTableId = flowTableStore.parseToSmallTableId(deviceId, globalTableId);

            // construct ofmatch20 object
            int DIP = 1;
            OFMatch20 ofMatch20 = new OFMatch20();
            ofMatch20.setFieldId((short) DIP);
            ofMatch20.setFieldName("DIP");
            ofMatch20.setOffset((short) 240);
            ofMatch20.setLength((short) 32);

            ArrayList<OFMatch20> ofMatch20ArrayList = new ArrayList<OFMatch20>();
            ofMatch20ArrayList.add(ofMatch20);

            // construct flow tables
            OFFlowTable ofFlowTable = new OFFlowTable();
            ofFlowTable.setTableId(smallTableId);
            ofFlowTable.setTableName("FirstEntryTable");
            ofFlowTable.setTableSize(32);
            ofFlowTable.setTableType(OFTableType.OF_MM_TABLE);
            ofFlowTable.setMatchFieldList(ofMatch20ArrayList);

            // build flow tables
            FlowTable.Builder flowTable = DefaultFlowTable.builder()
                    .withFlowTable(ofFlowTable)
                    .forDevice(deviceId)
                    .forTable(globalTableId)
                    .fromApp(appId);

            flowTableService.applyFlowTables(flowTable.build());

        }

    }

    public void sendPofFlowRules() {
        String h1 = "0a000001";  // 10.0.0.1
        String h2 = "0a000002";  // 10.0.0.2
        int DIP = 1;    // DIP fieldId
        int port1 = 1;
        int port2 = 2;

        TrafficSelector.Builder h1_selector = DefaultTrafficSelector.builder();
        ArrayList<Criterion> h1_list = new ArrayList<Criterion>();
        h1_list.add(Criteria.matchOffsetLength((short) DIP, (short) 240, (short) 32, h1, "ffFFffFF"));
        h1_selector.add(Criteria.matchOffsetLength(h1_list));

        TrafficSelector.Builder h2_selector = DefaultTrafficSelector.builder();
        ArrayList<Criterion> h2_list = new ArrayList<Criterion>();
        h2_list.add(Criteria.matchOffsetLength((short) DIP, (short) 240, (short) 32, h2, "ffFFffFF"));
        h2_selector.add(Criteria.matchOffsetLength(h2_list));

        // from h2 to h1
        TrafficTreatment.Builder h1_treatment = DefaultTrafficTreatment.builder();
        ArrayList<OFAction> h1_actions = new ArrayList<OFAction>();
        OFAction action_output_port1 = DefaultPofActions.output((short) 0, (short) 0, (short) 0, port1).action();
        h1_actions.add(action_output_port1);
        h1_treatment.add(DefaultPofInstructions.applyActions(h1_actions));

        for(DeviceId deviceId:deviceIdList) {
            long newEntryId = flowTableStore.getNewFlowEntryId(deviceId, globalTableId);

            FlowRule.Builder flowRule = DefaultFlowRule.builder()
                        .forDevice(deviceId)
                        .forTable(globalTableId)
                        .withSelector(h1_selector.build())
                        .withTreatment(h1_treatment.build())
                        .withCookie(newEntryId)
                        .withPriority(1)
                        .makePermanent();

            flowRuleService.applyFlowRules(flowRule.build());
        }
        log.info("flows to h1 set ok.");

        TrafficTreatment.Builder h2_treatment = DefaultTrafficTreatment.builder();
        ArrayList<OFAction> h2_actions = new ArrayList<OFAction>();
        OFAction action_output_port2 = DefaultPofActions.output((short) 0, (short) 0, (short) 0, port2).action();
        h2_actions.add(action_output_port2);
        h2_treatment.add(DefaultPofInstructions.applyActions(h2_actions));

        for(DeviceId deviceId:deviceIdList) {
            long newEntryId = flowTableStore.getNewFlowEntryId(deviceId, globalTableId);

            FlowRule.Builder flowRule = DefaultFlowRule.builder()
                    .forDevice(deviceId)
                    .forTable(globalTableId)
                    .withSelector(h2_selector.build())
                    .withTreatment(h2_treatment.build())
                    .withCookie(newEntryId)
                    .withPriority(1)
                    .makePermanent();

            flowRuleService.applyFlowRules(flowRule.build());
        }
        log.info("flows to h2 set ok");

    }

    public void removePofFlowRules() {

        for(DeviceId deviceId:deviceIdList) {
            flowTableService.removeFlowTablesByTableId(deviceId, FlowTableId.valueOf(globalTableId));
        }
    }
}
