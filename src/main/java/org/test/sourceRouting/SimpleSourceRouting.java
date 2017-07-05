package org.test.sourceRouting;

import org.apache.felix.scr.annotations.*;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.floodlightpof.protocol.OFMatch20;
import org.onosproject.floodlightpof.protocol.action.OFAction;
import org.onosproject.floodlightpof.protocol.table.OFFlowTable;
import org.onosproject.floodlightpof.protocol.table.OFTableType;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceAdminService;
import org.onosproject.net.flow.*;
import org.onosproject.net.flow.criteria.Criteria;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.instructions.DefaultPofActions;
import org.onosproject.net.flow.instructions.DefaultPofInstructions;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.table.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by tsf on 6/11/17.
 *
 * @Description create a simple sourceRouting app, which mainly adds
 *              SRH field in ingress routers, deletes port fields of
 *              SRH in intermediate routers.
 *              SRH --> type + ttl + port_list (112, 32)
 *
 * @TestTopo linear topology --> h1-s1-s2-s3-h2
 *           ping successfully with mininet.
 */

@Component(immediate = true)
public class SimpleSourceRouting {

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowTableService flowTableService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowTableStore flowTableStore;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceAdminService deviceAdminService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    private final Logger log = LoggerFactory.getLogger(getClass());
    protected String h1_ip = "0a000001";
    protected String h2_ip = "0a000002";
    protected List<DeviceId> deviceIdList = getDeviceId();
    protected List<Integer> h1_port_list = getPortList("h1");  // to h1, [1, 1, 1]
    protected List<Integer> h2_port_list = getPortList("h2");  // to h2, [2, 2, 2]
    protected int globalTableId;   // DIP table
    protected byte smallTableId;
    protected int globalTableId_SRH;  // SRH table
    protected byte smallTableId_SRH;
    private ApplicationId appId;
    protected int increment = -1;
    protected ReactivePacketProcessor processor = new ReactivePacketProcessor();

    @Activate
    public void activate() {
        appId = coreService.registerApplication("onos.test.sourceRouting");
        handlePortStatus();   // enable ports
        try {
            Thread.currentThread().sleep(100);
        } catch (Exception e) {
            System.out.println(e);
        }
        sendPofFlowTables();
        try {
            Thread.currentThread().sleep(100);
        } catch (Exception e) {
            System.out.println(e);
        }

        log.info("SimpleSourceRouting Started.");
        installGWFlowRules("0a000001", "pof:0000000000000003", h1_port_list, 1);
        log.info("h1_port_list: {}", h1_port_list);
        installGWFlowRules("0a000002", "pof:0000000000000001", h2_port_list, 1);
        log.info("h2_port_list: {}", h2_port_list);
        installInterRouterFlowRules(deviceIdList);
        log.info("installInterRouterFlowRules ok.");
        packetService.addProcessor(processor, PacketProcessor.director(2));
    }

    @Deactivate
    public void deactivate() {
        log.info("SimpleSourceRouting Stopped.");
        removePofFlowRules();
        packetService.removeProcessor(processor);
    }


    public String calSRH(List<Integer> port_list) {
        int ttl = port_list.size() - 1;
        String type_str = "0908";
        String ttl_str = Integer.toHexString(ttl);
        String outports_str = "";
        String SRH = "";

        if (ttl_str.length() < 2) {
            ttl_str = "0" + ttl_str;
        }

        for (int i = 0; i < port_list.size() -1; i++) {
            String port_str = Integer.toHexString(port_list.get(i));
            if (port_str.length() < 2) {
                port_str = "0" + port_str;
            }
            outports_str = outports_str + port_str;
        }

        SRH = type_str + ttl_str + outports_str;

        return SRH;
    }

