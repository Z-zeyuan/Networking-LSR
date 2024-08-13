package socs.network.node;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.*;

public class Link {

    RouterDescription router1;    // rd of Link holder
    RouterDescription router2;    // rd of Link holder's neighbour

    Socket socket;

    ObjectOutputStream outputStream;
    ObjectInputStream inputStream;

    public Link(RouterDescription r1, RouterDescription r2, Socket socket, ObjectInputStream inputStream, ObjectOutputStream outputStream) {
        router1 = r1;
        router2 = r2;
        this.socket = socket;
        this.inputStream = inputStream;
        this.outputStream = outputStream;
    }
}
