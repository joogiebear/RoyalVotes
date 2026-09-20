package com.vexsoftware.votifier.model;

/**
 * A vote, in the shape every Votifier listener expects.
 *
 * <p>This lives in {@code com.vexsoftware} rather than our own package on purpose: plugins that react
 * to votes (libreforge's {@code register_vote} trigger among them) are compiled against this exact
 * class name, so providing it is what lets RoyalVotes stand in for Votifier. It is an independent
 * implementation of that public surface, not a copy of Votifier's source.
 */
public class Vote {

    private String serviceName;
    private String username;
    private String address;
    private String timeStamp;
    private byte[] additionalData;

    public Vote() {
    }

    public Vote(String serviceName, String username, String address, String timeStamp) {
        this(serviceName, username, address, timeStamp, null);
    }

    public Vote(String serviceName, String username, String address, String timeStamp,
                byte[] additionalData) {
        this.serviceName = serviceName;
        this.username = username;
        this.address = address;
        this.timeStamp = timeStamp;
        this.additionalData = additionalData == null ? null : additionalData.clone();
    }

    public Vote(Vote vote) {
        this(vote.serviceName, vote.username, vote.address, vote.timeStamp, vote.additionalData);
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(String timeStamp) {
        this.timeStamp = timeStamp;
    }

    public byte[] getAdditionalData() {
        return additionalData == null ? null : additionalData.clone();
    }

    @Override
    public String toString() {
        return "Vote (from:" + serviceName + " username:" + username + " address:" + address
                + " timeStamp:" + timeStamp + ")";
    }
}
