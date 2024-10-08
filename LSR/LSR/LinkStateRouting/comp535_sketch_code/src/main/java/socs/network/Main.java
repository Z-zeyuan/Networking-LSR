package socs.network;

import socs.network.node.Router;
import socs.network.util.Configuration;

import java.io.IOException;
import java.net.UnknownHostException;

public class Main {

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("usage: program conf_path");
            System.exit(1);
        }
        Router r = new Router(new Configuration(args[0]));
        r.terminal();
    }
}