    // add SRH header (112,32) in DIP tables
    public void installGWFlowRules(String dst_ip, String deviceId, List<Integer> port_list, int DIP) {
        String SRH = calSRH(port_list);
        int SRH_filedId = 4;

        // TTL ofmatch20 object
        OFMatch20 TTL = new OFMatch20();
        TTL.setFieldName("TTL");
        TTL.setFieldId((short) 2);  // fieldId
        TTL.setOffset((short) 128);
        TTL.setLength((short) 8);

        // match dst_ip
        TrafficSelector.Builder dip_selector = DefaultTrafficSelector.builder();
        ArrayList<Criterion> dst_ip_match_list = new ArrayList<Criterion>();
        dst_ip_match_list.add(Criteria.matchOffsetLength((short) DIP, (short) 240, (short) 32, dst_ip, "ffFFffFF"));
        dip_selector.add(Criteria.matchOffsetLength(dst_ip_match_list));

        // action
        TrafficTreatment.Builder dip_treatment = DefaultTrafficTreatment.builder();
        ArrayList<OFAction> actions = new ArrayList<OFAction>();
        OFAction dip_add_field = DefaultPofActions.addField((short) DIP, (short) 112, SRH.length() * 4, SRH).action();
         OFAction dip_modify_ttl = DefaultPofActions.modifyField(TTL, increment).action();
        OFAction dip_del_field = DefaultPofActions.deleteField((short) 136, (short) 8).action();
        OFAction dip_output = DefaultPofActions.output((short) 0, (short) 0, (short) 0, port_list.get(0)).action();
        log.info("port_list.get(0): {}", port_list.get(0));
        actions.add(dip_add_field);
        actions.add(dip_modify_ttl);
        actions.add(dip_del_field);
        actions.add(dip_output);
        dip_treatment.add(DefaultPofInstructions.applyActions(actions));

        // build flow rules
        int newEntryId = flowTableStore.getNewFlowEntryId(DeviceId.deviceId(deviceId), globalTableId);
        FlowRule.Builder flowRule = DefaultFlowRule.builder()
                .forDevice(DeviceId.deviceId(deviceId))
                .forTable(globalTableId)
                .withSelector(dip_selector.build())
                .withTreatment(dip_treatment.build())
                .withCookie(newEntryId)
                .withPriority(0)
                .makePermanent();
        flowRuleService.applyFlowRules(flowRule.build());
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

    // get port_list to deviceId
    public List<Integer> getPortList(String Destinatation_Host) {
        List<Integer> port_list = new ArrayList<Integer>();
        if(Destinatation_Host.equals("h2")) {
            port_list.add(2);
            port_list.add(2);
            port_list.add(2);
        }
        else if(Destinatation_Host.equals("h1")) {
            port_list.add(1);
            port_list.add(1);
            port_list.add(1);
        }
        return port_list;
    }

    public void handlePortStatus() {
        for (DeviceId deviceId : deviceIdList) {
            deviceAdminService.changePortState(deviceId, PortNumber.portNumber(1), true);
            deviceAdminService.changePortState(deviceId, PortNumber.portNumber(2), true);
        }
        log.info("enable ports ok.");
    }

    public void sendPofFlowTables() {

        for (DeviceId deviceId : deviceIdList) {
            // install SRH tables
            if (deviceId.toString().equals("pof:0000000000000002")) {
                globalTableId_SRH = flowTableStore.getNewGlobalFlowTableId(deviceId, OFTableType.OF_MM_TABLE);
                smallTableId_SRH = flowTableStore.parseToSmallTableId(deviceId, globalTableId_SRH);

                // construct ofmatch20 object
                int SRH = 4;
                OFMatch20 ofMatch20 = new OFMatch20();
                ofMatch20.setFieldId((short) SRH);
                ofMatch20.setFieldName("SRH");
                ofMatch20.setOffset((short) 112);
                ofMatch20.setLength((short) 32);

                ArrayList<OFMatch20> ofMatch20ArrayList = new ArrayList<OFMatch20>();
                ofMatch20ArrayList.add(ofMatch20);

                // construct flow tables
                OFFlowTable ofFlowTable = new OFFlowTable();
                ofFlowTable.setTableId(smallTableId_SRH);
                ofFlowTable.setTableName("FirstEntryTable");
                ofFlowTable.setTableSize(32);
                ofFlowTable.setTableType(OFTableType.OF_MM_TABLE);
                ofFlowTable.setMatchFieldList(ofMatch20ArrayList);

                // build flow tables
                FlowTable.Builder flowTable = DefaultFlowTable.builder()
                        .withFlowTable(ofFlowTable)
                        .forDevice(deviceId)
                        .forTable(globalTableId_SRH)
                        .fromApp(appId);

                flowTableService.applyFlowTables(flowTable.build());
            }

            // install DIP tables
            if (deviceId.toString().equals("pof:0000000000000001") ||
                    deviceId.toString().equals("pof:0000000000000003")) {

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
    }


    public void removePofFlowRules() {

        for (DeviceId deviceId : deviceIdList) {
            if (deviceId.toString().equals("pof:0000000000000001") ||
                    deviceId.toString().equals("pof:0000000000000003")) {
                flowTableService.removeFlowTablesByTableId(deviceId, FlowTableId.valueOf(globalTableId));
            } else {
                flowTableService.removeFlowTablesByTableId(deviceId, FlowTableId.valueOf(globalTableId_SRH));
            }
        }

    }

    public void installInterRouterFlowRules(List<DeviceId> deviceIdList) {
        log.info("Start installInterRoutersFlowRules.");

        String h1 = "0a000001";  // 10.0.0.1
        String h2 = "0a000002";  // 10.0.0.2
        int SRH = 4;    // SRH fieldId
        int port1 = 1;
        int port2 = 2;

        // TTL ofmatch20 object
        OFMatch20 TTL = new OFMatch20();
        TTL.setFieldName("TTL");
        TTL.setFieldId((short) 2);  // fieldId
        TTL.setOffset((short) 128);
        TTL.setLength((short) 8);

        // Port ofmatch20 object
        OFMatch20 PORT = new OFMatch20();
        PORT.setFieldName("PORT");
        PORT.setFieldId((short) 3);  // fieldId
        PORT.setOffset((short) 136);
        PORT.setLength((short) 8);


        for(DeviceId deviceId:deviceIdList) {
            // for pof:01, destination to h1
         /*   if (deviceId.toString().equals("pof:0000000000000001")) {
                TrafficSelector.Builder h1_selector = DefaultTrafficSelector.builder();
                ArrayList<Criterion> h1_list = new ArrayList<Criterion>();
                h1_list.add(Criteria.matchOffsetLength((short) SRH, (short) 112, (short) 32, "09080001", "ffFFffFF"));
                h1_selector.add(Criteria.matchOffsetLength(h1_list));

                TrafficTreatment.Builder h1_treatment = DefaultTrafficTreatment.builder();
                ArrayList<OFAction> actions = new ArrayList<OFAction>();
                OFAction action_output = DefaultPofActions.output((short) 0, (short) 0, (short) 0, port1).action();
//                OFAction action_modify = DefaultPofActions.modifyField(TTL, increment).action();
                OFAction action_del_field = DefaultPofActions.deleteField(112, 32).action();
//                actions.add(action_modify);  // TTL-1
                actions.add(action_del_field);  // delete SRH
                actions.add(action_output);  //output to h1 by port1
                h1_treatment.add(DefaultPofInstructions.applyActions(actions));

                long newEntryId = flowTableStore.getNewFlowEntryId(deviceId, globalTableId_SRH);
                FlowRule.Builder flowRule = DefaultFlowRule.builder()
                        .forDevice(deviceId)
                        .forTable(globalTableId_SRH)
                        .withSelector(h1_selector.build())
                        .withTreatment(h1_treatment.build())
                        .withPriority(1)
                        .withCookie(newEntryId)
                        .makePermanent();

                flowRuleService.applyFlowRules(flowRule.build());
                log.info("pof:0000000000000001 - InterFlowRules setup ok.");

            }*/

            // set flows for h1 & h2
            if(deviceId.toString().equals("pof:0000000000000002")) {
                TrafficSelector.Builder h1_selector = DefaultTrafficSelector.builder();
                ArrayList<Criterion> h1_list = new ArrayList<Criterion>();
                h1_list.add(Criteria.matchOffsetLength((short) SRH, (short) 112, (short) 32, "09080101", "ffFFffFF"));
                h1_selector.add(Criteria.matchOffsetLength(h1_list));

                TrafficSelector.Builder h2_selector = DefaultTrafficSelector.builder();
                ArrayList<Criterion> h2_list = new ArrayList<Criterion>();
                h2_list.add(Criteria.matchOffsetLength((short) SRH, (short) 112, (short) 32, "09080102", "ffFFffFF"));
                h2_selector.add(Criteria.matchOffsetLength(h2_list));

                TrafficTreatment.Builder h1_treatment = DefaultTrafficTreatment.builder();
                ArrayList<OFAction> actions = new ArrayList<OFAction>();
                OFAction action_output_port1 = DefaultPofActions.output((short) 0, (short) 0, (short) 0, port1).action();
                OFAction action_del_SRH = DefaultPofActions.deleteField(112, 32).action();
//                OFAction action_modify = DefaultPofActions.modifyField(TTL, increment).action();
//                OFAction action_del_field = DefaultPofActions.deleteField(136, 8).action();
//                actions.add(action_modify);  // TTL-1
//                actions.add(action_del_field);  // delete PORT
                actions.add(action_del_SRH);
                actions.add(action_output_port1);  //output
                h1_treatment.add(DefaultPofInstructions.applyActions(actions));

                TrafficTreatment.Builder h2_treatment = DefaultTrafficTreatment.builder();
                ArrayList<OFAction> actions_2 = new ArrayList<OFAction>();
                OFAction action_output_port2 = DefaultPofActions.output((short) 0, (short) 0, (short) 0, port2).action();
//                OFAction action2_modify = DefaultPofActions.modifyField(TTL, increment).action();
//                OFAction action2_del_field = DefaultPofActions.deleteField(136, 8).action();
//                actions_2.add(action2_modify);  // TTL-1
//                actions_2.add(action2_del_field);  // delete PORT
                actions_2.add(action_del_SRH);
                actions_2.add(action_output_port2);  //output
                h2_treatment.add(DefaultPofInstructions.applyActions(actions_2));

                long newEntryId1 = flowTableStore.getNewFlowEntryId(deviceId, globalTableId_SRH);
                FlowRule.Builder flowRule1 = DefaultFlowRule.builder()
                        .forDevice(deviceId)
                        .forTable(globalTableId_SRH)
                        .withSelector(h1_selector.build())
                        .withTreatment(h1_treatment.build())
                        .withPriority(1)
                        .withCookie(newEntryId1)
                        .makePermanent();

                long newEntryId2 = flowTableStore.getNewFlowEntryId(deviceId, globalTableId_SRH);
                FlowRule.Builder flowRule2 = DefaultFlowRule.builder()
                        .forDevice(deviceId)
                        .forTable(globalTableId_SRH)
                        .withSelector(h2_selector.build())
                        .withTreatment(h2_treatment.build())
                        .withPriority(1)
                        .withCookie(newEntryId2)
                        .makePermanent();

                flowRuleService.applyFlowRules(flowRule1.build());
                flowRuleService.applyFlowRules(flowRule2.build());
                log.info("pof:0000000000000002 - InterFlowRules setup ok.");

            }

            // for pof:03, destination to h2
        /*    if (deviceId.toString().equals("pof:0000000000000003")) {
                TrafficSelector.Builder h2_selector = DefaultTrafficSelector.builder();
                ArrayList<Criterion> h2_list = new ArrayList<Criterion>();
                h2_list.add(Criteria.matchOffsetLength((short) SRH, (short) 112, (short) 32, "09080002", "ffFFffFF"));
                h2_selector.add(Criteria.matchOffsetLength(h2_list));

                TrafficTreatment.Builder h2_treatment = DefaultTrafficTreatment.builder();
                ArrayList<OFAction> actions = new ArrayList<OFAction>();
                OFAction action_output = DefaultPofActions.output((short) 0, (short) 0, (short) 0, port2).action();
//                OFAction action_modify = DefaultPofActions.modifyField(TTL, increment).action();
                OFAction action_del_field = DefaultPofActions.deleteField(112, 32).action();
//                actions.add(action_modify);  // TTL-1
                actions.add(action_del_field);  // delete SRH
                actions.add(action_output);  //output
                h2_treatment.add(DefaultPofInstructions.applyActions(actions));

                long newEntryId = flowTableStore.getNewFlowEntryId(deviceId, globalTableId_SRH);
                FlowRule.Builder flowRule = DefaultFlowRule.builder()
                        .forDevice(deviceId)
                        .forTable(globalTableId_SRH)
                        .withSelector(h2_selector.build())
                        .withTreatment(h2_treatment.build())
                        .withPriority(1)
                        .withCookie(newEntryId)
                        .makePermanent();

                flowRuleService.applyFlowRules(flowRule.build());
                log.info("pof:0000000000000003 - InterFlowRules setup ok.");

            }*/
        }

    }


    private class ReactivePacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
           /* if(context.isHandled()) {
                return;
            }*/

            InboundPacket packet = context.inPacket();
            String deviceId = packet.receivedFrom().deviceId().toString();
            String input_port = packet.receivedFrom().port().toString();

            short unknow_type = packet.unparsed().getShort(12);

            if(unknow_type == (short) 0x0800) {
                short unknow = packet.unparsed().getShort(12);
                short type = packet.unparsed().getShort(14);
                short ttl_port = packet.unparsed().getShort(16);
                short port_port1 = packet.unparsed().getShort(18);
                short port_port2 = packet.unparsed().getShort(20);
                log.info("deviceId: {}, port: {}.", deviceId, input_port);
//            log.info("src_ip: {}, dst_ip: {}", src_ip, dst_ip);
                log.info("Eth_Type[96, 16]: {}", Integer.toHexString(unknow));
                log.info("type[112, 16]: {}" , Integer.toHexString(type));
                log.info("ttl_port[128, 16]: {}", Integer.toHexString(ttl_port));
                log.info("port_port1[144, 16]: {}", Integer.toHexString(port_port1));
                log.info("port_port2: {}", Integer.toHexString(port_port2));

                IPv4 iPv4_packet = (IPv4) packet.parsed().getPayload();
                String src_ip = IPv4.fromIPv4Address(iPv4_packet.getSourceAddress());
                String dst_ip = IPv4.fromIPv4Address(iPv4_packet.getDestinationAddress());
                log.info("src_ip: {}, dst_ip: {}", src_ip, dst_ip);
                int src_ip_payload = packet.unparsed().getInt(26);
                int dst_ip_payload = packet.unparsed().getInt(30);
                log.info("src_ip_payload: {}, dst_ip_payload: {}", IPv4.fromIPv4Address(src_ip_payload), IPv4.fromIPv4Address(dst_ip_payload));
                packetOut(context, deviceId, dst_ip);
            }
        }

        public void packetOut(PacketContext context, String deviceId, String dst_ip) {
            int port1 = 1;
            int port2 = 2;

            if(dst_ip.equals("10.0.0.1") && deviceId.equals("pof:0000000000000001")) {
                List<OFAction> actions = new ArrayList<OFAction>();
                OFAction action_outport1 = DefaultPofActions.output((short) 0, (short) 0, (short) 0, port1).action();
                actions.add(action_outport1);
                context.treatmentBuilder().add(DefaultPofInstructions.applyActions(actions));
                context.send();
                log.info("Packet out to 10.0.0.1 successfully.");
            }
            else if(dst_ip.equals("10.0.0.2") && deviceId.equals("pof:0000000000000003")) {
                List<OFAction> actions = new ArrayList<OFAction>();
                OFAction action_outport2 = DefaultPofActions.output((short) 0, (short) 0, (short) 0, port2).action();
                actions.add(action_outport2);
                context.treatmentBuilder().add(DefaultPofInstructions.applyActions(actions));
                context.send();
                log.info("Packet out to 10.0.0.2 successfully.");
            }
            else
                return;
        }
    }

}
