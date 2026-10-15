package com.supriya.accreditation.platform.messaging;

/** Topic names. Topics are created by the deployment (compose init), never automatically. */
public final class Topics {

    public static final String ACCREDITATION_EVENTS = "accreditation.events.v1";
    public static final String USER_EVENTS = "user.events.v1";

    private Topics() {
    }

    /** One dead-letter topic per consumer group, so replaying one group never touches another. */
    public static String deadLetter(String topic, String groupId) {
        return topic + "." + groupId + ".dlt";
    }
}
