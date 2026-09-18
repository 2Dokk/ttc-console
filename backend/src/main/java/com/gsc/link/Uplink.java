package com.gsc.link;

import com.gsc.protocol.TcFrame;

/** Puts a telecommand frame on the air. Delivery is not guaranteed; the FOP handles that. */
public interface Uplink {

    void transmit(TcFrame frame);
}
