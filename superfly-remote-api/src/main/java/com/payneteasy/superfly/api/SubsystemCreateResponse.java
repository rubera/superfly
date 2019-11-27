package com.payneteasy.superfly.api;

import java.io.Serializable;

public class SubsystemCreateResponse implements Serializable {

    private String subsystemName;
    private String subsystemToken;

    public SubsystemCreateResponse(String subsystemName, String subsystemToken) {
        this.subsystemName = subsystemName;
        this.subsystemToken = subsystemToken;
    }

    public String getSubsystemName() {
        return subsystemName;
    }

    public String getSubsystemToken() {
        return subsystemToken;
    }
}
