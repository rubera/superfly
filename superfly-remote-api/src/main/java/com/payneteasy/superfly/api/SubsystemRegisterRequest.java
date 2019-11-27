package com.payneteasy.superfly.api;

import java.io.Serializable;

public class SubsystemRegisterRequest implements Serializable {

    private String subsystemTitle;
    private String subsystemName;
    private String subsystemUrl;
    private String callbackUrl;
    private String landingUrl;

    public String getSubsystemTitle() {
        return subsystemTitle;
    }

    public void setSubsystemTitle(String subsystemTitle) {
        this.subsystemTitle = subsystemTitle;
    }

    public String getSubsystemName() {
        return subsystemName;
    }

    public void setSubsystemName(String subsystemName) {
        this.subsystemName = subsystemName;
    }

    public String getSubsystemUrl() {
        return subsystemUrl;
    }

    public void setSubsystemUrl(String subsystemUrl) {
        this.subsystemUrl = subsystemUrl;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setCallbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }

    public String getLandingUrl() {
        return landingUrl;
    }

    public void setLandingUrl(String landingUrl) {
        this.landingUrl = landingUrl;
    }

    @Override
    public String toString() {
        return "SubsystemRegisterRequest{" +
                "subsystemTitle='" + subsystemTitle + '\'' +
                ", subsystemName='" + subsystemName + '\'' +
                ", subsystemUrl='" + subsystemUrl + '\'' +
                ", callbackUrl='" + callbackUrl + '\'' +
                ", landingUrl='" + landingUrl + '\'' +
                '}';
    }
}
