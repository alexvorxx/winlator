package com.winlator.alsaserver;

import com.winlator.xconnector.ConnectedClient;
import com.winlator.xconnector.ConnectionHandler;

public class ALSAClientConnectionHandler implements ConnectionHandler {
    @Override
    public void handleNewConnection(ConnectedClient client) {
        client.setTag(new ALSAClient());
    }

    @Override
    public void handleConnectionShutdown(ConnectedClient client) {
        if (client.getTag() != null) ((ALSAClient)client.getTag()).release();
    }
}
