package org.thoughtcrime.securesms.websocket;


import com.google.thoughtcrimegson.Gson;

public class WebsocketMessage {

    private long id;

    private String message;

    public WebsocketMessage(long id, String message) {
        this.id      = id;
        this.message = message;
    }

    public String getMessage(){
        return message;
    }

    public static WebsocketMessage fromJson(String data) {
        return new Gson().fromJson(data,WebsocketMessage.class);
    }

    public String toJSON() {
        return new Gson().toJson(this);
    }

    public long getId() {
        return id;
    }
}
