# ONOS-source-routing
A simple source  routing implementation tested on a linear topology which is s1-h1-h2-h3-s2.

Source routing is a novel high-performance route scheme. It adds SRH field in ingress switch, while it forwards packets and delete the current outport with decrement of TTL in SRH field. SRH defines as **Type + TTL + outport_list**, which *Type* stands for SRH packets, *TTL* is the hop counts between src_switch and dst_switch, *outport_list* means the corresponding port on the path.

I have test to ping this simple source routing based on ONOS 1.11, and its topo is shown as s1-h1-h2-h3-s2. Note that, I use *PacketOut* to forward packets in last one hop.
