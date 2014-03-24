package org.thoughtcrime.securesms.websocket;

import com.google.thoughtcrimegson.Gson;

public class IncomingWebsocketMessage {

    public static final int TYPE_ACKNOWLEDGE_MESSAGE = 1;
    public static final int TYPE_PING_MESSAGE = 2;
    public static final int TYPE_PONG_MESSAGE = 3;

    protected int type;


    public IncomingWebsocketMessage() {
    }

    public IncomingWebsocketMessage(int type) {
        this.type = type;
    }

    public int getType() {
        return type;
    }

    public static IncomingWebsocketMessage fromJson(String data) {
        return new Gson().fromJson(data, IncomingWebsocketMessage.class);
    }

    public String toJSON() {
        return new Gson().toJson(this);
    }
}
