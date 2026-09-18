package com.gsc.tracking;

/** Whether the satellite is currently above the station's elevation mask. */
public interface LinkStatus {

    boolean isVisible();
}
